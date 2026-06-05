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

class ListEventsToolTest {

    @Test
    void groupsHandlersAndPublishersByEventType(@TempDir Path root) throws Exception {
        // Trigger's eventName ("OrderValidated") is a user-chosen label that does NOT
        // match the dispatched event identity (io.example.OrderPlaced). The tool must
        // join on `eventType` (the trigger method's return-type FQN), not `eventName`.
        var store = storeWith(root, """
                {"schemaVersion":1, "module":"m",
                 "components":[], "factoryMethods":[], "configurations":[],
                 "eventHandlers":[
                   {"declaringClass":"io.example.N","methodName":"on","eventType":"io.example.OrderPlaced","async":false}
                 ],
                 "eventTriggers":[
                   {"handlerClass":"io.example.OrderService","handlerMethod":"validate",
                    "eventName":"OrderValidated","eventType":"io.example.OrderPlaced",
                    "async":false,"spread":false,"guards":[]}
                 ]}
                """);
        var tool = new ListEventsTool(store);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>)
                tool.execute(Map.of("eventType", "io.example.OrderPlaced")).get("events");
        assertThat(events).hasSize(1);
        Map<String, Object> e = events.get(0);
        assertThat(e.get("eventType")).isEqualTo("io.example.OrderPlaced");
        assertThat((List<?>) e.get("publishers")).hasSize(1);
        assertThat((List<?>) e.get("handlers")).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> publisher = ((List<Map<String, Object>>) e.get("publishers")).get(0);
        assertThat(publisher.get("eventName")).isEqualTo("OrderValidated");
    }

    @Test
    void filterByEventType(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1, "module":"m",
                 "components":[], "factoryMethods":[], "configurations":[],
                 "eventHandlers":[
                   {"declaringClass":"N","methodName":"a","eventType":"io.example.A","async":false},
                   {"declaringClass":"N","methodName":"b","eventType":"io.example.B","async":false}
                 ],
                 "eventTriggers":[]}
                """);
        var tool = new ListEventsTool(store);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>)
                tool.execute(Map.of("eventType", "io.example.A")).get("events");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("eventType")).isEqualTo("io.example.A");
    }

    @Test
    void bareCallWithoutEventTypeThrows(@TempDir Path root) throws Exception {
        // Per #278: bare call would dump every event in the topology.
        var store = storeWith(root, """
                {"schemaVersion":1, "module":"m",
                 "components":[], "factoryMethods":[], "configurations":[],
                 "eventHandlers":[], "eventTriggers":[]}
                """);
        var tool = new ListEventsTool(store);
        var args = Map.<String, Object>of();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tool.execute(args))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
    }

    private TopologyStore storeWith(Path root, String topologyJson) throws Exception {
        Path f = root.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, topologyJson, StandardCharsets.UTF_8);
        return TopologyStore.loadFrom(root);
    }
}
