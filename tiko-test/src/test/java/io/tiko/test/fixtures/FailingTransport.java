package io.tiko.test.fixtures;

import io.tiko.Container;
import io.tiko.TransportBootstrap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * A transport that starts an event-executor thread and then refuses to start, so the boot fails
 * after the executor has a live worker (#637). Discovered only through the class loader that
 * {@code TikoTestTeardownTest}'s boot-failure fixture installs (resource root {@code boot-failure/}),
 * never by the other tests of this module.
 */
public class FailingTransport implements TransportBootstrap {

    private static volatile Thread worker;

    @Override
    public void start(Container container) {
        var started = new CompletableFuture<Thread>();
        container.getEventExecutor().execute(() -> started.complete(Thread.currentThread()));
        try {
            worker = started.get();
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
