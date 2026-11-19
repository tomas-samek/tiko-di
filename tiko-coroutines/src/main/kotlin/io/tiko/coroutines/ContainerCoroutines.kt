package io.tiko.coroutines

import io.tiko.Container
import io.tiko.Event
import io.tiko.EventQueueOverflowException
import io.tiko.runtime.CarriedUnit
import io.tiko.runtime.ContainerWork
import io.tiko.runtime.EventChainContext
import io.tiko.runtime.OverflowPolicy
import io.tiko.runtime.UnitCarrier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The coroutines one booted container runs (#678): a scope of its own, created the first time
 * something is launched and stopped when the container closes.
 *
 * - **Threads.** Every dispatch starts a virtual thread the container made (`tiko-coroutine-N`).
 *   Nothing is queued and nothing is refused, so a coroutine that resumes never waits for a pool
 *   and never runs on the thread that happened to resume it. Blocking code in a coroutine blocks
 *   its virtual thread only.
 * - **One failure, one coroutine.** The scope has a `SupervisorJob`: a coroutine that fails does
 *   not cancel the others.
 * - **Deliveries.** A launched coroutine is a pending delivery of the container until it has
 *   completed and its unit of work has closed, so `close()` waits for it and
 *   `pendingAsyncDeliveries()` reports it.
 * - **Places.** A `suspend` handler holds one of `queueCapacity` places from its submission until
 *   its coroutine has completed (#679). A publish that finds none free gets what the container's
 *   `OverflowPolicy` says ([admit]).
 * - **Shutdown.** After that wait, `close()` [stops][stop] the scope: what is still running is
 *   cancelled and gets until the deadline to finish, which closes its unit; what runs past the
 *   deadline is interrupted.
 */
internal class ContainerCoroutines(private val work: ContainerWork) : ContainerWork.Stoppable {

    /** How a coroutine of the container is started. */
    enum class Start {
        /** On a thread of the container, holding no place: work that is not a handler's delivery. */
        UNCOUNTED,

        /** On a thread of the container, holding the place [admit] gave. */
        IN_A_PLACE,

        /** On the calling thread up to the first suspension, then on threads of the container; no place. */
        ON_THE_CALLER,
    }

    /** What a publish to a `suspend` handler is told when it asks for a place. */
    enum class Admission(val start: Start?) {
        /** A place was free, or became free while the publisher waited under `BLOCK`. */
        PLACE(Start.IN_A_PLACE),

        /** None free, and the publisher starts the handler itself: `CALLER_RUNS`. */
        ON_THE_CALLER(Start.ON_THE_CALLER),

        /** None free under `DROP`. */
        DROPPED(null),

        /** None free under `ROUTE_TO_DLQ`. */
        DEAD_LETTER(null),

        /** The publisher was interrupted while it waited under `BLOCK`; its interrupt flag is set. */
        INTERRUPTED(null),

        /** The container is closing. */
        CLOSING(null),
    }

    private val executor: ExecutorService = Executors.newThreadPerTaskExecutor(work.virtualThreads(THREAD_PREFIX))
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val places = Semaphore(work.queueCapacity())

    @Volatile
    private var stopping = false

    /**
     * Asks for a place for one more `suspend` handler in flight. With none free, the container's
     * `OverflowPolicy` decides, as it does for an async handler whose queue is full.
     *
     * - `BLOCK` waits on the calling thread. A thread of the container does not wait: the places it
     *   would wait for are held by handlers like the one it runs (#727), so it starts the handler
     *   itself, as `CALLER_RUNS` does.
     *
     * @throws EventQueueOverflowException with none free under `THROW`
     */
    fun admit(): Admission {
        if (stopping) {
            return Admission.CLOSING
        }
        if (places.tryAcquire()) {
            return Admission.PLACE
        }
        return when (work.onOverflow()) {
            OverflowPolicy.BLOCK -> if (work.isOwnThread()) Admission.ON_THE_CALLER else waitForAPlace()
            OverflowPolicy.DROP -> Admission.DROPPED
            OverflowPolicy.THROW -> throw EventQueueOverflowException(
                "The limit of suspend handlers in flight is reached and the overflow policy is THROW.",
            )
            OverflowPolicy.ROUTE_TO_DLQ -> Admission.DEAD_LETTER
            OverflowPolicy.CALLER_RUNS -> Admission.ON_THE_CALLER
        }
    }

    /** `BLOCK`: waits until a handler in flight completes, the container closes, or the caller is interrupted. */
    private fun waitForAPlace(): Admission {
        try {
            // A bounded wait, so close() releases a publisher also when no handler completes.
            while (!stopping) {
                if (places.tryAcquire(CLOSING_CHECK_MILLIS, TimeUnit.MILLISECONDS)) {
                    return Admission.PLACE
                }
            }
            return Admission.CLOSING
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return Admission.INTERRUPTED
        }
    }

    /**
     * Launches [block] in a unit of work of its own, which descends from the work the caller is
     * doing now. The returned job completes after that unit has closed.
     *
     * @param onFailure receives what [block] threw, once; a cancellation is not a failure
     * @return the coroutine's job; already cancelled, with [block] never run, once [stop] has begun
     */
    fun launchInUnit(
        carrier: UnitCarrier,
        onFailure: (Throwable) -> Unit,
        block: suspend CoroutineScope.() -> Unit,
    ): Job =
        // Read here, on the caller's thread: the unit is opened later, on a thread of its own.
        launchInUnit(EventChainContext.carriedUnitOpener(carrier), onFailure, Start.UNCOUNTED, block)

    /**
     * As above, with the unit opened by [openUnit]: a handler's unit descends from the event it handles.
     *
     * @param start what [admit] answered for this handler
     */
    fun launchInUnit(
        openUnit: Supplier<CarriedUnit>,
        onFailure: (Throwable) -> Unit,
        start: Start,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = launchCounted(onFailure, start) {
        // withContext returns once the unit has closed, so this coroutine, and with it the
        // delivery, ends after the unit's teardown and never before it.
        withContext(UnitOfWorkElement(openUnit), block)
    }

    /**
     * Launches [block] with no unit of work, for a handler of a framework lifecycle event: such an
     * event is a signal about units and opens none. Events [block] publishes descend from [origin].
     */
    fun launchWithoutUnit(
        origin: Event<*>?,
        onFailure: (Throwable) -> Unit,
        start: Start,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = launchCounted(onFailure, start) { withContext(OriginElement(origin), block) }

    private fun launchCounted(
        onFailure: (Throwable) -> Unit,
        start: Start,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        val release = work.retainDelivery()
        // Undispatched, the coroutine runs here, on the caller's thread, until it first suspends,
        // and resumes on a thread of the container. A scope that has been stopped runs nothing of
        // it: withContext, which every block starts with, refuses a cancelled job.
        val how = if (start == Start.ON_THE_CALLER) CoroutineStart.UNDISPATCHED else CoroutineStart.DEFAULT
        val job = scope.launch(CoroutineExceptionHandler { _, failure -> onFailure(failure) }, how, block)
        job.invokeOnCompletion {
            if (start == Start.IN_A_PLACE) {
                places.release()
            }
            release.run()
        }
        return job
    }

    /**
     * Cancels the scope and waits until [deadlineNanos] for its coroutines to finish. A cancelled
     * coroutine runs its `finally` blocks and closes its unit of work on its own threads. One that
     * is still running at the deadline, blocked in code cancellation does not reach, is interrupted.
     */
    override fun stop(deadlineNanos: Long) {
        stopping = true
        val finished = CountDownLatch(1)
        scope.coroutineContext.job.invokeOnCompletion { finished.countDown() }
        scope.cancel("The container is closing.")
        val allDone = try {
            finished.await(deadlineNanos - System.nanoTime(), TimeUnit.NANOSECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (allDone) executor.shutdown() else executor.shutdownNow()
    }

    companion object {
        private const val THREAD_PREFIX = "tiko-coroutine-"

        /** How often a publisher waiting under `BLOCK` looks whether the container is closing. */
        private const val CLOSING_CHECK_MILLIS = 100L

        /**
         * The coroutines of the boot [container] belongs to, created on first use.
         *
         * @return `null` once the container has begun to close
         * @throws IllegalStateException if [container] was not booted by `Tiko.create(...)`
         */
        fun of(container: Container): ContainerCoroutines? {
            val work = ContainerWork.of(container.eventExecutor)
            return work.stoppedAtClose(ContainerCoroutines::class.java) { ContainerCoroutines(work) }.orElse(null)
        }
    }
}
