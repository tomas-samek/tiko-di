package io.tiko.examples.basic;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * Test fixture for {@code OverflowThrowTest}: an async handler that waits at {@link #gate} when a
 * test has set one, so the test can fill the event executor's queue behind it.
 */
@Component(scope = Scope.SINGLETON)
public class GatedAsyncRecorder {

    public static final List<Integer> handled = new CopyOnWriteArrayList<>();

    /** Counted down when a handler has started; set by the test. */
    public static volatile CountDownLatch entered = new CountDownLatch(1);

    /** The handler waits here when non-null; set and released by the test. */
    public static volatile CountDownLatch gate;

    /** Counted down after each handled event; set by the test to the number it expects. */
    public static volatile CountDownLatch done = new CountDownLatch(0);

    @EventHandler(async = true)
    public void onPing(GatedPing event) {
        entered.countDown();
        var waitAt = gate;
        if (waitAt != null) {
            awaitGate(waitAt);
        }
        handled.add(event.number());
        done.countDown();
    }

    private static void awaitGate(CountDownLatch waitAt) {
        try {
            waitAt.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
