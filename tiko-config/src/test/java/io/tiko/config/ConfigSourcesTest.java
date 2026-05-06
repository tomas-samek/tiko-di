package io.tiko.config;

import io.tiko.ConfigSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigSourcesTest {

    @Test
    void fromMap_returns_supplied_tree() {
        Map<String, Object> data = Map.of("db", Map.of("url", "x"));
        ConfigSource src = ConfigSources.fromMap(data);
        assertThat(src.load()).isEqualTo(data);
    }

    @Test
    void classpath_loads_yaml_resource(@TempDir Path tmp) throws IOException {
        // Use file() for the classpath test substitute since classpath fixtures
        // are awkward in surefire — we cover classpath() resolution at integration test time.
        Path yaml = tmp.resolve("c.yaml");
        Files.writeString(yaml, "db:\n  url: jdbc:test\n");

        ConfigSource src = ConfigSources.file(yaml);
        Map<String, Object> root = src.load();
        Map<String, Object> db = (Map<String, Object>) root.get("db");
        assertThat(db).containsEntry("url", "jdbc:test");
    }

    @Test
    void file_throws_when_missing() {
        Path missing = Path.of("/no/such/file");
        assertThatThrownBy(() -> ConfigSources.file(missing).load())
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining(missing.toString());
    }

    @Test
    void layered_deep_merges_in_order() {
        ConfigSource base    = ConfigSources.fromMap(Map.of("a", 1, "b", Map.of("x", 1)));
        ConfigSource overlay = ConfigSources.fromMap(Map.of("b", Map.of("y", 2)));
        Map<String, Object> result = ConfigSources.layered(base, overlay).load();
        assertThat(result).containsEntry("a", 1);
        Map<String, Object> b = (Map<String, Object>) result.get("b");
        assertThat(b).containsEntry("x", 1).containsEntry("y", 2);
    }

    @Test
    void classpath_loads_real_resource() {
        // Resource is added at src/test/resources/test-config.yaml in step 7
        ConfigSource src = ConfigSources.classpath("test-config.yaml");
        Map<String, Object> root = src.load();
        assertThat(root).containsKey("db");
    }
}
