package io.tiko.coroutines

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * #681: [SuspendBridges.run] runs a `suspend` bridge on the calling thread, which waits for it, in
 * a unit of work of its own, and publishes what the bridge returns inside that unit. It is what a
 * Kafka consumer thread calls for each record of a `suspend` `@KafkaSource`.
 */
class SuspendBridgesTest : BootedContainerTest() {

    /** What synchronous subscribers of a published text saw. */
    private val published = CopyOnWriteArrayList<String>()

    private fun subscribe() {
        val caller = Thread.currentThread()
        container.eventBus.subscribe(String::class.java) {
            published += "$it in unit ${requests.currentId()}, on the calling thread=${Thread.currentThread() === caller}"
        }
    }

    @Test
    fun theBridgeRunsInOneUnitAcrossASuspensionAndADispatcherSwitch() {
        val ids = CopyOnWriteArrayList<String>()

        SuspendBridges.run(container) {
            ids += requests.currentId()
            delay(10)
            ids += requests.currentId()
            withContext(Dispatchers.IO) { ids += requests.currentId() }
            null
        }

        assertThat(ids).hasSize(3)
        assertThat(ids.distinct()).`as`("one unit of work").hasSize(1)
        assertThat(closeLog.closed()).`as`("closed once, when run returns").containsExactly(ids[0])
        assertThat(unitOpenHere()).`as`("nothing left open on the calling thread").isFalse()
    }

    @Test
    fun whatTheBridgeReturnsIsPublishedInsideItsUnitOnTheCallingThread() {
        subscribe()
        val bridgesUnit = AtomicReference<String>()

        SuspendBridges.run(container) {
            bridgesUnit.set(requests.currentId())
            withContext(Dispatchers.IO) { delay(5) }
            "an event"
        }

        assertThat(published).containsExactly("an event in unit ${bridgesUnit.get()}, on the calling thread=true")
    }

    @Test
    fun aBridgeThatReturnsNullPublishesNothing() {
        subscribe()

        SuspendBridges.run(container) {
            delay(1)
            null
        }

        assertThat(published).isEmpty()
        assertThat(unitsStarted()).`as`("it still ran in a unit of work").isEqualTo(1)
    }

    @Test
    fun aFailingBridgeThrowsToTheCallerAfterItsUnitClosed() {
        subscribe()
        val bridgesUnit = AtomicReference<String>()
        val closedWhenThrown = CopyOnWriteArrayList<String>()

        assertThatThrownBy {
            try {
                SuspendBridges.run(container) {
                    bridgesUnit.set(requests.currentId())
                    delay(1)
                    error("the bridge failed")
                }
            } finally {
                closedWhenThrown += closeLog.closed()
            }
        }.isInstanceOf(IllegalStateException::class.java).hasMessage("the bridge failed")

        assertThat(closedWhenThrown).containsExactly(bridgesUnit.get())
        assertThat(published).isEmpty()
        assertThat(unitOpenHere()).isFalse()
    }

    @Test
    fun anInterruptCancelsTheBridgeClosesItsUnitAndIsThrown() {
        subscribe()
        val suspended = CountDownLatch(1)
        val bridgesUnit = AtomicReference<String>()
        val cancelled = AtomicBoolean()
        val thrown = AtomicReference<Throwable>()
        val unitOpenAfterwards = AtomicBoolean(true)

        val caller = thread {
            try {
                SuspendBridges.run(container) {
                    bridgesUnit.set(requests.currentId())
                    suspended.countDown()
                    try {
                        delay(30_000)
                    } finally {
                        cancelled.set(!currentCoroutineContext().isActive)
                    }
                    "never published"
                }
            } catch (e: InterruptedException) {
                thrown.set(e)
            }
            unitOpenAfterwards.set(unitOpenHere())
        }
        assertThat(suspended.await(5, TimeUnit.SECONDS)).isTrue()
        caller.interrupt()
        caller.join(5_000)

        assertThat(caller.isAlive).isFalse()
        assertThat(thrown.get()).isInstanceOf(InterruptedException::class.java)
        assertThat(cancelled).`as`("the bridge was cancelled, and its finally ran").isTrue()
        assertThat(closeLog.closed()).`as`("its unit closed").containsExactly(bridgesUnit.get())
        assertThat(published).isEmpty()
        assertThat(unitOpenAfterwards).`as`("nothing left open on the calling thread").isFalse()
    }

    /**
     * #874: the interrupt comes while the bridge is suspended on another dispatcher, not on the
     * event loop of `runBlocking`. The calling thread still waits until the bridge's `finally` has
     * run and its unit has closed before the interrupt is thrown: nothing of the bridge outlives
     * [SuspendBridges.run].
     */
    @Test
    fun anInterruptWhileTheBridgeIsOnAnotherDispatcherClosesItsUnitBeforeItIsThrown() {
        val suspended = CountDownLatch(1)
        val bridgesUnit = AtomicReference<String>()
        val finallyRan = AtomicBoolean()
        val thrown = AtomicReference<Throwable>()
        val closedWhenThrown = CopyOnWriteArrayList<String>()
        val finallyRanWhenThrown = AtomicBoolean()

        val caller = thread {
            try {
                SuspendBridges.run(container) {
                    bridgesUnit.set(requests.currentId())
                    withContext(Dispatchers.IO) {
                        suspended.countDown()
                        try {
                            delay(30_000)
                        } finally {
                            // Slow on purpose: a caller that does not wait for it would throw first.
                            Thread.sleep(200)
                            finallyRan.set(true)
                        }
                    }
                    "never published"
                }
            } catch (e: InterruptedException) {
                closedWhenThrown += closeLog.closed()
                finallyRanWhenThrown.set(finallyRan.get())
                thrown.set(e)
            }
        }
        assertThat(suspended.await(5, TimeUnit.SECONDS)).isTrue()
        caller.interrupt()
        caller.join(5_000)

        assertThat(caller.isAlive).isFalse()
        assertThat(thrown.get()).isInstanceOf(InterruptedException::class.java)
        assertThat(finallyRanWhenThrown).`as`("the bridge's finally ran before run threw").isTrue()
        assertThat(closedWhenThrown).`as`("its unit closed before run threw").containsExactly(bridgesUnit.get())
    }

    @Test
    fun eachCallHasItsOwnUnit() {
        val ids = CopyOnWriteArrayList<String>()

        repeat(3) {
            SuspendBridges.run(container) {
                delay(1)
                ids += requests.currentId()
                null
            }
        }

        assertThat(ids.distinct()).hasSize(3)
        assertThat(closeLog.closed()).containsExactlyElementsOf(ids)
    }
}
