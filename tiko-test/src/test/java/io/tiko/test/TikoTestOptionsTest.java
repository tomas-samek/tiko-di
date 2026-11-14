package io.tiko.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.tiko.EventBus;
import io.tiko.runtime.TikoOptions;
import io.tiko.test.fixtures.FailingTransport;
import io.tiko.test.fixtures.FailingTransportLoader;
import io.tiko.test.fixtures.SimpleService;
import io.tiko.test.fixtures.StuckConsumerTransport;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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

/**
 * {@link TikoTestOptions} methods add to a {@code @TikoTest} container's options (#673) while the
 * test keeps its {@link RecordingEventBus} and its leaked-thread failure. Runs fixture classes in
 * a nested JUnit engine, as {@code TikoTestTeardownTest} does.
 */
class TikoTestOptionsTest {

    /** The configuration parameter that enables the fixture classes; set only by {@link #run}. */
    static final String FIXTURES = "tiko.test.options-fixtures";

    /** The order the {@link TikoTestOptions} methods of {@link OrderFixture} ran in. */
    private static final List<String> ORDER = new CopyOnWriteArrayList<>();

    /** The instance {@link OverrideFixture} handed to its container. */
    private static final SimpleService OVERRIDDEN = new SimpleService();

    @BeforeEach
    void resetFixtures() {
        ORDER.clear();
    }

    @AfterEach
    void releaseConsumer() throws InterruptedException {
        var consumer = StuckConsumerTransport.consumer();
        StuckConsumerTransport.releaseAndReset();
        if (consumer != null) {
            consumer.join(Duration.ofSeconds(5));
            assertThat(consumer.isAlive()).isFalse();
        }
    }

    @Test
    void optionsReachTheContainerAndTheTestKeepsItsRecordingBus() {
        var results = run(OverrideFixture.class, StaticPerClassFixture.class);

        assertThat(results.testEvents().failed().count()).isZero();
        assertThat(results.testEvents().succeeded().count()).isEqualTo(2);
    }

    @Test
    void consumerThreadOfAReplacedTransportFailsTheTestThatLeakedIt() {
        var results = run(StuckConsumerFixture.class);

        assertThat(failures(results))
                .containsOnlyKeys("StuckConsumerFixture")
                .hasEntrySatisfying(
                        "StuckConsumerFixture",
                        failure -> assertThat(failure)
                                .isInstanceOf(AssertionError.class)
                                .hasMessageStartingWith(
                                        "StuckConsumerFixture.leavesItsConsumerRunning: 1 thread(s) still running")
                                .hasMessageContaining("stuck-consumer"));
    }

    @Test
    void outerAndSuperclassOptionsRunBeforeTheClassesOwn() {
        var results = run(OrderFixture.class);

        assertThat(results.testEvents().failed().count()).isZero();
        assertThat(ORDER).containsExactly("enclosing", "base", "own");
    }

    /** #711: a method both an outer class and its {@code @Nested} class inherit runs once per boot. */
    @Test
    void optionsMethodSharedByOuterAndNestedClassRunsOnce() {
        var results = run(SharedBaseFixture.class);

        assertThat(results.testEvents().failed().count()).isZero();
        assertThat(ORDER).containsExactly("shared on Inner");
    }

    @Test
    void optionsMethodWithTheWrongSignatureFailsTheTest() {
        assertFailsWith(WrongSignatureFixture.class, "must return void and take one TikoOptions.Builder");
    }

    @Test
    void instanceOptionsMethodOfAPerClassContainerFailsTheClass() {
        assertFailsWith(
                InstanceMethodPerClassFixture.class,
                "Make the method static, or annotate the class with @TestInstance");
    }

    @Test
    void optionsThatSetAnOwnedSettingFailTheTest() {
        assertFailsWith(OwnedSettingFixture.class, "eventBusDecorator, which @TikoTest owns");
    }

    private static void assertFailsWith(Class<?> fixture, String fix) {
        assertThat(failures(run(fixture)))
                .hasEntrySatisfying(
                        fixture.getSimpleName(), failure -> assertThat(failure).hasMessageContaining(fix));
    }

    /** Enables the fixture classes only inside this test's engine runs. */
    static boolean launchedByOptionsTest(ExtensionContext context) {
        return context.getConfigurationParameter(FIXTURES).isPresent();
    }

    private static EngineExecutionResults run(Class<?>... fixtures) {
        var selectors =
                Arrays.stream(fixtures).map(DiscoverySelectors::selectClass).toArray(DiscoverySelector[]::new);
        return EngineTestKit.engine("junit-jupiter")
                .configurationParameter(FIXTURES, "true")
                .selectors(selectors)
                .execute();
    }

