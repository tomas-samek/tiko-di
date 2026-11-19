package io.tiko.coroutines

import io.tiko.Container
import io.tiko.DeliveryFailureKind
import io.tiko.ErrorContext
import io.tiko.ErrorHandler
import io.tiko.EventHandlerError
import io.tiko.EventHandlerInfo
import io.tiko.annotations.BackoffStrategy
import io.tiko.coroutines.fixtures.CloseLog
import io.tiko.coroutines.fixtures.Requests
import io.tiko.runtime.ContainerWork
import io.tiko.runtime.EventChainContext
import io.tiko.runtime.OverflowPolicy
import io.tiko.runtime.RetryPolicy
import io.tiko.runtime.Tiko
import io.tiko.runtime.TikoOptions
import io.tiko.runtime.UnitCarrier
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * #680: `timeout`, `retries` and `backoff` on a `suspend` handler. A timeout cancels the
 * coroutine, a backoff suspends it, and each attempt runs in a unit of work of its own. Every test
 * calls [SuspendHandlers.launch] the way generated registry code does, with the handler's
 * [RetryPolicy].
 */
@Timeout(60)
class SuspendHandlerResilienceTest {

    private lateinit var container: Container
    private val errors = CopyOnWriteArrayList<ErrorContext>()
    private val errorHandler = ErrorHandler { errors += it }
    private val info = EventHandlerInfo(SuspendHandlerResilienceTest::class.java, "onText", String::class.java, true)

    /** How often the handler was called. */
    private val attempts = AtomicInteger()

    /** The id of the unit of work each attempt ran in. */
    private val units = CopyOnWriteArrayList<String>()

    private val requests: Requests by lazy { container.get(Requests::class.java) }

    private fun boot(onOverflow: OverflowPolicy = OverflowPolicy.CALLER_RUNS, queueCapacity: Int = 1024) {
        container = Tiko.create(
            TikoOptions.builder()
                .onOverflow(onOverflow)
                .queueCapacity(queueCapacity)
                .shutdownTimeout(Duration.ofSeconds(5))
                .build(),
        )
    }

    @AfterEach
    fun close() {
        if (::container.isInitialized) {
            container.close()
        }
    }

    private fun timeout(millis: Long) = RetryPolicy(0, 0L, BackoffStrategy.FIXED, TimeUnit.MILLISECONDS.toNanos(millis))

    private fun retries(
        retries: Int,
        backoffMillis: Long,
        strategy: BackoffStrategy = BackoffStrategy.FIXED,
        timeoutMillis: Long = 0,
    ) = RetryPolicy(
        retries,
        TimeUnit.MILLISECONDS.toNanos(backoffMillis),
        strategy,
        TimeUnit.MILLISECONDS.toNanos(timeoutMillis),
    )

    private fun <T> publish(policy: RetryPolicy?, onSuccess: Consumer<T>? = null, handler: suspend () -> T) {
        SuspendHandlers.launch(container, EventChainContext.wrap("an event"), handler, onSuccess, errorHandler, info, policy)
    }

