package io.tiko.test;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import io.tiko.Container;
import io.tiko.EventBus;
import io.tiko.test.fixtures.FailingTransport;
import io.tiko.test.fixtures.ShutdownProbe;
import io.tiko.test.fixtures.StuckEvent;
import io.tiko.test.fixtures.StuckHandler;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Event;
import org.junit.platform.testkit.engine.Events;

/**
 * {@code @TikoTest} closes its container on every path and fails exactly the test whose container
 * left threads running (#637). Each case runs {@code @TikoTest} fixture classes through
 * {@link EngineTestKit} and asserts on how they finished.
 *
 * <p>The fixtures are static nested classes, which surefire does not select on its own, and each is
 * enabled only when {@link #FIXTURES} is set, which only this test's engine runs do. A fixture that
 * leaves a thread stuck in {@link StuckHandler} is released and joined in {@link #releaseStuckThreads()}.
 */
class TikoTestTeardownTest {

    /** The configuration parameter that enables the fixture classes; set only by {@link #run}. */
    static final String FIXTURES = "tiko.test.teardown-fixtures";

    private static final Duration ENTER_TIMEOUT = Duration.ofSeconds(10);

    @BeforeEach
    void resetFixtures() {
        StuckHandler.arm();
        ShutdownProbe.reset();
        FailingTransport.reset();
    }

    @AfterEach
    void releaseStuckThreads() throws InterruptedException {
        StuckHandler.releaseAndJoin();
        assertThat(StuckHandler.stuckThreads()).noneMatch(Thread::isAlive);
    }

    @Test
    void containerIsClosedWhenTheTestFails() {
        var results = run(FailingBodyFixture.class);

        assertThat(failures(results.testEvents()))
                .containsOnlyKeys("FailingBodyFixture")
                .hasEntrySatisfying(
                        "FailingBodyFixture", failure -> assertThat(failure).hasMessage("test body failed"));
        assertThat(ShutdownProbe.wasDestroyed()).isTrue();
    }

    @Test
    void executorIsReleasedWhenBootFails() throws InterruptedException {
        var results = run(BootFailureFixture.class);

        assertThat(failures(results.testEvents()))
                .hasEntrySatisfying(
                        "BootFailureFixture", failure -> assertThat(failure).hasMessage("transport refused to start"));
        var worker = FailingTransport.worker();
        assertThat(worker).isNotNull();
        assertThat(worker.getName()).startsWith("tiko-event-async-test-");
        worker.join(Duration.ofSeconds(5));
        assertThat(worker.isAlive())
                .as("event-executor worker of the failed boot")
                .isFalse();
    }

    @Test
    void leakedThreadFailsTheTestThatLeakedIt() {
        var results = run(StuckHandlerFixture.class);

        assertThat(failures(results.testEvents()))
                .hasEntrySatisfying(
                        "StuckHandlerFixture",
                        failure -> assertThat(failure)
                                .isInstanceOf(AssertionError.class)
                                .hasMessageStartingWith(
                                        "StuckHandlerFixture.publishesAndReturns: 1 thread(s) still running")
                                .hasMessageContaining("tiko-event-async-test-"));
    }

    @Test
    void perClassLeakFailsTheClass() {
        var results = run(PerClassStuckFixture.class);

        results.testEvents().assertStatistics(stats -> stats.succeeded(1).failed(0));
        assertThat(failures(results.containerEvents()))
                .hasEntrySatisfying(
                        "PerClassStuckFixture",
                        failure -> assertThat(failure)
                                .isInstanceOf(AssertionError.class)
                                .hasMessageStartingWith("PerClassStuckFixture: 1 thread(s) still running")
                                .hasMessageContaining("tiko-event-async-test-"));
    }

