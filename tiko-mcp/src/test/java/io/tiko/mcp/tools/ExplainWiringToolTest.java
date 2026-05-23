package io.tiko.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.mcp.TopologyStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExplainWiringToolTest {

    @Test
    void walksTransitiveDependencies(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1, "module":"m",
                 "components":[
                   {"qualifiedName":"io.example.A","scope":"SINGLETON","interfaces":[],
                    "constructorDependencies":[{"type":"io.example.B","qualifier":null,"kind":"DIRECT","pickedType":null}]},
                   {"qualifiedName":"io.example.B","scope":"SINGLETON","interfaces":[],
                    "constructorDependencies":[]}
                 ],
                 "factoryMethods":[], "eventHandlers":[], "eventTriggers":[], "configurations":[]}
                """);
        var tool = new ExplainWiringTool(store);

        Map<String, Object> result = tool.execute(Map.of("componentFqn", "io.example.A"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tree = (List<Map<String, Object>>) result.get("tree");
        assertThat(tree).hasSize(2);
        assertThat(tree.get(0).get("depth")).isEqualTo(0L);
        assertThat(tree.get(1).get("depth")).isEqualTo(1L);
    }

    @Test
    void flagsCycles(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1, "module":"m",
                 "components":[
                   {"qualifiedName":"io.example.A","scope":"SINGLETON","interfaces":[],
                    "constructorDependencies":[{"type":"io.example.B","qualifier":null,"kind":"DIRECT","pickedType":null}]},
                   {"qualifiedName":"io.example.B","scope":"SINGLETON","interfaces":[],
                    "constructorDependencies":[{"type":"io.example.A","qualifier":null,"kind":"DIRECT","pickedType":null}]}
                 ],
                 "factoryMethods":[], "eventHandlers":[], "eventTriggers":[], "configurations":[]}
                """);
        var tool = new ExplainWiringTool(store);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tree = (List<Map<String, Object>>)
                tool.execute(Map.of("componentFqn", "io.example.A")).get("tree");
        // A → B → A (cycle flagged on the re-visit)
        assertThat(tree.stream().anyMatch(n -> Boolean.TRUE.equals(n.get("cycle"))))
                .isTrue();
    }

    @Test
    void walksThroughInterfaceTypedDependencies(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1, "module":"m",
                 "components":[
                   {"qualifiedName":"io.example.A","scope":"SINGLETON","interfaces":[],
                    "constructorDependencies":[{"type":"io.example.IB","qualifier":null,"kind":"DIRECT","pickedType":null}]},
                   {"qualifiedName":"io.example.B","scope":"REQUEST","interfaces":["io.example.IB"],
                    "constructorDependencies":[]}
                 ],
                 "factoryMethods":[], "eventHandlers":[], "eventTriggers":[], "configurations":[]}
                """);
        var tool = new ExplainWiringTool(store);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tree = (List<Map<String, Object>>)
                tool.execute(Map.of("componentFqn", "io.example.A")).get("tree");
        assertThat(tree).hasSize(2);
        assertThat(tree.get(0).get("depth")).isEqualTo(0L);
        @SuppressWarnings("unchecked")
        Map<String, Object> implComponent = (Map<String, Object>) tree.get(1).get("component");
        assertThat(implComponent.get("qualifiedName")).isEqualTo("io.example.B");
        @SuppressWarnings("unchecked")
        Map<String, Object> via = (Map<String, Object>) tree.get(1).get("via");
        assertThat(via.get("type")).isEqualTo("io.example.IB");
    }

    @Test
    void unknownComponentThrowsWithSuggestions(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1, "module":"m",
                 "components":[
                   {"qualifiedName":"io.example.OrderService","scope":"SINGLETON","interfaces":[],"constructorDependencies":[]}
                 ],
                 "factoryMethods":[], "eventHandlers":[], "eventTriggers":[], "configurations":[]}
                """);
        var tool = new ExplainWiringTool(store);

        assertThatThrownBy(() -> tool.execute(Map.of("componentFqn", "OrderService")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("io.example.OrderService");
    }

    private TopologyStore storeWith(Path root, String topologyJson) throws Exception {
        Path f = root.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, topologyJson, StandardCharsets.UTF_8);
        return TopologyStore.loadFrom(root);
    }
}
