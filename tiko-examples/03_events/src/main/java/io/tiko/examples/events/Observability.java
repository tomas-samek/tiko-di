package io.tiko.examples.events;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.events.ApplicationEndingEvent;
import io.tiko.events.ApplicationStartedEvent;
import io.tiko.events.EventEndingEvent;
import io.tiko.events.EventStartedEvent;
import io.tiko.events.RequestEndingEvent;
import io.tiko.events.RequestStartedEvent;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Observability layer wired purely through container-published lifecycle events. Counts
 * scope entries and accumulates total elapsed time per scope, then prints a tiny report
 * on application shutdown — without a single line of instrumentation code in the
 * business handlers.
 */
@Component(scope = Scope.SINGLETON)
public class Observability {

    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicInteger eventCount = new AtomicInteger();
    private final AtomicLong totalRequestNanos = new AtomicLong();
    private final AtomicLong totalEventNanos = new AtomicLong();

    @EventHandler
    public void onApplicationStarted(ApplicationStartedEvent event) {
        System.out.println("[obs] application started at " + event.timestamp());
    }

    @EventHandler
    public void onRequestStarted(RequestStartedEvent event) {
        requestCount.incrementAndGet();
    }

    @EventHandler
    public void onRequestEnding(RequestEndingEvent event) {
        totalRequestNanos.addAndGet(event.duration().toNanos());
    }

    @EventHandler
    public void onEventStarted(EventStartedEvent event) {
        eventCount.incrementAndGet();
    }

    @EventHandler
    public void onEventEnding(EventEndingEvent event) {
        totalEventNanos.addAndGet(event.duration().toNanos());
    }

    @EventHandler
    public void onApplicationEnding(ApplicationEndingEvent event) {
        System.out.println();
        System.out.println("[obs] -- shutdown report --");
        System.out.println("[obs] uptime         : " + event.uptime());
        System.out.println("[obs] requests       : " + requestCount.get() + " (total "
                + Duration.ofNanos(totalRequestNanos.get()) + ")");
        System.out.println("[obs] events         : " + eventCount.get() + " (total "
                + Duration.ofNanos(totalEventNanos.get()) + ")");
    }
}
