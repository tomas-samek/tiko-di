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

    @Test
    void kafkaSinkCarriedEventIsNotTerminalAndExposesEgress(@TempDir Path root) throws Exception {
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m","components":[],"factoryMethods":[],"configurations":[],
                 "eventHandlers":[],"eventTriggers":[]}
                """, """
                {"schemaVersion":1,"kafkaSources":[],
                 "kafkaSinks":[
                   {"declaringClass":"x.OrderPublisher","methodName":"toKafka","topic":"orders",
                    "partitionKey":"orderId","eventType":"x.OrderPlaced","payloadType":"x.OrderPlaced"}
                 ]}
                """);
        var tool = new TraceEventFlowTool(store);

        var node = onlyNode(tool.execute(Map.of("eventType", "x.OrderPlaced")));
        assertThat(node.get("terminal")).isEqualTo(Boolean.FALSE);
        @SuppressWarnings("unchecked")
        var egress = (List<Map<String, Object>>) node.get("kafkaEgress");
        assertThat(egress).hasSize(1);
        assertThat(egress.get(0)).containsEntry("topic", "orders").containsEntry("partitionKey", "orderId");
    }

    @Test
    void kafkaSourceProducedEventIsTraceableWithIngress(@TempDir Path root) throws Exception {
        // No handler/trigger references this event — before #312 trace_event_flow threw "Unknown event".
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m","components":[],"factoryMethods":[],"configurations":[],
                 "eventHandlers":[],"eventTriggers":[]}
                """, """
                {"schemaVersion":1,"kafkaSinks":[],
                 "kafkaSources":[
                   {"declaringClass":"x.OrderConsumer","methodName":"fromKafka","topic":"orders",
                    "consumerGroup":"warehouse","eventType":"x.OrderPlaced","payloadType":"x.OrderPlaced",
                    "eventName":"OrderPlaced"}
                 ]}
                """);
        var tool = new TraceEventFlowTool(store);

        var node = onlyNode(tool.execute(Map.of("eventType", "x.OrderPlaced")));
        @SuppressWarnings("unchecked")
        var ingress = (List<Map<String, Object>>) node.get("kafkaIngress");
        assertThat(ingress).hasSize(1);
        assertThat(ingress.get(0)).containsEntry("topic", "orders").containsEntry("consumerGroup", "warehouse");
    }

    @Test
    void confirmsKafkaEndToEndPathFromIngestTopicToSinkTopic(@TempDir Path root) throws Exception {
        // ingest topic "orders" -> OrderPlaced -(handler+trigger)-> Shipment -> sink topic "shipments"
        var store = storeWith(root, """
                {"schemaVersion":1,"module":"m","components":[],"factoryMethods":[],"configurations":[],
                 "eventHandlers":[
                   {"declaringClass":"x.Warehouse","methodName":"on","eventType":"x.OrderPlaced",
                    "async":false,"hasEventWrapper":false}
                 ],
                 "eventTriggers":[
                   {"handlerClass":"x.Warehouse","handlerMethod":"on","eventName":"Shipment",
                    "eventType":"x.Shipment","async":false,"spread":false,"guards":[]}
                 ]}
                """, """
                {"schemaVersion":1,
                 "kafkaSources":[
                   {"declaringClass":"x.OrderConsumer","methodName":"fromKafka","topic":"orders",
                    "consumerGroup":"warehouse","eventType":"x.OrderPlaced","payloadType":"x.OrderPlaced",
                    "eventName":"OrderPlaced"}
                 ],
                 "kafkaSinks":[
                   {"declaringClass":"x.ShipmentPublisher","methodName":"toKafka","topic":"shipments",
                    "partitionKey":"id","eventType":"x.Shipment","payloadType":"x.Shipment"}
                 ]}
                """);
        var tool = new TraceEventFlowTool(store);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>)
                tool.execute(Map.of("eventType", "x.OrderPlaced")).get("nodes");
        assertThat(nodes).hasSize(2);
        // Ingress on the root event.
        assertThat(nodes.get(0)).containsKey("kafkaIngress");
        // Terminal Shipment node now forwards to a sink topic instead of reading as a dead end.
        var shipment = nodes.get(1);
        assertThat(shipment.get("event")).isEqualTo("x.Shipment");
        assertThat(shipment.get("terminal")).isEqualTo(Boolean.FALSE);
        @SuppressWarnings("unchecked")
        var egress = (List<Map<String, Object>>) shipment.get("kafkaEgress");
        assertThat(egress).hasSize(1);
        assertThat(egress.get(0)).containsEntry("topic", "shipments");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> onlyNode(Map<String, Object> result) {
        var nodes = (List<Map<String, Object>>) result.get("nodes");
        assertThat(nodes).hasSize(1);
        return nodes.get(0);
    }

    private TopologyStore storeWith(Path root, String json) throws Exception {
        var f = root.resolve("m/target/classes/META-INF/tiko/topology.json");
        Files.createDirectories(f.getParent());
        Files.writeString(f, json, StandardCharsets.UTF_8);
        return TopologyStore.loadFrom(root);
    }

    private TopologyStore storeWith(Path root, String topologyJson, String kafkaJson) throws Exception {
        var dir = root.resolve("m/target/classes/META-INF/tiko");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("topology.json"), topologyJson, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("topology-kafka.json"), kafkaJson, StandardCharsets.UTF_8);
        return TopologyStore.loadFrom(root);
    }
}
