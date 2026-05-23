package io.tiko.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.mcp.TopologyStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ListComponentsToolTest {

    @Test
    void noFilterReturnsAll(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1, "module":"m",
                 "components":[
                   {"qualifiedName":"io.example.A","scope":"SINGLETON","interfaces":[]},
                   {"qualifiedName":"io.example.B","scope":"REQUEST","interfaces":["io.example.IB"]}
                 ],
                 "factoryMethods":[], "eventHandlers":[], "eventTriggers":[], "configurations":[]}
                """);
        var tool = new ListComponentsTool(store);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result =
                (List<Map<String, Object>>) tool.execute(Map.of()).get("components");
        assertThat(result).hasSize(2);
    }

    @Test
    void filterByScope(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1, "module":"m",
                 "components":[
                   {"qualifiedName":"io.example.A","scope":"SINGLETON","interfaces":[]},
                   {"qualifiedName":"io.example.B","scope":"REQUEST","interfaces":[]}
                 ],
                 "factoryMethods":[], "eventHandlers":[], "eventTriggers":[], "configurations":[]}
                """);
        var tool = new ListComponentsTool(store);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>)
                tool.execute(Map.of("scope", "REQUEST")).get("components");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("qualifiedName")).isEqualTo("io.example.B");
    }

    @Test
    void filterByInterface(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1, "module":"m",
                 "components":[
                   {"qualifiedName":"io.example.A","scope":"SINGLETON","interfaces":["io.example.Marker"]},
                   {"qualifiedName":"io.example.B","scope":"SINGLETON","interfaces":[]}
                 ],
                 "factoryMethods":[], "eventHandlers":[], "eventTriggers":[], "configurations":[]}
                """);
        var tool = new ListComponentsTool(store);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>)
                tool.execute(Map.of("interface", "io.example.Marker")).get("components");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("qualifiedName")).isEqualTo("io.example.A");
    }

    @Test
    void includesProducedFactoryOutputs(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[
                   {"qualifiedName":"example.Producers","scope":"SINGLETON","interfaces":[]}
                 ],
                 "factoryMethods":[
                   {"declaringClass":"example.Producers","methodName":"db",
                    "returnType":"javax.sql.DataSource","scope":"SINGLETON",
                    "qualifier":"primary","profiles":[],"static":false,
                    "autoCloseable":true,"requiresProxy":false,
                    "constructorDependencies":[]}
                 ],
                 "eventHandlers":[],"eventTriggers":[],"configurations":[]}
                """);
        var tool = new ListComponentsTool(store);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result =
                (List<Map<String, Object>>) tool.execute(Map.of()).get("components");

        assertThat(result).hasSize(2);
        assertThat(result).anySatisfy(c -> {
            assertThat(c.get("kind")).isEqualTo("PRODUCED");
            assertThat(c.get("qualifiedName")).isEqualTo("javax.sql.DataSource");
            assertThat(c.get("qualifier")).isEqualTo("primary");
            @SuppressWarnings("unchecked")
            var pb = (Map<String, Object>) c.get("producedBy");
            assertThat(pb.get("componentFqn")).isEqualTo("example.Producers");
            assertThat(pb.get("methodName")).isEqualTo("db");
            assertThat(pb.get("isStatic")).isEqualTo(Boolean.FALSE);
        });
    }

    private TopologyStore storeWith(Path root, String topologyJson) throws Exception {
        var f = root.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, topologyJson, StandardCharsets.UTF_8);
        return TopologyStore.loadFrom(root);
    }
}
