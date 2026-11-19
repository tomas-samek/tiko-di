package io.tiko.coroutines;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.kafka.processor.ksp.KafkaSymbolProcessorProvider;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.core.ksp.KspCompiler.KspResult;
import io.tiko.processor.core.ksp.KspProgram;
import io.tiko.processor.ksp.TikoSymbolProcessorProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * #681: a {@code suspend} {@code @KafkaSource} bridge end to end. A Kotlin program is compiled with
 * both KSP processors, the generated Java with it, and booted over {@code FakeKafkaBroker};
 * {@code app.Scenarios} produces records and answers what the bridge and its handlers saw.
 */
@Timeout(120)
class KspSuspendKafkaSourceTest {

    private static final String PROGRAM = """
            package app

            import io.tiko.Container
            import io.tiko.Scope
            import io.tiko.annotations.Component
            import io.tiko.annotations.EventHandler
            import io.tiko.annotations.EventTrigger
            import io.tiko.annotations.PreDestroy
            import io.tiko.config.ConfigSources
            import io.tiko.kafka.KafkaContext
            import io.tiko.kafka.KafkaTransport
            import io.tiko.kafka.annotations.KafkaSource
            import io.tiko.kafka.serializer.JsonKafkaSerializer
            import io.tiko.kafka.test.FakeKafkaBroker
            import io.tiko.kafka.test.FakeKafkaTransport
            import io.tiko.runtime.Tiko
            import io.tiko.runtime.TikoOptions
            import java.util.UUID
            import java.util.concurrent.CopyOnWriteArrayList
            import java.util.concurrent.CountDownLatch
            import java.util.concurrent.TimeUnit
            import java.util.concurrent.atomic.AtomicInteger
            import kotlinx.coroutines.CompletableDeferred
            import kotlinx.coroutines.Dispatchers
            import kotlinx.coroutines.currentCoroutineContext
            import kotlinx.coroutines.delay
            import kotlinx.coroutines.isActive
            import kotlinx.coroutines.withContext

            object Seen {
                @JvmField val log = CopyOnWriteArrayList<String>()
                @JvmField val never = CompletableDeferred<Unit>()
                @JvmField val suspended = CountDownLatch(1)
            }

            interface RequestId { fun value(): String }

            @Component(scope = Scope.EVENT)
            class FreshRequestId : RequestId {
                private val id = UUID.randomUUID().toString()
                override fun value(): String = id
                @PreDestroy fun closed() { Seen.log += "unit closed" }
            }

            @JvmRecord data class Payment(val id: String, val mode: String)
            data class Received(val id: String)

            @Component(scope = Scope.SINGLETON)
            class Payments(private val requestId: RequestId) {
                private val deliveries = AtomicInteger()

                @KafkaSource(topic = "payments")
                @EventTrigger(eventName = "Received")
                suspend fun onPayment(payment: Payment, ctx: KafkaContext): Received {
                    val before = requestId.value()
                    Seen.log += "bridge ${payment.id} at offset ${ctx.offset()} on ${Thread.currentThread().name}"
                    when (payment.mode) {
                        "fail-once" -> check(deliveries.incrementAndGet() > 1) { "the first delivery fails" }
                        "hold" -> {
                            Seen.suspended.countDown()
                            try {
                                Seen.never.await()
                            } finally {
                                Seen.log += "bridge ${payment.id} ended, cancelled=${!currentCoroutineContext().isActive}"
                            }
                        }
                        else -> delay(20)
                    }
                    val elsewhere = withContext(Dispatchers.IO) { requestId.value() }
                    Seen.log += "bridge ${payment.id}: same unit after suspending=${requestId.value() == before}," +
                        " on another dispatcher=${elsewhere == before}"
                    return Received(payment.id)
                }
            }

            @Component(scope = Scope.SINGLETON)
            class Handlers(private val requestId: RequestId) {
                @EventHandler
                fun onReceived(received: Received) {
                    Seen.log += "handled ${received.id} in a unit=${requestId.value().isNotEmpty()}" +
                        " on ${Thread.currentThread().name}"
                }
            }

            object Scenarios {
                private val json = JsonKafkaSerializer()
                private val broker = FakeKafkaBroker()

                private fun boot(): Container =
                    Tiko.create(
                        TikoOptions.builder()
                            .configSource(
                                ConfigSources.fromMap(
                                    mapOf(
                                        "tiko" to mapOf(
                                            "kafka" to mapOf(
                                                "poll-timeout" to "PT0.05S",
                                                "shutdown-timeout" to "PT0.3S",
                                                "seek-backoff" to "PT0.05S",
                                            ),
                                        ),
                                    ),
                                ),
                            )
                            .replaceTransport(KafkaTransport::class.java) { FakeKafkaTransport.over(it, broker) }
                            .errorHandler { context -> Seen.log += "ERROR ${context.javaClass.simpleName}" }
                            .build(),
                    )

                private fun produce(payment: Payment) = broker.produce("payments", json.serialize(payment))

                private fun awaitLog(line: String) {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                    while (Seen.log.none { it.startsWith(line) }) {
                        check(System.nanoTime() < deadline) { "never logged '$line': ${Seen.log}" }
                        Thread.sleep(5)
                    }
                }

                private fun consumerThreads() =
                    Thread.getAllStackTraces().keys.count { it.isAlive && it.name.startsWith("tiko-kafka-consumer-") }

                @JvmStatic
                fun oneUnitForTheRecord(): List<String> {
                    boot().use {
                        produce(Payment("p1", "ok"))
                        awaitLog("handled p1")
                    }
                    return Seen.log.toList()
                }

                @JvmStatic
                fun aFailedRecordIsDeliveredAgain(): List<String> {
                    boot().use {
                        produce(Payment("p2", "fail-once"))
                        awaitLog("handled p2")
                    }
                    return Seen.log.toList()
                }

                @JvmStatic
                fun closeWhileSuspended(): List<String> {
                    val container = boot()
                    produce(Payment("p3", "hold"))
                    check(Seen.suspended.await(20, TimeUnit.SECONDS)) { "the bridge never suspended" }
                    val before = System.nanoTime()
                    container.close()
                    Seen.log += "close took under 5 s=${System.nanoTime() - before < TimeUnit.SECONDS.toNanos(5)}"
                    Seen.log += "consumer threads left=${consumerThreads()}"
                    return Seen.log.toList()
                }
            }
            """;