    @Test
    void leakIsAttributedToItsOwnTestUnderParallelExecution() {
        var results = run(
                Map.of(
                        "junit.jupiter.execution.parallel.enabled", "true",
                        "junit.jupiter.execution.parallel.mode.default", "concurrent",
                        "junit.jupiter.execution.parallel.mode.classes.default", "concurrent",
                        "junit.jupiter.execution.parallel.config.strategy", "fixed",
                        "junit.jupiter.execution.parallel.config.fixed.parallelism", "2"),
                LeakingParallelFixture.class,
                CleanParallelFixture.class);

        assertThat(failures(results.testEvents()))
                .containsOnlyKeys("LeakingParallelFixture")
                .hasEntrySatisfying(
                        "LeakingParallelFixture",
                        failure -> assertThat(failure)
                                .hasMessageStartingWith(
                                        "LeakingParallelFixture.publishesAndReturns: 1 thread(s) still running"));
        assertThat(failures(results.containerEvents())).isEmpty();
        results.testEvents().assertStatistics(stats -> stats.succeeded(1).failed(1));
    }

    // A @TikoTest container's close() fails only when a custom ErrorHandler throws, which the
    // extension never installs, so the close-failure path runs on the boot directly.

    @Test
    void executorIsReleasedWhenCloseThrows() throws Exception {
        var executor = CountingThreadPoolExecutor.withFrameworkDefaults();
        var worker = startIdleWorker(executor);
        var boot = new BootedContainer("CloseFixture.closes", containerWhoseCloseThrows(), executor, List.of());

        assertThatThrownBy(boot::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("close failed")
                .hasNoSuppressedExceptions();
        worker.join(Duration.ofSeconds(5));
        assertThat(worker.isAlive()).as("idle event-executor worker").isFalse();
        assertThat(executor.isTerminated()).isTrue();
    }

    @Test
    void leakIsSuppressedOnTheCloseFailure() throws Exception {
        var release = new CountDownLatch(1);
        var leaked = Thread.ofPlatform().name("leaked-by-close-fixture").start(() -> awaitIgnoringInterrupts(release));
        try {
            var executor = CountingThreadPoolExecutor.withFrameworkDefaults();
            var boot =
                    new BootedContainer("CloseFixture.closes", containerWhoseCloseThrows(), executor, List.of(leaked));

            assertThatThrownBy(boot::close)
                    .hasMessage("close failed")
                    .satisfies(failure -> assertThat(failure.getSuppressed())
                            .singleElement()
                            .isInstanceOf(AssertionError.class)
                            .extracting(Throwable::getMessage, as(InstanceOfAssertFactories.STRING))
                            .startsWith("""
                                    CloseFixture.closes: 1 thread(s) still running after its container closed:
                                      leaked-by-close-fixture
                                        at\s"""));
        } finally {
            release.countDown();
            leaked.join(Duration.ofSeconds(5));
        }
    }

    private static Thread startIdleWorker(CountingThreadPoolExecutor executor) throws Exception {
        var worker = new CompletableFuture<Thread>();
        executor.execute(() -> worker.complete(Thread.currentThread()));
        return worker.get(5, TimeUnit.SECONDS);
    }

    private static Container containerWhoseCloseThrows() {
        return (Container) Proxy.newProxyInstance(
                Container.class.getClassLoader(), new Class<?>[] {Container.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close") || method.getName().equals("shutdown")) {
                        throw new IllegalStateException("close failed");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Waits for {@code latch} through interrupts, like code that does not honour them, then restores the flag. */
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

    /** Enables the fixture classes only inside this test's engine runs. */
    static boolean launchedByTeardownTest(ExtensionContext context) {
        return context.getConfigurationParameter(FIXTURES).isPresent();
    }

    private static EngineExecutionResults run(Class<?>... fixtures) {
        return run(Map.of(), fixtures);
    }

    private static EngineExecutionResults run(Map<String, String> parameters, Class<?>... fixtures) {
        var selectors =
                Arrays.stream(fixtures).map(DiscoverySelectors::selectClass).toArray(DiscoverySelector[]::new);
        return EngineTestKit.engine("junit-jupiter")
                .configurationParameter(FIXTURES, "true")
                .configurationParameters(parameters)
                .selectors(selectors)
                .execute();
    }

    /** The failure of each failed event, keyed by the simple name of the fixture class it belongs to. */
    private static Map<String, Throwable> failures(Events events) {
        return events.failed().stream()
                .collect(Collectors.toMap(TikoTestTeardownTest::fixtureName, TikoTestTeardownTest::throwable));
    }

    private static String fixtureName(Event event) {
        var source = event.getTestDescriptor().getSource().orElseThrow();
        var className =
                switch (source) {
                    case MethodSource method -> method.getClassName();
                    case ClassSource type -> type.getClassName();
                    default -> throw new IllegalStateException("unexpected source " + source);
                };
        return className.substring(className.lastIndexOf('$') + 1);
    }

    private static Throwable throwable(Event event) {
        return event.getRequiredPayload(TestExecutionResult.class)
                .getThrowable()
                .orElseThrow();
    }

    @TikoTest
    @EnabledIf("io.tiko.test.TikoTestTeardownTest#launchedByTeardownTest")
    static class FailingBodyFixture {
        @Test
        void fails(ShutdownProbe probe) {
            assertThat(probe).isNotNull();
            throw new AssertionError("test body failed");
        }
    }

    @TikoTest
    @EnabledIf("io.tiko.test.TikoTestTeardownTest#launchedByTeardownTest")
    static class BootFailureFixture {
        private static ClassLoader previous;
        private static URLClassLoader withFailingTransport;

        /** Runs before the extension boots each test's container, so that boot finds {@link FailingTransport}. */
        @BeforeAll
        static void addFailingTransport() {
            URL root = BootFailureFixture.class.getResource("/boot-failure/");
            previous = Thread.currentThread().getContextClassLoader();
            withFailingTransport = new URLClassLoader(new URL[] {root}, previous);
            Thread.currentThread().setContextClassLoader(withFailingTransport);
        }

        @AfterAll
        static void restoreClassLoader() throws IOException {
            Thread.currentThread().setContextClassLoader(previous);
            withFailingTransport.close();
        }

        @Test
        void neverRuns() {
            fail("the container boot should have failed first");
        }
    }

    @TikoTest
    @EnabledIf("io.tiko.test.TikoTestTeardownTest#launchedByTeardownTest")
    static class StuckHandlerFixture {
        @Test
        void publishesAndReturns(EventBus bus) throws InterruptedException {
            publishAndWaitUntilStuck(bus);
        }
    }

    @TikoTest(lifecycle = TikoTest.Lifecycle.PER_CLASS)
    @EnabledIf("io.tiko.test.TikoTestTeardownTest#launchedByTeardownTest")
    static class PerClassStuckFixture {
        @Test
        void publishesAndReturns(EventBus bus) throws InterruptedException {
            publishAndWaitUntilStuck(bus);
        }
    }

    @TikoTest
    @EnabledIf("io.tiko.test.TikoTestTeardownTest#launchedByTeardownTest")
    static class LeakingParallelFixture {
        @Test
        void publishesAndReturns(EventBus bus) throws InterruptedException {
            publishAndWaitUntilStuck(bus);
        }
    }

    /** Closes its container while {@link LeakingParallelFixture}'s thread is stuck, and must pass. */
    @TikoTest
    @EnabledIf("io.tiko.test.TikoTestTeardownTest#launchedByTeardownTest")
    static class CleanParallelFixture {
        @Test
        void returnsWhileTheOtherClassLeaks(EventBus bus) throws InterruptedException {
            assertThat(bus).isNotNull();
            assertThat(StuckHandler.awaitEntered(ENTER_TIMEOUT))
                    .as("the leaking fixture runs at the same time")
                    .isTrue();
        }
    }

    private static void publishAndWaitUntilStuck(EventBus bus) throws InterruptedException {
        bus.publish(new StuckEvent());
        assertThat(StuckHandler.awaitEntered(ENTER_TIMEOUT)).isTrue();
    }
}
