package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.PreDestroyFailure;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Threads a container starts and that are still running once {@code close()} has finished are
 * reported, once, to {@code TikoOptions.onThreadsOutlivingClose} (#634). Every scenario runs on
 * both boot shapes {@code Tiko.create(...)} returns: the single-module fast path (the generated
 * container behind the bootstrap's wrapper) and the aggregated path.
 */
class ThreadsOutlivingCloseTest {

    /** Shorter than the 1 s exit grace, so a stuck worker outlives the executor drain quickly. */
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofMillis(50);

    /** The longest teardown bound a hook gets to start in before the test gives up on the machine. */
    private static final Duration MAX_TEARDOWN_BOUND = Duration.ofMillis(6400);

    private final List<List<Thread>> reports = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);

    @TempDir
    Path tmp;

    private ClassLoader previousLoader;

    /** The two shapes {@code Tiko.create(...)} returns. */
    enum Shape {
        SINGLE_MODULE,
        AGGREGATED
    }

    @BeforeEach
    void rememberContextLoader() {
        previousLoader = Thread.currentThread().getContextClassLoader();
        CapturingLoggerFinder.clear();
    }

    @AfterEach
    void releaseStuckThreads() {
        release.countDown();
        Thread.currentThread().setContextClassLoader(previousLoader);
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void asyncHandlerStuckPastShutdownIsReported(Shape shape) throws Exception {
        var container = boot(shape, reportingOptions());
        var started = new CountDownLatch(1);
        container.getEventExecutor().execute(ignoringInterrupts(started));
        started.await();

        container.close();

        assertThat(reports)
                .singleElement()
                .satisfies(threads -> assertThat(threads)
                        .singleElement()
                        .satisfies(t -> assertThat(t.getName()).startsWith("tiko-event-async-")));
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void cleanCloseReportsNothing(Shape shape) throws Exception {
        var container = boot(shape, reportingOptions());
        var ran = new CountDownLatch(1);
        container.getEventExecutor().execute(ran::countDown);
        ran.await();

        container.close();

        assertThat(reports).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void threadThatExitsWithinTheGraceIsNotReported(Shape shape) throws Exception {
        var container = boot(shape, reportingOptions());
        var started = new CountDownLatch(1);
        container.getEventExecutor().execute(exitingShortlyAfterInterrupt(started));
        started.await();

        container.close();

        assertThat(reports).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void interruptedCloserStillGivesThreadsTheGrace(Shape shape) throws Exception {
        var container = boot(shape, reportingOptions());
        var started = new CountDownLatch(1);
        container.getEventExecutor().execute(exitingShortlyAfterInterrupt(started));
        started.await();

        boolean stillInterrupted;
        Thread.currentThread().interrupt();
        try {
            container.close();
        } finally {
            stillInterrupted = Thread.interrupted();
        }

        assertThat(reports).isEmpty();
        assertThat(stillInterrupted)
                .as("the closing thread's interrupt is restored")
                .isTrue();
    }

    /** Finishes its last bit of work about 100 ms after the interrupt, then exits. */
    private Runnable exitingShortlyAfterInterrupt(CountDownLatch started) {
        return () -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                await().pollDelay(Duration.ofMillis(100)).until(() -> true);
                Thread.currentThread().interrupt();
            }
        };
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void secondCloseReportsNothing(Shape shape) throws Exception {
        var container = boot(shape, reportingOptions());
        var started = new CountDownLatch(1);
        container.getEventExecutor().execute(ignoringInterrupts(started));
        started.await();

        container.close();
        container.close();

        assertThat(reports).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void threadsFromTheContainersThreadFactoryAreTracked(Shape shape) throws Exception {
        var container = boot(shape, reportingOptions());
        assertThat(container.threadFactory()).isInstanceOf(OwnedThreads.class);
        var started = new CountDownLatch(1);
        var thread = container.threadFactory().newThread(ignoringInterrupts(started));
        thread.setName("transport-consumer");
        thread.start();
        started.await();

        container.close();

        assertThat(reports)
                .singleElement()
                .satisfies(threads -> assertThat(threads).singleElement().isSameAs(thread));
    }

    @Test
    void defaultHandlerLogsAWarningNamingTheThread() throws Exception {
        var container = boot(Shape.SINGLE_MODULE, TikoOptions.builder().shutdownTimeout(SHUTDOWN_TIMEOUT));
        var started = new CountDownLatch(1);
        container.getEventExecutor().execute(ignoringInterrupts(started));
        started.await();

        container.close();

        assertThat(CapturingLoggerFinder.RECORDS)
                .filteredOn(r -> r.message() != null && r.message().startsWith("Container closed"))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.loggerName()).isEqualTo("io.tiko.events");
                    assertThat(r.level()).isEqualTo(System.Logger.Level.WARNING);
                    assertThat(r.message())
                            .startsWith("Container closed, but 1 thread(s) it started are still running: ")
                            .containsPattern("tiko-event-async-\\d+ \\(at .+\\)");
                });
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void userSuppliedExecutorThreadsAreNotTracked(Shape shape) throws Exception {
        var userExecutor = Executors.newSingleThreadExecutor();
        try {
            var container = boot(
                    shape, TikoOptions.builder().eventExecutor(userExecutor).onThreadsOutlivingClose(reports::add));
            var started = new CountDownLatch(1);
            container.getEventExecutor().execute(ignoringInterrupts(started));
            started.await();

            container.close();

            assertThat(reports).isEmpty();
        } finally {
            release.countDown();
            userExecutor.shutdownNow();
            assertThat(userExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * A hook its bound cancels before the teardown thread reaches it never runs, so that thread
     * exits and there is nothing to track. On a loaded machine the bound doubles until the hook
     * starts within it; the attempt where it does asserts the tracking.
     */
    @Test
    void teardownThreadsStartedWhileBoundToAnOwnerAreTracked() {
        for (var bound = Duration.ofMillis(50); ; bound = bound.multipliedBy(2)) {
            assertThat(bound)
                    .as("the teardown thread reaches its hook within its bound")
                    .isLessThanOrEqualTo(MAX_TEARDOWN_BOUND);
            var timeout = bound;
            var owner = new OwnedThreads();
            var started = new CountDownLatch(1);
            List<ErrorContext> routed = new CopyOnWriteArrayList<>();

            OwnedThreads.runBound(
                    owner,
                    () -> BoundedExecution.run(
                            () -> ignoringInterrupts(started).run(),
                            timeout,
                            routed::add,
                            cause -> new PreDestroyFailure(ThreadsOutlivingCloseTest.class, cause)));

            assertThat(routed).hasSize(1);
            var survivors = owner.stillAlive(Duration.ofMillis(50));
            // Read after the survivors: a hook that started never returns, so its thread was alive for them.
            if (started.getCount() == 0) {
                assertThat(survivors)
                        .singleElement()
                        .satisfies(t -> assertThat(t.getName()).isEqualTo("tiko-teardown"));
                return;
            }
        }
    }

    private TikoOptions.Builder reportingOptions() {
        return TikoOptions.builder().shutdownTimeout(SHUTDOWN_TIMEOUT).onThreadsOutlivingClose(reports::add);
    }

    /** Boots through {@code Tiko.create}; the aggregated shape opts into a one-module test descriptor. */
    private Container boot(Shape shape, TikoOptions.Builder options) {
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

    /** Waits for {@link #release}, swallowing interrupts so shutdownNow() can't stop it. */
    private Runnable ignoringInterrupts(CountDownLatch started) {
        return () -> {
            started.countDown();
            var interrupted = false;
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        };
    }
}
