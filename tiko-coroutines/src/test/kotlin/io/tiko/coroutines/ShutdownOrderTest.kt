package io.tiko.coroutines

import io.tiko.coroutines.fixtures.Note
import io.tiko.coroutines.fixtures.NoteOrigins
import io.tiko.coroutines.fixtures.ShutdownOrder
import io.tiko.events.EventEndingEvent
import io.tiko.runtime.TikoOptions
import io.tiko.runtime.UnitCarrier
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #678: where the container's coroutines stand in `close()`. The container waits for them like
 * for any pending delivery; what is still running after the wait is cancelled, and its unit of
 * work closes, before events stop being delivered and before any singleton is destroyed.
 */
class ShutdownOrderTest : BootedContainerTest() {

    private val outlivingClose = CopyOnWriteArrayList<String>()

    override fun options(): TikoOptions =
        TikoOptions.builder()
            .shutdownTimeout(SHUTDOWN_TIMEOUT)
            .onThreadsOutlivingClose { threads -> threads.forEach { outlivingClose += it.name } }
            .build()

    private val order: ShutdownOrder by lazy { container.get(ShutdownOrder::class.java) }

    private fun launch(block: suspend CoroutineScope.() -> Unit): Job =
        ContainerCoroutines.of(container)!!.launchInUnit(container as UnitCarrier, {}, block)

    /** Launches [block], waits until it runs, records when its unit of work ends, and closes the container. */
    private fun closeWhile(block: suspend CoroutineScope.(started: () -> Unit) -> Unit): Pair<Job, Long> {
        val started = CompletableDeferred<Unit>()
        val log = order // resolved now: the container refuses lookups once it is closing
        container.eventBus.subscribe(EventEndingEvent::class.java) { log.note("unit ended") }
        val job = launch { block { started.complete(Unit) } }
        runBlocking { started.await() }
        val before = System.nanoTime()
        container.close()
        return job to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)
    }

    @Test
    fun aCoroutineThatFinishesInTimeIsWaitedForNotCancelled() {
        val (job, _) = closeWhile { started ->
            started()
            delay(100)
            order.note("coroutine finished")
        }

        assertThat(job.isCancelled).isFalse()
        assertThat(order.seen())
            .containsExactly("application ending", "coroutine finished", "unit ended", "singleton destroyed")
    }

    @Test
    fun aSuspendedCoroutineIsCancelledAfterTheWaitAndItsUnitClosesBeforeAnySingletonIsDestroyed() {
        val (job, closeTookMillis) = closeWhile { started ->
            try {
                started()
                delay(60_000)
            } finally {
                order.note("coroutine cancelled")
            }
        }

        assertThat(job.isCancelled).isTrue()
        assertThat(order.seen())
            .containsExactly("application ending", "coroutine cancelled", "unit ended", "singleton destroyed")
        assertThat(closeTookMillis)
            .`as`("the wait for pending deliveries, then the cancellation, which is prompt")
            .isBetween(SHUTDOWN_TIMEOUT.toMillis() - 50, SHUTDOWN_TIMEOUT.toMillis() + 1_500)
    }

    /** Sync handlers of what a cancelled coroutine publishes on its way out still run: delivery stops later. */
    @Test
    fun eventsACancelledCoroutinePublishesInItsFinallyAreStillDelivered() {
        val origins = container.get(NoteOrigins::class.java)

        closeWhile { started ->
            try {
                started()
                delay(60_000)
            } finally {
                container.eventBus.publish(Note("from a cancelled coroutine"))
            }
        }

        assertThat(origins.seen()).containsExactly("from a cancelled coroutine <- nothing")
    }

    @Test
    fun aCoroutineBlockedInSleepIsInterruptedAndItsUnitCloses() {
        val (_, closeTookMillis) = closeWhile { started ->
            try {
                started()
                Thread.sleep(60_000) // cancellation does not reach blocking code
            } finally {
                order.note("blocked coroutine interrupted")
            }
        }
        runBlocking { eventually("the interrupted coroutine's unit closes") { order.seen().contains("unit ended") } }

        assertThat(order.seen()).contains("blocked coroutine interrupted")
        assertThat(closeTookMillis)
            .`as`("close() is bounded: the wait, one second for cancelled coroutines, the thread report")
            .isLessThan(SHUTDOWN_TIMEOUT.toMillis() + 4_000)
        assertThat(outlivingClose).`as`("it ended when it was interrupted").isEmpty()
    }

    @Test
    fun aCoroutineThatSwallowsInterruptsIsReportedAsAThreadOutlivingClose() {
        val letGo = AtomicBoolean()
        try {
            closeWhile { started ->
                started()
                while (!letGo.get()) {
                    try {
                        Thread.sleep(20)
                    } catch (ignored: InterruptedException) {
                        // a handler that will not stop
                    }
                }
            }

            assertThat(outlivingClose).singleElement().asString().startsWith("tiko-coroutine-")
        } finally {
            letGo.set(true)
        }
    }

    @Test
    fun nothingCanBeLaunchedOnceTheContainerHasClosed() {
        container.close()

        assertThat(ContainerCoroutines.of(container)).isNull()
    }

    private companion object {
        val SHUTDOWN_TIMEOUT: Duration = Duration.ofMillis(300)
    }
}
