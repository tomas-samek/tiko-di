package io.tiko.examples.basic;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import java.util.UUID;

/**
 * EVENT-scoped context implementation.
 * One instance per event processing.
 *
 * When injected into SINGLETON/REQUEST scope, a proxy will be generated automatically.
 */
@Component(scope = Scope.EVENT)
public class EventContextImpl implements EventContext {

    private final String eventId;
    private final long timestamp;

    public EventContextImpl() {
        this.eventId = "EVT-" + UUID.randomUUID().toString().substring(0, 8);
        this.timestamp = System.currentTimeMillis();
        System.out.println("[EventContext] Created for event: " + eventId);
    }

    @Override
    public String getEventId() {
        return eventId;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "EventContext{eventId='" + eventId + "', timestamp=" + timestamp + "}";
    }
}
