package io.tiko.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.kafka.common.compress.Compression;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.utils.BufferSupplier;
import org.apache.kafka.common.utils.ByteBufferOutputStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code kafka-clients} 3.9.2 pulls compression codecs that public advisories affect, so the root
 * pom manages each at a fixed version and {@code tiko-kafka} declares it directly, which is what
 * makes applications resolve it too.
 *
 * <ul>
 *   <li>{@code at.yawk.lz4:lz4-java}: kafka-clients asks for 1.10.1. GHSA-4v53-57pg-c464,
 *       GHSA-6cx8-rjf8-pr8g, GHSA-xx22-p4ch-683r are fixed in 1.11.2 (#504); GHSA-mcr4-qmvw-px4g,
 *       GHSA-gm45-99xc-r7wv, GHSA-343h-94h5-c4wr in 1.11.4 (#513).
 *   <li>{@code org.xerial.snappy:snappy-java}: kafka-clients asks for 1.1.10.5. GHSA-gwq9-9ffj-mvrv,
 *       GHSA-q233-g92q-qxp6, GHSA-xqrx-qh46-34m7 are fixed in 1.1.10.9 (#554).
 * </ul>
 *
 * <p>The round trips prove Kafka's own compression streams still work against those versions.
 */
class CompressionCodecVersionTest {

    static Stream<Arguments> codecs() {
        return Stream.of(
                Arguments.of(
                        "lz4-java",
                        "at/yawk/lz4",
                        new int[] {1, 11, 4},
                        Compression.lz4().build()),
                Arguments.of(
                        "snappy-java",
                        "org/xerial/snappy",
                        new int[] {1, 1, 10, 9},
                        Compression.snappy().build()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("codecs")
    void codecOnTheRuntimeClasspathHasTheAdvisoryFixes(
            String artifact, String groupPath, int[] firstFixed, Compression ignored) {
        var jar = Pattern.compile(".*[/\\\\]" + groupPath.replace("/", "[/\\\\]") + "[/\\\\]" + artifact
                + "[/\\\\]([^/\\\\]+)[/\\\\]" + artifact + "-\\1\\.jar$");
        var versions = Stream.of(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(jar::matcher)
                .filter(Matcher::matches)
                .map(m -> m.group(1))
                .toList();

        assertThat(versions).as("%s resolved for tiko-kafka", artifact).hasSize(1);
        assertThat(atLeast(versions.get(0), firstFixed))
                .as("%s %s must include the advisory fixes", artifact, versions.get(0))
                .isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("codecs")
    void kafkaCompressionRoundTrips(String artifact, String groupPath, int[] firstFixed, Compression compression)
            throws IOException {
        var payload = "tiko ".repeat(1_000).getBytes(StandardCharsets.UTF_8);

        var sink = new ByteBufferOutputStream(256);
        try (var out = compression.wrapForOutput(sink, RecordBatch.CURRENT_MAGIC_VALUE)) {
            out.write(payload);
        }
        var compressed = sink.buffer().flip();

        byte[] restored;
        try (var in =
                compression.wrapForInput(compressed, RecordBatch.CURRENT_MAGIC_VALUE, BufferSupplier.NO_CACHING)) {
            restored = in.readAllBytes();
        }

        assertThat(restored).isEqualTo(payload);
    }

    private static boolean atLeast(String version, int[] minimum) {
        var parts = version.split("[.-]");
        for (int i = 0; i < minimum.length; i++) {
            int part = i < parts.length && parts[i].matches("\\d+") ? Integer.parseInt(parts[i]) : 0;
            if (part != minimum[i]) return part > minimum[i];
        }
        return true;
    }
}
