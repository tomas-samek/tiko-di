package io.tiko.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.mcp.TopologyStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReloadToolTest {

    @Test
    void reloadReturnsReloadedTrueAndTimestamp(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[{"qualifiedName":"io.example.A","scope":"SINGLETON","interfaces":[]}],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new ReloadTool(store);

        Map<String, Object> result = tool.execute(Map.of());

        assertThat(result.get("reloaded")).isEqualTo(Boolean.TRUE);
        assertThat(result.get("topologyTimestamp")).isInstanceOf(String.class);
        assertThat((String) result.get("topologyTimestamp")).isNotBlank();
    }

    @Test
    void reloadPicksUpAddedComponent(@TempDir Path root) throws Exception {
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

        var tool = new ReloadTool(store);
        tool.execute(Map.of());

        assertThat(store.components()).hasSize(2);
    }

    private TopologyStore storeWith(Path root, String topologyJson) throws Exception {
        var f = root.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, topologyJson, StandardCharsets.UTF_8);
        return TopologyStore.loadFrom(root);
    }
}
