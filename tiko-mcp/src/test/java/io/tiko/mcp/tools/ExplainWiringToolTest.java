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
    void surfacesConfigurationDependenciesAsLeafEntries(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1, "module":"m",
                 "components":[
                   {"qualifiedName":"io.example.OrderRepository","scope":"REQUEST","interfaces":[],
                    "constructorDependencies":[{"type":"io.example.DbConfig","qualifier":null,"kind":"DIRECT","pickedType":null}]}
                 ],
                 "factoryMethods":[], "eventHandlers":[], "eventTriggers":[],
                 "configurations":[
                   {"qualifiedName":"io.example.DbConfig","prefix":"database","fields":[]}
                 ]}
                """);
        var tool = new ExplainWiringTool(store);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tree =
                (List<Map<String, Object>>) tool.execute(Map.of("componentFqn", "io.example.OrderRepository"))
                        .get("tree");

        assertThat(tree).hasSize(2);
        assertThat(tree.get(0).get("kind")).isEqualTo("COMPONENT");
        assertThat(tree.get(1).get("kind")).isEqualTo("CONFIG");
        assertThat(tree.get(1).get("depth")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) tree.get(1).get("component");
        assertThat(config.get("prefix")).isEqualTo("database");
    }

    @Test
    void unknownComponentWithNoNearMatchesOmitsSuggestionClause(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1, "module":"m",
                 "components":[
                   {"qualifiedName":"io.example.OrderService","scope":"SINGLETON","interfaces":[],"constructorDependencies":[]}
                 ],
                 "factoryMethods":[], "eventHandlers":[], "eventTriggers":[], "configurations":[]}
                """);
        var tool = new ExplainWiringTool(store);

        assertThatThrownBy(() -> tool.execute(Map.of("componentFqn", "io.example.DoesNotExist")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("io.example.DoesNotExist")
                .hasMessageNotContaining("[]")
                .hasMessageNotContaining("Did you mean");
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

    @Test
    void walksProducerEdge(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"example.Producers","scope":"SINGLETON","interfaces":[],
                    "constructorDependencies":[]},
                   {"qualifiedName":"example.Repo","scope":"SINGLETON","interfaces":[],
                    "constructorDependencies":[
                      {"type":"javax.sql.DataSource","qualifier":"primary","kind":"DIRECT","pickedType":null}]}
                 ],
                 "factoryMethods":[
                   {"declaringClass":"example.Producers","methodName":"db",
                    "returnType":"javax.sql.DataSource","scope":"SINGLETON","qualifier":"primary",
                    "profiles":[],"static":false,"autoCloseable":true,"requiresProxy":false,
                    "constructorDependencies":[]}
                 ],
                 "eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new ExplainWiringTool(store);

        @SuppressWarnings("unchecked")
        var tree = (List<Map<String, Object>>)
                tool.execute(Map.of("componentFqn", "example.Repo")).get("tree");

        // Three nodes: Repo (depth 0), DataSource produced by Producers (depth 1),
        // Producers component (depth 2 via the producer edge).
        assertThat(tree).hasSize(3);
        assertThat(tree.get(1).get("kind")).isEqualTo("PRODUCED");
        assertThat(tree.get(2).get("kind")).isEqualTo("COMPONENT");
        @SuppressWarnings("unchecked")
        var producedBy =
                (Map<String, Object>) ((Map<String, Object>) tree.get(1).get("component")).get("producedBy");
        assertThat(producedBy.get("componentFqn")).isEqualTo("example.Producers");
    }

    @Test
    void respectsProfileWhenResolvingInterfaceDeps(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"x.DevImpl","scope":"SINGLETON","interfaces":["x.IThing"],
                    "profiles":["dev"],"constructorDependencies":[]},
                   {"qualifiedName":"x.ProdImpl","scope":"SINGLETON","interfaces":["x.IThing"],
                    "profiles":["prod"],"constructorDependencies":[]},
                   {"qualifiedName":"x.Consumer","scope":"SINGLETON","interfaces":[],"profiles":[],
                    "constructorDependencies":[
                      {"type":"x.IThing","qualifier":null,"kind":"DIRECT","pickedType":null}]}
                 ],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new ExplainWiringTool(store);

        @SuppressWarnings("unchecked")
        var tree = (List<Map<String, Object>>) tool.execute(Map.of("componentFqn", "x.Consumer", "profile", "prod"))
                .get("tree");

        // Consumer (depth 0) + ProdImpl picked by profile (depth 1). DevImpl excluded.
        assertThat(tree).hasSize(2);
        @SuppressWarnings("unchecked")
        var picked = (Map<String, Object>) tree.get(1).get("component");
        assertThat(picked.get("qualifiedName")).isEqualTo("x.ProdImpl");
    }

    private TopologyStore storeWith(Path root, String topologyJson) throws Exception {
        Path f = root.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, topologyJson, StandardCharsets.UTF_8);
        return TopologyStore.loadFrom(root);
    }
}