    /** The failure of each failed test or class, keyed by the simple name of its fixture class. */
    private static Map<String, Throwable> failures(EngineExecutionResults results) {
        return results.allEvents().failed().stream()
                .collect(Collectors.toMap(TikoTestOptionsTest::fixtureName, TikoTestOptionsTest::throwable));
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

    /** An instance method: it sees the test's fields. */
    @TikoTest
    @EnabledIf("io.tiko.test.TikoTestOptionsTest#launchedByOptionsTest")
    static class OverrideFixture {
        private final SimpleService service = OVERRIDDEN;

        @TikoTestOptions
        void options(TikoOptions.Builder options) {
            options.override(SimpleService.class, () -> service);
        }

        @Test
        void resolvesTheOverride(SimpleService resolved, EventBus bus, RecordingEventBus recording) {
            assertThat(resolved).isSameAs(OVERRIDDEN);
            assertThat(bus).isSameAs(recording);
        }
    }

    /** A per-class container takes a static method. */
    @TikoTest(lifecycle = TikoTest.Lifecycle.PER_CLASS)
    @EnabledIf("io.tiko.test.TikoTestOptionsTest#launchedByOptionsTest")
    static class StaticPerClassFixture {
        @TikoTestOptions
        static void options(TikoOptions.Builder options) {
            options.override(SimpleService.class, () -> OVERRIDDEN);
        }

        @Test
        void resolvesTheOverride(SimpleService resolved) {
            assertThat(resolved).isSameAs(OVERRIDDEN);
        }
    }

    /** Replaces the discovered transport with one whose consumer thread outlives the container. */
    @TikoTest
    @EnabledIf("io.tiko.test.TikoTestOptionsTest#launchedByOptionsTest")
    static class StuckConsumerFixture {
        @BeforeAll
        static void addTransport() {
            FailingTransportLoader.install();
        }

        @AfterAll
        static void restoreClassLoader() throws IOException {
            FailingTransportLoader.restore();
        }

        @TikoTestOptions
        void options(TikoOptions.Builder options) {
            options.replaceTransport(FailingTransport.class, transport -> new StuckConsumerTransport());
        }

        @Test
        void leavesItsConsumerRunning() {
            assertThat(StuckConsumerTransport.consumer().isAlive()).isTrue();
        }
    }

    abstract static class OrderBase {
        @TikoTestOptions
        void baseOptions(TikoOptions.Builder options) {
            ORDER.add("base");
        }
    }

    abstract static class SharedBase {
        @TikoTestOptions
        void sharedOptions(TikoOptions.Builder options) {
            ORDER.add("shared on " + getClass().getSimpleName());
        }
    }

    @TikoTest
    @EnabledIf("io.tiko.test.TikoTestOptionsTest#launchedByOptionsTest")
    static class SharedBaseFixture extends SharedBase {
        @Nested
        class Inner extends SharedBase {
            @Test
            void boots() {
                assertThat(ORDER).isNotEmpty();
            }
        }
    }

    @TikoTest
    @EnabledIf("io.tiko.test.TikoTestOptionsTest#launchedByOptionsTest")
    static class OrderFixture {
        @TikoTestOptions
        void enclosingOptions(TikoOptions.Builder options) {
            ORDER.add("enclosing");
        }

        @Nested
        class Inner extends OrderBase {
            @TikoTestOptions
            void ownOptions(TikoOptions.Builder options) {
                ORDER.add("own");
            }

            @Test
            void boots() {
                assertThat(ORDER).isNotEmpty();
            }
        }
    }

    @TikoTest
    @EnabledIf("io.tiko.test.TikoTestOptionsTest#launchedByOptionsTest")
    static class WrongSignatureFixture {
        @TikoTestOptions
        TikoOptions.Builder options(TikoOptions.Builder options) {
            return options;
        }

        @Test
        void neverRuns() {
            fail("the container boot should have failed first");
        }
    }

    @TikoTest(lifecycle = TikoTest.Lifecycle.PER_CLASS)
    @EnabledIf("io.tiko.test.TikoTestOptionsTest#launchedByOptionsTest")
    static class InstanceMethodPerClassFixture {
        @TikoTestOptions
        void options(TikoOptions.Builder options) {
            // an instance method a per-class container cannot call
        }

        @Test
        void neverRuns() {
            fail("the container boot should have failed first");
        }
    }

    @TikoTest
    @EnabledIf("io.tiko.test.TikoTestOptionsTest#launchedByOptionsTest")
    static class OwnedSettingFixture {
        @TikoTestOptions
        void options(TikoOptions.Builder options) {
            options.eventBusDecorator(bus -> bus);
        }

        @Test
        void neverRuns() {
            fail("the container boot should have failed first");
        }
    }
}
