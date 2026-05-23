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
 * {@code eventTriggers[]} sections. Programmatic {@code EventBus.publish(...)} calls are not seen.
 */
public final class TraceEventFlowTool {

    public static final String NAME = "trace_event_flow";

    private static final long DEFAULT_MAX_DEPTH = 20L;

    private final TopologyStore store;

    public TraceEventFlowTool(TopologyStore store) {
        this.store = store;
    }

    public Map<String, Object> execute(Map<String, Object> args) {
        var eventType = required(args, "eventType");
        long maxDepth = args.get("maxDepth") instanceof Long l ? l : DEFAULT_MAX_DEPTH;

        if (handlersFor(eventType).isEmpty() && !isReachable(eventType)) {
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
            if (!isCycle) {
                for (var handler : handlersFor(f.event())) {
                    var handlerClass = (String) handler.get("declaringClass");
                    var handlerMethod = (String) handler.get("methodName");
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
            }
            node.put("edges", edges);
            node.put("terminal", edges.isEmpty() && !isCycle);
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

    private static String required(Map<String, Object> args, String key) {
        var v = args.get(key);
        if (v == null || v.toString().isEmpty()) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return v.toString();
    }

    private record Frame(String event, long depth) {}
}
