package io.tiko.kafka.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.core.ksp.RecordingCodeGenerator;
import io.tiko.processor.core.ksp.RoundWatcher;
import io.tiko.processor.ksp.TikoSymbolProcessorProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    /** KSP logs a notice for every processor that has not opted in to its upcoming features. */
    @Test
    void processorsHaveOptedInToUpcomingFeatures() {
        var result = KspCompiler.compile(
                List.of(new TikoSymbolProcessorProvider(), new KafkaSymbolProcessorProvider()), Map.of(), bridge());

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        assertThat(result.messages()).doesNotContain("has not opted in for upcoming features");
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
                "class Ambiguous {",
                "    constructor(name: String)",
                "    constructor()",
                "}");

        var result = KspCompiler.compile(
                List.of(new TikoSymbolProcessorProvider(), new KafkaSymbolProcessorProvider()),
                Map.of(),
                bridge(),
                broken);

        assertThat(result.exitCode()).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR);
        assertThat(result.messages())
                .as("the DI processor's collection error")
                .contains("must have an @Inject-annotated constructor or a single public constructor");
        assertThat(result.generatedJava("io.tiko.generated.KafkaTransportBootstrap"))
                .isPresent();
    }

    /** Every Kafka output (bootstrap, service entry, topology) names each bridge's file, and only those. */
    @Test
    void kafkaOutputsNameTheBridgeFilesTheyDeriveFrom() {
        var sink = KspCompiler.kotlin(
                "Publisher.kt",
                "package demo",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "import io.tiko.kafka.annotations.KafkaSink",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class OrderPublisher {",
                "    @KafkaSink(topic = \"orders-out\")",
                "    fun toKafka(placed: OrderPlaced): OrderPlaced = placed",
                "}");
        var unrelated = KspCompiler.kotlin(
                "Unrelated.kt",
                "package demo",
                "",
                "@io.tiko.annotations.Component(scope = io.tiko.Scope.SINGLETON)",
                "class Unrelated");
        var requests = new ArrayList<RecordingCodeGenerator.Request>();

        var result = KspCompiler.compile(
                List.of(
                        new TikoSymbolProcessorProvider(),
                        RecordingCodeGenerator.recording(new KafkaSymbolProcessorProvider(), requests)),
                Map.of(),
                bridge(),
                sink,
                unrelated);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        assertThat(requests)
                .extracting(RecordingCodeGenerator.Request::path)
                .anyMatch(path -> path.endsWith("KafkaTransportBootstrap.java"))
                .contains("META-INF/services/io.tiko.TransportBootstrap");
        assertThat(requests).hasSizeGreaterThanOrEqualTo(3).allSatisfy(request -> {
            assertThat(request.aggregating()).as(request.path()).isTrue();
            assertThat(request.originatingFiles()).as(request.path()).containsExactly("Orders.kt", "Publisher.kt");
        });
    }

    /**
     * The Kotlin construct rule for bridges: a construct the generated bootstrap can't call is a
     * located Tiko error before collection (no processor crash, no javac error on generated code),
     * and no bootstrap is written.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedBridges")
    void unsupportedKotlinBridgeIsRejected(String name, String body, List<String> fragments) {
        var source = KspCompiler.kotlin(
                "Bridges.kt",
                "package demo",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "import io.tiko.annotations.EventTrigger",
                "import io.tiko.kafka.annotations.KafkaSink",
                "import io.tiko.kafka.annotations.KafkaSource",
                "",
                "@JvmRecord data class OrderPlaced(val orderId: String)",
                "",
                body);

        var result = KspCompiler.compile(
                List.of(new TikoSymbolProcessorProvider(), new KafkaSymbolProcessorProvider()), Map.of(), source);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR);
        assertThat(result.messages())
                .as("located Tiko error")
                .contains("Bridges.kt:")
                .contains(fragments)
                .contains("Suggested fixes:")
                .doesNotContain("INTERNAL_ERROR")
                .doesNotContain("KafkaTransportBootstrap.java");
        assertThat(result.generatedJava("io.tiko.generated.KafkaTransportBootstrap"))
                .isEmpty();
    }

    static Stream<Arguments> unsupportedBridges() {
        return Stream.of(
                Arguments.of(
                        "topLevelSourceRejected",
                        """
                        @KafkaSource(topic = "orders")
                        fun fromKafka(placed: OrderPlaced) {}
                        """,
                        List.of("@KafkaSource on a top-level function 'fromKafka'", "move it into a @Component class")),
                Arguments.of(
                        "topLevelSinkRejected",
                        """
                        @KafkaSink(topic = "orders")
                        fun toKafka(placed: OrderPlaced): OrderPlaced = placed
                        """,
                        List.of("@KafkaSink on a top-level function 'toKafka'", "move it into a @Component class")),
                Arguments.of(
                        "suspendSourceRejectedUntilK2",
                        """
                        @Component(scope = Scope.SINGLETON)
                        class OrderConsumer {
                            @KafkaSource(topic = "orders")
                            suspend fun fromKafka(placed: OrderPlaced) {}
                        }
                        """,
                        List.of(
                                "suspend @KafkaSource is not supported yet",
                                "'OrderConsumer.fromKafka' is a suspend fun",
                                "coroutine support (K2)")),
                Arguments.of(
                        "suspendSinkRejected",
                        """
                        @Component(scope = Scope.SINGLETON)
                        class OrderPublisher {
                            @KafkaSink(topic = "orders")
                            suspend fun toKafka(placed: OrderPlaced): OrderPlaced = placed
                        }
                        """,
                        List.of("suspend @KafkaSink is not supported", "make it a regular fun")),
                Arguments.of(
                        "extensionSourceRejected",
                        """
                        class Config
                        @Component(scope = Scope.SINGLETON)
                        class OrderConsumer {
                            @KafkaSource(topic = "orders")
                            fun Config.fromKafka(placed: OrderPlaced) {}
                        }
                        """,
                        List.of(
                                "@KafkaSource on the extension function 'OrderConsumer.fromKafka'",
                                "declare a regular fun that takes the receiver as a parameter")),
                Arguments.of(
                        "objectSourceRejected",
                        """
                        object OrderConsumer {
                            @KafkaSource(topic = "orders")
                            fun fromKafka(placed: OrderPlaced) {}
                        }
                        """,
                        List.of("@KafkaSource inside an object declaration", "move it into a @Component class")),
                Arguments.of(
                        "companionSinkRejected",
                        """
                        @Component(scope = Scope.SINGLETON)
                        class OrderPublisher {
                            companion object {
                                @KafkaSink(topic = "orders")
                                fun toKafka(placed: OrderPlaced): OrderPlaced = placed
                            }
                        }
                        """,
                        List.of("@KafkaSink inside a companion object", "move it to the enclosing @Component class")),
                Arguments.of(
                        "twoEventTriggersRejected",
                        """
                        @Component(scope = Scope.SINGLETON)
                        class OrderConsumer {
                            @KafkaSource(topic = "orders")
                            @EventTrigger(eventName = "a")
                            @EventTrigger(eventName = "b")
                            fun fromKafka(placed: OrderPlaced): OrderPlaced = placed
                        }
                        """,
                        List.of(
                                "more than one @EventTrigger on a @KafkaSource is not supported",
                                "Keep a single @EventTrigger")),
                Arguments.of(
                        "valueClassPayloadRejected",
                        """
                        @JvmInline value class OrderId(val raw: String)
                        @Component(scope = Scope.SINGLETON)
                        class OrderConsumer {
                            @KafkaSource(topic = "orders")
                            fun fromKafka(id: OrderId) {}
                        }
                        """,
                        List.of(
                                "@KafkaSource 'OrderConsumer.fromKafka' takes the Kotlin value class demo.OrderId",
                                "Use a regular class")));
    }

    /**
     * Kotlin compiles a {@code @JvmRecord} component to the record accessor {@code orderId()}, so
     * that is the partition key, as on javac over the compiled record; the bootstrap calls it.
     */
    @Test
    void jvmRecordPartitionKeyIsTheRecordAccessor() {
        var result = KspCompiler.compile(
                List.of(new TikoSymbolProcessorProvider(), new KafkaSymbolProcessorProvider()),
                Map.of(),
                jvmRecordSink("orderId"));

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        assertThat(result.generatedJava("io.tiko.generated.KafkaTransportBootstrap"))
                .hasValueSatisfying(bootstrap -> assertThat(bootstrap).contains("p).orderId()"));
    }

    /** {@code getOrderId} does not exist on the compiled record: a located Tiko error, not a javac one. */
    @Test
    void jvmRecordGetterNameIsNoPartitionKey() {
        var result = KspCompiler.compile(
                List.of(new TikoSymbolProcessorProvider(), new KafkaSymbolProcessorProvider()),
                Map.of(),
                jvmRecordSink("getOrderId"));

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.COMPILATION_ERROR);
        assertThat(result.messages())
                .as("located Tiko error")
                .contains("Sinks.kt:")
                .contains(
                        "@KafkaSink partitionKey 'getOrderId' does not resolve to a zero-arg method on demo.OrderPlaced")
                .doesNotContain("KafkaTransportBootstrap.java");
    }

    private static com.tschuchort.compiletesting.SourceFile jvmRecordSink(String partitionKey) {
        return KspCompiler.kotlin(
                "Sinks.kt",
                "package demo",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "import io.tiko.kafka.annotations.KafkaSink",
                "",
                "@JvmRecord data class OrderPlaced(val orderId: String)",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class OrderPublisher {",
                "    @KafkaSink(topic = \"orders\", partitionKey = \"" + partitionKey + "\")",
                "    fun toKafka(placed: OrderPlaced): OrderPlaced = placed",
                "}");
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
                "@JvmRecord data class OrderPlaced(val orderId: String)",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class OrderConsumer {",
                "    @KafkaSource(topic = \"orders\")",
                "    @EventTrigger(eventName = \"OrderPlaced\")",
                "    fun fromKafka(placed: OrderPlaced): OrderPlaced = placed",
                "}");
    }
}
