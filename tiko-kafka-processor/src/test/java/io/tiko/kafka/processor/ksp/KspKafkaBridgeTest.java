package io.tiko.kafka.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.ksp.TikoSymbolProcessorProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Kafka processor runs on KSP2 over a Kotlin bridge, next to the DI processor. */
class KspKafkaBridgeTest {

    @Test
    void kotlinKafkaSourceGeneratesBootstrap() {
        var source = KspCompiler.kotlin(
                "Orders.kt",
                "package demo",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "import io.tiko.annotations.EventTrigger",
                "import io.tiko.kafka.annotations.KafkaSource",
                "",
                "data class OrderPlaced(val orderId: String)",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class OrderConsumer {",
                "    @KafkaSource(topic = \"orders\")",
                "    @EventTrigger(eventName = \"OrderPlaced\")",
                "    fun fromKafka(placed: OrderPlaced): OrderPlaced = placed",
                "}");

        var result = KspCompiler.compile(
                List.of(new TikoSymbolProcessorProvider(), new KafkaSymbolProcessorProvider()), Map.of(), source);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        assertThat(result.generatedJava("io.tiko.generated.KafkaTransportBootstrap"))
                .hasValueSatisfying(bootstrap ->
                        assertThat(bootstrap).contains("\"orders\"").contains("fromKafka"));
        assertThat(result.generatedResource("META-INF/services/io.tiko.TransportBootstrap"))
                .hasValue("io.tiko.generated.KafkaTransportBootstrap\n");
    }
}
