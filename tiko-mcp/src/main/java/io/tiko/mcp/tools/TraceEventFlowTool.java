package io.tiko.mcp.tools;

import io.tiko.mcp.TopologyStore;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool: walks {@code @EventTrigger} chains and returns the event-flow DAG starting from the
 * given event type. Each node holds the event FQN plus the outbound edges (handler -> trigger).
 * Terminal events (no handlers or no triggers) carry {@code terminal: true}; revisits carry
 * {@code cycle: true}.
 *
 * <p>Purely static — derived from the processor's {@code eventHandlers[]} and
 * {@code eventTriggers[]} sections, plus the Kafka transport edges from
 * {@code topology-kafka.json} (#312): a node carries {@code kafkaIngress} when a
 * {@code @KafkaSource} publishes that event (topic → event) and {@code kafkaEgress} when a
 * {@code @KafkaSink} forwards it (event → topic). A sink-carried event is therefore no
 * longer reported as {@code terminal}. Programmatic {@code EventBus.publish(...)} calls are
 * not seen.
 */
public final class TraceEventFlowTool {

    public static final String NAME = "trace_event_flow";

    private static final long DEFAULT_MAX_DEPTH = 20L;

    private static final String DECLARING_CLASS = "declaringClass";
    private static final String METHOD_NAME = "methodName";
    private static final String TOPIC = "topic";

    private final TopologyStore store;

    public TraceEventFlowTool(TopologyStore store) {
        this.store = store;
    }

    public Map<String, Object> execute(Map<String, Object> args) {
        var eventType = ToolArgs.required(args, "eventType");
        long maxDepth = args.get("maxDepth") instanceof Long l ? l : DEFAULT_MAX_DEPTH;

        if (!isKnownEvent(eventType)) {
            throw new IllegalArgumentException("Unknown event '" + eventType + "'.");
        }

        var nodes = new ArrayList<Map<String, Object>>();
        var visited = new HashSet<String>();
        var queue = new ArrayDeque<Frame>();
        queue.add(new Frame(eventType, 0L));

        while (!queue.isEmpty()) {
            var f = queue.poll();
            if (f.depth() > maxDepth) continue;

            boolean isCycle = !visited.add(f.event());
            var node = new LinkedHashMap<String, Object>();
            node.put("event", f.event());
            node.put("depth", f.depth());
            node.put("cycle", isCycle);

            var edges = new ArrayList<Map<String, Object>>();
            var kafkaIngress = new ArrayList<Map<String, Object>>();
            var kafkaEgress = new ArrayList<Map<String, Object>>();
            if (!isCycle) {
                for (var handler : handlersFor(f.event())) {
                    var handlerClass = (String) handler.get(DECLARING_CLASS);
                    var handlerMethod = (String) handler.get(METHOD_NAME);
                    for (var trig : triggersOn(handlerClass, handlerMethod)) {
                        var next = (String) trig.get("eventType");
                        var edge = new LinkedHashMap<String, Object>();
                        edge.put("via", handlerClass + "#" + handlerMethod);
                        edge.put("eventName", trig.get("eventName"));
                        edge.put("async", trig.getOrDefault("async", false));
                        edge.put("spread", trig.getOrDefault("spread", false));
                        edge.put("guards", trig.getOrDefault("guards", List.of()));
                        edge.put("nextEvent", next);
                        edges.add(edge);
                        if (next != null) queue.add(new Frame(next, f.depth() + 1));
                    }
                }
                kafkaIngress.addAll(kafkaIngressFor(f.event()));
                kafkaEgress.addAll(kafkaEgressFor(f.event()));
            }
            node.put("edges", edges);
            // Kafka edges are emitted only when present, keeping non-Kafka nodes unchanged.
            if (!kafkaIngress.isEmpty()) node.put("kafkaIngress", kafkaIngress);
            if (!kafkaEgress.isEmpty()) node.put("kafkaEgress", kafkaEgress);
            // A @KafkaSink carries the event onward, so it is not a dead end.
            node.put("terminal", edges.isEmpty() && kafkaEgress.isEmpty() && !isCycle);
            nodes.add(node);
        }

        var out = new LinkedHashMap<String, Object>();
        out.put("root", eventType);
        out.put("nodes", nodes);
        return out;
    }

    private List<Map<String, Object>> handlersFor(String eventType) {
        var result = new ArrayList<Map<String, Object>>();
        for (var h : store.eventHandlers()) {
            if (eventType.equals(h.get("eventType"))) result.add(h);
        }
        return result;
    }

    private List<Map<String, Object>> triggersOn(String handlerClass, String handlerMethod) {
        var result = new ArrayList<Map<String, Object>>();
        for (var t : store.eventTriggers()) {
            if (handlerClass.equals(t.get("handlerClass")) && handlerMethod.equals(t.get("handlerMethod"))) {
                result.add(t);
            }
        }
        return result;
    }

    private boolean isReachable(String eventType) {
        for (var t : store.eventTriggers()) {
            if (eventType.equals(t.get("eventType"))) return true;
        }
        return false;
    }

    /**
     * An event is traceable if anything in the static graph references it: a handler, a
     * trigger target, a {@code @KafkaSource} that publishes it, or a {@code @KafkaSink} that
     * forwards it. The Kafka cases are what let a Kafka-ingested or Kafka-forwarded event be
     * traced at all (#312) — before, such an event was reported as unknown.
     */
    private boolean isKnownEvent(String eventType) {
        return !handlersFor(eventType).isEmpty()
                || isReachable(eventType)
                || !kafkaIngressFor(eventType).isEmpty()
                || !kafkaEgressFor(eventType).isEmpty();
    }

    /** {@code @KafkaSource} edges that publish {@code eventType} onto the bus (topic → event). */
    private List<Map<String, Object>> kafkaIngressFor(String eventType) {
        var result = new ArrayList<Map<String, Object>>();
        for (var s : store.kafkaSources()) {
            if (eventType.equals(s.get("eventType"))) {
                var edge = new LinkedHashMap<String, Object>();
                edge.put(TOPIC, s.get(TOPIC));
                edge.put("consumerGroup", s.get("consumerGroup"));
                edge.put("via", s.get(DECLARING_CLASS) + "#" + s.get(METHOD_NAME));
                result.add(edge);
            }
        }
        return result;
    }

    /** {@code @KafkaSink} edges that forward {@code eventType} to a topic (event → topic). */
    private List<Map<String, Object>> kafkaEgressFor(String eventType) {
        var result = new ArrayList<Map<String, Object>>();
        for (var s : store.kafkaSinks()) {
            if (eventType.equals(s.get("eventType"))) {
                var edge = new LinkedHashMap<String, Object>();
                edge.put(TOPIC, s.get(TOPIC));
                edge.put("partitionKey", s.get("partitionKey"));
                edge.put("via", s.get(DECLARING_CLASS) + "#" + s.get(METHOD_NAME));
                result.add(edge);
            }
        }
        return result;
    }

    private record Frame(String event, long depth) {}
}
