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
            import io.tiko.EventHandlerError
            import io.tiko.NoActiveEventScopeException
            import io.tiko.Scope
            import io.tiko.annotations.Component
            import io.tiko.annotations.EventHandler
            import io.tiko.annotations.EventTrigger
            import io.tiko.annotations.PreDestroy
            import io.tiko.events.ApplicationStartedEvent
            import io.tiko.runtime.Tiko
            import io.tiko.runtime.TikoOptions
            import java.time.Duration
            import java.util.UUID
            import java.util.concurrent.CopyOnWriteArrayList
            import java.util.concurrent.CountDownLatch
            import java.util.concurrent.TimeUnit
            import kotlinx.coroutines.CompletableDeferred
            import kotlinx.coroutines.currentCoroutineContext
            import kotlinx.coroutines.delay
            import kotlinx.coroutines.isActive

            object Seen {
                @JvmField val log = CopyOnWriteArrayList<String>()
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

                private fun boot(shutdownTimeout: Duration = Duration.ofSeconds(5)): Container =
                    Tiko.create(
                        TikoOptions.builder()
                            .shutdownTimeout(shutdownTimeout)
                            .errorHandler { context -> Seen.errors += describe(context) }
                            .build(),
                    ).also { settle(it) }

                private fun describe(context: ErrorContext): String = when (context) {
                    is EventHandlerError ->
                        "EventHandlerError ${context.handler().declaringClass().simpleName}.${context.handler().methodName()}" +
                            " async=${context.handler().async()} cause=${context.cause().javaClass.simpleName}:" +
                            " ${context.cause().message} attempts=${context.attempts()}"
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
                        .contains("SuspendHandlers.launch(container, __asyncWrapper, true, "
                                + "__continuation -> container.getHandlers().onPing(event, __asyncWrapper, __continuation), "
                                + "null, __err, HANDLER_INFO_")
                        .contains(
                                "SuspendHandlers.<Stepped>launch(container, __asyncWrapper, true, "
                                        + "__continuation -> container.getHandlers().onStep(event, __continuation), __result -> {")
                        .as("a handler of a framework lifecycle event runs without a unit of work")
                        .contains("SuspendHandlers.launch(container, __asyncWrapper, false, "
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
                                + " cause=IllegalStateException: boom after a suspension attempts=1");
    }

    @Test
    void aTriggerFiresAfterTheHandlerCompletesAndNotWhenItFails() {
        assertThat(run("trigger"))
                .containsExactlyInAnyOrder(
                        "stepped ok",
                        "singleton destroyed",
                        "ERROR EventHandlerError Handlers.onStep async=true"
                                + " cause=IllegalStateException: step bad failed attempts=1");
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

    @Test
    void aPublishAfterTheContainerClosedStartsNothing() {
        assertThat(run("publishAfterClose")).containsExactly("pending=0");
    }
}
