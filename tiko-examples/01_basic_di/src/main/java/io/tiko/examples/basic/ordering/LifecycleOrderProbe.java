package io.tiko.examples.basic.ordering;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.Inject;
import io.tiko.annotations.PostConstruct;
import io.tiko.events.ApplicationStartedEvent;
import io.tiko.events.EventEndingEvent;
import io.tiko.events.EventStartedEvent;
import io.tiko.events.RequestEndingEvent;
import io.tiko.events.RequestStartedEvent;

/**
 * Records lifecycle-event arrivals and the synchronous user-handler call into the shared
 * {@link OrderLog}, so {@code LifecycleOrderingTest} can assert their ordering relative to
 * {@code @PostConstruct} and to each other (#167).
 */
@Component(scope = Scope.SINGLETON)
public class LifecycleOrderProbe {

    private final OrderLog log;

    @Inject
    public LifecycleOrderProbe(OrderLog log) {
        this.log = log;
    }

    @PostConstruct
    public void init() {
        log.record("PC:Probe");
    }

    @EventHandler
    public void onApplicationStarted(ApplicationStartedEvent e) {
        log.record("APP_STARTED");
    }

    @EventHandler
    public void onRequestStarted(RequestStartedEvent e) {
        log.record("REQ_START");
    }

    @EventHandler
    public void onRequestEnding(RequestEndingEvent e) {
        log.record("REQ_END");
    }

    @EventHandler
    public void onEventStarted(EventStartedEvent e) {
        log.record("EVT_START");
    }

    @EventHandler
    public void onEventEnding(EventEndingEvent e) {
        log.record("EVT_END");
    }

    @EventHandler
    public void onPing(Ping ping) {
        log.record("PING_HANDLED");
    }
}