    /** What every handler here starts with: counts the attempt and notes the unit it runs in. */
    private fun attempt(): Int {
        units += requests.currentId()
        return attempts.incrementAndGet()
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        await().alias(what).atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(5)).until(condition)
    }

    private fun nothingPending() = eventually("no delivery is pending") { container.pendingAsyncDeliveries() == 0 }

    private fun threadsRunning() = ContainerWork.of(container.eventExecutor).virtualThreadsRunning()

    /** The delivery has failed [failed] times and now waits, on no thread, for its next attempt. */
    private fun waitsOutItsBackoff(failed: Int) =
        attempts.get() == failed && threadsRunning() == 0 && container.pendingAsyncDeliveries() == 1

    private fun theOneError(): EventHandlerError {
        assertThat(errors).hasSize(1)
        return errors[0] as EventHandlerError
    }

    private fun unitsClosed(): List<String> = container.get(CloseLog::class.java).closed()

    @Test
    fun aHandlerThatOverrunsItsTimeoutIsCancelledAndReportedOnce() {
        boot()
        val cancelled = AtomicBoolean()

        publish(timeout(100)) {
            attempt()
            try {
                delay(30_000)
            } finally {
                cancelled.set(!currentCoroutineContext().isActive)
            }
        }
        nothingPending()

        val error = theOneError()
        assertThat(error.cause()).isInstanceOf(TimeoutException::class.java)
        assertThat(error.kind()).isEqualTo(DeliveryFailureKind.TIMEOUT)
        assertThat(error.attempts()).isEqualTo(1)
        assertThat(error.event()).isEqualTo("an event")
        assertThat(cancelled).`as`("the handler was cancelled, and its finally ran").isTrue()
        assertThat(unitsClosed()).`as`("the attempt's unit closed once").containsExactlyElementsOf(units)
    }

    @Test
    fun aHandlerThatFinishesInTimeReportsNothing() {
        boot()

        publish(timeout(5_000)) {
            attempt()
            delay(10)
        }
        nothingPending()

        assertThat(attempts).hasValue(1)
        assertThat(errors).isEmpty()
    }

    /** A `withTimeout` of the handler's own is a failure of the handler, not the annotation's timeout. */
    @Test
    fun aTimeoutOfTheHandlersOwnIsNotTheAnnotationsTimeout() {
        boot()

        publish(timeout(5_000)) {
            attempt()
            withTimeout(20) { delay(30_000) }
        }
        nothingPending()

        val error = theOneError()
        assertThat(error.cause()).isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(error.kind()).isEqualTo(DeliveryFailureKind.EXCEPTION)
    }

    /** Cancellation does not interrupt a thread: code that blocks runs on to its next suspension. */
    @Test
    fun aTimeoutTakesEffectAtTheNextSuspensionPoint() {
        boot()
        val neverCounted = CountDownLatch(1)
        val wentOnAfterSuspending = AtomicBoolean()
        val started = System.nanoTime()

        publish(timeout(100)) {
            attempt()
            neverCounted.await(400, TimeUnit.MILLISECONDS) // blocks its thread past the timeout
            delay(1)
            wentOnAfterSuspending.set(true)
        }
        nothingPending()
        val reportedAfterMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertThat(theOneError().cause()).isInstanceOf(TimeoutException::class.java)
        assertThat(reportedAfterMillis).`as`("reported once the blocking call had returned").isGreaterThanOrEqualTo(390)
        assertThat(wentOnAfterSuspending).isFalse()
    }

    @Test
    fun retriesRunUntilAnAttemptSucceeds() {
        boot()
        val results = CopyOnWriteArrayList<String>()

        publish(retries(3, backoffMillis = 10), Consumer { results += it }) {
            val n = attempt()
            delay(1)
            check(n >= 3) { "attempt $n failed" }
            "result of attempt $n"
        }
        nothingPending()

        assertThat(attempts).hasValue(3)
        assertThat(errors).`as`("the attempt that succeeded wins").isEmpty()
        assertThat(results).containsExactly("result of attempt 3")
        assertThat(units.distinct()).`as`("a unit of work for each attempt").hasSize(3)
        assertThat(unitsClosed()).`as`("each closed once").containsExactlyInAnyOrderElementsOf(units)
    }

    @Test
    fun anExhaustedBudgetIsReportedOnceWithItsAttemptCount() {
        boot()

        publish(retries(2, backoffMillis = 10)) {
            val n = attempt()
            delay(1)
            error("attempt $n failed")
        }
        nothingPending()

        val error = theOneError()
        assertThat(error.attempts()).`as`("the initial attempt and two retries").isEqualTo(3)
        assertThat(error.kind()).isEqualTo(DeliveryFailureKind.EXHAUSTED)
        assertThat(error.cause()).`as`("the last failure").hasMessage("attempt 3 failed")
        assertThat(attempts).hasValue(3)
    }

    @ParameterizedTest
    @EnumSource(BackoffStrategy::class)
    fun backoffWaitsBetweenAttempts(strategy: BackoffStrategy) {
        boot()
        val startedAt = CopyOnWriteArrayList<Long>()

        publish(retries(2, backoffMillis = 100, strategy = strategy)) {
            startedAt += System.nanoTime()
            error("failed")
        }
        nothingPending()

        val gaps = startedAt.zipWithNext { earlier, later -> TimeUnit.NANOSECONDS.toMillis(later - earlier) }
        val second = if (strategy == BackoffStrategy.EXPONENTIAL) 200L else 100L
        assertThat(gaps).hasSize(2)
        assertThat(gaps[0]).`as`("before the first retry").isGreaterThanOrEqualTo(95)
        assertThat(gaps[1]).`as`("before the second retry, $strategy").isGreaterThanOrEqualTo(second - 5)
    }

    @Test
    fun noThreadIsOccupiedWhileAHandlerWaitsOutItsBackoff() {
        boot()

        publish(retries(1, backoffMillis = 600)) {
            val n = attempt()
            check(n >= 2) { "attempt $n failed" }
        }

        await().alias("pending, and on no thread, through the backoff")
            .atMost(Duration.ofSeconds(5))
            .during(Duration.ofMillis(200))
            .until { waitsOutItsBackoff(failed = 1) }
        nothingPending()
        assertThat(attempts).hasValue(2)
        assertThat(errors).isEmpty()
    }

    @Test
    fun aTimedOutAttemptIsRetried() {
        boot()

        publish(retries(1, backoffMillis = 10, timeoutMillis = 100)) {
            val n = attempt()
            if (n == 1) {
                delay(30_000)
            }
        }
        nothingPending()

        assertThat(attempts).hasValue(2)
        assertThat(errors).isEmpty()
        assertThat(units.distinct()).hasSize(2)
        assertThat(unitsClosed()).`as`("the timed-out attempt's unit closed once, too")
            .containsExactlyInAnyOrderElementsOf(units)
    }

    /** An attempt is the handler and the publishing of what it triggers, as an async handler's body is. */
    @Test
    fun aFailureWhilePublishingTriggersCountsAsAFailedAttempt() {
        boot()
        val triggered = CopyOnWriteArrayList<String>()
        val onSuccess = Consumer<String> {
            check(attempts.get() >= 2) { "publishing the triggered event failed" }
            triggered += it
        }

        publish(retries(1, backoffMillis = 10), onSuccess) { "result of attempt ${attempt()}" }
        nothingPending()

        assertThat(triggered).containsExactly("result of attempt 2")
        assertThat(errors).isEmpty()
    }

    @Test
    fun anErrorIsNotRetried() {
        boot()

        publish(retries(3, backoffMillis = 10)) {
            attempt()
            throw LinkageError("an Error, which is logged and never retried")
        }
        nothingPending()

        assertThat(attempts).hasValue(1)
        assertThat(errors).`as`("an Error is kept out of the ErrorHandler, as for async handlers").isEmpty()
    }

    @Test
    fun aHandlerCancelledByTheContainerIsNotRetried() {
        boot()
        val suspended = CompletableDeferred<Unit>()

        publish(retries(3, backoffMillis = 10)) {
            attempt()
            suspended.complete(Unit)
            delay(30_000)
        }
        eventually("the handler is suspended") { suspended.isCompleted }
        ContainerCoroutines.of(container)!!.stop(System.nanoTime() + TimeUnit.SECONDS.toNanos(3))
        nothingPending()

        assertThat(attempts).hasValue(1)
        assertThat(errors).isEmpty()
    }

    /** D12: in flight from the publish until its last attempt, so it holds its place through a backoff. */
    @Test
    fun aHandlerInBackoffKeepsItsPlace() {
        boot(OverflowPolicy.DROP, queueCapacity = 1)
        val secondRan = AtomicBoolean()

        publish(retries(1, backoffMillis = 600)) {
            val n = attempt()
            check(n >= 2) { "attempt $n failed" }
        }
        eventually("the first handler waits out its backoff") { waitsOutItsBackoff(failed = 1) }
        SuspendHandlers.launch(
            container,
            EventChainContext.wrap("a second event"),
            suspend { secondRan.set(true) },
            null,
            errorHandler,
            info,
            null,
        )
        nothingPending()

        assertThat(secondRan).`as`("no place was free: dropped").isFalse()
        assertThat(attempts).hasValue(2)
    }

    /** D11: `close()` does not wait for a backoff, as it does not for an async handler's. */
    @Test
    fun aBackoffIsDroppedWhenTheContainerCloses() {
        boot()

        publish(retries(1, backoffMillis = 30_000)) {
            val n = attempt()
            error("attempt $n failed")
        }
        eventually("the handler waits out its backoff") { waitsOutItsBackoff(failed = 1) }
        val started = System.nanoTime()
        container.close()
        val closedAfterMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertThat(closedAfterMillis).`as`("far less than the backoff, or the shutdown timeout").isLessThan(3_000)
        assertThat(attempts).`as`("no further attempt").hasValue(1)
        assertThat(errors).`as`("dropped with a WARNING, like an async retry").isEmpty()
        assertThat(container.pendingAsyncDeliveries()).isZero()
    }

    @Test
    fun aHandlerOfALifecycleEventRetriesWithoutAUnit() {
        boot()
        val seen = CopyOnWriteArrayList<String>()

        SuspendHandlers.launchWithoutUnit(
            container,
            EventChainContext.wrap("a lifecycle event"),
            suspend {
                val n = attempts.incrementAndGet()
                val origin = EventChainContext.wrap("probe").origin.map { it.payload.toString() }.orElse("none")
                seen += "attempt $n: unit open=${(container as UnitCarrier).hasUnitOpen()}, origin=$origin"
                delay(1)
                check(n >= 2) { "attempt $n failed" }
            },
            null,
            errorHandler,
            info,
            retries(1, backoffMillis = 10),
        )
        nothingPending()

        assertThat(seen).containsExactly(
            "attempt 1: unit open=false, origin=a lifecycle event",
            "attempt 2: unit open=false, origin=a lifecycle event",
        )
        assertThat(errors).isEmpty()
    }
}
