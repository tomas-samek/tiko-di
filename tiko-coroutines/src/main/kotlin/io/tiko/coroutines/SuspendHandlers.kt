package io.tiko.coroutines

import io.tiko.Container
import io.tiko.ErrorHandler
import io.tiko.Event
import io.tiko.EventHandlerInfo
import io.tiko.runtime.EventChainContext
import io.tiko.runtime.UnitCarrier
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

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
     * - It runs in a unit of work of its own, which closes when the coroutine completes, fails or
     *   is cancelled.
     * - Events the handler publishes descend from [origin], the event it handles.
     * - [onSuccess] gets the handler's result after the handler returned, still in the handler's
     *   unit: it publishes the handler's `@EventTrigger` events.
     * - What the handler, or [onSuccess], throws goes to [errorHandler] as an `EventHandlerError`.
     * - Once the container has begun to close, the delivery is dropped, with the `WARNING` every
     *   delivery dropped at shutdown gets.
     *
     * @param handler the call of the handler method; generated Java passes a lambda that takes
     *   the continuation, which is what a `suspend () -> T` is on the JVM
     */
    @JvmStatic
    public fun <T> launch(
        container: Container,
        origin: Event<*>,
        handler: suspend () -> T,
        onSuccess: Consumer<T>?,
        errorHandler: ErrorHandler,
        info: EventHandlerInfo,
        event: Any,
    ) {
        val reporter = Reporter(errorHandler, info, event)
        deliver(container, event, handler, onSuccess, reporter) { coroutines, onFailure, delivery ->
            coroutines.launchInUnit({ (container as UnitCarrier).openCarriedUnit(origin) }, onFailure, delivery)
        }
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
        event: Any,
    ) {
        val reporter = Reporter(errorHandler, info, event)
        deliver(container, event, handler, onSuccess, reporter) { coroutines, onFailure, delivery ->
            coroutines.launchWithoutUnit(origin, onFailure, delivery)
        }
    }

    /** Routes a handler's failure to the `ErrorHandler`, as its `EventHandlerError`. */
    private class Reporter(val errorHandler: ErrorHandler, val info: EventHandlerInfo, val event: Any) {
        fun report(failure: Throwable) {
            EventChainContext.routeHandlerFailure(failure, errorHandler, info, event)
        }
    }

    private fun <T> deliver(
        container: Container,
        event: Any,
        handler: suspend () -> T,
        onSuccess: Consumer<T>?,
        reporter: Reporter,
        start: (ContainerCoroutines, (Throwable) -> Unit, suspend CoroutineScope.() -> Unit) -> Job,
    ) {
        val coroutines = ContainerCoroutines.of(container)
        if (coroutines == null) {
            EventChainContext.logDroppedDuringShutdown(event)
            return
        }
        val job = start(coroutines, reporter::report) {
            val result = handler()
            onSuccess?.accept(result)
        }
        if (job.isCancelled) {
            // close() stopped the scope between the check above and the launch: nothing ran.
            EventChainContext.logDroppedDuringShutdown(event)
        }
    }
}
