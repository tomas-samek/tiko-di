package io.tiko.runtime;

import io.tiko.Container;
import java.util.concurrent.CountDownLatch;

/**
 * Handle for a daemon-style Tiko application (#92): wraps a started {@link Container} and registers
 * a JVM shutdown hook that calls {@code shutdown()} on process exit, so a long-lived process gets
 * {@code @PreDestroy} / {@code AutoCloseable.close()} and {@code ApplicationEndingEvent} on
 * {@code Ctrl+C} / {@code SIGTERM} without wiring its own {@code Runtime.addShutdownHook}.
 *
 * <p>Deliberately <strong>not</strong> {@link AutoCloseable}. A daemon's lifecycle is owned by the
 * framework (the hook), not a try-with-resources block — making the handle non-closeable is the
 * signal "don't manage this yourself". Resolve beans and enter scopes through {@link #container()};
 * call {@link #stop()} only for an explicit/programmatic shutdown (tests, in-process restart).
 *
 * <p>For a caller-managed lifecycle, use {@link Tiko#create(TikoOptions)} instead: it returns an
 * AutoCloseable {@link Container} and installs no hook.
 *
 * <p>Obtain via {@link Tiko#daemon()} / {@link Tiko#daemon(TikoOptions)}.
 */
public final class TikoDaemon {

    private final Container container;
    private final Thread hook;
    private final CountDownLatch terminated = new CountDownLatch(1);

    TikoDaemon(Container container) {
        this.container = container;
        this.hook = new Thread(this::onShutdown, "tiko-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
    }

    /** The underlying container — use it to resolve beans and enter scopes. */
    public Container container() {
        return container;
    }

    /**
     * Blocks the calling thread until the daemon is shut down — by {@code Ctrl+C} / {@code SIGTERM}
     * (the JVM runs the hook) or by an explicit {@link #stop()}. This is the canonical "keep the
     * process alive" idiom for a headless daemon (a Kafka consumer, a scheduler) whose worker
     * threads are all daemon threads and would otherwise let {@code main} return and the JVM exit
     * immediately. An app that already runs a non-daemon foreground server (e.g. an HTTP server)
     * does not need it — that thread keeps the JVM up.
     *
     * <p>Returns (rather than looping) if the waiting thread is interrupted, restoring the
     * interrupt flag so callers can observe it.
     */
    public void awaitShutdown() {
        try {
            terminated.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Shutdown-hook target: shut the container down, then release any {@link #awaitShutdown()} waiter. */
    private void onShutdown() {
        try {
            container.shutdown();
        } finally {
            terminated.countDown();
        }
    }

    /** The registered JVM shutdown hook — package-private for tests. */
    Thread shutdownHook() {
        return hook;
    }

    /**
     * Shuts the daemon down now and removes the JVM hook so it does not fire again at exit. Safe to
     * call more than once and safe to race with JVM shutdown — the container's own shutdown
     * short-circuits a second call, and {@code removeShutdownHook} failing because the JVM is
     * already exiting is treated as a no-op.
     */
    public void stop() {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException jvmAlreadyShuttingDown) {
            // Reached during JVM shutdown (e.g. via the hook itself) — nothing to remove.
        }
        container.shutdown();
        terminated.countDown();
    }
}
