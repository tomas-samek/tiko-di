package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.EventHandlerInfo;
import io.tiko.annotations.BackoffStrategy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A retried async handler's next attempt runs only on the event executor or on a thread the
 * container tracks (#653). With a saturated {@code CALLER_RUNS} queue the attempt runs on the
 * thread that re-submits it after the backoff, so that thread must be one {@code close()} reports
 * when the attempt is stuck. Every scenario runs on both shapes {@code Tiko.create(...)} returns,
 * with the default executor and with a supplied one.
 */
class RetryThreadTrackingTest {

    /** Shorter than the 1 s exit grace, so a stuck worker outlives the executor drain quickly. */
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofMillis(50);

    private static final EventHandlerInfo INFO =
            new EventHandlerInfo(RetryThreadTrackingTest.class, "onEvent", String.class, true);

    private final List<List<Thread>> reports = new CopyOnWriteArrayList<>();
    private final List<ErrorContext> errors = new CopyOnWriteArrayList<>();
    private final List<Thread> stuck = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch fillerRelease = new CountDownLatch(1);

    @TempDir
    Path tmp;

    private ClassLoader previousLoader;
    private ThreadPoolExecutor supplied;

    enum Shape {
        SINGLE_MODULE,
        AGGREGATED
    }

    enum ExecutorKind {
        DEFAULT,
        SUPPLIED
    }

    static Stream<Arguments> shapesAndExecutors() {
        return Stream.of(Shape.values())
                .flatMap(shape -> Stream.of(ExecutorKind.values()).map(kind -> Arguments.of(shape, kind)));
    }

    @BeforeEach
    void rememberContextLoader() {
        previousLoader = Thread.currentThread().getContextClassLoader();
        CapturingLoggerFinder.clear();
    }

    @AfterEach
    void releaseStuckThreads() throws InterruptedException {
        release.countDown();
        fillerRelease.countDown();
        for (var thread : stuck) {
            thread.join(Duration.ofSeconds(5));
        }
        if (supplied != null) {
            supplied.shutdownNow();
            assertThat(supplied.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        Thread.currentThread().setContextClassLoader(previousLoader);
        assertThat(stuck).noneMatch(Thread::isAlive);
    }

    @ParameterizedTest
    @MethodSource("shapesAndExecutors")
    void retryStuckAfterItsBackoffOnASaturatedCallerRunsQueueIsReported(Shape shape, ExecutorKind kind)
            throws Exception {
        var container = boot(shape, kind);
        saturate((ThreadPoolExecutor) container.getEventExecutor());
        var entered = new CountDownLatch(1);
        var attempts = new AtomicInteger();

        EventChainContext.runAsyncWithRetry(
                () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("first attempt fails");
                    }
                    stuck.add(Thread.currentThread());
                    entered.countDown();
                    awaitIgnoringInterrupts(release);
                },
                new RetryPolicy(1, Duration.ofMillis(10).toNanos(), BackoffStrategy.FIXED, 0L),
                StubContainer.lastDispatchExecutor,
                errors::add,
                INFO,
                "evt");
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        container.close();

        assertThat(reports)
                .singleElement()
                .satisfies(threads -> assertThat(threads).containsExactly(stuck.getFirst()));
        assertThat(stuck.getFirst().getName()).startsWith("tiko-event-retry-");
    }

    @ParameterizedTest
    @MethodSource("shapesAndExecutors")
    void idleRetrySchedulerIsNotReported(Shape shape, ExecutorKind kind) throws Exception {
        var container = boot(shape, kind);
        var succeeded = new CountDownLatch(1);
        var attempts = new AtomicInteger();

        EventChainContext.runAsyncWithRetry(
                () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("first attempt fails");
                    }
                    succeeded.countDown();
                },
                new RetryPolicy(1, Duration.ofMillis(10).toNanos(), BackoffStrategy.FIXED, 0L),
                StubContainer.lastDispatchExecutor,
                errors::add,
                INFO,
                "evt");
        assertThat(succeeded.await(10, TimeUnit.SECONDS)).isTrue();

        container.close();

        assertThat(reports).isEmpty();
        assertThat(errors).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("shapesAndExecutors")
    void retryPendingAtCloseIsDroppedWithAWarning(Shape shape, ExecutorKind kind) throws Exception {
        var container = boot(shape, kind);
        // Saturated, so the first attempt runs and fails on this thread and its retry is scheduled
        // before runAsyncWithRetry returns.
        saturate((ThreadPoolExecutor) container.getEventExecutor());
        var attempts = new AtomicInteger();

        EventChainContext.runAsyncWithRetry(
                () -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("attempt fails");
                },
                new RetryPolicy(1, Duration.ofMinutes(10).toNanos(), BackoffStrategy.FIXED, 0L),
                StubContainer.lastDispatchExecutor,
                errors::add,
                INFO,
                "evt");
        assertThat(attempts.get()).isEqualTo(1);

        container.close();

        assertThat(reports).isEmpty();
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(CapturingLoggerFinder.RECORDS)
                .filteredOn(r -> r.message() != null && r.message().contains("pending async retr"))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.level()).isEqualTo(System.Logger.Level.WARNING);
                    assertThat(r.message()).startsWith("1 pending async retry was dropped");
                });
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void retryOfAnAttemptFailingAfterCloseIsDroppedWithAWarning(Shape shape) throws Exception {
        var container = boot(shape, ExecutorKind.SUPPLIED);
        var dispatchExecutor = StubContainer.lastDispatchExecutor;
        container.close();
        var attempts = new AtomicInteger();
        var ran = new CountDownLatch(1);

        EventChainContext.runAsyncWithRetry(
                () -> {
                    attempts.incrementAndGet();
                    ran.countDown();
                    throw new IllegalStateException("attempt fails");
                },
                new RetryPolicy(1, Duration.ofMillis(10).toNanos(), BackoffStrategy.FIXED, 0L),
                dispatchExecutor,
                errors::add,
                INFO,
                "evt");
        assertThat(ran.await(10, TimeUnit.SECONDS)).isTrue();

        await().atMost(Duration.ofSeconds(10))
                .until(() -> CapturingLoggerFinder.RECORDS.stream()
                        .anyMatch(r -> r.message() != null
                                && r.message()
                                        .equals("Event of type java.lang.String was dropped: the container is "
                                                + "shutting down.")));
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(errors).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void suppliedExecutorIsTheOneTheContainerReturns(Shape shape) {
        var container = boot(shape, ExecutorKind.SUPPLIED);
        try {
            assertThat(container.getEventExecutor()).isSameAs(supplied);
        } finally {
            container.close();
        }
    }

    /** Occupies the executor's only worker and its only queue slot until {@link #fillerRelease}. */
    private void saturate(ThreadPoolExecutor executor) throws InterruptedException {
        var started = new CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            awaitInterruptibly(fillerRelease);
        });
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        executor.execute(() -> awaitInterruptibly(fillerRelease));
        assertThat(executor.getQueue().remainingCapacity()).isZero();
    }

    /** Boots with a one-worker, one-slot {@code CALLER_RUNS} executor of the given kind. */
    private Container boot(Shape shape, ExecutorKind kind) {
        var options = TikoOptions.builder().shutdownTimeout(SHUTDOWN_TIMEOUT).onThreadsOutlivingClose(reports::add);
        if (kind == ExecutorKind.SUPPLIED) {
            supplied = new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(1),
                    new ThreadPoolExecutor.CallerRunsPolicy());
            options.eventExecutor(supplied);
        } else {
            options.queueCapacity(1)
                    .eventExecutorCoreSize(1)
                    .eventExecutorMaxSize(1)
                    .onOverflow(OverflowPolicy.CALLER_RUNS);
        }
        if (shape == Shape.AGGREGATED) {
            Thread.currentThread().setContextClassLoader(testDescriptorLoader());
            options.testWiring(true);
        }
        return Tiko.create(options.build());
    }

    private ClassLoader testDescriptorLoader() {
        try {
            var meta = Files.createDirectories(tmp.resolve("META-INF").resolve("tiko"));
            var descriptor = new Properties();
            descriptor.setProperty("impl", StubContainer.class.getName());
            try (var out = Files.newOutputStream(meta.resolve("test-container.properties"))) {
                descriptor.store(out, "test");
            }
            Files.writeString(meta.resolve("components.txt"), "");
            return new URLClassLoader(
                    new URL[] {tmp.toUri().toURL()}, getClass().getClassLoader());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void awaitInterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Waits for {@code latch} through interrupts, like a handler that does not honour them. */
    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        var interrupted = false;
        while (latch.getCount() > 0) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
