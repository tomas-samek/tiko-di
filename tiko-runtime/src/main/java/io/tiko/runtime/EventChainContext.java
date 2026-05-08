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
import java.util.logging.Level;
import java.util.logging.Logger;

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

    private EventChainContext() {}

    /**
     * Last-resort logging when an {@code ErrorHandler.onError} implementation itself throws.
     * Called exclusively from generated {@code EventRegistry} code. Kept here so the
     * generated source never directly imports a logging framework — generated source
     * stays free of {@code java.util.logging} (and historically slf4j) imports.
     *
     * @param inner the exception thrown by the user's ErrorHandler implementation
     */
    public static void logErrorHandlerFailure(Throwable inner) {
        Logger.getLogger("io.tiko.events")
            .log(Level.SEVERE, "ErrorHandler.onError threw", inner);
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
            EventBus bus, Object payload, Event<?> origin,
            ExecutorService executor, ErrorHandler errorHandler, EventHandlerInfo info) {
        if (payload == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture
            .runAsync(() -> publishWithOrigin(bus, payload, origin), executor)
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
            EventBus bus, Object payload, Event<?> origin,
            ExecutorService executor, ErrorHandler errorHandler, EventHandlerInfo info) {
        if (payload == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture
            .runAsync(() -> publishSpreadWithOrigin(bus, payload, origin), executor)
            .handle((__, throwable) -> {
                reportIfFailed(throwable, payload, errorHandler, info);
                return null;
            });
    }

    private static void reportIfFailed(Throwable throwable, Object payload,
                                        ErrorHandler errorHandler, EventHandlerInfo info) {
        if (throwable == null) return;
        Throwable cause = (throwable instanceof CompletionException && throwable.getCause() != null)
            ? throwable.getCause() : throwable;
        try {
            errorHandler.onError(new EventHandlerError(info, payload, cause));
        } catch (Exception inner) {
            logErrorHandlerFailure(inner);
        }
    }
}
