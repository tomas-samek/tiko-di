package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.DeliveryFailureKind;
import io.tiko.ErrorContext;
import io.tiko.EventDispatchRejected;
import io.tiko.EventHandlerError;
import io.tiko.EventHandlerInfo;
import io.tiko.annotations.BackoffStrategy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * #678: what a Tiko module that runs work of its own for a booted container, coroutines for one,
 * asks of that boot: threads it tracks, deliveries it waits for, and state it stops when it closes.
 */
class ContainerWorkTest {

    /** State of a module that notes when the boot stops it, and until when. */
    private static class Recorded implements ContainerWork.Stoppable {
        final List<Long> stoppedWithNanosLeft = new ArrayList<>();
        int toldCloseBegan;

        @Override
        public void closeBegan() {
            toldCloseBegan++;
        }

        @Override
        public void stop(long deadlineNanos) {
            stoppedWithNanosLeft.add(deadlineNanos - System.nanoTime());
        }
    }

    private static final class Other extends Recorded {}

    private static final class Failing implements ContainerWork.Stoppable {
        @Override
        public void closeBegan() {
            throw new IllegalStateException("this module failed when close began");
        }

        @Override
        public void stop(long deadlineNanos) {
            throw new IllegalStateException("this module failed to stop");
        }
    }

    private final OwnedThreads owner = new OwnedThreads();
    private final ExecutorService supplied = Executors.newSingleThreadExecutor();
    private final ContainerWork work = ContainerWork.of(new RetrySchedulingExecutor(supplied, owner));

    @Test
    void anExecutorThatIsNotABootsIsRefused() {
        try {
            assertThatThrownBy(() -> ContainerWork.of(supplied))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Tiko.create");
        } finally {
            supplied.shutdown();
        }
    }

    /** #679: the limit and the policy a module applies to work of its own are the boot's. */
    @Test
    void tellsTheBootsQueueCapacityAndOverflowPolicy() {
        var limited = ContainerWork.of(new RetrySchedulingExecutor(supplied, new OwnedThreads(3, OverflowPolicy.DROP)));
        var defaults = TikoOptions.builder().build();

        assertThat(limited.queueCapacity()).isEqualTo(3);
        assertThat(limited.onOverflow()).isEqualTo(OverflowPolicy.DROP);
        assertThat(work.queueCapacity()).isEqualTo(defaults.queueCapacity());
        assertThat(work.onOverflow()).isEqualTo(defaults.onOverflow());
        supplied.shutdown();
    }

    /** #679: a publish from a thread of the boot must not wait for work of that same boot (#727). */
    @Test
    void aThreadTheBootCreatedIsItsOwn() throws InterruptedException {
        var onVirtual = new AtomicBoolean();
        var onPlatform = new AtomicBoolean();
        var virtual = work.virtualThreads("own-").newThread(() -> onVirtual.set(work.isOwnThread()));
        var platform = owner.newThread(() -> onPlatform.set(work.isOwnThread()));

        virtual.start();
        platform.start();
        virtual.join();
        platform.join();

        assertThat(onVirtual).as("a virtual thread of the boot, while it runs").isTrue();
        assertThat(onPlatform)
                .as("a platform thread of the boot: an async worker")
                .isTrue();
        assertThat(work.isOwnThread()).as("the test's thread").isFalse();
        supplied.shutdown();
    }

    /** #679: an event that meets the limit under ROUTE_TO_DLQ is handed over as async dispatch does it. */
    @Test
    void aRejectedDispatchReachesTheErrorHandlerAsEventDispatchRejected() {
        var seen = new ArrayList<ErrorContext>();

        EventChainContext.routeDispatchRejected("an event", seen::add);
        EventChainContext.routeDispatchRejected("another", context -> {
            throw new IllegalStateException("the error handler failed");
        });

        assertThat(seen).containsExactly(new EventDispatchRejected("an event"));
        supplied.shutdown();
    }

    /** #680: a module learns that close() began at the moment async retries are dropped, before the wait. */
    @Test
    void closeBeganIsToldOnceBeforeTheWaitForDeliveries() {
        work.stoppedAtClose(Failing.class, Failing::new);
        var early = work.stoppedAtClose(Recorded.class, Recorded::new).orElseThrow();

        owner.awaitDeliveries(System.nanoTime() + 20_000_000L);
        owner.awaitDeliveries(System.nanoTime() + 20_000_000L);
        var late = work.stoppedAtClose(Other.class, Other::new).orElseThrow();

        assertThat(early.toldCloseBegan)
                .as("once, whatever another module threw")
                .isEqualTo(1);
        assertThat(early.stoppedWithNanosLeft).as("not stopped yet").isEmpty();
        assertThat(late.toldCloseBegan)
                .as("created after close began: told as it is created")
                .isEqualTo(1);
        supplied.shutdown();
    }

