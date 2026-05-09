package io.tiko.examples.asyncstart;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.events.ApplicationStartedEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Stand-in for expensive one-time startup work — connection-pool warmup, cache priming,
 * remote-config fetch, schema migration, etc. The 500 ms sleep is deliberately chunky
 * so the demo's timestamps make the off-critical-path behaviour obvious.
 *
 * <p>Because the {@link EventHandler} is annotated with {@code async = true}, the
 * framework dispatches this method on the shared event executor instead of inline.
 * That means {@code Tiko.create()} publishes {@link ApplicationStartedEvent} and
 * returns to the caller without waiting for this method to finish.
 */
@Component(scope = Scope.SINGLETON)
public class SlowStartupHook {

    /** How long the simulated warmup blocks for. */
    static final long WORK_MS = 500;

    private static final CountDownLatch DONE = new CountDownLatch(1);

    @EventHandler(async = true)
    public void onApplicationStarted(ApplicationStartedEvent event) {
        try {
            Thread.sleep(WORK_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            DONE.countDown();
        }
    }

    /** Blocks until the async handler signals completion. */
    static void awaitDone() throws InterruptedException {
        if (!DONE.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("async ApplicationStartedEvent handler did not complete within 5s");
        }
    }
}
