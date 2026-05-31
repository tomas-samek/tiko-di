package io.tiko.examples.http;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.events.EventEndingEvent;
import io.tiko.events.EventStartedEvent;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Subscribes to Tiko's framework lifecycle events to demonstrate that every
 * HTTP request — including reads, including 404s — opens and closes a Tiko
 * request scope, and therefore gets per-request observability for free.
 *
 * <p>The framework's {@code EventStartedEvent.requestId()} is a separate
 * identifier from the application's {@link RequestId#value()}. Both are
 * carried in the logs so tests can correlate; in a real app the framework
 * ID is what oncall would search for in distributed traces, and the
 * application ID would come from / propagate to a header like
 * {@code X-Request-Id}.
 */
@Component(scope = Scope.SINGLETON)
public class RequestTimer {

    private static final System.Logger LOG = System.getLogger("io.tiko.examples.http.timer");

    private final AtomicInteger startedCount = new AtomicInteger();
    private final AtomicInteger endedCount = new AtomicInteger();

    @EventHandler
    public void onRequestStarted(EventStartedEvent event) {
        startedCount.incrementAndGet();
        LOG.log(System.Logger.Level.INFO, () -> "[REQ " + event.eventId() + "] started at " + event.timestamp());
    }

    @EventHandler
    public void onRequestEnding(EventEndingEvent event) {
        endedCount.incrementAndGet();
        LOG.log(System.Logger.Level.INFO, () -> "[REQ " + event.eventId() + "] completed in " + event.duration());
    }

    public int startedCount() {
        return startedCount.get();
    }

    public int endedCount() {
        return endedCount.get();
    }
}
