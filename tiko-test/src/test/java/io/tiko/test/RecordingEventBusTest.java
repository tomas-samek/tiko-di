package io.tiko.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.EventBus;
import io.tiko.EventCallback;
import io.tiko.Subscription;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class RecordingEventBusTest {

    record FooEvent(String id) {}

    record BarEvent(int n) {}

    @Test
    void capturesPublishesAndForwardsToDelegate() {
        List<Object> delegateReceived = new ArrayList<>();
        EventBus delegate = new EventBus() {
            @Override
            public <T> void publish(T event) {
                delegateReceived.add(event);
            }

            @Override
            public <T> Subscription subscribe(Class<T> t, EventCallback<T> c) {
                return new NoopSubscription();
            }
        };
        var rec = new RecordingEventBus(delegate);

        rec.publish(new FooEvent("a"));
        rec.publish(new BarEvent(7));

        assertThat(delegateReceived).hasSize(2);
        assertThat(rec.events()).hasSize(2);
        assertThat(rec.events(FooEvent.class)).containsExactly(new FooEvent("a"));
    }

    @Test
    void assertPublishedPassesWhenTypeMatched() {
        var rec = newRec();
        rec.publish(new FooEvent("a"));
        rec.assertPublished(FooEvent.class);
    }

    @Test
    void assertPublishedFailsWithDiagnosticWhenAbsent() {
        var rec = newRec();
        rec.publish(new BarEvent(1));
        assertThatThrownBy(() -> rec.assertPublished(FooEvent.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("FooEvent")
                .hasMessageContaining("BarEvent");
    }

    @Test
    void withPayloadMatchesPredicate() {
        var rec = newRec();
        rec.publish(new FooEvent("xyz"));
        rec.assertPublished(FooEvent.class).withPayload((FooEvent e) -> e.id().equals("xyz"));
    }

    @Test
    void assertPublishedExactlyCountsAcrossPublishes() {
        var rec = newRec();
        rec.publish(new FooEvent("a"));
        rec.publish(new FooEvent("b"));
        rec.assertPublishedExactly(2, FooEvent.class);
    }

    @Test
    void assertNoneOfFailsIfPresent() {
        var rec = newRec();
        rec.publish(new FooEvent("a"));
        assertThatThrownBy(() -> rec.assertNoneOf(FooEvent.class)).isInstanceOf(AssertionError.class);
    }

    @Test
    void clearResetsCaptureBuffer() {
        var rec = newRec();
        rec.publish(new FooEvent("a"));
        rec.clear();
        assertThat(rec.events()).isEmpty();
    }

    @Test
    void subscribeIsForwardedToDelegate() {
        var subscribeCalls = new java.util.concurrent.atomic.AtomicInteger();
        EventBus delegate = new EventBus() {
            @Override
            public <T> void publish(T event) {
                // no-op
            }

            @Override
            public <T> Subscription subscribe(Class<T> t, EventCallback<T> c) {
                subscribeCalls.incrementAndGet();
                return new NoopSubscription();
            }
        };
        var rec = new RecordingEventBus(delegate);

        rec.subscribe(FooEvent.class, e -> {});

        assertThat(subscribeCalls).hasValue(1);
    }

    @Test
    void assertPublishedExactlyFailsWithDiagnosticWhenCountWrong() {
        var rec = newRec();
        rec.publish(new FooEvent("a"));
        assertThatThrownBy(() -> rec.assertPublishedExactly(2, FooEvent.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected exactly 2")
                .hasMessageContaining("saw 1");
    }

    @Test
    void assertNoneOfPassesWhenAbsent() {
        var rec = newRec();
        rec.publish(new BarEvent(1));
        rec.assertNoneOf(FooEvent.class);
    }

    @Test
    void awaitAsyncDispatchWithoutExecutorThrowsIllegalState() {
        var rec = newRec();
        assertThatThrownBy(() -> rec.awaitAsyncDispatch(Duration.ofMillis(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("setEventExecutor");
    }

    @Test
    void awaitAsyncDispatchFallsBackToSleepForNonThreadPoolExecutor() throws TimeoutException {
        var rec = newRec();
        // Executors.newSingleThreadExecutor() returns a wrapper, not a ThreadPoolExecutor.
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            rec.setEventExecutor(exec);
            long start = System.nanoTime();
            rec.awaitAsyncDispatch(Duration.ofMillis(50));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            // Hit the fallback Math.min(timeout.toMillis(), 1_000L) sleep branch.
            assertThat(elapsedMs).isGreaterThanOrEqualTo(40L);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void awaitAsyncDispatchDrainsThreadPoolExecutor() throws TimeoutException, InterruptedException {
        var rec = newRec();
        ThreadPoolExecutor tpe = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            rec.setEventExecutor(tpe);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(1);
            tpe.submit(() -> {
                started.countDown();
                try {
                    finish.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            });
            started.await();
            // Release the worker, then await drain.
            finish.countDown();
            rec.awaitAsyncDispatch(Duration.ofSeconds(2));
            assertThat(tpe.getActiveCount()).isZero();
        } finally {
            tpe.shutdownNow();
        }
    }

    @Test
    void awaitAsyncDispatchTimesOutWhenExecutorNeverDrains() throws InterruptedException {
        var rec = newRec();
        ThreadPoolExecutor tpe = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        CountDownLatch hold = new CountDownLatch(1);
        try {
            rec.setEventExecutor(tpe);
            tpe.submit(() -> {
                try {
                    hold.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThatThrownBy(() -> rec.awaitAsyncDispatch(Duration.ofMillis(80)))
                    .isInstanceOf(TimeoutException.class)
                    .hasMessageContaining("did not drain");
        } finally {
            hold.countDown();
            tpe.shutdownNow();
        }
    }

    @Test
    void awaitAsyncDispatchWaitsOnInFlightAcrossTheDequeueToLockWindow() throws InterruptedException, TimeoutException {
        var rec = newRec();
        // Reproduce the ThreadPoolExecutor flake window (#443): a task has been dequeued (queue
        // empty) but the worker has not yet locked, so getActiveCount() momentarily reads 0 while
        // the task is still in flight. Forcing getActiveCount() to 0 makes that window deterministic.
        var cte =
                new CountingThreadPoolExecutor(
                        1,
                        1,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<Runnable>(),
                        Executors.defaultThreadFactory(),
                        new ThreadPoolExecutor.CallerRunsPolicy()) {
                    @Override
                    public int getActiveCount() {
                        return 0;
                    }
                };
        var release = new CountDownLatch(1);
        var running = new CountDownLatch(1);
        try {
            rec.setEventExecutor(cte);
            cte.execute(() -> {
                running.countDown();
                awaitQuietly(release);
            });
            running.await();

            // Task is in flight, real queue is empty, and getActiveCount() reads 0: the old
            // active-count/queue check would exit here. The in-flight counter must keep it waiting.
            assertThatThrownBy(() -> rec.awaitAsyncDispatch(Duration.ofMillis(120)))
                    .isInstanceOf(TimeoutException.class)
                    .hasMessageContaining("did not drain");

            release.countDown();
            rec.awaitAsyncDispatch(Duration.ofSeconds(2));
            assertThat(cte.inFlight()).isZero();
        } finally {
            release.countDown();
            cte.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static RecordingEventBus newRec() {
        return new RecordingEventBus(new EventBus() {
            @Override
            public <T> void publish(T event) {
                // no-op: test stub
            }

            @Override
            public <T> Subscription subscribe(Class<T> t, EventCallback<T> c) {
                return new NoopSubscription();
            }
        });
    }

    private static final class NoopSubscription implements Subscription {
        @Override
        public void unsubscribe() {
            // no-op: test stub
        }

        @Override
        public boolean isActive() {
            return false;
        }
    }
}
