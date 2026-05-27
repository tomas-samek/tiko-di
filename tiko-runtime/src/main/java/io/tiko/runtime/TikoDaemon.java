package io.tiko.runtime;

import io.tiko.Container;

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

    TikoDaemon(Container container) {
        this.container = container;
        this.hook = new Thread(container::shutdown, "tiko-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
    }

    /** The underlying container — use it to resolve beans and enter scopes. */
    public Container container() {
        return container;
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
    }
}
