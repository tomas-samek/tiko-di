package io.tiko.test.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * An async handler that leaks its worker thread: it blocks until {@link #releaseAndJoin()} and
 * ignores interrupts, so the thread survives the executor's {@code shutdownNow()} (#637).
 *
 * <p>Inert in every other test of this module, which never publish {@link StuckEvent}. The test that
 * publishes it calls {@link #arm()} before and {@link #releaseAndJoin()} after, so no stuck thread
 * outlives it.
 */
@Component(scope = Scope.SINGLETON)
public class StuckHandler {

    private static final List<Thread> STUCK = new CopyOnWriteArrayList<>();
    private static volatile CountDownLatch entered = new CountDownLatch(1);
    private static volatile CountDownLatch release = new CountDownLatch(1);

    @EventHandler(async = true)
    public void onStuck(StuckEvent event) {
        var releaseLatch = release;
        STUCK.add(Thread.currentThread());
        entered.countDown();
        var interrupted = false;
        while (releaseLatch.getCount() > 0) {
            try {
                releaseLatch.await();
            } catch (InterruptedException e) {
                // Deliberately ignored: this handler stands for code that does not honour interrupts.
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Resets the latches before a test publishes {@link StuckEvent}. */
    public static void arm() {
        STUCK.clear();
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
    }

    /** Waits until a handler invocation is blocked; true when one is. */
    public static boolean awaitEntered(Duration timeout) throws InterruptedException {
        return entered.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Opens the latch and waits for every stuck thread to leave the handler. */
    public static void releaseAndJoin() throws InterruptedException {
        release.countDown();
        for (var thread : STUCK) {
            thread.join(Duration.ofSeconds(5));
        }
        STUCK.clear();
    }

    /** The threads blocked in, or released from, the handler since {@link #arm()}. */
    public static List<Thread> stuckThreads() {
        return List.copyOf(STUCK);
    }
}