    private static KspResult program;

    @BeforeAll
    static void compile() {
        program = KspCompiler.compile(
                List.of(new TikoSymbolProcessorProvider(), new KafkaSymbolProcessorProvider()),
                Map.of(),
                KspCompiler.kotlin("Program.kt", PROGRAM));
        assertThat(program.exitCode()).as(program.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
    }

    @SuppressWarnings("unchecked")
    private static List<String> run(String scenario) {
        return (List<String>) KspProgram.run(program, "app.Scenarios", scenario);
    }

    /** Generated code stays Java: the bridge call is a lambda that passes its continuation on. */
    @Test
    void theBootstrapRunsTheBridgeThroughSuspendBridges() {
        assertThat(program.generatedJava("io.tiko.generated.KafkaTransportBootstrap"))
                .hasValueSatisfying(source -> assertThat(source)
                        .contains("SuspendBridges.run(container, __continuation -> "
                                + "container.get(Payments.class).onPayment((Payment) payload, ctx, __continuation));")
                        .as("the runner is told that this source opens its unit itself")
                        .contains("true, this::source0, true))"));
    }

    @Test
    void theBridgeRunsInOneUnitOnTheConsumerThreadAndItsEventIsHandledThere() {
        assertThat(run("oneUnitForTheRecord"))
                .containsExactly(
                        "bridge p1 at offset 0 on tiko-kafka-consumer-payments",
                        "bridge p1: same unit after suspending=true, on another dispatcher=true",
                        "handled p1 in a unit=true on tiko-kafka-consumer-payments",
                        "unit closed");
    }

    /** The existing ingest-error path: the failure is routed, and SEEK delivers the record again. */
    @Test
    void aFailingBridgeTakesTheIngestErrorPath() {
        assertThat(run("aFailedRecordIsDeliveredAgain"))
                .containsExactly(
                        "bridge p2 at offset 0 on tiko-kafka-consumer-payments",
                        "unit closed",
                        "ERROR KafkaIngestError",
                        "bridge p2 at offset 0 on tiko-kafka-consumer-payments",
                        "bridge p2: same unit after suspending=true, on another dispatcher=true",
                        "handled p2 in a unit=true on tiko-kafka-consumer-payments",
                        "unit closed");
    }

    /** D13: the bridge gets the shutdown timeout, is then cancelled, and the consumer thread ends. */
    @Test
    void closeCancelsASuspendedBridgeAndLeavesNoConsumerThread() {
        assertThat(run("closeWhileSuspended"))
                .containsExactly(
                        "bridge p3 at offset 0 on tiko-kafka-consumer-payments",
                        "bridge p3 ended, cancelled=true",
                        "unit closed",
                        "close took under 5 s=true",
                        "consumer threads left=0");
    }
}
