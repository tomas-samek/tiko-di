package io.tiko.coroutines;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.core.ksp.KspCompiler.KspResult;
import io.tiko.processor.core.ksp.KspProgram;
import io.tiko.processor.ksp.TikoSymbolProcessorProvider;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * #677, #678: {@code suspend} event handlers end to end. A Kotlin program is compiled with KSP,
 * the generated Java with it, and then booted; {@code app.Scenarios} drives the container inside
 * the program and answers what it saw.
 */
@Timeout(120)
class KspSuspendHandlerTest {

    private static final String PROGRAM = """
            package app

            import io.tiko.Container
            import io.tiko.ErrorContext
            import io.tiko.Event
            import io.tiko.EventBus
            import io.tiko.EventDispatchRejected
            import io.tiko.EventHandlerError
            import io.tiko.EventQueueOverflowException
            import io.tiko.NoActiveEventScopeException
            import io.tiko.Scope
            import io.tiko.annotations.BackoffStrategy
            import io.tiko.annotations.Component
            import io.tiko.annotations.EventHandler
            import io.tiko.annotations.EventTrigger
            import io.tiko.annotations.PreDestroy
            import io.tiko.events.ApplicationStartedEvent
            import io.tiko.runtime.OverflowPolicy
            import io.tiko.runtime.Tiko
            import io.tiko.runtime.TikoOptions
            import java.time.Duration
            import java.util.UUID
            import java.util.concurrent.CopyOnWriteArrayList
            import java.util.concurrent.CountDownLatch
            import java.util.concurrent.TimeUnit
            import java.util.concurrent.atomic.AtomicInteger
            import kotlinx.coroutines.CompletableDeferred
            import kotlinx.coroutines.currentCoroutineContext
            import kotlinx.coroutines.delay
            import kotlinx.coroutines.isActive

            object Seen {
                @JvmField val log = CopyOnWriteArrayList<String>()
                @JvmField val units = CopyOnWriteArrayList<String>()
                @JvmField val errors = CopyOnWriteArrayList<String>()
                @JvmField val release = CompletableDeferred<Unit>()
                @JvmField val holding = CountDownLatch(1)
            }

            interface RequestId { fun value(): String }

            @Component(scope = Scope.EVENT)
            class FreshRequestId : RequestId {
                private val id = UUID.randomUUID().toString()
                override fun value(): String = id
                @PreDestroy fun closed() { Seen.log += "unit closed" }
            }

            @Component(scope = Scope.SINGLETON)
            class Lifetime {
                @PreDestroy fun destroyed() { Seen.log += "singleton destroyed" }
            }

            data class Ping(val text: String)
            data class Echo(val text: String)
            data class Boom(val text: String)
            data class Step(val name: String, val fail: Boolean)
            data class Stepped(val name: String)
            class Hold
            data class Slow(val millis: Long)
            data class Both(val n: Int)
            data class Flaky(val failures: Int)
            data class Stuck(val text: String)

            @Component(scope = Scope.SINGLETON)
            class Handlers(private val requestId: RequestId, private val bus: EventBus, private val lifetime: Lifetime) {

                fun currentId(): String = requestId.value()

                @EventHandler
                suspend fun onPing(ping: Ping, wrapper: Event<*>) {
                    val before = requestId.value()
                    delay(20)
                    val thread = Thread.currentThread()
                    Seen.log += "ping ${ping.text}: own unit=${before != Seen.log.firstOrNull()?.substringAfter("publisher id=")}" +
                        " same after delay=${requestId.value() == before} virtual=${thread.isVirtual}" +
                        " named=${thread.name.startsWith("tiko-coroutine-")} wrapper is the event=${wrapper.payload == ping}"
                    bus.publish(Echo(ping.text))
                }

                @EventHandler
                fun onEcho(echo: Echo, wrapper: Event<*>) {
                    Seen.log += "echo ${echo.text} descends from ${wrapper.origin.map { it.payload }.orElse(null)}"
                }

                @EventHandler
                suspend fun onBoom(boom: Boom) {
                    delay(5)
                    error("boom after a suspension")
                }

                @EventHandler
                @EventTrigger(eventName = "Stepped")
                suspend fun onStep(step: Step): Stepped {
                    delay(5)
                    check(!step.fail) { "step ${step.name} failed" }
                    return Stepped(step.name)
                }

                @EventHandler
                fun onStepped(stepped: Stepped) {
                    Seen.log += "stepped ${stepped.name}"
                }

                @EventHandler
                suspend fun onHold(hold: Hold) {
                    Seen.log += "hold started"
                    Seen.holding.countDown()
                    try {
                        Seen.release.await()
                        Seen.log += "hold finished"
                    } finally {
                        val cancelled = !currentCoroutineContext().isActive
                        Seen.log += "hold ended, cancelled=$cancelled"
                        if (cancelled) bus.publish(Ping("from a cancelled handler"))
                    }
                }

                @EventHandler
                suspend fun onBothSuspending(both: Both) {
                    Seen.log += "suspend handler got ${both.n}"
                    Seen.release.await()
                }

                @EventHandler
                fun onBothSync(both: Both) {
                    Seen.log += "sync handler got ${both.n}"
                }

                private val flakyAttempts = AtomicInteger()

                @EventHandler(timeout = "PT0.2S", retries = 2, backoff = "PT0.05S", backoffStrategy = BackoffStrategy.EXPONENTIAL)
                suspend fun onFlaky(flaky: Flaky) {
                    val n = flakyAttempts.incrementAndGet()
                    val id = requestId.value()
                    Seen.log += "flaky attempt $n in a new unit=${Seen.units.add(id)}"
                    if (n == 1) delay(30_000)
                    check(n > flaky.failures) { "attempt $n failed" }
                }

                @EventHandler(timeout = "PT0.1S")
                suspend fun onStuck(stuck: Stuck) {
                    delay(30_000)
                }

                @EventHandler
                suspend fun onSlow(slow: Slow) {
                    delay(slow.millis)
                    Seen.log += "slow finished"
                }

                @EventHandler
                suspend fun onStarted(started: ApplicationStartedEvent) {
                    delay(1)
                    Seen.log += try {
                        requestId.value()
                        "started handler: in a unit of work"
                    } catch (e: NoActiveEventScopeException) {
                        "started handler: no unit of work"
                    }
                }
            }

            object Scenarios {

                private fun boot(
                    shutdownTimeout: Duration = Duration.ofSeconds(5),
                    onOverflow: OverflowPolicy = OverflowPolicy.CALLER_RUNS,
                    queueCapacity: Int = 1024,
                ): Container =
                    Tiko.create(
                        TikoOptions.builder()
                            .shutdownTimeout(shutdownTimeout)
                            .onOverflow(onOverflow)
                            .queueCapacity(queueCapacity)
                            .errorHandler { context -> Seen.errors += describe(context) }
                            .build(),
                    ).also { settle(it) }

                private fun describe(context: ErrorContext): String = when (context) {
                    is EventHandlerError ->
                        "EventHandlerError ${context.handler().declaringClass().simpleName}.${context.handler().methodName()}" +
                            " async=${context.handler().async()} cause=${context.cause().javaClass.simpleName}:" +
                            " ${context.cause().message} attempts=${context.attempts()} kind=${context.kind()}"
                    is EventDispatchRejected -> "EventDispatchRejected ${context.event()}"
                    else -> context.javaClass.simpleName
                }

                /** Waits until the container has no delivery pending: every launched handler has completed. */
                private fun settle(container: Container) {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                    while (container.pendingAsyncDeliveries() > 0) {
                        check(System.nanoTime() < deadline) { "deliveries still pending: ${Seen.log}" }
                        Thread.sleep(5)
                    }
                }

                private fun report(): List<String> = Seen.log.toList() + Seen.errors.map { "ERROR $it" }

                @JvmStatic
                fun startedHandler(): List<String> {
                    boot().close()
                    return report()
                }

                @JvmStatic
                fun ownUnit(): List<String> {
                    boot().use { container ->
                        Seen.log.clear()
                        val handlers = container.get(Handlers::class.java)
                        container.runInEventScope {
                            Seen.log += "publisher id=${handlers.currentId()}"
                            container.eventBus.publish(Ping("a"))
                            Seen.log += "publish returned"
                        }
                        settle(container)
                    }
                    return report()
                }

                @JvmStatic
                fun publishReturnsFirst(): List<String> {
                    boot().use { container ->
                        Seen.log.clear()
                        container.eventBus.publish(Hold())
                        Seen.holding.await()
                        Seen.log += "publish returned, pending=${container.pendingAsyncDeliveries()}"
                        Seen.release.complete(Unit)
                        settle(container)
                        Seen.log += "pending after the handler=${container.pendingAsyncDeliveries()}"
                    }
                    return report()
                }

                @JvmStatic
                fun failure(): List<String> {
                    boot().use { container ->
                        Seen.log.clear()
                        container.eventBus.publish(Boom("x"))
                        settle(container)
                    }
                    return report()
                }

                @JvmStatic
                fun trigger(): List<String> {
                    boot().use { container ->
                        Seen.log.clear()
                        container.eventBus.publish(Step("ok", false))
                        container.eventBus.publish(Step("bad", true))
                        settle(container)
                    }
                    return report()
                }

                @JvmStatic
                fun closeWaits(): List<String> {
                    val container = boot()
                    Seen.log.clear()
                    container.eventBus.publish(Slow(150))
                    container.close()
                    return report()
                }

                @JvmStatic
                fun closeCancels(): List<String> {
                    val container = boot(Duration.ofMillis(300))
                    Seen.log.clear()
                    container.eventBus.publish(Hold())
                    Seen.holding.await()
                    val before = System.nanoTime()
                    container.close()
                    Seen.log += "close took under 3 s=${System.nanoTime() - before < TimeUnit.SECONDS.toNanos(3)}"
                    return report()
                }

                /** #679: one place, and a second event that finds it taken. */
                private fun secondEventAtTheLimit(onOverflow: OverflowPolicy): List<String> {
                    boot(onOverflow = onOverflow, queueCapacity = 1).use { container ->
                        Seen.log.clear()
                        container.eventBus.publish(Both(1))
                        Seen.log += try {
                            container.eventBus.publish(Both(2))
                            "publish returned"
                        } catch (e: EventQueueOverflowException) {
                            "publish threw ${e.javaClass.simpleName}"
                        }
                        Seen.release.complete(Unit)
                        settle(container)
                    }
                    return report()
                }

                /** #680: the first attempt times out, the second fails, and the third is past [failures] or not. */
                private fun flaky(failures: Int): List<String> {
                    boot().use { container ->
                        Seen.log.clear()
                        container.eventBus.publish(Flaky(failures))
                        settle(container)
                    }
                    return report()
                }

                @JvmStatic
                fun timeoutThenRetry(): List<String> = flaky(failures = 2)

                @JvmStatic
                fun exhausted(): List<String> = flaky(failures = 3)

                @JvmStatic
                fun timeoutAlone(): List<String> {
                    boot().use { container ->
                        Seen.log.clear()
                        container.eventBus.publish(Stuck("x"))
                        settle(container)
                    }
                    return report()
                }

                @JvmStatic
                fun throwAtTheLimit(): List<String> = secondEventAtTheLimit(OverflowPolicy.THROW)

                @JvmStatic
                fun deadLetterAtTheLimit(): List<String> = secondEventAtTheLimit(OverflowPolicy.ROUTE_TO_DLQ)

                @JvmStatic
                fun publishAfterClose(): List<String> {
                    val container = boot()
                    val bus = container.eventBus
                    container.close()
                    Seen.log.clear()
                    bus.publish(Ping("late"))
                    Thread.sleep(100)
                    Seen.log += "pending=${container.pendingAsyncDeliveries()}"
                    return report()
                }
            }
            """;

