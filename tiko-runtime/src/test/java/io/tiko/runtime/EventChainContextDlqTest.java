package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.ErrorContext;
import io.tiko.ErrorHandler;
import io.tiko.EventDispatchRejected;
import io.tiko.EventHandlerInfo;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * End-to-end check that the {@code ROUTE_TO_DLQ} overflow policy (#111) routes a rejected async
 * dispatch to the {@code ErrorHandler} as an {@link EventDispatchRejected}, rather than throwing,
 * dropping, or blocking.
 */
class EventChainContextDlqTest {

    private static final EventHandlerInfo INFO = new EventHandlerInfo(String.class, "onEvent", Object.class, true);

    @Test
    void routeToDlqOverflowRoutesEventDispatchRejected() throws Exception {
        // capacity-1 queue, single worker, ROUTE_TO_DLQ — saturate, then the next submit overflows.
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1),
                new OverflowRejectionHandler(OverflowPolicy.ROUTE_TO_DLQ));
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workerRunning = new CountDownLatch(1);
        List<ErrorContext> routed = new CopyOnWriteArrayList<>();
        ErrorHandler recorder = routed::add;
        var bus = new LocalEventBus();
        var overflowingEvent = "overflow-me";
        try {
            pool.execute(() -> {
                workerRunning.countDown();
                awaitQuietly(release);
            }); // occupies the only worker
            assertThat(workerRunning.await(2, TimeUnit.SECONDS)).isTrue();
            pool.execute(() -> {}); // fills the capacity-1 queue

            // This dispatch must overflow and be routed to the DLQ, not thrown back to us.
            EventChainContext.publishAsync(bus, overflowingEvent, null, pool, recorder, INFO);

            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(routed)
                            .singleElement()
                            .isInstanceOfSatisfying(
                                    EventDispatchRejected.class,
                                    r -> assertThat(r.event()).isEqualTo(overflowingEvent)));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
