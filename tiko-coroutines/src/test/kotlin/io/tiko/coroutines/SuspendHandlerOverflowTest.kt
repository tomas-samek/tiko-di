package io.tiko.coroutines

import io.tiko.Container
import io.tiko.ErrorContext
import io.tiko.ErrorHandler
import io.tiko.EventDispatchRejected
import io.tiko.EventHandlerError
import io.tiko.EventHandlerInfo
import io.tiko.EventQueueOverflowException
import io.tiko.coroutines.fixtures.CloseLog
import io.tiko.coroutines.fixtures.Requests
import io.tiko.runtime.EventChainContext
import io.tiko.runtime.OverflowPolicy
import io.tiko.runtime.Tiko
import io.tiko.runtime.TikoOptions
import io.tiko.runtime.UnitCarrier
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * #679: at most `queueCapacity` `suspend` handlers hold a place, from their submission until their
 * coroutine has completed, and a publish that finds no place free gets what the container's
 * `OverflowPolicy` says. Every test boots a container with two places and calls
 * [SuspendHandlers.launch] the way generated registry code does.
 */
@Timeout(60)
class SuspendHandlerOverflowTest {

    /** How a handler that holds a place ends. */
    enum class Ending { COMPLETES, FAILS, THROWS_A_CANCELLATION }

    private lateinit var container: Container
    private val errors = CopyOnWriteArrayList<ErrorContext>()
    private val errorHandler = ErrorHandler { errors += it }
    private val info = EventHandlerInfo(SuspendHandlerOverflowTest::class.java, "onText", String::class.java, true)

    /** What the handlers that fill the places wait for. */
    private val gate = CompletableDeferred<Unit>()

    private fun boot(policy: OverflowPolicy) {
        container = Tiko.create(
            TikoOptions.builder()
                .queueCapacity(2)
                .onOverflow(policy)
                .shutdownTimeout(Duration.ofMillis(500))
                .build(),
        )
    }

    @AfterEach
    fun close() {
        gate.complete(Unit)
        if (::container.isInitialized) {
            container.close()
        }
    }

    private fun publish(event: String, handler: suspend () -> Unit) {
        SuspendHandlers.launch(container, EventChainContext.wrap(event), handler, null, errorHandler, info, null)
    }