    private static KspResult program;

    @BeforeAll
    static void compile() {
        program = KspCompiler.compile(new TikoSymbolProcessorProvider(), KspCompiler.kotlin("Program.kt", PROGRAM));
        assertThat(program.exitCode()).as(program.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
    }

    @SuppressWarnings("unchecked")
    private static List<String> run(String scenario) {
        return (List<String>) KspProgram.run(program, "app.Scenarios", scenario);
    }

    /** Generated code stays Java: the handler call is a lambda that passes its continuation on. */
    @Test
    void theRegistryCallsTheHandlerThroughSuspendHandlers() {
        var registry = program.generatedJavaNames().stream()
                .filter(name -> name.startsWith("io.tiko.generated.EventRegistry_"))
                .findFirst()
                .flatMap(program::generatedJava);

        assertThat(registry)
                .hasValueSatisfying(source -> assertThat(source)
                        .contains("SuspendHandlers.launch(container, __asyncWrapper, "
                                + "__continuation -> container.getHandlers().onPing(event, __asyncWrapper, __continuation), "
                                + "null, __err, HANDLER_INFO_")
                        .contains(
                                "SuspendHandlers.<Stepped>launch(container, __asyncWrapper, "
                                        + "__continuation -> container.getHandlers().onStep(event, __continuation), __result -> {")
                        .as("a handler with neither a timeout nor retries has no policy")
                        .containsPattern(
                                "onPing\\(event, __asyncWrapper, __continuation\\), null, __err, HANDLER_INFO_\\d+, null\\);")
                        .as("#680: the handler's timeout, retries and backoff travel as its RetryPolicy")
                        .containsPattern("onFlaky\\(event, __continuation\\), null, __err, HANDLER_INFO_\\d+, "
                                + "new RetryPolicy\\(2, 50000000L, BackoffStrategy.EXPONENTIAL, 200000000L\\)\\);")
                        .containsPattern("onStuck\\(event, __continuation\\), null, __err, HANDLER_INFO_\\d+, "
                                + "new RetryPolicy\\(0, 0L, BackoffStrategy.FIXED, 100000000L\\)\\);")
                        .as("a handler of a framework lifecycle event runs without a unit of work")
                        .contains("SuspendHandlers.launchWithoutUnit(container, __asyncWrapper, "
                                + "__continuation -> __handler.onStarted(event, __continuation), null"));
    }

    @Test
    void aSuspendHandlerRunsInItsOwnUnitOnAContainerThread() {
        assertThat(run("ownUnit"))
                .containsSubsequence(
                        "publish returned",
                        "ping a: own unit=true same after delay=true virtual=true named=true wrapper is the event=true")
                .as("the handler's unit closed, and the publisher's did")
                .filteredOn("unit closed"::equals)
                .hasSize(2);
    }

    @Test
    void eventsAHandlerPublishesDescendFromTheEventItHandles() {
        assertThat(run("ownUnit")).contains("echo a descends from Ping(text=a)");
    }

    @Test
    void publishReturnsBeforeTheHandlerHasFinishedAndTheHandlerIsPendingMeanwhile() {
        assertThat(run("publishReturnsFirst"))
                .containsExactly(
                        "hold started",
                        "publish returned, pending=1",
                        "hold finished",
                        "hold ended, cancelled=false",
                        "pending after the handler=0",
                        "singleton destroyed");
    }

    @Test
    void aFailureAfterASuspensionReachesTheErrorHandlerOnce() {
        assertThat(run("failure"))
                .containsExactly(
                        "singleton destroyed",
                        "ERROR EventHandlerError Handlers.onBoom async=true"
                                + " cause=IllegalStateException: boom after a suspension attempts=1 kind=EXCEPTION");
    }

    @Test
    void aTriggerFiresAfterTheHandlerCompletesAndNotWhenItFails() {
        assertThat(run("trigger"))
                .containsExactlyInAnyOrder(
                        "stepped ok",
                        "singleton destroyed",
                        "ERROR EventHandlerError Handlers.onStep async=true"
                                + " cause=IllegalStateException: step bad failed attempts=1 kind=EXCEPTION");
    }

    @Test
    void aHandlerOfALifecycleEventRunsWithoutAUnit() {
        assertThat(run("startedHandler")).contains("started handler: no unit of work");
    }

    @Test
    void closeWaitsForAHandlerThatFinishesInTime() {
        assertThat(run("closeWaits")).containsExactly("slow finished", "singleton destroyed");
    }

    /** #678: the handler is cancelled after the wait, and what it publishes on its way out to a suspend handler is dropped. */
    @Test
    void closeCancelsAHandlerThatIsStillSuspendedBeforeAnySingletonIsDestroyed() {
        assertThat(run("closeCancels"))
                .containsExactly(
                        "hold started",
                        "hold ended, cancelled=true",
                        "singleton destroyed",
                        "close took under 3 s=true");
    }

    /** #680: a timed-out attempt and a failed one are retried, each in a unit of its own, until one succeeds. */
    @Test
    void aTimeoutAndAFailureAreRetriedUntilAnAttemptSucceeds() {
        assertThat(run("timeoutThenRetry"))
                .containsSubsequence(
                        "flaky attempt 1 in a new unit=true",
                        "flaky attempt 2 in a new unit=true",
                        "flaky attempt 3 in a new unit=true")
                .as("three units closed, nothing reported")
                .containsOnlyOnce("singleton destroyed")
                .noneMatch(line -> line.startsWith("ERROR"))
                .filteredOn("unit closed"::equals)
                .hasSize(3);
    }

    /** #680: a suspend handler needs no async = true for a timeout, and its timeout is checked like any other. */
    @Test
    void aSuspendHandlersTimeoutMustBeADuration() {
        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), KspCompiler.kotlin("Bad.kt", """
                        package bad

                        import io.tiko.Scope
                        import io.tiko.annotations.Component
                        import io.tiko.annotations.EventHandler

                        class Ping

                        @Component(scope = Scope.SINGLETON)
                        class Listener {
                            @EventHandler(timeout = "5s", retries = 2)
                            suspend fun onPing(ping: Ping) {}
                        }
                        """));

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR);
        assertThat(result.messages())
                .contains("@EventHandler timeout '5s' is not a valid ISO-8601 Duration")
                .doesNotContain("requires async = true");
    }

    @Test
    void anExhaustedRetryBudgetIsReportedOnceWithItsAttempts() {
        assertThat(run("exhausted"))
                .filteredOn(line -> line.startsWith("ERROR"))
                .containsExactly("ERROR EventHandlerError Handlers.onFlaky async=true"
                        + " cause=IllegalStateException: attempt 3 failed attempts=3 kind=EXHAUSTED");
    }

    @Test
    void aTimeoutAloneIsReportedAsATimeout() {
        assertThat(run("timeoutAlone"))
                .filteredOn(line -> line.startsWith("ERROR"))
                .singleElement()
                .asString()
                .startsWith("ERROR EventHandlerError Handlers.onStuck async=true cause=TimeoutException:")
                .endsWith("attempts=1 kind=TIMEOUT");
    }

    /** #679, #714: the publisher is told, after the event's other handlers got it. */
    @Test
    void underThrowThePublisherGetsTheOverflowAfterTheOtherHandlersGotTheEvent() {
        assertThat(run("throwAtTheLimit"))
                .containsExactlyInAnyOrder(
                        "suspend handler got 1",
                        "sync handler got 1",
                        "publish threw EventQueueOverflowException",
                        "sync handler got 2",
                        "singleton destroyed");
    }

    /** #679, #111: the event that found no place goes to the ErrorHandler; its other handlers still get it. */
    @Test
    void underRouteToDlqAnEventAtTheLimitIsDeadLettered() {
        assertThat(run("deadLetterAtTheLimit"))
                .containsExactlyInAnyOrder(
                        "suspend handler got 1",
                        "sync handler got 1",
                        "publish returned",
                        "sync handler got 2",
                        "singleton destroyed",
                        "ERROR EventDispatchRejected Both(n=2)");
    }

    @Test
    void aPublishAfterTheContainerClosedStartsNothing() {
        assertThat(run("publishAfterClose")).containsExactly("pending=0");
    }
}
