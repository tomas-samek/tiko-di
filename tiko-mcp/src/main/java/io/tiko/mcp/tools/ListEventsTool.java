package io.tiko.mcp.tools;

import io.tiko.mcp.TopologyStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * MCP tool: list every event type carrying at least one {@code @EventHandler}
 * or {@code @EventTrigger}. Each entry lists handlers (subscribers) and publishers
 * (declarative triggers). Programmatic {@code EventBus.publish(...)} calls are
 * invisible to the processor and not included.
 */
public final class ListEventsTool {

    public static final String NAME = "list_events";

    private final TopologyStore store;

    public ListEventsTool(TopologyStore store) {
        this.store = store;
    }

    public Map<String, Object> execute(Map<String, Object> args) {
        String filter =
                args.get("eventType") == null ? null : args.get("eventType").toString();

        // Join handlers and triggers on the trigger-method return-type FQN — the actual
        // event identity. `eventName` is a user-chosen label and may not match the FQN
        // (e.g. `@EventTrigger(eventName = "OrderValidated")` on a method returning
        // `example.events.OrderValidated`).
        var eventTypes = new LinkedHashSet<String>();
        for (var h : store.eventHandlers()) {
            String t = (String) h.get("eventType");
            if (t != null) eventTypes.add(t);
        }
        for (var t : store.eventTriggers()) {
            String type = (String) t.get("eventType");
            if (type != null) eventTypes.add(type);
        }

        var events = new ArrayList<Map<String, Object>>();
        for (String eventType : eventTypes) {
            if (filter != null && !filter.equals(eventType)) continue;
            var entry = new LinkedHashMap<String, Object>();
            entry.put("eventType", eventType);
            entry.put(
                    "publishers",
                    store.eventTriggers().stream()
                            .filter(t -> eventType.equals(t.get("eventType")))
                            .map(t -> {
                                var p = new LinkedHashMap<String, Object>();
                                p.put("class", t.get("handlerClass"));
                                p.put("method", t.get("handlerMethod"));
                                p.put("eventName", t.get("eventName"));
                                p.put("async", t.get("async"));
                                return (Map<String, Object>) p;
                            })
                            .toList());
            entry.put(
                    "handlers",
                    store.eventHandlers().stream()
                            .filter(h -> eventType.equals(h.get("eventType")))
                            .map(h -> {
                                var p = new LinkedHashMap<String, Object>();
                                p.put("class", h.get("declaringClass"));
                                p.put("method", h.get("methodName"));
                                p.put("async", h.get("async"));
                                return (Map<String, Object>) p;
                            })
                            .toList());
            events.add(entry);
        }

        var out = new LinkedHashMap<String, Object>();
        out.put("events", events);
        return out;
    }
}
