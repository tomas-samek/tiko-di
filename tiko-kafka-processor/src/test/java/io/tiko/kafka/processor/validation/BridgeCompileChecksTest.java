package io.tiko.kafka.processor.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.kafka.processor.KafkaAnnotationProcessor;
import io.tiko.processor.TikoAnnotationProcessor;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Bridges the generated bootstrap cannot compile against (#753), and bridges that compile but
 * cannot work against a broker (#750, #756), are located errors with a suggested fix. None of
 * them reaches the generated {@code KafkaTransportBootstrap}.
 */
class BridgeCompileChecksTest {

    private static final String IMPORTS = """
            package demo;
            import io.tiko.Scope;
            import io.tiko.annotations.Component;
            import io.tiko.annotations.EventTrigger;
            import io.tiko.kafka.annotations.KafkaSink;
            import io.tiko.kafka.annotations.KafkaSource;
            import java.util.List;
            """;

    private static final Pattern BRIDGE_CLASS = Pattern.compile("\\)\\s+(?:public )?class (\\w+)");

    private static Compilation compile(String... classes) {
        var sources = new java.util.ArrayList<JavaFileObject>();
        sources.add(JavaFileObjects.forSourceString(
                "demo.OrderPlaced", "package demo; public record OrderPlaced(String orderId) {}"));
        sources.add(JavaFileObjects.forSourceString(
                "demo.OrderShipped", "package demo; public record OrderShipped(String orderId) {}"));
        for (String source : classes) {
            sources.add(JavaFileObjects.forSourceString("demo." + bridgeClassOf(source), IMPORTS + source));
        }
        return Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(sources);
    }

    /** The component class of a fixture: javac wants a public class in a file of its name. */
    private static String bridgeClassOf(String source) {
        var matcher = BRIDGE_CLASS.matcher(source);
        assertThat(matcher.find()).as("fixture declares a bridge class").isTrue();
        return matcher.group(1);
    }

    private static List<String> errorsOf(Compilation compilation) {
        return compilation.errors().stream()
                .map(error -> error.getMessage(Locale.ROOT))
                .toList();
    }

    private static void assertLocatedError(Compilation compilation, List<String> fragments) {
        assertThat(compilation.status()).isEqualTo(Compilation.Status.FAILURE);
        assertThat(errorsOf(compilation))
                .anySatisfy(message -> assertThat(message).contains(fragments).contains("Suggested fixes:"));
        assertThat(compilation.errors())
                .as("no error in generated code")
                .extracting(BridgeCompileChecksTest::sourceName)
                .noneMatch(name -> name.contains("KafkaTransportBootstrap"));
    }

    private static String sourceName(Diagnostic<? extends JavaFileObject> diagnostic) {
        return diagnostic.getSource() == null ? "" : diagnostic.getSource().getName();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedShapes")
    void unsupportedBridgeShapeIsALocatedError(String name, String bridge, List<String> fragments) {
        assertLocatedError(compile(bridge), fragments);
    }

    static Stream<Arguments> unsupportedShapes() {
        return Stream.of(
                Arguments.of(
                        "parameterizedSourcePayload",
                        """
                        @Component(scope = Scope.SINGLETON)
                        public class OrderConsumer {
                            @KafkaSource(topic = "orders")
                            @EventTrigger(eventName = "OrderPlaced")
                            public OrderPlaced on(List<OrderPlaced> batch) { return batch.get(0); }
                        }
                        """,
                        List.of(
                                "@KafkaSource 'OrderConsumer.on'",
                                "java.util.List<demo.OrderPlaced>",
                                "is parameterized")),
                Arguments.of(
                        "parameterizedSinkEvent",
                        """
                        @Component(scope = Scope.SINGLETON)
                        public class OrderPublisher {
                            @KafkaSink(topic = "orders")
                            public OrderPlaced toKafka(List<OrderPlaced> batch) { return batch.get(0); }
                        }
                        """,
                        List.of(
                                "@KafkaSink 'OrderPublisher.toKafka'",
                                "java.util.List<demo.OrderPlaced>",
                                "is parameterized")),
                Arguments.of(
                        "packagePrivateSourceMethod",
                        """
                        @Component(scope = Scope.SINGLETON)
                        public class OrderConsumer {
                            @KafkaSource(topic = "orders")
                            @EventTrigger(eventName = "OrderPlaced")
                            OrderPlaced on(OrderPlaced placed) { return placed; }
                        }
                        """,
                        List.of("@KafkaSource 'OrderConsumer.on' is not public", "Make 'on' public")),
                Arguments.of(
                        "packagePrivateSinkMethod",
                        """
                        @Component(scope = Scope.SINGLETON)
                        public class OrderPublisher {
                            @KafkaSink(topic = "orders")
                            OrderPlaced toKafka(OrderPlaced placed) { return placed; }
                        }
                        """,
                        List.of("@KafkaSink 'OrderPublisher.toKafka' is not public", "Make 'toKafka' public")),
                Arguments.of(
                        "nonPublicSourcePayload",
                        """
                        record Hidden(String id) {}
                        @Component(scope = Scope.SINGLETON)
                        public class OrderConsumer {
                            @KafkaSource(topic = "orders")
                            @EventTrigger(eventName = "OrderPlaced")
                            public OrderPlaced on(Hidden hidden) { return new OrderPlaced(hidden.id()); }
                        }
                        """,
                        List.of(
                                "@KafkaSource 'OrderConsumer.on'",
                                "demo.Hidden, which is not public",
                                "Make demo.Hidden public")),
                Arguments.of(
                        "nonPublicSinkEvent",
                        """
                        record Hidden(String id) {}
                        @Component(scope = Scope.SINGLETON)
                        public class OrderPublisher {
                            @KafkaSink(topic = "orders")
                            public OrderPlaced toKafka(Hidden hidden) { return new OrderPlaced(hidden.id()); }
                        }
                        """,
                        List.of(
                                "@KafkaSink 'OrderPublisher.toKafka'",
                                "demo.Hidden, which is not public",
                                "Make demo.Hidden public")),
                Arguments.of(
                        "nonPublicKeyedSinkPayload",
                        """
                        record Hidden(String id) {
                            public String id() { return id; }
                        }
                        @Component(scope = Scope.SINGLETON)
                        public class OrderPublisher {
                            @KafkaSink(topic = "orders", partitionKey = "id")
                            public Hidden toKafka(OrderPlaced placed) { return new Hidden(placed.orderId()); }
                        }
                        """,
                        List.of(
                                "@KafkaSink 'OrderPublisher.toKafka'",
                                "demo.Hidden, which is not public",
                                "Make demo.Hidden public")),
                Arguments.of(
                        "packagePrivateBridgeClass",
                        """
                        @Component(scope = Scope.SINGLETON)
                        class OrderConsumer {
                            @KafkaSource(topic = "orders")
                            @EventTrigger(eventName = "OrderPlaced")
                            public OrderPlaced on(OrderPlaced placed) { return placed; }
                        }
                        """,
                        List.of(
                                "@KafkaSource 'OrderConsumer.on'",
                                "demo.OrderConsumer, which is not public",
                                "Make demo.OrderConsumer public")));
    }

    /** #750: one group gives each partition to one consumer, so two sources would split the topic. */
    @Test
    void twoSourcesOnOneTopicInOneGroupAreRejected() {
        var compilation = compile("""
                @Component(scope = Scope.SINGLETON)
                public class OrderConsumer {
                    @KafkaSource(topic = "orders")
                    @EventTrigger(eventName = "OrderPlaced")
                    public OrderPlaced placed(OrderPlaced placed) { return placed; }

                    @KafkaSource(topic = "orders")
                    @EventTrigger(eventName = "OrderShipped")
                    public OrderShipped shipped(OrderPlaced placed) { return new OrderShipped(placed.orderId()); }
                }
                """);

        assertLocatedError(
                compilation,
                List.of(
                        "@KafkaSource 'OrderConsumer.shipped' reads topic 'orders'",
                        "same consumer group as 'OrderConsumer.placed'",
                        "consumerGroup"));
    }

    @Test
    void twoSourcesOnOneTopicInDifferentGroupsCompile() {
        var compilation = compile("""
                @Component(scope = Scope.SINGLETON)
                public class OrderConsumer {
                    @KafkaSource(topic = "orders")
                    @EventTrigger(eventName = "OrderPlaced")
                    public OrderPlaced placed(OrderPlaced placed) { return placed; }

                    @KafkaSource(topic = "orders", consumerGroup = "shipping")
                    @EventTrigger(eventName = "OrderShipped")
                    public OrderShipped shipped(OrderPlaced placed) { return new OrderShipped(placed.orderId()); }
                }
                """);

        assertThat(errorsOf(compilation)).isEmpty();
        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
    }

    /** #756: what the source publishes, the sink sends back to the topic the source reads. */
    @Test
    void sourceAndSinkOnOneTopicAndEventTypeAreRejected() {
        var compilation = compile("""
                @Component(scope = Scope.SINGLETON)
                public class OrderConsumer {
                    @KafkaSource(topic = "orders")
                    @EventTrigger(eventName = "OrderPlaced")
                    public OrderPlaced fromKafka(OrderPlaced placed) { return placed; }
                }
                """, """
                @Component(scope = Scope.SINGLETON)
                public class OrderPublisher {
                    @KafkaSink(topic = "orders")
                    public OrderPlaced toKafka(OrderPlaced placed) { return placed; }
                }
                """);

        assertLocatedError(
                compilation,
                List.of(
                        "@KafkaSink 'OrderPublisher.toKafka' sends demo.OrderPlaced to topic 'orders'",
                        "@KafkaSource 'OrderConsumer.fromKafka'",
                        "without end"));
    }

    @Test
    void sourceAndSinkOnOneTopicWithDifferentEventTypesCompile() {
        var compilation = compile("""
                @Component(scope = Scope.SINGLETON)
                public class OrderConsumer {
                    @KafkaSource(topic = "orders")
                    @EventTrigger(eventName = "OrderPlaced")
                    public OrderPlaced fromKafka(OrderPlaced placed) { return placed; }
                }
                """, """
                @Component(scope = Scope.SINGLETON)
                public class OrderPublisher {
                    @KafkaSink(topic = "orders")
                    public OrderPlaced toKafka(OrderShipped shipped) { return new OrderPlaced(shipped.orderId()); }
                }
                """);

        assertThat(errorsOf(compilation)).isEmpty();
        assertThat(compilation.status()).isEqualTo(Compilation.Status.SUCCESS);
    }
}
