package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import io.tiko.ErrorContext;
import io.tiko.ErrorHandler;
import io.tiko.EventDispatchRejected;
import io.tiko.EventHandlerInfo;
import io.tiko.annotations.BackoffStrategy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * End-to-end checks that the {@code ROUTE_TO_DLQ} overflow policy (#111) routes a rejected async
 * dispatch to the {@code ErrorHandler} as an {@link EventDispatchRejected} — across every async
 * submit entry point in {@link EventChainContext}, rather than throwing, dropping, or losing it.
 */
class EventChainContextDlqTest {

    private static final EventHandlerInfo INFO = new EventHandlerInfo(String.class, "onEvent", Object.class, true);

    private final List<ErrorContext> routed = new CopyOnWriteArrayList<>();
    private final ErrorHandler recorder = routed::add;
    private final LocalEventBus bus = new LocalEventBus();

    // --- Single-submit overflow: each async entry point dead-letters the rejected dispatch. ---

    @Test
    void publishAsyncOverflowRoutesDispatchRejected() throws Exception {
        try (SaturatedPool s = saturate()) {
            EventChainContext.publishAsync(bus, "trigger", null, s.pool, recorder, INFO);
            assertRouted("trigger");
        }
    }

    @Test
    void publishSpreadAsyncOverflowRoutesDispatchRejected() throws Exception {
        // Overflow happens at submit, before the spread runs, so the whole payload is dead-lettered.
        List<String> payload = List.of("a", "b");
        try (SaturatedPool s = saturate()) {
            EventChainContext.publishSpreadAsync(bus, payload, null, s.pool, recorder, INFO);
            assertRouted(payload);
        }
    }

    @Test
    void timeoutHandlerOverflowRoutesDispatchRejected() throws Exception {
        try (SaturatedPool s = saturate()) {
            EventChainContext.runAsyncWithTimeout(() -> {}, TimeUnit.SECONDS.toNanos(1), s.pool, recorder, INFO, "evt");
            assertRouted("evt");
        }
    }

    @Test
    void plainAsyncHandlerOverflowRoutesDispatchRejected() throws Exception {
        // timeout == 0 is the path the generated plain @EventHandler(async=true) dispatch now uses (#111).
        try (SaturatedPool s = saturate()) {
            EventChainContext.runAsyncWithTimeout(() -> {}, 0L, s.pool, recorder, INFO, "evt");
            assertRouted("evt");
        }
    }

    @Test
    void retryFirstSubmitOverflowRoutesDispatchRejected() throws Exception {
        RetryPolicy policy = new RetryPolicy(2, 0L, BackoffStrategy.FIXED, 0L);
        try (SaturatedPool s = saturate()) {
            EventChainContext.runAsyncWithRetry(() -> {}, policy, s.pool, recorder, INFO, "evt");
            assertRouted("evt");
        }
    }

    // --- Finding #2 fix: a backoff>0 retry RE-submission that overflows is dead-lettered, not lost. ---

    @Test
    void backoffRetryResubmitOverflowRoutesDispatchRejected() {
        // First attempt runs inline and throws → a retry is scheduled after backoff; the re-submission
        // overflows. Before the fix the DlqOverflowSignal escaped on the delay-scheduler thread and the
        // event vanished. A counting executor makes the resubmit overflow deterministic (no timing race).
        CountingExecutor executor = new CountingExecutor();
        RetryPolicy policy = new RetryPolicy(1, TimeUnit.MILLISECONDS.toNanos(20), BackoffStrategy.FIXED, 0L);

        EventChainContext.runAsyncWithRetry(
                () -> {
                    throw new IllegalStateException("attempt failed");
                },
                policy,
                executor,
                recorder,
                INFO,
                "evt");

        assertRouted("evt");
        assertThat(executor.calls.get())
                .as("first attempt submitted, then the resubmit")
                .isEqualTo(2);
    }

    @Test
    void publishSpreadAsyncDeliversEachElementOnHappyPath() {
        // Non-overflow path: each element is published, nothing is dead-lettered.
        var delivered = new CopyOnWriteArrayList<String>();
        bus.subscribe(String.class, delivered::add);
        var pool = DefaultEventExecutorFactory.create();
        try {
            EventChainContext.publishSpreadAsync(bus, List.of("a", "b"), null, pool, recorder, INFO);
            await().atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(delivered).containsExactlyInAnyOrder("a", "b"));
            assertThat(routed).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void dlqRoutingSwallowsAThrowingErrorHandler() throws Exception {
        // If the user's ErrorHandler itself throws while dead-lettering, it must be logged as a last
        // resort, never propagated to the publisher's thread.
        ErrorHandler throwing = ctx -> {
            throw new IllegalStateException("handler boom");
        };
        try (SaturatedPool s = saturate()) {
            assertThatCode(() -> EventChainContext.publishAsync(bus, "evt", null, s.pool, throwing, INFO))
                    .doesNotThrowAnyException();
        }
    }

    private void assertRouted(Object expectedEvent) {
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(routed)
                        .singleElement()
                        .isInstanceOfSatisfying(
                                EventDispatchRejected.class,
                                r -> assertThat(r.event()).isEqualTo(expectedEvent)));
    }

    /** Saturates a capacity-1 / single-worker ROUTE_TO_DLQ pool so the next submit overflows. */
    private SaturatedPool saturate() throws InterruptedException {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1),
                new OverflowRejectionHandler(OverflowPolicy.ROUTE_TO_DLQ));
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        pool.execute(() -> {
            running.countDown();
            awaitQuietly(release);
        }); // occupies the worker
        assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
        pool.execute(() -> {}); // fills the capacity-1 queue
        return new SaturatedPool(pool, release);
    }

    private record SaturatedPool(ThreadPoolExecutor pool, CountDownLatch release) implements AutoCloseable {
        @Override
        public void close() {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** Runs the first submission inline, then rejects every later one with a DlqOverflowSignal. */
    private static final class CountingExecutor extends AbstractExecutorService {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void execute(Runnable command) {
            if (calls.incrementAndGet() == 1) {
                command.run(); // first attempt runs inline (its body throws → schedules a retry)
            } else {
                throw new DlqOverflowSignal("queue full on resubmit");
            }
        }

        @Override
        public void shutdown() {
            // no-op: this fake's lifecycle is not exercised by the retry-resubmit test.
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
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
