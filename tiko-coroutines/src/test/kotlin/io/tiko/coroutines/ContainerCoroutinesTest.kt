package io.tiko.coroutines

import io.tiko.runtime.UnitCarrier
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** #678: the coroutines a booted container runs, in a scope of its own on virtual threads it made. */
class ContainerCoroutinesTest : BootedContainerTest() {

    private val failures = CopyOnWriteArrayList<Throwable>()

    private val coroutines: ContainerCoroutines by lazy { ContainerCoroutines.of(container)!! }

    private fun launch(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): Job =
        coroutines.launchInUnit(container as UnitCarrier, { failures += it }, block)

    private fun soon() = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)

    @AfterEach
    fun stopTheCoroutines() {
        coroutines.stop(soon())
    }

    @Test
    fun aLaunchedCoroutineRunsInAUnitOfItsOwnOnAContainerThread() {
        val seen = CompletableDeferred<List<String>>()
        val threads = CopyOnWriteArrayList<Thread>()

        val job = launch {
            val before = requests.currentId()
            threads += Thread.currentThread()
            delay(5)
            threads += Thread.currentThread()
            seen.complete(listOf(before, requests.currentId()))
        }
        val (before, after) = runBlocking { seen.await().also { job.join() } }

        assertThat(after).`as`("one unit across the suspension").isEqualTo(before)
        assertThat(threads).allMatch { it.isVirtual && it.name.startsWith("tiko-coroutine-") }
        assertThat(closeLog.closed()).`as`("the job completes after its unit has closed").containsExactly(before)
        assertThat(failures).isEmpty()
    }

    @Test
    fun itIsAPendingDeliveryUntilItCompletes() {
        val suspended = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()

        val job = launch {
            suspended.complete(Unit)
            finish.await()
        }
        runBlocking { suspended.await() }
        val whileSuspended = container.pendingAsyncDeliveries()
        finish.complete(Unit)
        runBlocking {
            job.join()
            eventually("the delivery is released") { container.pendingAsyncDeliveries() == 0 }
        }

        assertThat(whileSuspended).`as`("a suspended coroutine holds no thread, and is still pending").isEqualTo(1)
    }

    @Test
    fun aFailingCoroutineReportsOnceClosesItsUnitAndLeavesItsSiblingRunning() {
        val siblingId = CompletableDeferred<String>()
        val failingId = CompletableDeferred<String>()
        val goOn = CompletableDeferred<Unit>()

        val sibling = launch {
            val before = requests.currentId()
            goOn.await()
            assertThat(requests.currentId()).isEqualTo(before)
            siblingId.complete(before)
        }
        val failing = launch {
            failingId.complete(requests.currentId())
            error("this handler failed")
        }
        runBlocking { failing.join() }
        val closedAfterTheFailure = closeLog.closed()
        goOn.complete(Unit)
        val (sibId, failId) = runBlocking { sibling.join(); siblingId.await() to failingId.await() }

        assertThat(failures).singleElement().matches { it.message == "this handler failed" }
        assertThat(closedAfterTheFailure).`as`("the failed coroutine's unit, and only that one").containsExactly(failId)
        assertThat(sibling.isCancelled).`as`("the sibling ran to its end").isFalse()
        assertThat(closeLog.closed()).containsExactly(failId, sibId)
    }

    @Test
    fun theBootHasOneScope() {
        assertThat(ContainerCoroutines.of(container)).isSameAs(coroutines)
    }

    @Test
    fun aLaunchAfterTheScopeWasStoppedStartsNothing() {
        coroutines.stop(soon())
        var ran = false

        val job = launch { ran = true }
        runBlocking { job.join() }

        assertThat(job.isCancelled).isTrue()
        assertThat(ran).isFalse()
        assertThat(container.pendingAsyncDeliveries()).isZero()
        assertThat(lifecycle).`as`("no unit of work was opened").isEmpty()
    }

    @Test
    fun stopCancelsWhatIsSuspendedAndInterruptsWhatIsBlocked() {
        val bothRunning = CompletableDeferred<Unit>()
        val inFinally = CopyOnWriteArrayList<String>()
        val running = java.util.concurrent.atomic.AtomicInteger()
        fun started() {
            if (running.incrementAndGet() == 2) bothRunning.complete(Unit)
        }

        val suspended = launch {
            val mine = requests.currentId()
            try {
                started()
                delay(60_000)
            } finally {
                inFinally += "suspended one, still in its unit: ${requests.currentId() == mine}"
            }
        }
        val blocked = launch {
            val mine = requests.currentId()
            try {
                started()
                Thread.sleep(60_000) // cancellation does not reach blocking code
            } finally {
                inFinally += "blocked one, still in its unit: ${requests.currentId() == mine}"
            }
        }
        runBlocking { bothRunning.await() }

        val startedAt = System.nanoTime()
        coroutines.stop(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500))
        val stopTookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        runBlocking {
            joinAll(suspended, blocked)
            eventually("both units are closed") { closeLog.closed().size == 2 }
        }

        assertThat(stopTookMillis).`as`("stop returns at its deadline").isLessThan(2_000)
        assertThat(inFinally).containsExactlyInAnyOrder(
            "suspended one, still in its unit: true",
            "blocked one, still in its unit: true",
        )
        assertThat(unitsEnded()).hasSize(2)
        assertThat(failures).`as`("an interrupt at shutdown is reported as the handler's failure").hasSize(1)
    }

    /** The load case of the virtual-thread experiment, kept as a regression test. */
    @Test
    fun twoThousandCoroutinesKeepTheirUnits() {
        val n = 2_000
        val results = CopyOnWriteArrayList<Boolean>()

        val jobs = (1..n).map {
            launch {
                val a = requests.currentId()
                delay(1)
                val b = requests.currentId()
                val c = withContext(Dispatchers.IO) { requests.currentId() }
                yield()
                val child = async { requests.currentId() }.await()
                results += (setOf(a, b, c, requests.currentId()).size == 1 && child != a)
            }
        }
        runBlocking {
            jobs.joinAll()
            eventually("every unit is closed") { closeLog.closed().size == 2 * n }
        }

        assertThat(results).hasSize(n).doesNotContain(false)
        assertThat(unitsStarted()).isEqualTo(2 * n)
        assertThat(container.pendingAsyncDeliveries()).isZero()
        assertThat(failures).isEmpty()
    }
}
