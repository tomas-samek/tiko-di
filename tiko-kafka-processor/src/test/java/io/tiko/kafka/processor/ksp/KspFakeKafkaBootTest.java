package io.tiko.kafka.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.core.ksp.KspProgram;
import io.tiko.processor.ksp.TikoSymbolProcessorProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A KSP-compiled Kotlin bridge boots over the fake broker through {@code replaceTransport}. */
class KspFakeKafkaBootTest {

    @Test
    void kotlinBridgeConsumesFromTheFakeBroker() throws Exception {
        var result = KspCompiler.compile(
                List.of(new TikoSymbolProcessorProvider(), new KafkaSymbolProcessorProvider()),
                Map.of(),
                KspCompiler.kotlin(
                        "Orders.kt",
                        "package demo",
                        "",
                        "import io.tiko.Scope",
                        "import io.tiko.annotations.Component",
                        "import io.tiko.annotations.EventHandler",
                        "import io.tiko.annotations.EventTrigger",
                        "import io.tiko.kafka.annotations.KafkaSource",
                        "",
                        "@JvmRecord data class OrderPlaced(val orderId: String)",
                        "",
                        "@Component(scope = Scope.SINGLETON)",
                        "class OrderConsumer {",
                        "    @KafkaSource(topic = \"orders\")",
                        "    @EventTrigger(eventName = \"OrderPlaced\")",
                        "    fun fromKafka(placed: OrderPlaced): OrderPlaced = placed",
                        "}",
                        "",
                        "@Component(scope = Scope.SINGLETON)",
                        "class Recorder {",
                        "    companion object { @JvmField val seen: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList() }",
                        "    @EventHandler",
                        "    fun on(placed: OrderPlaced) { seen.add(placed.orderId) }",
                        "}"));
        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);

        var program = KspProgram.bootWithFakeKafka(result);
        try {
            program.send("orders", "A1", "{\"orderId\":\"A1\"}");

            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(program.staticList("demo.Recorder", "seen"))
                            .contains("A1"));
        } finally {
            program.close();
        }

        assertThat(program.leakReports()).isEmpty();
    }
}