    /** #680: what shows that work which waits holds no thread. */
    @Test
    void countsTheVirtualThreadsThatAreRunning() throws InterruptedException {
        var running = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blocked = work.virtualThreads("counted-").newThread(() -> {
            running.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        int before = work.virtualThreadsRunning();
        blocked.start();
        running.await();
        int during = work.virtualThreadsRunning();
        release.countDown();
        blocked.join();

        assertThat(List.of(before, during, work.virtualThreadsRunning())).containsExactly(0, 1, 0);
        supplied.shutdown();
    }

    /** #680: a retried suspend handler reports its attempts as an async one does. */
    @Test
    void aFailureIsRoutedWithItsAttemptCount() {
        var seen = new ArrayList<ErrorContext>();
        var info = new EventHandlerInfo(ContainerWorkTest.class, "onText", String.class, true);
        var cause = new IllegalStateException("the handler failed");

        EventChainContext.routeHandlerFailure(cause, seen::add, info, "an event", 3);
        EventChainContext.routeHandlerFailure(cause, seen::add, info, "an event", 1);

        assertThat(seen)
                .containsExactly(
                        new EventHandlerError(info, "an event", cause, 3),
                        new EventHandlerError(info, "an event", cause, 1));
        assertThat(seen)
                .extracting(context -> ((EventHandlerError) context).kind())
                .containsExactly(DeliveryFailureKind.EXHAUSTED, DeliveryFailureKind.EXCEPTION);
        supplied.shutdown();
    }

    @ParameterizedTest(name = "{0}, before retry {1}: {2} ns")
    @CsvSource({"FIXED, 0, 100", "FIXED, 3, 100", "EXPONENTIAL, 0, 100", "EXPONENTIAL, 1, 200", "EXPONENTIAL, 3, 800"})
    void backoffDelayIsConstantForFixedAndDoublesForExponential(
            BackoffStrategy strategy, int attemptIndex, long expectedNanos) {
        var policy = new RetryPolicy(5, 100L, strategy, 0L);

        assertThat(EventChainContext.backoffDelayNanos(policy, attemptIndex)).isEqualTo(expectedNanos);
        supplied.shutdown();
    }

    @Test
    void aRetainedDeliveryIsPendingUntilReleasedOnce() {
        var release = work.retainDelivery();
        int whileRetained = owner.deliveries().count();
        boolean drainedWhileRetained = owner.awaitDeliveries(System.nanoTime() + 20_000_000L);

        release.run();
        release.run();

        assertThat(whileRetained).isEqualTo(1);
        assertThat(drainedWhileRetained).as("close() waits for it").isFalse();
        assertThat(owner.deliveries().count())
                .as("released once, however often the release runs")
                .isZero();
        assertThat(owner.awaitDeliveries(System.nanoTime() + 20_000_000L)).isTrue();
        supplied.shutdown();
    }

    @Test
    void theBootsOneInstanceIsCreatedOnce() {
        var created = new AtomicInteger();

        var first = work.stoppedAtClose(Recorded.class, () -> {
            created.incrementAndGet();
            return new Recorded();
        });
        var second = work.stoppedAtClose(Recorded.class, Recorded::new);

        assertThat(first).isPresent();
        assertThat(second).containsSame(first.get());
        assertThat(created).hasValue(1);
        supplied.shutdown();
    }

    @Test
    void stopWorkStopsEachInstanceOnceAndGivesItAtLeastASecond() {
        var one = work.stoppedAtClose(Recorded.class, Recorded::new).orElseThrow();
        var other = work.stoppedAtClose(Other.class, Other::new).orElseThrow();

        owner.stopWork(System.nanoTime()); // the caller's own deadline has already passed
        owner.stopWork(System.nanoTime());

        assertThat(one.stoppedWithNanosLeft).hasSize(1);
        assertThat(other.stoppedWithNanosLeft).hasSize(1);
        assertThat(one.stoppedWithNanosLeft.get(0))
                .as("what a cancelled coroutine needs to run its cleanup")
                .isBetween(
                        Duration.ofMillis(900).toNanos(), Duration.ofSeconds(1).toNanos());
        supplied.shutdown();
    }

    @Test
    void aLaterDeadlineIsPassedOn() {
        var one = work.stoppedAtClose(Recorded.class, Recorded::new).orElseThrow();

        owner.stopWork(System.nanoTime() + Duration.ofSeconds(30).toNanos());

        assertThat(one.stoppedWithNanosLeft.get(0))
                .isGreaterThan(Duration.ofSeconds(29).toNanos());
        supplied.shutdown();
    }

    @Test
    void nothingIsCreatedOnceCloseHasBegun() {
        owner.stopWork(System.nanoTime());

        var late = work.stoppedAtClose(Recorded.class, () -> {
            throw new AssertionError("created after close() began");
        });

        assertThat(late).isEmpty();
        supplied.shutdown();
    }

    @Test
    void aModuleThatFailsToStopDoesNotKeepTheOthersRunning() {
        work.stoppedAtClose(Failing.class, Failing::new);
        var after = work.stoppedAtClose(Recorded.class, Recorded::new).orElseThrow();

        owner.stopWork(System.nanoTime());

        assertThat(after.stoppedWithNanosLeft).hasSize(1);
        supplied.shutdown();
    }

    @Test
    void virtualThreadsAreNamedByPrefixAndTrackedWhileTheyRun() throws InterruptedException {
        var threads = work.virtualThreads("tiko-coroutine-");
        var running = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = threads.newThread(() -> {});
        var blocked = threads.newThread(() -> {
            running.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        finished.start();
        finished.join();
        blocked.start();
        running.await();
        try {
            var alive = owner.stillAlive(Duration.ofMillis(50));

            assertThat(blocked.isVirtual()).isTrue();
            assertThat(blocked.getName()).startsWith("tiko-coroutine-");
            assertThat(finished.getName()).startsWith("tiko-coroutine-").isNotEqualTo(blocked.getName());
            assertThat(alive)
                    .as("reported while it runs, forgotten once it has finished")
                    .containsExactly(blocked);
        } finally {
            release.countDown();
            blocked.join();
            supplied.shutdown();
        }
        assertThat(owner.stillAlive(Duration.ofMillis(50))).isEmpty();
    }
}
