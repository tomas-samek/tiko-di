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

class FindDependentsToolTest {

    @Test
    void directDependents(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"example.Orders","scope":"SINGLETON","interfaces":[],
                    "constructorDependencies":[]},
                   {"qualifiedName":"example.OrderService","scope":"SINGLETON","interfaces":[],
                    "constructorDependencies":[{"type":"example.Orders","qualifier":null,"kind":"DIRECT","pickedType":null}]}
                 ],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new FindDependentsTool(store);

        @SuppressWarnings("unchecked")
        var dependents = (List<String>)
                tool.execute(Map.of("componentFqn", "example.Orders")).get("dependents");
        assertThat(dependents).containsExactly("example.OrderService");
    }

    @Test
    void transitiveDependents(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"example.Orders","scope":"SINGLETON","interfaces":[],
                    "constructorDependencies":[]},
                   {"qualifiedName":"example.OrderService","scope":"SINGLETON","interfaces":[],
                    "constructorDependencies":[{"type":"example.Orders","qualifier":null,"kind":"DIRECT","pickedType":null}]},
                   {"qualifiedName":"example.OrderController","scope":"SINGLETON","interfaces":[],
                    "constructorDependencies":[{"type":"example.OrderService","qualifier":null,"kind":"DIRECT","pickedType":null}]}
                 ],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new FindDependentsTool(store);

        @SuppressWarnings("unchecked")
        var dependents = (List<String>) tool.execute(Map.of("componentFqn", "example.Orders", "transitive", true))
                .get("dependents");
        assertThat(dependents).containsExactlyInAnyOrder("example.OrderService", "example.OrderController");
    }

    @Test
    void unknownFqnThrowsWithDidYouMean(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"example.Orders","scope":"SINGLETON","interfaces":[],
                    "constructorDependencies":[]}
                 ],
                 "factoryMethods":[],"eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new FindDependentsTool(store);

        assertThatThrownBy(() -> tool.execute(Map.of("componentFqn", "example.Order")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown component 'example.Order'")
                .hasMessageContaining("Did you mean")
                .hasMessageContaining("example.Orders");
    }

    private TopologyStore storeWith(Path root, String topologyJson) throws Exception {
        var f = root.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, topologyJson, StandardCharsets.UTF_8);
        return TopologyStore.loadFrom(root);
    }
}
