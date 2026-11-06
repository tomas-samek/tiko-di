package io.tiko.kafka.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.core.ksp.RoundWatcher;
import io.tiko.processor.ksp.TikoSymbolProcessorProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Kafka processor runs on KSP2 over a Kotlin bridge, next to the DI processor. */
class KspKafkaBridgeTest {

    @Test
    void kotlinKafkaSourceGeneratesBootstrap() {
        var source = bridge();

        var result = KspCompiler.compile(
                List.of(new TikoSymbolProcessorProvider(), new KafkaSymbolProcessorProvider()), Map.of(), source);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        assertThat(result.generatedJava("io.tiko.generated.KafkaTransportBootstrap"))
                .hasValueSatisfying(bootstrap ->
                        assertThat(bootstrap).contains("\"orders\"").contains("fromKafka"));
        assertThat(result.generatedResource("META-INF/services/io.tiko.TransportBootstrap"))
                .hasValue("io.tiko.generated.KafkaTransportBootstrap\n");
    }

    /** The trigger: the bootstrap is written in the first round without bridges after one with them. */
    @Test
    void bootstrapIsWrittenInTheFirstRoundWithoutBridges() {
        var watcher = new RoundWatcher();

        var result = KspCompiler.compile(
                List.of(watcher, new TikoSymbolProcessorProvider(), new KafkaSymbolProcessorProvider()),
                Map.of(),
                bridge());

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        assertThat(watcher.newFilesPerRound()).as("new files per round").hasSizeGreaterThanOrEqualTo(3);
        assertThat(watcher.newFilesPerRound().get(2)).contains("KafkaTransportBootstrap.java");
    }

    /**
     * After a round that logged an error (here the DI processor's collection error), KSP calls
     * {@code onError()} instead of {@code finish()}; the Kafka entry still writes its output, as
     * on javac where the two processors run independently.
     */
    @Test
    void bootstrapIsStillWrittenAfterAnotherProcessorsError() {
        var broken = KspCompiler.kotlin(
                "Broken.kt",
                "package demo",
                "",
                "@io.tiko.annotations.Component",
                "class Ambiguous(val name: String) {",
                "    constructor() : this(\"\")",
                "}");

        var result = KspCompiler.compile(
                List.of(new TikoSymbolProcessorProvider(), new KafkaSymbolProcessorProvider()),
                Map.of(),
                bridge(),
                broken);

        assertThat(result.exitCode()).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR);
        assertThat(result.generatedJava("io.tiko.generated.KafkaTransportBootstrap"))
                .isPresent();
    }

    private static com.tschuchort.compiletesting.SourceFile bridge() {
        return KspCompiler.kotlin(
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
    }
}
