package io.tiko.runtime;

import io.tiko.ErrorHandler;
import io.tiko.Event;
import io.tiko.EventBus;
import io.tiko.EventHandlerError;
import io.tiko.EventHandlerInfo;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the currently-executing event wrapper across a thread of event delivery, so that
 * events triggered by an {@code @EventTrigger} handler can chain their origin back to the
 * event that caused the handler to fire.
 *
 * <p>Used by generated {@code EventRegistry} code; not part of the public API.
 *
 * <p>Async helpers ({@link #publishAsync}, {@link #publishSpreadAsync}) take the
 * container's {@link ExecutorService} and {@link ErrorHandler} as parameters — there is
 * no longer a global static executor. Exceptional completions of submitted tasks are
 * routed to the supplied error handler with the originating handler's
 * {@link EventHandlerInfo}, ensuring no async failure is silently swallowed even when
 * the returned future is discarded.
 */
public final class EventChainContext {

    private static final ThreadLocal<Event<?>> CURRENT = new ThreadLocal<>();

    /** Lazy holder: defers System.LoggerFinder resolution until the first failure path runs. */
    private static final class LoggerHolder {
        static final System.Logger LOG = System.getLogger("io.tiko.events");
    }

    private EventChainContext() {}

    /**
     * Last-resort logging when an {@code ErrorHandler.onError} implementation itself throws.
     * Called exclusively from generated {@code EventRegistry} code. Kept here so the
     * generated source never directly imports a logging framework — generated source
     * stays free of any logging framework imports.
     *
     * @param inner the exception thrown by the user's ErrorHandler implementation
     */
    public static void logErrorHandlerFailure(Throwable inner) {
        LoggerHolder.LOG.log(System.Logger.Level.ERROR, "ErrorHandler.onError threw", inner);
    }

    /**
     * Last-resort logging for an {@code Error} thrown by an async {@code @EventHandler}.
     * Called exclusively from generated {@code EventRegistry} code. {@code CompletableFuture}
     * captures the {@code Error} into the future rather than letting it surface on the executor
     * thread, so without this it would vanish entirely. Kept out of {@code ErrorHandler}
     * (consistent with the sync path, which routes only {@code Exception}); the point is purely
     * to make the {@code Error} observable. Kept here so the generated source never directly
     * imports a logging framework.
     *
     * @param error the {@code Error} thrown by the user's async handler
     */
    public static void logUnhandledAsyncError(Throwable error) {
        LoggerHolder.LOG.log(System.Logger.Level.ERROR, "Async @EventHandler threw an Error", error);
    }

    /**
     * Observability for an event dropped because the container is shutting down (#346).
     * Called exclusively from generated {@code EventRegistry} code at the stopped-gate: once
     * shutdown has gated the container the dispatcher skips delivery (it would otherwise run
     * handlers on destroyed singletons, #337), and this WARNING records the dropped event
     * rather than letting it vanish silently. Kept here so generated source imports no logging
     * framework.
     *
     * @param event the event whose delivery was skipped
     */
    public static void logDroppedDuringShutdown(Object event) {
        String type = event == null ? "null" : event.getClass().getName();
        LoggerHolder.LOG.log(
                System.Logger.Level.WARNING, "Event of type " + type + " was dropped: the container is shutting down.");
    }

    /**
     * Wraps {@code payload} in an {@link Event}, chaining its origin to the wrapper currently
     * being delivered on this thread (if any).
     */
    public static <T> Event<T> wrap(T payload) {
        return new Event<>(payload, CURRENT.get());
    }

    /**
     * Runs {@code body} with {@code wrapper} set as the current chain origin, restoring the
     * previous value (typically {@code null}) on exit. Intended to bracket an event handler
     * invocation.
     */
    public static void runWith(Event<?> wrapper, Runnable body) {
        Event<?> previous = enter(wrapper);
        try {
            body.run();
        } finally {
            exit(previous);
        }
    }

    /**
     * Pushes {@code wrapper} as the current chain origin and returns the previous value
     * (so the caller can restore it via {@link #exit(Event)}). Designed for generated code
     * that uses an explicit try/finally rather than a Runnable.
     */
    public static Event<?> enter(Event<?> wrapper) {
        Event<?> previous = CURRENT.get();
        CURRENT.set(wrapper);
        return previous;
    }

    /**
     * Restores {@code previous} as the current chain origin (the value returned by
     * {@link #enter(Event)}). Pass {@code null} to clear.
     */
    public static void exit(Event<?> previous) {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }

    /**
     * Sync trigger: publishes {@code payload} on {@code bus} with the supplied {@code origin}
     * established as the chain root for the duration of the call.
     */
    public static void publishWithOrigin(EventBus bus, Object payload, Event<?> origin) {
        if (payload == null) return;
        runWith(origin, () -> bus.publish(payload));
    }

    /**
     * Spread sync trigger: each element of a Collection / array / Iterable becomes its own
     * publish call. Non-iterable values fall back to a single publish.
     */
    public static void publishSpreadWithOrigin(EventBus bus, Object payload, Event<?> origin) {
        if (payload == null) return;
        if (payload instanceof Collection<?> collection) {
            runWith(origin, () -> {
                for (Object item : collection) if (item != null) bus.publish(item);
            });
        } else if (payload.getClass().isArray()) {
            runWith(origin, () -> {
                int len = Array.getLength(payload);
                for (int i = 0; i < len; i++) {
                    Object item = Array.get(payload, i);
                    if (item != null) bus.publish(item);
                }
            });
        } else if (payload instanceof Iterable<?> iterable) {
            runWith(origin, () -> {
                for (Object item : iterable) if (item != null) bus.publish(item);
            });
        } else {
            // Non-iterable: degrade to a single publish so the trigger isn't silently dropped.
            publishWithOrigin(bus, payload, origin);
        }
    }

    /**
     * Async trigger: schedules a sync publish on the supplied executor. The origin is captured
     * here so the dispatched task sees the same chain even though it runs on a different thread.
     * Exceptional completions are routed to {@code errorHandler} via
     * {@link EventHandlerError}; if {@code errorHandler.onError} itself throws, the failure
     * is logged via {@link #logErrorHandlerFailure(Throwable)} as a last resort.
     */
    public static CompletableFuture<Void> publishAsync(
            EventBus bus,
            Object payload,
            Event<?> origin,
            ExecutorService executor,
            ErrorHandler errorHandler,
            EventHandlerInfo info) {
        if (payload == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> publishWithOrigin(bus, payload, origin), executor)
                .handle((__, throwable) -> {
                    reportIfFailed(throwable, payload, errorHandler, info);
                    return null;
                });
    }

    /**
     * Async spread trigger: like {@link #publishAsync} but each element of a
     * Collection / array / Iterable is published separately.
     */
    public static CompletableFuture<Void> publishSpreadAsync(
            EventBus bus,
            Object payload,
            Event<?> origin,
            ExecutorService executor,
            ErrorHandler errorHandler,
            EventHandlerInfo info) {
        if (payload == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> publishSpreadWithOrigin(bus, payload, origin), executor)
                .handle((__, throwable) -> {
                    reportIfFailed(throwable, payload, errorHandler, info);
                    return null;
                });
    }

    private static void reportIfFailed(
            Throwable throwable, Object payload, ErrorHandler errorHandler, EventHandlerInfo info) {
        if (throwable == null) return;
        Throwable cause = (throwable instanceof CompletionException && throwable.getCause() != null)
                ? throwable.getCause()
                : throwable;
        try {
            errorHandler.onError(new EventHandlerError(info, payload, cause));
        } catch (Exception inner) {
            logErrorHandlerFailure(inner);
        }
    }

    /**
     * Runs an async {@code @EventHandler} body under a wall-clock timeout (#107). Called
     * exclusively from generated {@code EventRegistry} code for a handler that declared
     * {@code @EventHandler(async = true, timeout = ...)}.
     *
     * <p>The body runs on {@code executor} via {@link CompletableFuture#runAsync} (which captures
     * every {@link Throwable}, so a handler {@link Error} is never lost on the executor thread). If
     * it does not finish within {@code timeoutNanos}, {@link CompletableFuture#orTimeout} completes
     * the future with a {@link TimeoutException} and the worker thread is interrupted — best-effort,
     * since Java cannot force-stop a thread that ignores interruption — which frees the executor
     * slot once the handler returns. The overrun is routed to {@code errorHandler} as an
     * {@link EventHandlerError} whose cause is the {@code TimeoutException}. A handler that fails on
     * its own routes that failure instead; exactly one outcome is reported, because {@code orTimeout}
     * completes the single shared future once.
     *
     * <p>Routing mirrors the plain async path: an {@link Error} is logged via
     * {@link #logUnhandledAsyncError} (kept out of {@code ErrorHandler}), everything else goes
     * through {@code ErrorHandler}.
     */
    public static void runAsyncWithTimeout(
            Runnable body,
            long timeoutNanos,
            ExecutorService executor,
            ErrorHandler errorHandler,
            EventHandlerInfo info,
            Object event) {
        // The worker thread is captured so a breached timeout can interrupt it directly (orTimeout
        // has no handle on it).
        AtomicReference<Thread> worker = new AtomicReference<>();
        CompletableFuture<Void> work = CompletableFuture.runAsync(
                () -> {
                    worker.set(Thread.currentThread());
                    body.run();
                },
                executor);
        // orTimeout returns the same future, completing it exceptionally with TimeoutException only
        // if the body has not completed it first — so whenComplete fires exactly once.
        work.orTimeout(timeoutNanos, TimeUnit.NANOSECONDS).whenComplete((ignored, throwable) -> {
            if (throwable == null) {
                return;
            }
            Throwable cause = unwrapCompletion(throwable);
            if (cause instanceof TimeoutException) {
                interruptWorker(worker.get());
            }
            reportAsyncHandlerFailure(cause, errorHandler, info, event);
        });
    }

    /** Unwraps the {@link CompletionException} wrapper a {@link CompletableFuture} adds, if present. */
    private static Throwable unwrapCompletion(Throwable throwable) {
        return (throwable instanceof CompletionException && throwable.getCause() != null)
                ? throwable.getCause()
                : throwable;
    }

    /**
     * Best-effort interrupt of a timed-out handler's worker thread — frees the executor slot once
     * the handler returns. A handler that ignores interruption keeps running (documented limitation).
     */
    private static void interruptWorker(Thread worker) {
        if (worker != null) {
            worker.interrupt();
        }
    }

    /**
     * Routes an async {@code @EventHandler} failure exactly as the plain async path does: an
     * {@link Error} is logged (kept out of {@code ErrorHandler}); everything else — including a
     * timeout's {@link TimeoutException} — goes through {@code ErrorHandler} as an
     * {@link EventHandlerError}, with a throwing handler logged as a last resort.
     */
    private static void reportAsyncHandlerFailure(
            Throwable cause, ErrorHandler errorHandler, EventHandlerInfo info, Object event) {
        if (cause instanceof Error) {
            logUnhandledAsyncError(cause);
            return;
        }
        try {
            errorHandler.onError(new EventHandlerError(info, event, cause));
        } catch (Exception inner) {
            logErrorHandlerFailure(inner);
        }
    }
}
