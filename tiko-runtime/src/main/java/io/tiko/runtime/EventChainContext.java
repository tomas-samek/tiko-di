package io.tiko.runtime;

import io.tiko.ErrorHandler;
import io.tiko.Event;
import io.tiko.EventBus;
import io.tiko.EventDispatchRejected;
import io.tiko.EventHandlerError;
import io.tiko.EventHandlerInfo;
import io.tiko.EventQueueOverflowException;
import io.tiko.annotations.BackoffStrategy;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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
        CompletableFuture<Void> submitted = submitOrDlq(
                () -> CompletableFuture.runAsync(() -> publishWithOrigin(bus, payload, origin), executor),
                payload,
                errorHandler);
        if (submitted == null) return CompletableFuture.completedFuture(null); // overflowed → dead-lettered
        return submitted.handle((ignored, throwable) -> {
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
        CompletableFuture<Void> submitted = submitOrDlq(
                () -> CompletableFuture.runAsync(() -> publishSpreadWithOrigin(bus, payload, origin), executor),
                payload,
                errorHandler);
        if (submitted == null) return CompletableFuture.completedFuture(null); // overflowed → dead-lettered
        return submitted.handle((ignored, throwable) -> {
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
     * <p>If the body does not finish within {@code timeoutNanos}, the worker thread is interrupted
     * (best-effort — Java cannot force-stop a thread that ignores interruption), the executor slot
     * is freed, and the overrun is routed to {@code errorHandler} as an {@link EventHandlerError}
     * whose cause is a {@link TimeoutException}. A handler that fails on its own routes that failure
     * instead; exactly one outcome is reported. An {@link Error} is logged via
     * {@link #logUnhandledAsyncError} (kept out of {@code ErrorHandler}), matching the plain async
     * path.
     */
    public static void runAsyncWithTimeout(
            Runnable body,
            long timeoutNanos,
            ExecutorService executor,
            ErrorHandler errorHandler,
            EventHandlerInfo info,
            Object event) {
        CompletableFuture<Void> once = submitOrDlq(() -> runOnce(body, timeoutNanos, executor), event, errorHandler);
        if (once == null) return; // overflowed on submit → dead-lettered
        once.whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                reportAsyncHandlerFailure(unwrapCompletion(throwable), errorHandler, info, event, 1);
            }
        });
    }

    /**
     * Runs an async {@code @EventHandler} body with retries and backoff (#108). Called exclusively
     * from generated {@code EventRegistry} code for a handler that declared
     * {@code @EventHandler(async = true, retries = ...)}.
     *
     * <p>The body is re-invoked up to {@code retries} times after the initial attempt. The first
     * attempt that completes normally wins (no error routed). Once the budget is exhausted, a single
     * {@link EventHandlerError} carrying the total {@code attempts} is routed. Backoff between
     * attempts is scheduled (via {@link CompletableFuture#delayedExecutor}), so it never holds an
     * executor slot. When {@code timeoutNanos > 0} each attempt is time-boxed (composing #107) and a
     * timed-out attempt counts as a failed attempt. {@link Error}s are not retried — they are logged
     * and stop the loop, consistent with the plain async path.
     */
    public static void runAsyncWithRetry(
            Runnable body,
            RetryPolicy policy,
            ExecutorService executor,
            ErrorHandler errorHandler,
            EventHandlerInfo info,
            Object event) {
        attempt(body, 0, policy, executor, errorHandler, info, event);
    }

    private static void attempt(
            Runnable body,
            int attemptIndex,
            RetryPolicy policy,
            ExecutorService executor,
            ErrorHandler errorHandler,
            EventHandlerInfo info,
            Object event) {
        CompletableFuture<Void> once =
                submitOrDlq(() -> runOnce(body, policy.timeoutNanos(), executor), event, errorHandler);
        if (once == null) return; // overflowed on submit → dead-lettered, loop abandoned
        once.whenComplete((ignored, throwable) -> {
            if (throwable == null) {
                return; // attempt succeeded
            }
            Throwable cause = unwrapCompletion(throwable);
            if (cause instanceof Error) {
                logUnhandledAsyncError(cause); // Errors are never retried
                return;
            }
            if (attemptIndex >= policy.retries()) {
                // Budget exhausted: attempts made = initial + retries = attemptIndex + 1.
                reportAsyncHandlerFailure(cause, errorHandler, info, event, attemptIndex + 1);
                return;
            }
            scheduleRetry(body, attemptIndex, policy, executor, errorHandler, info, event);
        });
    }

    /**
     * Schedules the next retry attempt. Backoff waits on the default delayed executor, then the
     * re-submission to {@code executor} runs under the {@code ROUTE_TO_DLQ} guard — so an overflow at
     * (re)submit time is dead-lettered even when backoff defers the submit onto a scheduler thread.
     * (Wrapping {@code delayedExecutor(.., executor).execute(..)} directly would miss that: the real
     * {@code executor.execute} then runs off the catch's stack and the signal would leak and be lost.)
     */
    private static void scheduleRetry(
            Runnable body,
            int attemptIndex,
            RetryPolicy policy,
            ExecutorService executor,
            ErrorHandler errorHandler,
            EventHandlerInfo info,
            Object event) {
        Runnable resubmit = () -> executeOrDlq(
                () -> attempt(body, attemptIndex + 1, policy, executor, errorHandler, info, event),
                executor,
                event,
                errorHandler);
        long delay = backoffDelayNanos(policy, attemptIndex);
        if (delay > 0) {
            CompletableFuture.delayedExecutor(delay, TimeUnit.NANOSECONDS).execute(resubmit);
        } else {
            resubmit.run();
        }
    }

    /**
     * Runs {@code body} on {@code executor} once, returning a future that mirrors its outcome.
     * {@link CompletableFuture#runAsync} captures every {@link Throwable} (so a handler {@link Error}
     * is never lost on the executor thread). When {@code timeoutNanos > 0}, the future is completed
     * with a {@link TimeoutException} on breach and the worker thread interrupted to free the slot.
     */
    private static CompletableFuture<Void> runOnce(Runnable body, long timeoutNanos, ExecutorService executor) {
        if (timeoutNanos <= 0) {
            return CompletableFuture.runAsync(body, executor);
        }
        // The worker thread is captured so a breached timeout can interrupt it directly (orTimeout
        // has no handle on it).
        AtomicReference<Thread> worker = new AtomicReference<>();
        CompletableFuture<Void> work = CompletableFuture.runAsync(
                () -> {
                    worker.set(Thread.currentThread());
                    body.run();
                },
                executor);
        // orTimeout returns the same future, completing it with TimeoutException only if the body
        // has not completed it first. The whenComplete propagates that outcome to the caller.
        return work.orTimeout(timeoutNanos, TimeUnit.NANOSECONDS).whenComplete((ignored, throwable) -> {
            if (throwable != null && unwrapCompletion(throwable) instanceof TimeoutException) {
                interruptWorker(worker.get());
            }
        });
    }

    /** Backoff delay before the next retry: constant for FIXED, doubling for EXPONENTIAL. */
    private static long backoffDelayNanos(RetryPolicy policy, int attemptIndex) {
        long base = policy.backoffNanos();
        if (base <= 0) {
            return 0L;
        }
        return policy.backoffStrategy() == BackoffStrategy.EXPONENTIAL ? base << Math.min(attemptIndex, 30) : base;
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
     * {@link EventHandlerError} carrying {@code attempts}, with a throwing handler logged as a
     * last resort.
     */
    private static void reportAsyncHandlerFailure(
            Throwable cause, ErrorHandler errorHandler, EventHandlerInfo info, Object event, int attempts) {
        if (cause instanceof Error) {
            logUnhandledAsyncError(cause);
            return;
        }
        try {
            errorHandler.onError(new EventHandlerError(info, event, cause, attempts));
        } catch (Exception inner) {
            logErrorHandlerFailure(inner);
        }
    }

    /**
     * Submits an async dispatch via {@code submitAction} (which calls the executor and may throw
     * {@link DlqOverflowSignal} synchronously on a {@code ROUTE_TO_DLQ} overflow). On overflow it
     * routes an {@link EventDispatchRejected} and returns {@code null}, so the caller skips its
     * post-submit wiring; otherwise it returns the submitted future. Single choke point for every
     * async submit site, so the DLQ guard cannot be forgotten at one of them.
     */
    private static CompletableFuture<Void> submitOrDlq(
            Supplier<CompletableFuture<Void>> submitAction, Object event, ErrorHandler errorHandler) {
        try {
            return submitAction.get();
        } catch (DlqOverflowSignal ignored) {
            routeDispatchRejected(event, errorHandler);
            return null;
        }
    }

    /**
     * {@code execute}-shaped sibling of {@link #submitOrDlq}: runs {@code task} on {@code executor},
     * routing a {@code ROUTE_TO_DLQ} overflow to the dead-letter handler rather than letting the
     * {@link DlqOverflowSignal} leak.
     */
    private static void executeOrDlq(Runnable task, ExecutorService executor, Object event, ErrorHandler errorHandler) {
        try {
            executor.execute(task);
        } catch (DlqOverflowSignal ignored) {
            routeDispatchRejected(event, errorHandler);
        } catch (EventQueueOverflowException overflow) {
            // THROW policy, but off the publisher's stack: this re-submission runs on the backoff
            // scheduler thread (or a worker thread), where no publisher can receive the exception.
            // Degrade to an observable logged drop rather than letting it vanish (#395), mirroring
            // the shutdown-degradation path. The synchronous initial submit still propagates THROW
            // to the publisher — that path never routes through here.
            logDroppedRetryOverflow(event);
        }
    }

    /**
     * Observability for an async retry re-submission dropped because the event queue overflowed
     * under {@code OverflowPolicy.THROW} (#395). Unlike the initial dispatch, a retry re-submission
     * runs off the publishing thread, so the {@link EventQueueOverflowException} has nowhere to
     * propagate; this WARNING records the dropped event rather than letting it vanish on a scheduler
     * thread, mirroring {@link #logDroppedDuringShutdown(Object)}.
     */
    private static void logDroppedRetryOverflow(Object event) {
        String type = event == null ? "null" : event.getClass().getName();
        LoggerHolder.LOG.log(
                System.Logger.Level.WARNING,
                "Event of type " + type + " was dropped: an async retry re-submission overflowed the "
                        + "event queue (overflow policy = THROW).");
    }

    /**
     * Routes a queue-overflow rejection to the {@code ErrorHandler} as an {@link EventDispatchRejected}
     * dead-letter context (#111, {@code ROUTE_TO_DLQ}). No handler ran, so there is no
     * {@link EventHandlerInfo} and no cause; a throwing handler is logged as a last resort.
     */
    private static void routeDispatchRejected(Object event, ErrorHandler errorHandler) {
        try {
            errorHandler.onError(new EventDispatchRejected(event));
        } catch (Exception inner) {
            logErrorHandlerFailure(inner);
        }
    }
}
