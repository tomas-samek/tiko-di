package io.tiko.examples.basic;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.events.ApplicationEndingEvent;
import io.tiko.events.ApplicationStartedEvent;
import io.tiko.events.EventEndingEvent;
import io.tiko.events.EventStartedEvent;
import io.tiko.events.RequestEndingEvent;
import io.tiko.events.RequestStartedEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Captures every lifecycle event the container emits. Used by
 * {@code LifecycleEventsTest} to assert ordering and timing.
 */
@Component(scope = Scope.SINGLETON)
public class LifecycleRecorder {

    private final List<Object> events = new CopyOnWriteArrayList<>();

    @EventHandler
    public void onApplicationStarted(ApplicationStartedEvent event) {
        events.add(event);
    }

    @EventHandler
    public void onApplicationEnding(ApplicationEndingEvent event) {
        events.add(event);
    }

    @EventHandler
    public void onRequestStarted(RequestStartedEvent event) {
        events.add(event);
    }

    @EventHandler
    public void onRequestEnding(RequestEndingEvent event) {
        events.add(event);
    }

    @EventHandler
    public void onEventStarted(EventStartedEvent event) {
        events.add(event);
    }

    @EventHandler
    public void onEventEnding(EventEndingEvent event) {
        events.add(event);
    }

    public List<Object> getEvents() {
        return List.copyOf(events);
    }
}
