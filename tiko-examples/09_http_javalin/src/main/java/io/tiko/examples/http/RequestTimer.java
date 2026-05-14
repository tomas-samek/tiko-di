package io.tiko.examples.http;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.events.RequestEndingEvent;
import io.tiko.events.RequestStartedEvent;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Subscribes to Tiko's framework lifecycle events to demonstrate that every
 * HTTP request — including reads, including 404s — opens and closes a Tiko
 * request scope, and therefore gets per-request observability for free.
 *
 * <p>The framework's {@code RequestStartedEvent.requestId()} is a separate
 * identifier from the application's {@link RequestId#value()}. Both are
 * carried in the logs so tests can correlate; in a real app the framework
 * ID is what oncall would search for in distributed traces, and the
 * application ID would come from / propagate to a header like
 * {@code X-Request-Id}.
 */
@Component(scope = Scope.SINGLETON)
public class RequestTimer {

    private static final Logger LOG = Logger.getLogger("io.tiko.examples.http.timer");

    private final AtomicInteger startedCount = new AtomicInteger();
    private final AtomicInteger endedCount = new AtomicInteger();

    @EventHandler
    public void onRequestStarted(RequestStartedEvent event) {
        startedCount.incrementAndGet();
        LOG.info(() -> "[REQ " + event.requestId() + "] started at " + event.timestamp());
    }

    @EventHandler
    public void onRequestEnding(RequestEndingEvent event) {
        endedCount.incrementAndGet();
        LOG.info(() -> "[REQ " + event.requestId() + "] completed in " + event.duration());
    }

    public int startedCount() {
        return startedCount.get();
    }

    public int endedCount() {
        return endedCount.get();
    }
}
