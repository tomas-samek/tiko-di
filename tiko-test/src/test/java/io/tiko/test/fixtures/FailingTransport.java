package io.tiko.test.fixtures;

import io.tiko.Container;
import io.tiko.TransportBootstrap;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * A transport that starts an event-executor thread and then refuses to start, so the boot fails
 * after the executor has a live worker (#637). Discovered only through the class loader that
 * {@code TikoTestTeardownTest}'s boot-failure fixture installs (resource root {@code boot-failure/}),
 * never by the other tests of this module.
 *
 * <p>When {@link StuckHandler#armAtStartup()} is in effect, it refuses only once the async
 * {@code ApplicationStartedEvent} handler is stuck, so the failed boot leaves that worker running (#646).
 */
public class FailingTransport implements TransportBootstrap {

    private static volatile Thread worker;

    @Override
    public void start(Container container) {
        var started = new CompletableFuture<Thread>();
        container.getEventExecutor().execute(() -> started.complete(Thread.currentThread()));
        try {
            worker = started.get();
            if (StuckHandler.armedAtStartup() && !StuckHandler.awaitEntered(Duration.ofSeconds(10))) {
                throw new IllegalStateException("the startup handler never got stuck");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalStateException("transport refused to start");
    }

    @Override
    public void shutdown() {
        // nothing started
    }

    /** The executor worker the last failed start used, or {@code null}. */
    public static Thread worker() {
        return worker;
    }

    public static void reset() {
        worker = null;
    }
}
