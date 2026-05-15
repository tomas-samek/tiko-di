package io.tiko.examples.http;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Async notification handler — runs on Tiko's framework executor, NOT on the
 * HTTP worker thread. The HTTP response is already on the wire by the time
 * this runs. Exposes a {@link CountDownLatch} so the integration test can
 * deterministically wait for it instead of sleeping.
 */
@Component(scope = Scope.SINGLETON)
public class NotificationSender {

    private static final Logger LOG = Logger.getLogger("io.tiko.examples.http.notify");

    /**
     * Test hook. The integration test calls {@link #expectNotifications(int)}
     * before issuing requests, then awaits this latch. Initialised to a
     * zero-count latch so production code that never touches it doesn't
     * block.
     */
    private final AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(0));

    /** Resets the latch to count down the given number of notifications. */
    public CountDownLatch expectNotifications(int count) {
        var fresh = new CountDownLatch(count);
        latch.set(fresh);
        return fresh;
    }

    @EventHandler(async = true)
    public void onTicketCreated(TicketCreated event) {
        LOG.info(() -> "[NOTIFY req=" + event.requestId() + "] would email about ticket " + event.id());
        latch.get().countDown();
    }
}
