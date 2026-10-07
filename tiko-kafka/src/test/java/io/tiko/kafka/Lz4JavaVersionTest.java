package io.tiko.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.kafka.common.compress.Compression;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.utils.BufferSupplier;
import org.apache.kafka.common.utils.ByteBufferOutputStream;
import org.junit.jupiter.api.Test;

/**
 * {@code kafka-clients} 3.9.2 pulls {@code at.yawk.lz4:lz4-java} 1.10.1, which has three published
 * advisories (GHSA-4v53-57pg-c464, GHSA-6cx8-rjf8-pr8g, GHSA-xx22-p4ch-683r), so the root pom
 * manages it at a fixed version (#504). The round trip proves Kafka's own LZ4 streams still work
 * against that version.
 */
class Lz4JavaVersionTest {

    private static final Pattern LZ4_JAR = Pattern.compile(
            ".*[/\\\\]at[/\\\\]yawk[/\\\\]lz4[/\\\\]lz4-java[/\\\\]([^/\\\\]+)[/\\\\]lz4-java-\\1\\.jar$");

    /** First release that fixes all three advisories. */
    private static final int[] FIRST_FIXED = {1, 11, 2};

    @Test
    void lz4JavaOnTheRuntimeClasspathHasTheAdvisoryFixes() {
        var versions = Stream.of(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(LZ4_JAR::matcher)
                .filter(java.util.regex.Matcher::matches)
                .map(m -> m.group(1))
                .toList();

        assertThat(versions).as("lz4-java resolved for tiko-kafka").hasSize(1);
        assertThat(atLeast(versions.get(0), FIRST_FIXED))
                .as("lz4-java %s must be >= 1.11.2", versions.get(0))
                .isTrue();
    }

    @Test
    void kafkaLz4CompressionRoundTrips() throws IOException {
        var payload = "tiko ".repeat(1_000).getBytes(StandardCharsets.UTF_8);
        var compression = Compression.lz4().build();

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
