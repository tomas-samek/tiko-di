package io.tiko.kafka.processor.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.kafka.processor.KafkaAnnotationProcessor;
import io.tiko.processor.TikoAnnotationProcessor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code META-INF/tiko/topology-kafka.json} companion fragment (#312). The core
 * {@code tiko-processor} writes {@code topology.json} but is Kafka-agnostic; the Kafka
 * processor emits this sibling fragment so the MCP topology store can see source/sink
 * edges (topic↔event) and {@code trace_event_flow} can confirm a Kafka end-to-end path.
 */
class KafkaTopologyFragmentTest {

    @Test
    void sourceAndSinkEmitTopologyKafkaFragment() throws IOException {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String orderId) {}"),
                        JavaFileObjects.forSourceString("demo.OrderConsumer", """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.annotations.EventTrigger;
                                import io.tiko.kafka.annotations.KafkaSource;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class OrderConsumer {
                                    @KafkaSource(topic = "orders", consumerGroup = "warehouse")
                                    @EventTrigger(eventName = "OrderPlaced")
                                    public OrderPlaced fromKafka(OrderPlaced p) { return p; }
                                }
                                """),
                        JavaFileObjects.forSourceString("demo.OrderPublisher", """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.kafka.annotations.KafkaSink;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class OrderPublisher {
                                    @KafkaSink(topic = "orders", partitionKey = "orderId")
                                    public OrderPlaced toKafka(OrderPlaced e) { return e; }
                                }
                                """));

        CompilationSubject.assertThat(compilation).succeeded();
        CompilationSubject.assertThat(compilation)
                .generatedFile(StandardLocation.CLASS_OUTPUT, "", "META-INF/tiko/topology-kafka.json");

        String json = fragment(compilation);
        // schema + both edge arrays present
        assertThat(json)
                .contains("\"schemaVersion\"")
                .contains("\"kafkaSources\"")
                .contains("\"kafkaSinks\"");
        // source edge: topic -> produced event (the join key the MCP traces by)
        assertThat(json)
                .contains("\"declaringClass\": \"demo.OrderConsumer\"")
                .contains("\"topic\": \"orders\"")
                .contains("\"consumerGroup\": \"warehouse\"")
                .contains("\"eventType\": \"demo.OrderPlaced\"");
        // sink edge: consumed event -> topic, with partition key
        assertThat(json)
                .contains("\"declaringClass\": \"demo.OrderPublisher\"")
                .contains("\"partitionKey\": \"orderId\"");
    }

    @Test
    void noKafkaAnnotationsMeansNoFragment() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(JavaFileObjects.forSourceString(
                        "demo.Plain", "package demo; public record Plain(String id) {}"));

        CompilationSubject.assertThat(compilation).succeeded();
        // No topology-kafka.json when there are no @KafkaSource / @KafkaSink methods.
        org.junit.jupiter.api.Assertions.assertTrue(
                compilation
                        .generatedFile(StandardLocation.CLASS_OUTPUT, "", "META-INF/tiko/topology-kafka.json")
                        .isEmpty(),
                "no Kafka annotations should not emit a topology-kafka.json fragment");
    }

    private static String fragment(Compilation compilation) throws IOException {
        var file = compilation
                .generatedFile(StandardLocation.CLASS_OUTPUT, "", "META-INF/tiko/topology-kafka.json")
                .orElseThrow(() -> new AssertionError("topology-kafka.json was not generated"));
        return new String(file.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
