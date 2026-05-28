package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.ErrorContext;
import io.tiko.PreDestroyFailure;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests the teardown-bounding primitive (#106). The generated container shutdown delegates
 * each SINGLETON {@code @PreDestroy} / factory {@code AutoCloseable.close()} to
 * {@link BoundedExecution#run}, so the inline / bounded / timeout / thrown paths are pinned here
 * once rather than re-asserted through compile-testing.
 *
 * <p>Deterministic via latches — the "hung" hook blocks on a latch that is never released, so the
 * only thing that can unblock it is the {@code cancel(true)} interrupt the timeout path fires.
 */
class BoundedExecutionTest {

    private static final Function<Throwable, ErrorContext> AS_PREDESTROY =
            t -> new PreDestroyFailure(BoundedExecutionTest.class, t);

    @Test
    void nullTimeoutRunsInlineAndSucceeds() {
        AtomicReference<ErrorContext> routed = new AtomicReference<>();
        AtomicBoolean ran = new AtomicBoolean(false);

        BoundedExecution.run(() -> ran.set(true), null, routed::set, AS_PREDESTROY);

        assertThat(ran).isTrue();
        assertThat(routed.get()).isNull();
    }

    @Test
    void nullTimeoutRoutesThrownFailureInline() {
        AtomicReference<ErrorContext> routed = new AtomicReference<>();
        RuntimeException boom = new RuntimeException("boom");

        BoundedExecution.run(
                () -> {
                    throw boom;
                },
                null,
                routed::set,
                AS_PREDESTROY);

        assertThat(routed.get()).isInstanceOf(PreDestroyFailure.class);
        assertThat(((PreDestroyFailure) routed.get()).cause()).isSameAs(boom);
    }

    @Test
    void boundedFastTaskCompletesWithoutTimeout() {
        AtomicReference<ErrorContext> routed = new AtomicReference<>();
        AtomicBoolean ran = new AtomicBoolean(false);

        BoundedExecution.run(() -> ran.set(true), Duration.ofSeconds(5), routed::set, AS_PREDESTROY);

        assertThat(ran).isTrue();
        assertThat(routed.get()).isNull();
    }

    @Test
    void boundedSlowTaskTimesOutAndInterrupts() throws InterruptedException {
        AtomicReference<ErrorContext> routed = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);

        BoundedExecution.CheckedRunnable hung = () -> {
            started.countDown();
            try {
                neverReleased.await(); // only the timeout's cancel(true) interrupt can wake this
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
        };

        BoundedExecution.run(hung, Duration.ofMillis(50), routed::set, AS_PREDESTROY);

        assertThat(started.await(2, TimeUnit.SECONDS))
                .as("hook must have started")
                .isTrue();
        assertThat(routed.get()).isInstanceOf(PreDestroyFailure.class);
        assertThat(((PreDestroyFailure) routed.get()).cause())
                .as("an overrun routes a TimeoutException-caused failure")
                .isInstanceOf(TimeoutException.class);
        assertThat(interrupted.await(2, TimeUnit.SECONDS))
                .as("cancel(true) must interrupt the hung hook")
                .isTrue();
    }

    @Test
    void boundedTaskThrowsRoutesOriginalCause() {
        AtomicReference<ErrorContext> routed = new AtomicReference<>();
        IllegalStateException boom = new IllegalStateException("kaboom");

        BoundedExecution.run(
                () -> {
                    throw boom;
                },
                Duration.ofSeconds(5),
                routed::set,
                AS_PREDESTROY);

        assertThat(routed.get()).isInstanceOf(PreDestroyFailure.class);
        assertThat(((PreDestroyFailure) routed.get()).cause())
                .as("a thrown failure is routed with its original cause, not wrapped in ExecutionException")
                .isSameAs(boom);
    }

    @Test
    void zeroTimeoutRoutesTimeoutForAHookThatCannotFinishInstantly() throws InterruptedException {
        AtomicReference<ErrorContext> routed = new AtomicReference<>();
        CountDownLatch neverReleased = new CountDownLatch(1);

        BoundedExecution.CheckedRunnable hung = () -> {
            try {
                neverReleased.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        BoundedExecution.run(hung, Duration.ZERO, routed::set, AS_PREDESTROY);

        assertThat(routed.get()).isInstanceOf(PreDestroyFailure.class);
        assertThat(((PreDestroyFailure) routed.get()).cause()).isInstanceOf(TimeoutException.class);
    }
}