    /** Two handlers take the two places and stay suspended until [gate] opens. */
    private fun fillThePlaces() {
        val holding = CountDownLatch(2)
        repeat(2) { n ->
            publish("holder $n") {
                holding.countDown()
                gate.await()
            }
        }
        assertThat(holding.await(5, TimeUnit.SECONDS)).`as`("both holders run").isTrue()
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        await().alias(what).atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(5)).until(condition)
    }

    private fun nothingPending() = eventually("no delivery is pending") { container.pendingAsyncDeliveries() == 0 }

    @Test
    fun belowTheLimitAHandlerIsLaunchedOffThePublishersThread() {
        boot(OverflowPolicy.CALLER_RUNS)
        val ranOn = CompletableDeferred<Thread>()

        publish("one") { ranOn.complete(Thread.currentThread()) }
        nothingPending()

        assertThat(ranOn.getCompleted().name).startsWith("tiko-coroutine-")
        assertThat(ranOn.getCompleted()).isNotSameAs(Thread.currentThread())
    }

    @Test
    fun callerRunsStartsTheHandlerOnThePublishersThreadAndFinishesItOnAContainerThread() {
        boot(OverflowPolicy.CALLER_RUNS)
        fillThePlaces()
        val goOn = CompletableDeferred<Unit>()
        val threads = CopyOnWriteArrayList<Thread>()
        val finished = AtomicBoolean()

        publish("third") {
            threads += Thread.currentThread()
            goOn.await()
            threads += Thread.currentThread()
            finished.set(true)
        }
        val startedOn = threads.toList()
        val finishedWhenPublishReturned = finished.get()
        goOn.complete(Unit)
        eventually("the handler finishes") { finished.get() }

        assertThat(startedOn).`as`("up to its first suspension").containsExactly(Thread.currentThread())
        assertThat(finishedWhenPublishReturned).`as`("publish does not wait for the handler").isFalse()
        assertThat(threads[1].isVirtual).isTrue()
        assertThat(threads[1].name).`as`("after it").startsWith("tiko-coroutine-")
        assertThat(errors).isEmpty()
    }

    @Test
    fun callerRunsLeavesThePublishersOpenUnitAndOriginAsTheyWere() {
        boot(OverflowPolicy.CALLER_RUNS)
        fillThePlaces()
        val requests = container.get(Requests::class.java)
        val goOn = CompletableDeferred<Unit>()
        val handlersIds = CopyOnWriteArrayList<String>()
        val publishers = ArrayList<String>()

        container.runInEventScope {
            publishers += requests.currentId()
            publish("third") {
                handlersIds += requests.currentId()
                goOn.await()
                handlersIds += requests.currentId()
            }
            publishers += requests.currentId()
            publish("fails before it suspends") { error("early") }
            publishers += requests.currentId()
            publishers += EventChainContext.wrap("probe").origin.map { it.payload.toString() }.orElse("no origin")
        }
        val unitOpenAfterwards = (container as UnitCarrier).hasUnitOpen()
        goOn.complete(Unit)
        eventually("the handler finishes") { handlersIds.size == 2 }
        gate.complete(Unit)
        nothingPending()

        assertThat(publishers.take(3).distinct()).`as`("the publisher's unit, before and after").hasSize(1)
        assertThat(publishers[3]).`as`("the publisher's origin").isEqualTo("no origin")
        assertThat(unitOpenAfterwards).isFalse()
        assertThat(handlersIds.distinct()).`as`("the handler's own unit, across its suspension")
            .hasSize(1)
            .doesNotContain(publishers[0])
        assertThat(container.get(CloseLog::class.java).closed()).`as`("the handler's unit closed").contains(handlersIds[0])
        assertThat(errors).`as`("a failure before the first suspension goes to the ErrorHandler, not to the publisher")
            .singleElement()
            .isInstanceOfSatisfying(EventHandlerError::class.java) {
                assertThat(it.cause()).hasMessage("early")
                assertThat(it.event()).isEqualTo("fails before it suspends")
            }
    }

    /** D10: the default policy slows a publisher down; it is `BLOCK` and the others that cap. */
    @Test
    fun underCallerRunsTheLimitDoesNotCapHandlersInFlight() {
        boot(OverflowPolicy.CALLER_RUNS)

        repeat(10) { n -> publish("event $n") { gate.await() } }

        assertThat(container.pendingAsyncDeliveries()).isEqualTo(10)
    }

    @Test
    fun blockHoldsThePublisherUntilAPlaceIsFree() {
        boot(OverflowPolicy.BLOCK)
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        val release = List(3) { CompletableDeferred<Unit>() }
        val started = CopyOnWriteArrayList<Int>()
        fun publishCounted(n: Int) = publish("event $n") {
            most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            started += n
            try {
                release[n].await()
            } finally {
                inFlight.decrementAndGet()
            }
        }
        publishCounted(0)
        publishCounted(1)
        eventually("two handlers hold the places") { started.size == 2 }

        val publisher = thread { publishCounted(2) }
        publisher.join(200)
        val heldWhileFull = publisher.isAlive && started.size == 2
        release[0].complete(Unit)
        publisher.join(5_000)
        eventually("the third handler runs") { started.size == 3 }
        release.forEach { it.complete(Unit) }
        nothingPending()

        assertThat(heldWhileFull).`as`("the publisher waits and the third handler has not started").isTrue()
        assertThat(publisher.isAlive).`as`("released by a free place").isFalse()
        assertThat(most.get()).`as`("handlers in flight at once").isEqualTo(2)
    }

    /** The places it would wait for are held by handlers like itself (#727). */
    @Test
    fun blockFromAContainerThreadDoesNotWait() {
        boot(OverflowPolicy.BLOCK)
        val third = CompletableDeferred<Unit>()
        val publishedFromAHandler = CompletableDeferred<Unit>()
        publish("holder") { gate.await() }

        publish("publishes") {
            publish("third") { third.complete(Unit) }
            publishedFromAHandler.complete(Unit)
            gate.await()
        }

        eventually("the handler's publish returned and its event was handled") {
            publishedFromAHandler.isCompleted && third.isCompleted
        }
        assertThat(gate.isCompleted).`as`("no place was freed").isFalse()
    }

    @Test
    fun blockReleasesAWaitingPublisherWhenTheContainerCloses() {
        boot(OverflowPolicy.BLOCK)
        fillThePlaces()
        val ran = AtomicBoolean()

        val publisher = thread { publish("third") { ran.set(true) } }
        publisher.join(200)
        val waiting = publisher.isAlive
        container.close()
        publisher.join(5_000)

        assertThat(waiting).isTrue()
        assertThat(publisher.isAlive).`as`("released by close()").isFalse()
        assertThat(ran).`as`("its event was dropped").isFalse()
    }

    @Test
    fun blockKeepsTheInterruptFlagOfAnInterruptedPublisher() {
        boot(OverflowPolicy.BLOCK)
        fillThePlaces()
        val ran = AtomicBoolean()
        val interruptedAfterwards = AtomicBoolean()

        val publisher = thread {
            publish("third") { ran.set(true) }
            interruptedAfterwards.set(Thread.currentThread().isInterrupted)
        }
        publisher.join(200)
        publisher.interrupt()
        publisher.join(5_000)

        assertThat(publisher.isAlive).isFalse()
        assertThat(interruptedAfterwards).`as`("the interrupt is still the publisher's to see").isTrue()
        assertThat(ran).`as`("its event was dropped").isFalse()
    }

    @Test
    fun dropDeliversNothingAndLeavesNothingPending() {
        boot(OverflowPolicy.DROP)
        fillThePlaces()
        val ran = AtomicBoolean()

        publish("third") { ran.set(true) }
        val pendingAtTheLimit = container.pendingAsyncDeliveries()
        gate.complete(Unit)
        nothingPending()

        assertThat(pendingAtTheLimit).`as`("the two holders only").isEqualTo(2)
        assertThat(ran).isFalse()
        assertThat(errors).isEmpty()
    }

    @Test
    fun throwGivesThePublisherAnEventQueueOverflowException() {
        boot(OverflowPolicy.THROW)
        fillThePlaces()
        val ran = AtomicBoolean()

        assertThatThrownBy { publish("third") { ran.set(true) } }
            .isInstanceOf(EventQueueOverflowException::class.java)
            .hasMessageContaining("suspend handlers in flight")
            .hasMessageContaining("THROW")

        assertThat(ran).isFalse()
        assertThat(container.pendingAsyncDeliveries()).isEqualTo(2)
    }

    @Test
    fun routeToDlqGivesTheErrorHandlerTheEventAsEventDispatchRejected() {
        boot(OverflowPolicy.ROUTE_TO_DLQ)
        fillThePlaces()
        val ran = AtomicBoolean()

        publish("third") { ran.set(true) }

        assertThat(errors).containsExactly(EventDispatchRejected("third"))
        assertThat(ran).isFalse()
    }

    @ParameterizedTest
    @EnumSource(Ending::class)
    fun aPlaceIsFreeAgainAfterAHandlerCompletedFailedOrThrewACancellation(ending: Ending) {
        boot(OverflowPolicy.DROP)
        val end = CompletableDeferred<Unit>()
        repeat(2) { n ->
            publish("first round $n") {
                end.await()
                when (ending) {
                    Ending.COMPLETES -> Unit
                    Ending.FAILS -> error("the handler failed")
                    Ending.THROWS_A_CANCELLATION -> throw CancellationException("thrown by the handler itself")
                }
            }
        }
        end.complete(Unit)
        nothingPending()
        val secondRound = CopyOnWriteArrayList<String>()

        repeat(3) { n -> publish("second round $n") { secondRound += "ran $n"; gate.await() } }
        eventually("two handlers of the second round run") { secondRound.size == 2 }

        assertThat(secondRound).`as`("two places again, and the third publish is dropped")
            .containsExactlyInAnyOrder("ran 0", "ran 1")
        assertThat(errors).`as`("a cancellation the handler threw itself is a failure").hasSize(if (ending == Ending.COMPLETES) 0 else 2)
    }

    @Test
    fun aHandlerOfALifecycleEventTakesAPlaceToo() {
        boot(OverflowPolicy.DROP)
        val holding = CountDownLatch(2)
        val ran = AtomicBoolean()
        repeat(2) { n ->
            val event = "lifecycle $n"
            SuspendHandlers.launchWithoutUnit(
                container,
                EventChainContext.wrap(event),
                suspend {
                    holding.countDown()
                    gate.await()
                },
                null,
                errorHandler,
                info,
                null,
            )
        }
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue()

        publish("third") { ran.set(true) }
        gate.complete(Unit)
        nothingPending()

        assertThat(ran).`as`("dropped, so it never runs").isFalse()
    }

    /** `CALLER_RUNS` starts a handler on the publisher's thread, and must not once the coroutines are stopped. */
    @Test
    fun afterTheCoroutinesWereStoppedAPublishAtTheLimitRunsNothing() {
        boot(OverflowPolicy.CALLER_RUNS)
        val stuck = CompletableDeferred<Unit>()
        val holding = CountDownLatch(2)
        val cancelled = CountDownLatch(2)
        val ran = AtomicBoolean()
        repeat(2) {
            publish("holder") {
                try {
                    holding.countDown()
                    gate.await()
                } finally {
                    // Keeps its place past the cancellation, as a handler blocked in cleanup does.
                    cancelled.countDown()
                    withContext(NonCancellable) { stuck.await() }
                }
            }
        }
        // A coroutine that is cancelled before it has started never runs, and so has no finally.
        assertThat(holding.await(5, TimeUnit.SECONDS)).`as`("both holders run").isTrue()
        val stopper = thread { ContainerCoroutines.of(container)!!.stop(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)) }
        assertThat(cancelled.await(5, TimeUnit.SECONDS)).`as`("the coroutines are being stopped").isTrue()

        publish("third") { ran.set(true) }
        stuck.complete(Unit)
        stopper.join(5_000)

        assertThat(ran).isFalse()
        assertThat(stopper.isAlive).isFalse()
    }
}
