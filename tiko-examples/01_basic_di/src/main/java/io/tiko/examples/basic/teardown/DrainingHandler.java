package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.Inject;
import java.util.concurrent.CountDownLatch;

/**
 * Test fixture for {@code CloseDrainOrderTest}: an async handler that waits at {@link #gate} and
 * then reads a SINGLETON, so a test can close the container while the handler is still running.
 */
@Component(scope = Scope.SINGLETON)
public class DrainingHandler {

    /** Counted down when the handler has started; set by the test. */
    public static volatile CountDownLatch entered = new CountDownLatch(1);

    /** The handler waits here before it reads the resource; set and released by the test. */
    public static volatile CountDownLatch gate = new CountDownLatch(0);

    private final DrainedResource resource;

    @Inject
    public DrainingHandler(DrainedResource resource) {
        this.resource = resource;
    }

    @EventHandler(async = true)
    public void onPing(DrainPing event) {
        entered.countDown();
        try {
            gate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            TeardownRecorder.record("handler interrupted");
            return;
        }
        TeardownRecorder.record("handler read the resource, closed=" + resource.isClosed());
    }
}
