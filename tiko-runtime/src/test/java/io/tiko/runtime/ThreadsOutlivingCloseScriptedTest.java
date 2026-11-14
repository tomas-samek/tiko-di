package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.tiko.Container;
import io.tiko.TransportBootstrap;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The close-time thread report against a module whose shutdown is scripted (#634): teardown
 * threads of a real {@code close()} and of a failed boot's rollback (#651) are reported, a
 * transport's threads are tracked, and a throwing {@code onThreadsOutlivingClose} handler never
 * masks the failure that was already on its way out. Every scenario runs on each shape
 * {@code Tiko.create(...)} returns that can reach it.
 */
class ThreadsOutlivingCloseScriptedTest {

    /**
     * Long enough for the teardown thread to reach its hook before the bound cancels it, even on a
     * loaded machine: a hook cancelled before it starts never gets stuck.
     */
    private static final Duration ROLLBACK_TEARDOWN_TIMEOUT = Duration.ofMillis(500);

    private final List<List<Thread>> reports = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);

    @TempDir
    Path tmp;

    private ClassLoader previousLoader;

    enum Shape {
        SINGLE_MODULE,
        AGGREGATED
    }

    @BeforeEach
    void rememberContextLoader() {
        previousLoader = Thread.currentThread().getContextClassLoader();
        ScriptedShutdownContainer.reset();
    }

    @AfterEach
    void releaseStuckThreads() {
        release.countDown();
        ScriptedShutdownContainer.reset();
        Thread.currentThread().setContextClassLoader(previousLoader);
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void teardownStuckPastTheTeardownTimeoutIsReported(Shape shape) {
        ScriptedShutdownContainer.stuckTeardownUntil = release;
        var container = boot(shape, TikoOptions.builder().teardownTimeout(Duration.ofMillis(50)));

        container.close();

        assertThat(reports)
                .singleElement()
                .satisfies(threads -> assertThat(threads)
                        .singleElement()
                        .satisfies(t -> assertThat(t.getName()).isEqualTo("tiko-teardown")));
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void threadATransportStartsIsReported(Shape shape) throws Exception {
        var started = new CountDownLatch(1);
        var transportThread = new Thread[1];
        var container = boot(shape, withTransport(TikoOptions.builder(), c -> {
            assertThat(c.threadFactory()).isInstanceOf(OwnedThreads.class);
            transportThread[0] = c.threadFactory().newThread(ignoringInterrupts(started));
            transportThread[0].start();
        }));
        started.await();

        container.close();

        assertThat(reports)
                .singleElement()
                .satisfies(threads -> assertThat(threads).singleElement().isSameAs(transportThread[0]));
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void observerFailureWinsOverAThrowingHandler(Shape shape) throws Exception {
        var observerFailure = new IllegalStateException("@PreDestroy observer failed");
        ScriptedShutdownContainer.observerFailure = observerFailure;
        var handlerFailure = new AssertionError("leak");
        var container = boot(shape, throwingHandler(handlerFailure));
        startStuckThread(container);

        var thrown = catchThrowable(container::close);

        assertThat(thrown).isSameAs(observerFailure);
        assertThat(thrown.getSuppressed()).containsExactly(handlerFailure);
    }

    /** Only the single-module wrapper sees its delegate fail; the aggregate logs a failing module. */
    @Test
    void shutdownFailureWinsOverAThrowingHandler() throws Exception {
        var shutdownFailure = new IllegalStateException("shutdown failed");
        ScriptedShutdownContainer.shutdownFailure = shutdownFailure;
        var handlerFailure = new AssertionError("leak");
        var container = boot(Shape.SINGLE_MODULE, throwingHandler(handlerFailure));
        startStuckThread(container);

        var thrown = catchThrowable(container::close);

        assertThat(thrown).isSameAs(shutdownFailure);
        assertThat(thrown.getSuppressed()).containsExactly(handlerFailure);
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void bootFailureWinsOverAHandlerError(Shape shape) {
        var bootFailure = new IllegalStateException("transport failed to start");
        var handlerFailure = new AssertionError("leak");
        var options = withTransport(throwingHandler(handlerFailure), c -> {
            c.threadFactory()
                    .newThread(ignoringInterrupts(new CountDownLatch(1)))
                    .start();
            throw bootFailure;
        });

        var thrown = catchThrowable(() -> boot(shape, options));

        assertThat(thrown).isSameAs(bootFailure);
        assertThat(thrown.getSuppressed()).contains(handlerFailure);
    }

    /** How a failing boot is entered: both roll back inside {@code Tiko.create(...)}. */
    enum Entry {
        CREATE,
        DAEMON
    }

    /**
     * A failed start rolls the module back from inside {@code start()} (#651). Single-module only: the
     * aggregate starts no module, so its only rollback is the transport case below.
     */
    @ParameterizedTest
    @EnumSource(Entry.class)
    void teardownStuckInAFailedStartsRollbackIsReported(Entry entry) throws InterruptedException {
        var bootFailure = new IllegalStateException("@PostConstruct failed");
        ScriptedShutdownContainer.startFailure = bootFailure;
        ScriptedShutdownContainer.stuckTeardownUntil = release;
        var handlerFailure = new AssertionError("leak");
        var options = throwingHandler(handlerFailure).teardownTimeout(ROLLBACK_TEARDOWN_TIMEOUT);
        try {
            var thrown = catchThrowable(() -> boot(Shape.SINGLE_MODULE, options, entry));

            assertThat(thrown).isSameAs(bootFailure);
            assertThat(thrown.getSuppressed()).contains(handlerFailure);
            assertReportedTeardownThread();
        } finally {
            releaseAndJoinReported();
        }
    }

    /** A transport that refuses to start rolls the started container back (#651). */
    @ParameterizedTest
    @EnumSource(Shape.class)
    void teardownStuckInARefusedTransportsRollbackIsReported(Shape shape) throws InterruptedException {
        var bootFailure = new IllegalStateException("transport failed to start");
        ScriptedShutdownContainer.stuckTeardownUntil = release;
        var handlerFailure = new AssertionError("leak");
        var options = withTransport(throwingHandler(handlerFailure), c -> {
                    throw bootFailure;
                })
                .teardownTimeout(ROLLBACK_TEARDOWN_TIMEOUT);
        try {
            var thrown = catchThrowable(() -> boot(shape, options));

            assertThat(thrown).isSameAs(bootFailure);
            assertThat(thrown.getSuppressed()).contains(handlerFailure);
            assertReportedTeardownThread();
        } finally {
            releaseAndJoinReported();
        }
    }

    private void assertReportedTeardownThread() {
        assertThat(reports)
                .singleElement()
                .satisfies(threads -> assertThat(threads)
                        .singleElement()
                        .satisfies(t -> assertThat(t.getName()).isEqualTo("tiko-teardown")));
    }

    /** Lets every reported thread finish, so none outlives the test that started it. */
    private void releaseAndJoinReported() throws InterruptedException {
        release.countDown();
        for (var threads : reports) {
            for (var thread : threads) {
                assertThat(thread.join(Duration.ofSeconds(5)))
                        .as(thread.getName() + " exits")
                        .isTrue();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void handlerFailurePropagatesWhenNothingElseFailed(Shape shape) throws Exception {
        var handlerFailure = new AssertionError("leak");
        var container = boot(shape, throwingHandler(handlerFailure));
        startStuckThread(container);

        assertThat(catchThrowable(container::close)).isSameAs(handlerFailure);
    }

    private TikoOptions.Builder throwingHandler(Throwable failure) {
        return TikoOptions.builder().onThreadsOutlivingClose(threads -> {
            reports.add(threads);
            throw Unchecked.<RuntimeException>sneakyThrow(failure);
        });
    }

    private void startStuckThread(Container container) throws InterruptedException {
        var started = new CountDownLatch(1);
        container.threadFactory().newThread(ignoringInterrupts(started)).start();
        started.await();
    }

    /** Replaces the classpath's recording transport with one that runs {@code onStart}. */
    private static TikoOptions.Builder withTransport(TikoOptions.Builder options, Consumer<Container> onStart) {
        return options.replaceTransport(
                TransportBootstrapDiscoveryTest.RecordingTransportBootstrap.class,
                discovered -> new TransportBootstrap() {
                    @Override
                    public void start(Container container) {
                        onStart.accept(container);
                    }

                    @Override
                    public void shutdown() {
                        // nothing to stop: the test releases the threads it started
                    }
                });
    }

    /**
     * Boots a {@link ScriptedShutdownContainer} module through {@code Tiko.create}: as the only
     * module (single-module) or as the opted-in test module next to the classpath's main one.
     */
    private Container boot(Shape shape, TikoOptions.Builder options) {
        return boot(shape, options, Entry.CREATE);
    }

    private Container boot(Shape shape, TikoOptions.Builder options, Entry entry) {
        if (options.build().threadsOutlivingCloseHandler() == null) {
            options.onThreadsOutlivingClose(reports::add);
        }
        var descriptor = shape == Shape.SINGLE_MODULE ? "container.properties" : "test-container.properties";
        Thread.currentThread().setContextClassLoader(descriptorLoader(descriptor));
        var built = options.testWiring(shape == Shape.AGGREGATED).build();
        return switch (entry) {
            case CREATE -> Tiko.create(built);
            case DAEMON -> Tiko.daemon(built).container();
        };
    }

    private ClassLoader descriptorLoader(String descriptor) {
        try {
            var meta = Files.createDirectories(tmp.resolve("META-INF").resolve("tiko"));
            var props = new Properties();
            props.setProperty("impl", ScriptedShutdownContainer.class.getName());
            try (var out = Files.newOutputStream(meta.resolve(descriptor))) {
                props.store(out, "test");
            }
            Files.writeString(meta.resolve("components.txt"), "");
            return new OwnDescriptorsOnly(tmp.toUri().toURL(), getClass().getClassLoader());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Waits for {@link #release}, swallowing interrupts so no shutdown step can stop it. */
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

    /**
     * Serves the container descriptors only from its own root, hiding the test classpath's main
     * descriptor, so a single scripted module boots on the single-module path. Everything else,
     * including the recording transport's service file, comes from the parent.
     */
    private static final class OwnDescriptorsOnly extends URLClassLoader {
        private static final Set<String> DESCRIPTORS = Set.of(
                AggregatingContainer.DEFAULT_DESCRIPTOR,
                "META-INF/tiko/test-container.properties",
                AggregatingContainer.TEST_SHADOWS);

        OwnDescriptorsOnly(URL root, ClassLoader parent) {
            super(new URL[] {root}, parent);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (DESCRIPTORS.contains(name)) {
                var own = findResource(name);
                return own == null ? Collections.emptyEnumeration() : Collections.enumeration(List.of(own));
            }
            return super.getResources(name);
        }
    }
}
