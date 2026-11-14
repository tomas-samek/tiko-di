package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void teardownRetainsMultipleObserverFailuresWithoutSelfSuppression() {
        var firstHookFailure = new IllegalStateException("first hook");
        var firstObserverFailure = new IllegalArgumentException("observer failed");
        var secondHookFailure = new IllegalStateException("second hook");
        var errors = new BoundedExecution.TeardownErrors(
                context -> {
                    if (context.cause() == firstHookFailure) {
                        throw firstObserverFailure;
                    }
                    throw secondHookFailure;
                },
                null);

        errors.onError(AS_PREDESTROY.apply(firstHookFailure));
        errors.onError(AS_PREDESTROY.apply(secondHookFailure));

        assertThatThrownBy(() -> errors.rethrowIfFailed(false)).isSameAs(firstObserverFailure);
        assertThat(firstObserverFailure.getSuppressed()).containsExactly(firstHookFailure, secondHookFailure);
        assertThat(secondHookFailure.getSuppressed()).isEmpty();
    }

    /**
     * The bound counts from the moment the hook starts (#699): even a zero bound runs the hook
     * before it reports the timeout, so a hook is never reported as timed out without having run.
     * Repeated because a hook its bound cancels before the worker reaches it would be skipped only
     * sometimes.
     */
    @Test
    void timeoutIsReportedOnlyForAHookThatStarted() throws InterruptedException {
        for (var attempt = 0; attempt < 20; attempt++) {
            AtomicReference<ErrorContext> routed = new AtomicReference<>();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            CountDownLatch neverReleased = new CountDownLatch(1);

            BoundedExecution.CheckedRunnable hung = () -> {
                started.countDown();
                try {
                    neverReleased.await();
                } catch (InterruptedException e) {
                    interrupted.countDown();
                }
            };

            BoundedExecution.run(hung, Duration.ZERO, routed::set, AS_PREDESTROY);

            assertThat(started.await(2, TimeUnit.SECONDS))
                    .as("attempt %d: the timed-out hook ran", attempt)
                    .isTrue();
            assertThat(((PreDestroyFailure) routed.get()).cause()).isInstanceOf(TimeoutException.class);
            assertThat(interrupted.await(2, TimeUnit.SECONDS))
                    .as("attempt %d: the timed-out hook is interrupted", attempt)
                    .isTrue();
        }
    }

    /**
     * #709: a closing thread that arrives interrupted (a worker stopped by {@code shutdownNow()}
     * whose {@code finally} closes the container) still runs the bounded hook, and keeps its flag.
     */
    @Test
    void boundedHookRunsWhenTheCallerArrivesInterrupted() {
        AtomicReference<ErrorContext> routed = new AtomicReference<>();
        AtomicBoolean ran = new AtomicBoolean();

        Thread.currentThread().interrupt();
        boolean stillInterrupted;
        try {
            BoundedExecution.run(() -> ran.set(true), Duration.ofSeconds(5), routed::set, AS_PREDESTROY);
        } finally {
            stillInterrupted = Thread.interrupted();
        }

        assertThat(ran).as("the hook ran").isTrue();
        assertThat(routed.get()).isNull();
        assertThat(stillInterrupted).as("the caller's interrupt flag is kept").isTrue();
    }

    /** #709: an interrupt that arrives while the hook runs does not cut the hook short either. */
    @Test
    void boundedHookFinishesWhenTheCallerIsInterruptedWhileWaiting() throws InterruptedException {
        AtomicReference<ErrorContext> routed = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var caller = Thread.currentThread();
        var interrupter = Thread.ofPlatform().daemon().start(() -> {
            try {
                entered.await();
                caller.interrupt();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                release.countDown();
            }
        });

        boolean stillInterrupted;
        try {
            BoundedExecution.run(
                    () -> {
                        entered.countDown();
                        release.await();
                        finished.set(true);
                    },
                    Duration.ofSeconds(5),
                    routed::set,
                    AS_PREDESTROY);
        } finally {
            stillInterrupted = Thread.interrupted();
        }
        interrupter.join(Duration.ofSeconds(5));

        assertThat(finished).as("the hook ran to its end").isTrue();
        assertThat(routed.get()).isNull();
        assertThat(stillInterrupted).as("the caller's interrupt flag is kept").isTrue();
    }

    /** #709: the bound still applies to a hook an interrupted caller runs, and its overrun is routed. */
    @Test
    void boundedHungHookTimesOutWhenTheCallerArrivesInterrupted() throws InterruptedException {
        AtomicReference<ErrorContext> routed = new AtomicReference<>();
        CountDownLatch hookInterrupted = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        BoundedExecution.CheckedRunnable hung = () -> {
            try {
                neverReleased.await();
            } catch (InterruptedException e) {
                hookInterrupted.countDown();
            }
        };

        Thread.currentThread().interrupt();
        boolean stillInterrupted;
        try {
            BoundedExecution.run(hung, Duration.ofMillis(50), routed::set, AS_PREDESTROY);
        } finally {
            stillInterrupted = Thread.interrupted();
        }

        assertThat(routed.get()).isInstanceOf(PreDestroyFailure.class);
        assertThat(((PreDestroyFailure) routed.get()).cause()).isInstanceOf(TimeoutException.class);
        assertThat(hookInterrupted.await(2, TimeUnit.SECONDS))
                .as("the overrunning hook is interrupted")
                .isTrue();
        assertThat(stillInterrupted).as("the caller's interrupt flag is kept").isTrue();
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
