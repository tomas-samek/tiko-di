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
    void mergesKafkaTopologyFragmentAcrossModules(@TempDir Path root) throws Exception {
        writeJson(root.resolve("module-a/target/classes/META-INF/tiko/topology-kafka.json"), """
                        {"schemaVersion":1,
                         "kafkaSources":[{"declaringClass":"io.example.Consumer","methodName":"in",
                          "topic":"orders","consumerGroup":"wh","eventType":"io.example.OrderPlaced"}],
                         "kafkaSinks":[]}
                        """);
        writeJson(root.resolve("module-b/target/classes/META-INF/tiko/topology-kafka.json"), """
                        {"schemaVersion":1,
                         "kafkaSources":[],
                         "kafkaSinks":[{"declaringClass":"io.example.Publisher","methodName":"out",
                          "topic":"shipments","partitionKey":"id","eventType":"io.example.Shipment"}]}
                        """);

        var store = TopologyStore.loadFrom(root);
        assertThat(store.kafkaSources()).hasSize(1);
        assertThat(store.kafkaSources().get(0)).containsEntry("eventType", "io.example.OrderPlaced");
        assertThat(store.kafkaSinks()).hasSize(1);
        assertThat(store.kafkaSinks().get(0)).containsEntry("topic", "shipments");
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

    @Test
    void loadsWiringErrorsAcrossModules(@TempDir Path root) throws Exception {
        writeJson(root.resolve("module-a/target/classes/META-INF/tiko/wiring-errors.json"), """
                        {"errors":[
                          {"kind":"MISSING_DEPENDENCY","sourceFile":null,"line":0,
                           "componentFqn":"io.example.A","message":"x","suggestedFix":null}
                        ]}
                        """);
        writeJson(root.resolve("module-b/target/classes/META-INF/tiko/wiring-errors.json"), """
                        {"errors":[]}
                        """);
        // Empty topology so the store can be loaded; the wiring-errors loop runs
        // independently of the topology.json loop.
        writeJson(root.resolve("module-a/target/classes/META-INF/tiko/topology.json"), """
                        {"schemaVersion":1,"module":"m","components":[],"factoryMethods":[],
                         "eventHandlers":[],"eventTriggers":[],"configurations":[]}
                        """);

        var store = TopologyStore.loadFrom(root);
        assertThat(store.wiringErrors()).hasSize(1);
        assertThat(store.wiringErrors().get(0).get("kind")).isEqualTo("MISSING_DEPENDENCY");
    }

    @Test
    void reloadPicksUpFilesystemChanges(@TempDir Path root) throws Exception {
        var f = root.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, """
                {"schemaVersion":1,"module":"m",
                 "components":[{"qualifiedName":"io.example.A","scope":"SINGLETON","interfaces":[]}],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """, StandardCharsets.UTF_8);

        var store = TopologyStore.loadFrom(root);
        assertThat(store.components()).hasSize(1);

        Files.writeString(f, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"io.example.A","scope":"SINGLETON","interfaces":[]},
                   {"qualifiedName":"io.example.B","scope":"REQUEST","interfaces":[]}
                 ],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """, StandardCharsets.UTF_8);

        store.reload();
        assertThat(store.components()).hasSize(2);
    }

    private static void writeJson(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
