package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.ErrorHandler;
import io.tiko.EventBus;
import io.tiko.Provider;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link TikoDaemon} (#92). Exercised directly with a stub delegate (tiko-runtime
 * has no annotation processor, so there is no generated container here). Each test removes the
 * hook it registered so the test JVM does not accumulate hooks.
 */
class TikoDaemonTest {

    @Test
    void installsHookOnConstruction() {
        TikoDaemon daemon = new TikoDaemon(new RecordingContainer());
        // removeShutdownHook returns true only if the hook was actually registered.
        assertThat(Runtime.getRuntime().removeShutdownHook(daemon.shutdownHook()))
                .isTrue();
    }

    @Test
    void containerAccessorReturnsUnderlying() {
        RecordingContainer delegate = new RecordingContainer();
        TikoDaemon daemon = new TikoDaemon(delegate);
        try {
            assertThat(daemon.container()).isSameAs(delegate);
        } finally {
            Runtime.getRuntime().removeShutdownHook(daemon.shutdownHook());
        }
    }

    @Test
    void stopRemovesHookAndShutsDown() {
        RecordingContainer delegate = new RecordingContainer();
        TikoDaemon daemon = new TikoDaemon(delegate);
        Thread hook = daemon.shutdownHook();

        daemon.stop();

        assertThat(delegate.shutdownCount).isEqualTo(1);
        // Already removed by stop() → removeShutdownHook now returns false (not registered).
        assertThat(Runtime.getRuntime().removeShutdownHook(hook)).isFalse();
    }

    @Test
    void awaitShutdownBlocksUntilStop() throws InterruptedException {
        TikoDaemon daemon = new TikoDaemon(new RecordingContainer());
        java.util.concurrent.CountDownLatch returned = new java.util.concurrent.CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            daemon.awaitShutdown();
            returned.countDown();
        });
        waiter.setDaemon(true);
        waiter.start();

        // It must keep blocking while the daemon is live — this is what keeps the process alive.
        assertThat(returned.await(200, java.util.concurrent.TimeUnit.MILLISECONDS))
                .as("awaitShutdown must not return while the daemon is running")
                .isFalse();

        daemon.stop();

        assertThat(returned.await(2, java.util.concurrent.TimeUnit.SECONDS))
                .as("awaitShutdown must return once the daemon is stopped")
                .isTrue();
    }

    @Test
    void stopReleasesWaiterWhenContainerShutdownThrows() throws InterruptedException {
        var delegate = new RecordingContainer();
        var failure = new IllegalStateException("shutdown failed");
        delegate.shutdownFailure = failure;
        var daemon = new TikoDaemon(delegate);
        var waiter = Thread.ofPlatform().daemon().start(daemon::awaitShutdown);
        try {
            await().atMost(Duration.ofSeconds(2)).until(() -> waiter.getState() == Thread.State.WAITING);

            assertThatThrownBy(daemon::stop).isSameAs(failure);

            assertThat(waiter.join(Duration.ofSeconds(2)))
                    .as("awaitShutdown must return when explicit shutdown throws")
                    .isTrue();
        } finally {
            waiter.interrupt();
            waiter.join(Duration.ofSeconds(2));
            Runtime.getRuntime().removeShutdownHook(daemon.shutdownHook());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentShutdownKeepsWaiterBlockedUntilCleanupCompletes(boolean jvmHook) throws InterruptedException {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var delegate = new RecordingContainer();
        delegate.shutdownAction = () -> {
            // Generated containers return early from a concurrent shutdown call.
            if (delegate.shutdownCount == 1) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        var daemon = new TikoDaemon(delegate);
        var timeout = Duration.ofSeconds(5);
        var owner = Thread.ofPlatform().daemon().start(daemon::stop);
        var waiter = Thread.ofPlatform().daemon().unstarted(daemon::awaitShutdown);
        var duplicate = Thread.ofPlatform().daemon().unstarted(jvmHook ? daemon.shutdownHook() : daemon::stop);
        try {
            assertThat(entered.await(timeout.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            waiter.start();
            duplicate.start();
            if (jvmHook) {
                await().atMost(timeout)
                        .until(() -> duplicate.getState() == Thread.State.WAITING || !duplicate.isAlive());
            } else {
                assertThat(duplicate.join(timeout)).isTrue();
            }
            assertThat(waiter.join(Duration.ofMillis(200)))
                    .as("awaitShutdown must wait for the caller doing teardown")
                    .isFalse();
            if (jvmHook) {
                assertThat(duplicate.getState()).isEqualTo(Thread.State.WAITING);
            }

            release.countDown();
            assertThat(owner.join(timeout) && duplicate.join(timeout) && waiter.join(timeout))
                    .isTrue();
            assertThat(delegate.shutdownCount).isEqualTo(1);
        } finally {
            release.countDown();
            owner.join(timeout);
            if (waiter.isAlive()) {
                waiter.interrupt();
                waiter.join(timeout);
            }
            if (duplicate.isAlive()) {
                duplicate.join(timeout);
            }
            Runtime.getRuntime().removeShutdownHook(daemon.shutdownHook());
        }
    }

    @Test
    void awaitShutdownUnblocksWhenHookFires() throws InterruptedException {
        TikoDaemon daemon = new TikoDaemon(new RecordingContainer());
        java.util.concurrent.CountDownLatch returned = new java.util.concurrent.CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            daemon.awaitShutdown();
            returned.countDown();
        });
        waiter.setDaemon(true);
        waiter.start();

        daemon.shutdownHook().run(); // simulate JVM running the hook on SIGTERM
        try {
            assertThat(returned.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            Runtime.getRuntime().removeShutdownHook(daemon.shutdownHook());
        }
    }

    @Test
    void hookInvokesContainerShutdown() {
        RecordingContainer delegate = new RecordingContainer();
        TikoDaemon daemon = new TikoDaemon(delegate);
        Thread hook = daemon.shutdownHook();

        hook.run(); // run the hook's target synchronously

        assertThat(delegate.shutdownCount).isEqualTo(1);
        Runtime.getRuntime().removeShutdownHook(hook); // cleanup
    }

    /** Minimal {@link Container} that records {@code shutdown()} calls and can fail on shutdown. */
    private static final class RecordingContainer implements Container {
        int shutdownCount;
        RuntimeException shutdownFailure;
        Runnable shutdownAction = () -> {};

        @Override
        public void shutdown() {
            shutdownCount++;
            shutdownAction.run();
            if (shutdownFailure != null) {
                throw shutdownFailure;
            }
        }

        @Override
        public <T> T get(Class<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T get(Class<T> type, String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> List<T> getAll(Class<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Provider<T> getProvider(Class<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Provider<T> getProvider(Class<T> type, String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void runInEventScope(Runnable runnable) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T supplyInEventScope(Supplier<T> supplier) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void start() {
            throw new UnsupportedOperationException();
        }

        @Override
        public EventBus getEventBus() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExecutorService getEventExecutor() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ErrorHandler getErrorHandler() {
            throw new UnsupportedOperationException();
        }
    }
}
