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
    void factoryHostIsReportedAsDependent(@TempDir Path root) throws Exception {
        // A @Produces factory method's parameter dep on the target → the factory's
        // declaringClass appears in the result (#183).
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"example.DbConfig","scope":"SINGLETON","interfaces":[],
                    "constructorDependencies":[]},
                   {"qualifiedName":"example.Producers","scope":"SINGLETON","interfaces":[],
                    "constructorDependencies":[]}
                 ],
                 "factoryMethods":[
                   {"declaringClass":"example.Producers","methodName":"primaryShim",
                    "returnType":"example.HikariShim","scope":"SINGLETON","qualifier":"primary",
                    "profiles":[],"static":false,"autoCloseable":false,"requiresProxy":false,
                    "constructorDependencies":[{"type":"example.DbConfig","qualifier":null,"kind":"DIRECT","pickedType":null}]}
                 ],
                 "eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new FindDependentsTool(store);

        @SuppressWarnings("unchecked")
        var dependents = (List<String>)
                tool.execute(Map.of("componentFqn", "example.DbConfig")).get("dependents");
        assertThat(dependents).containsExactly("example.Producers");
    }

    @Test
    void componentAndFactoryHostDedupedWhenSameHostUsesBoth(@TempDir Path root) throws Exception {
        // A @Component that both injects the target AND hosts a factory method taking the
        // target must appear at most once in the result (#183 dedup contract).
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"example.DbConfig","scope":"SINGLETON","interfaces":[],
                    "constructorDependencies":[]},
                   {"qualifiedName":"example.MultiUser","scope":"SINGLETON","interfaces":[],
                    "constructorDependencies":[{"type":"example.DbConfig","qualifier":null,"kind":"DIRECT","pickedType":null}]}
                 ],
                 "factoryMethods":[
                   {"declaringClass":"example.MultiUser","methodName":"helper",
                    "returnType":"example.Helper","scope":"SINGLETON","qualifier":null,
                    "profiles":[],"static":false,"autoCloseable":false,"requiresProxy":false,
                    "constructorDependencies":[{"type":"example.DbConfig","qualifier":null,"kind":"DIRECT","pickedType":null}]}
                 ],
                 "eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new FindDependentsTool(store);

        @SuppressWarnings("unchecked")
        var dependents = (List<String>)
                tool.execute(Map.of("componentFqn", "example.DbConfig")).get("dependents");
        assertThat(dependents).containsExactly("example.MultiUser");
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
