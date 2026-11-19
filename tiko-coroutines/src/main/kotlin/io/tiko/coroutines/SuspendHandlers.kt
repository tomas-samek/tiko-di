package io.tiko.coroutines

import io.tiko.Container
import io.tiko.ErrorHandler
import io.tiko.Event
import io.tiko.EventHandlerInfo
import io.tiko.runtime.EventChainContext
import io.tiko.runtime.RetryPolicy
import io.tiko.runtime.UnitCarrier
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.function.Consumer
import java.util.function.Supplier
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs `suspend` event handlers (#677). For generated event-registry code, which calls [launch]
 * or [launchWithoutUnit] once for every delivery to a `suspend` `@EventHandler`; applications
 * don't call it.
 */
public object SuspendHandlers {

    /**
     * Runs [handler] as a coroutine of [container], off the calling thread, and returns at once.
     *
     * - The coroutine runs in the container's own scope, on a thread the container made, and is a
     *   pending delivery until it has completed: `close()` waits for it, and cancels it when it
     *   outruns the shutdown timeout.
     * - It runs in a unit of work of its own, which closes when the handler completes, fails or
     *   is cancelled.
     * - Events the handler publishes descend from [origin], the event it handles.
     * - [onSuccess] gets the handler's result after the handler returned, still in the handler's
     *   unit: it publishes the handler's `@EventTrigger` events.
     * - What the handler, or [onSuccess], throws goes to [errorHandler] as an `EventHandlerError`.
     * - With a [policy] (#680), an attempt that outruns its timeout is cancelled and fails with a
     *   `TimeoutException`, and a failed attempt is tried again after the backoff, each attempt
     *   in a unit of work of its own. The backoff suspends: it holds no thread. One
     *   `EventHandlerError` with the number of attempts is routed when the last one failed.
     * - Once the container has begun to close, the delivery is dropped, with the `WARNING` every
     *   delivery dropped at shutdown gets; so is a retry that waits out its backoff then.
     * - With as many `suspend` handlers in flight as `TikoOptions.queueCapacity` allows, the
     *   container's `OverflowPolicy` decides (#679): `CALLER_RUNS` starts the handler on the calling
     *   thread, which runs it up to its first suspension; `BLOCK` holds the calling thread until one
     *   completes; `DROP` logs a `WARNING`; `ROUTE_TO_DLQ` gives [errorHandler] an
     *   `EventDispatchRejected`. A handler holds its place until its last attempt has finished.
     *
     * @param origin the wrapper of the event the handler handles
     * @param handler the call of the handler method; generated Java passes a lambda that takes
     *   the continuation, which is what a `suspend () -> T` is on the JVM
     * @param policy the handler's `timeout`, `retries` and `backoff`, or `null` when it has none
     * @throws io.tiko.EventQueueOverflowException at that limit under `OverflowPolicy.THROW`
     */
    @JvmStatic
    public fun <T> launch(
        container: Container,
        origin: Event<*>,
        handler: suspend () -> T,
        onSuccess: Consumer<T>?,
        errorHandler: ErrorHandler,
        info: EventHandlerInfo,
        policy: RetryPolicy?,
    ) {
        val openUnit = Supplier { (container as UnitCarrier).openCarriedUnit(origin) }
        Delivery(handler, onSuccess, errorHandler, info, origin.payload, policy)
            .launch(container) { attempt -> withContext(UnitOfWorkElement(openUnit), attempt) }
    }

    /**
     * As [launch], with no unit of work: for a handler of a framework lifecycle event
     * (`io.tiko.events.*`). Such an event is a signal about units of work and opens none.
     */
    @JvmStatic
    public fun <T> launchWithoutUnit(
        container: Container,
        origin: Event<*>,
        handler: suspend () -> T,
        onSuccess: Consumer<T>?,
        errorHandler: ErrorHandler,
        info: EventHandlerInfo,
        policy: RetryPolicy?,
    ) {
        Delivery(handler, onSuccess, errorHandler, info, origin.payload, policy)
            .launch(container) { attempt -> withContext(OriginElement(origin), attempt) }
    }

    /** One event on its way to one `suspend` handler: every attempt, and where its outcome goes. */
    private class Delivery<T>(
        private val handler: suspend () -> T,
        private val onSuccess: Consumer<T>?,
        private val errorHandler: ErrorHandler,
        private val info: EventHandlerInfo,
        private val event: Any?,
        private val policy: RetryPolicy?,
    ) {

        /**
         * @param inContext runs one attempt in the context it needs: a unit of work of its own, or
         *   only the origin of what it publishes
         */
        fun launch(container: Container, inContext: suspend (suspend CoroutineScope.() -> Unit) -> Unit) {
            val coroutines = ContainerCoroutines.of(container)
            if (coroutines == null) {
                EventChainContext.logDroppedDuringShutdown(event)
                return
            }
            val admission = coroutines.admit()
            val start = admission.start
            if (start == null) {
                notDelivered(admission)
                return
            }
            val job = coroutines.launch(::logAnError, start) { attempts(coroutines, inContext) }
            if (job.isCancelled) {
                // close() stopped the scope between the check above and the launch: nothing ran.
                EventChainContext.logDroppedDuringShutdown(event)
            }
        }

        /** Tries until an attempt succeeds, the retries are used up, or `close()` begins. */
        private suspend fun attempts(
            coroutines: ContainerCoroutines,
            inContext: suspend (suspend CoroutineScope.() -> Unit) -> Unit,
        ) {
            val retries = policy?.retries() ?: 0
            var failed = 0
            while (true) {
                val failure = failureOf { inContext { attempt() } } ?: return
                failed++
                if (failed > retries) {
                    EventChainContext.routeHandlerFailure(failure, errorHandler, info, event, failed)
                    return
                }
                if (!coroutines.backoff(EventChainContext.backoffDelayNanos(policy, failed - 1))) {
                    EventChainContext.logDroppedDuringShutdown(event)
                    return
                }
            }
        }

        /** The handler and the publishing of what it triggers, time-boxed when the handler has a timeout. */
        private suspend fun attempt() {
            val timeoutNanos = policy?.timeoutNanos() ?: 0L
            if (timeoutNanos <= 0) {
                onSuccess.accept(handler())
                return
            }
            // null only when this timeout ran out: one of the handler's own surfaces as its exception.
            withTimeoutOrNull(timeoutNanos.nanoseconds) { onSuccess.accept(handler()) }
                ?: throw TimeoutException(
                    "The suspend handler did not finish within its timeout of ${Duration.ofNanos(timeoutNanos)}.",
                )
        }

        private fun Consumer<T>?.accept(result: T) {
            this?.accept(result)
        }

        /**
         * What made an attempt fail, or `null` when it succeeded. An `Error` is not a failed
         * attempt and goes on to [logAnError]. Neither is the cancellation of the delivery, by
         * `close()`. A `CancellationException` the handler threw while nothing cancelled its
         * delivery, a `withTimeout` of its own for one, is a failure like any other.
         */
        private suspend fun failureOf(attempt: suspend () -> Unit): Exception? =
            try {
                attempt()
                null
            } catch (cancellation: CancellationException) {
                currentCoroutineContext().ensureActive()
                cancellation
            } catch (failure: Exception) {
                failure
            }

        /** What escapes the attempts is an `Error`: logged, and kept out of the `ErrorHandler`. */
        private fun logAnError(error: Throwable) {
            EventChainContext.routeHandlerFailure(error, errorHandler, info, event)
        }

        /** The event met the limit of handlers in flight (#679), or the container is closing. */
        private fun notDelivered(admission: ContainerCoroutines.Admission) {
            when (admission) {
                ContainerCoroutines.Admission.DEAD_LETTER -> EventChainContext.routeDispatchRejected(event, errorHandler)
                ContainerCoroutines.Admission.DROPPED ->
                    EventChainContext.logDroppedAtTheLimit(event, "the overflow policy is DROP.")
                ContainerCoroutines.Admission.INTERRUPTED -> EventChainContext.logDroppedAtTheLimit(
                    event,
                    "its publisher was interrupted while it waited for a place (overflow policy = BLOCK).",
                )
                else -> EventChainContext.logDroppedDuringShutdown(event)
            }
        }
    }
}
