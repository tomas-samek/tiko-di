package io.tiko.examples.basic.ordering;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.Inject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * An async handler that blocks on a gate so a test can deterministically observe that scope exit
 * does NOT await it — proving async dispatch detaches from the triggering scope (#167). The gate
 * removes any timing race: the handler cannot record until the test opens it.
 */
@Component(scope = Scope.SINGLETON)
public class AsyncProbe {

    private final OrderLog log;
    private final CountDownLatch gate = new CountDownLatch(1);
    private final CountDownLatch done = new CountDownLatch(1);

    @Inject
    public AsyncProbe(OrderLog log) {
        this.log = log;
    }

    @EventHandler(async = true)
    public void onAsyncPing(AsyncPing ping) {
        try {
            gate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        log.record("ASYNC_HANDLED");
        done.countDown();
    }

    /** Releases the gated async handler. */
    public void openGate() {
        gate.countDown();
    }

    /** Waits for the async handler to finish recording. */
    public boolean awaitDone(long timeout, TimeUnit unit) throws InterruptedException {
        return done.await(timeout, unit);
    }
}
