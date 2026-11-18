package io.tiko.coroutines

import io.tiko.Container
import io.tiko.runtime.ContainerWork
import io.tiko.runtime.EventChainContext
import io.tiko.runtime.UnitCarrier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
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
 * - **Shutdown.** After that wait, `close()` [stops][stop] the scope: what is still running is
 *   cancelled and gets until the deadline to finish, which closes its unit; what runs past the
 *   deadline is interrupted.
 */
internal class ContainerCoroutines(private val work: ContainerWork) : ContainerWork.Stoppable {

    private val executor: ExecutorService = Executors.newThreadPerTaskExecutor(work.virtualThreads(THREAD_PREFIX))
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

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
    ): Job {
        // Read here, on the caller's thread: the unit is opened later, on a thread of its own.
        val openUnit = EventChainContext.carriedUnitOpener(carrier)
        val release = work.retainDelivery()
        val job = scope.launch(CoroutineExceptionHandler { _, failure -> onFailure(failure) }) {
            // withContext returns once the unit has closed, so this coroutine, and with it the
            // delivery, ends after the unit's teardown and never before it.
            withContext(UnitOfWorkElement(openUnit), block)
        }
        job.invokeOnCompletion { release.run() }
        return job
    }

    /**
     * Cancels the scope and waits until [deadlineNanos] for its coroutines to finish. A cancelled
     * coroutine runs its `finally` blocks and closes its unit of work on its own threads. One that
     * is still running at the deadline, blocked in code cancellation does not reach, is interrupted.
     */
    override fun stop(deadlineNanos: Long) {
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
