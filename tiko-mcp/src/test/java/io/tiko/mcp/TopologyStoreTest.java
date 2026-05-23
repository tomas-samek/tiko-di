package io.tiko.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TopologyStoreTest {

    @Test
    void mergesTopologyAcrossModules(@TempDir Path root) throws Exception {
        writeJson(root.resolve("module-a/target/classes/META-INF/tiko/topology.json"), """
                        {"schemaVersion": 1, "module": "io.tiko.generated.ContainerA",
                         "components": [{"qualifiedName": "io.example.A", "scope": "SINGLETON", "interfaces": []}],
                         "factoryMethods": [], "eventHandlers": [], "eventTriggers": [], "configurations": []}
                        """);
        writeJson(root.resolve("module-b/target/classes/META-INF/tiko/topology.json"), """
                        {"schemaVersion": 1, "module": "io.tiko.generated.ContainerB",
                         "components": [{"qualifiedName": "io.example.B", "scope": "REQUEST", "interfaces": []}],
                         "factoryMethods": [], "eventHandlers": [], "eventTriggers": [], "configurations": []}
                        """);

        var store = TopologyStore.loadFrom(root);
        assertThat(store.components()).hasSize(2);
        assertThat(store.components())
                .extracting("qualifiedName")
                .containsExactlyInAnyOrder("io.example.A", "io.example.B");
    }

    @Test
    void emptyProjectGivesEmptyStore(@TempDir Path root) {
        var store = TopologyStore.loadFrom(root);
        assertThat(store.components()).isEmpty();
        assertThat(store.configSchema()).isNull();
    }

    @Test
    void loadsConfigSchemaWhenPresent(@TempDir Path root) throws Exception {
        writeJson(root.resolve("m/target/classes/META-INF/tiko/config-schema.json"), """
                        {"$schema": "https://json-schema.org/draft/2020-12/schema",
                         "type": "object",
                         "properties": {"database": {"type": "object", "properties": {}}}}
                        """);
        var store = TopologyStore.loadFrom(root);
        assertThat(store.configSchema()).isNotNull();
        assertThat(store.configSchemaPrefixes()).containsExactly("database");
    }

    private static void writeJson(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
