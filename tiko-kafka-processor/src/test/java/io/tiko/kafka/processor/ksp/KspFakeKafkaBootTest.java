package io.tiko.kafka.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tschuchort.compiletesting.KotlinCompilation;
import com.tschuchort.compiletesting.SourceFile;
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

    @Test
    void plainDataClassPayloadIsRejectedWithTheDefaultSerializer() {
        var result = KspCompiler.compile(
                List.of(new TikoSymbolProcessorProvider(), new KafkaSymbolProcessorProvider()),
                Map.of(),
                orders("@KafkaSource(topic = \"orders\")", "@KafkaSink(topic = \"out\")"));

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR);
        assertThat(result.messages())
                .contains("Orders.kt:")
                .contains("@KafkaSource payload demo.OrderPlaced is a Kotlin data class without @JvmRecord")
                .contains("@KafkaSink payload demo.OrderPlaced is a Kotlin data class without @JvmRecord")
                .contains("Suggested fixes:")
                .contains("Annotate demo.OrderPlaced with @JvmRecord")
                .contains("serializer = YourSerializer::class");
    }

    /** An explicit serializer is the user's own, so the rule is off. */
    @Test
    void plainDataClassPayloadPassesWithAnExplicitSerializer() {
        var result = KspCompiler.compile(
                List.of(new TikoSymbolProcessorProvider(), new KafkaSymbolProcessorProvider()),
                Map.of(),
                orders(
                        "@KafkaSource(topic = \"orders\", serializer = MySerializer::class)",
                        "@KafkaSink(topic = \"out\", serializer = MySerializer::class)"));

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
    }

    private static SourceFile orders(String source, String sink) {
        var payload = "OrderPlaced";
        return KspCompiler.kotlin(
                "Orders.kt",
                "package demo",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "import io.tiko.annotations.EventTrigger",
                "import io.tiko.kafka.KafkaSerializer",
                "import io.tiko.kafka.annotations.KafkaSink",
                "import io.tiko.kafka.annotations.KafkaSource",
                "",
                "data class " + payload + "(val orderId: String)",
                "",
                "class MySerializer : KafkaSerializer {",
                "    override fun serialize(value: Any): ByteArray = ByteArray(0)",
                "    override fun <T> deserialize(bytes: ByteArray, type: Class<T>): T = throw UnsupportedOperationException()",
                "}",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Bridges {",
                "    " + source,
                "    @EventTrigger(eventName = \"OrderPlaced\")",
                "    fun fromKafka(placed: " + payload + "): " + payload + " = placed",
                "",
                "    " + sink,
                "    fun toKafka(placed: " + payload + "): " + payload + " = placed",
                "}");
    }
}
