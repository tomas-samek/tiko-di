package io.tiko.examples.http;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deliberately slow async audit handler used to demonstrate the
 * graceful-shutdown drain (#48) end-to-end. Runs on Tiko's framework
 * executor (not the HTTP worker thread), so the HTTP response returns
 * before this handler completes.
 *
 * <p>The {@link CountDownLatch} test hook (mirrors {@link NotificationSender})
 * lets the integration test wait deterministically for completion.
 */
@Component(scope = Scope.SINGLETON)
public class SlowAuditService {

    private static final Duration WORK_DURATION = Duration.ofSeconds(2);

    private final AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(0));

    /** Resets the latch to count down once when the slow handler completes. */
    public CountDownLatch expectOne() {
        var fresh = new CountDownLatch(1);
        latch.set(fresh);
        return fresh;
    }

    @EventHandler(async = true)
    public void onTicketCreated(TicketCreated event) {
        // Slow path is opt-in via expectOne(). Tests/main that don't arm the latch
        // see this handler return immediately, avoiding a per-event 2s tax.
        CountDownLatch current = latch.get();
        if (current.getCount() == 0) {
            return;
        }
        System.out.println("[async] slow audit work starting for ticket " + event.id());
        try {
            Thread.sleep(WORK_DURATION.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }
        System.out.println("[async] slow audit work complete for ticket " + event.id());
        current.countDown();
    }
}
