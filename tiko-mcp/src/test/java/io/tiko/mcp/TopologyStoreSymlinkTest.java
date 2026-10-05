package io.tiko.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code TopologyStore} reads only files whose real path is under the real project root (#475).
 * A file under {@code target/classes/META-INF/tiko/} that is a symlink to somewhere outside the
 * project must not be loaded — for every file the store reads.
 */
class TopologyStoreSymlinkTest {

    private static final String TIKO_DIR = "target/classes/META-INF/tiko/";

    static Stream<Arguments> loadedFiles() {
        Function<TopologyStore, Object> components = TopologyStore::components;
        Function<TopologyStore, Object> kafkaSources = TopologyStore::kafkaSources;
        Function<TopologyStore, Object> wiringErrors = TopologyStore::wiringErrors;
        Function<TopologyStore, Object> configSchema = TopologyStore::configSchema;
        return Stream.of(
                Arguments.of("topology.json", "{\"components\":[{\"qualifiedName\":\"outside.Secret\"}]}", components),
                Arguments.of("topology-kafka.json", "{\"kafkaSources\":[{\"topic\":\"outside\"}]}", kafkaSources),
                Arguments.of("wiring-errors.json", "{\"errors\":[{\"message\":\"outside\"}]}", wiringErrors),
                Arguments.of("config-schema.json", "{\"properties\":{\"outside\":{}}}", configSchema));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("loadedFiles")
    void symlinkedFileOutsideTheRootIsNotLoaded(
            String fileName, String outsideJson, Function<TopologyStore, Object> view, @TempDir Path tmp)
            throws Exception {
        var root = Files.createDirectories(tmp.resolve("project"));
        var outsideFile = Files.createDirectories(tmp.resolve("outside")).resolve(fileName);
        Files.writeString(outsideFile, outsideJson, StandardCharsets.UTF_8);
        Symlinks.linkOrSkip(root.resolve(TIKO_DIR + fileName), outsideFile);

        var store = TopologyStore.loadFrom(root);

        assertThat(String.valueOf(view.apply(store)))
                .as("%s symlinked outside the project root must not be read", fileName)
                .doesNotContain("outside");
    }

    @Test
    void symlinkThatStaysInsideTheRootIsStillLoaded(@TempDir Path tmp) throws Exception {
        var root = Files.createDirectories(tmp.resolve("project"));
        var shared = Files.createDirectories(root.resolve("shared")).resolve("topology.json");
        Files.writeString(shared, "{\"components\":[{\"qualifiedName\":\"inside.Ok\"}]}", StandardCharsets.UTF_8);
        Symlinks.linkOrSkip(root.resolve(TIKO_DIR + "topology.json"), shared);

        var store = TopologyStore.loadFrom(root);

        assertThat(store.components()).extracting(c -> c.get("qualifiedName")).containsExactly("inside.Ok");
    }
}
