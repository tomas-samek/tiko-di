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

class TraceEventFlowToolTest {

    @Test
    void tracesLinearChain(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[],"factoryMethods":[],"configurations":[],
                 "eventHandlers":[
                   {"declaringClass":"example.OrderService","methodName":"validate",
                    "eventType":"example.events.OrderPlaced","async":false,"hasEventWrapper":false}
                 ],
                 "eventTriggers":[
                   {"handlerClass":"example.OrderService","handlerMethod":"validate",
                    "eventName":"OrderValidated","eventType":"example.events.OrderValidated",
                    "async":false,"spread":false,"guards":[]}
                 ]}
                """);
        var tool = new TraceEventFlowTool(store);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>)
                tool.execute(Map.of("eventType", "example.events.OrderPlaced")).get("nodes");
        assertThat(nodes).hasSize(2);
        assertThat(nodes.get(0).get("event")).isEqualTo("example.events.OrderPlaced");
        assertThat(nodes.get(1).get("event")).isEqualTo("example.events.OrderValidated");
        assertThat(nodes.get(1).get("terminal")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void cycleDetected(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[],"factoryMethods":[],"configurations":[],
                 "eventHandlers":[
                   {"declaringClass":"x.A","methodName":"h1","eventType":"x.E1","async":false,"hasEventWrapper":false},
                   {"declaringClass":"x.B","methodName":"h2","eventType":"x.E2","async":false,"hasEventWrapper":false}
                 ],
                 "eventTriggers":[
                   {"handlerClass":"x.A","handlerMethod":"h1","eventName":"E2","eventType":"x.E2","async":false,"spread":false,"guards":[]},
                   {"handlerClass":"x.B","handlerMethod":"h2","eventName":"E1","eventType":"x.E1","async":false,"spread":false,"guards":[]}
                 ]}
                """);
        var tool = new TraceEventFlowTool(store);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>)
                tool.execute(Map.of("eventType", "x.E1")).get("nodes");
        // Spec: cycle-back to E1 is emitted as a duplicate E1 node with cycle=true and empty edges.
        assertThat(nodes).hasSize(3);
        assertThat(nodes.get(0)).containsEntry("event", "x.E1").containsEntry("cycle", false);
        assertThat(nodes.get(1)).containsEntry("event", "x.E2").containsEntry("cycle", false);
        assertThat(nodes.get(2)).containsEntry("event", "x.E1").containsEntry("cycle", true);
        @SuppressWarnings("unchecked")
        var cycleNodeEdges = (List<Map<String, Object>>) nodes.get(2).get("edges");
        assertThat(cycleNodeEdges).isEmpty();
    }

    @Test
    void unknownEventThrows(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[],"factoryMethods":[],"configurations":[],
                 "eventHandlers":[],"eventTriggers":[]}
                """);
        var tool = new TraceEventFlowTool(store);
        assertThatThrownBy(() -> tool.execute(Map.of("eventType", "nope.NotAnEvent")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown event");
    }

    @Test
    void maxDepthCutoff(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m",
                 "components":[],"factoryMethods":[],"configurations":[],
                 "eventHandlers":[
                   {"declaringClass":"x.A","methodName":"h1","eventType":"x.E1","async":false,"hasEventWrapper":false},
                   {"declaringClass":"x.B","methodName":"h2","eventType":"x.E2","async":false,"hasEventWrapper":false},
                   {"declaringClass":"x.C","methodName":"h3","eventType":"x.E3","async":false,"hasEventWrapper":false}
                 ],
                 "eventTriggers":[
                   {"handlerClass":"x.A","handlerMethod":"h1","eventName":"E2","eventType":"x.E2","async":false,"spread":false,"guards":[]},
                   {"handlerClass":"x.B","handlerMethod":"h2","eventName":"E3","eventType":"x.E3","async":false,"spread":false,"guards":[]},
                   {"handlerClass":"x.C","handlerMethod":"h3","eventName":"E4","eventType":"x.E4","async":false,"spread":false,"guards":[]}
                 ]}
                """);
        var tool = new TraceEventFlowTool(store);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>)
                tool.execute(Map.of("eventType", "x.E1", "maxDepth", 1L)).get("nodes");
        // E1 (depth 0) and E2 (depth 1) emitted. E3 (depth 2) and E4 (depth 3) skipped by the cutoff.
        assertThat(nodes).hasSize(2);
        assertThat(nodes.get(0)).containsEntry("event", "x.E1").containsEntry("depth", 0L);
        assertThat(nodes.get(1)).containsEntry("event", "x.E2").containsEntry("depth", 1L);
    }

    private TopologyStore storeWith(Path root, String json) throws Exception {
        var f = root.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, json, StandardCharsets.UTF_8);
        return TopologyStore.loadFrom(root);
    }
}
