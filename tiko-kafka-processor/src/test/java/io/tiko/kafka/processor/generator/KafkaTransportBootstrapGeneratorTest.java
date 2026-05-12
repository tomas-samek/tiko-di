package io.tiko.kafka.processor.generator;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.kafka.processor.KafkaAnnotationProcessor;
import io.tiko.processor.TikoAnnotationProcessor;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;

class KafkaTransportBootstrapGeneratorTest {

    @Test
    void source_and_sink_generate_bootstrap_and_service_entry() {
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
                                    @KafkaSource(topic = "orders")
                                    @EventTrigger(eventName = "OrderPlaced")
                                    public OrderPlaced fromKafka(OrderPlaced p) { return p; }
                                }
                                """),
                        JavaFileObjects.forSourceString("demo.OrderPublisher", """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.annotations.EventHandler;
                                import io.tiko.kafka.annotations.KafkaSink;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class OrderPublisher {
                                    @EventHandler
                                    @KafkaSink(topic = "orders", partitionKey = "orderId")
                                    public OrderPlaced toKafka(OrderPlaced e) { return e; }
                                }
                                """));

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("io.tiko.generated.KafkaTransportBootstrap");
        assertThat(compilation)
                .generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/services/io.tiko.TransportBootstrap");
    }

    @Test
    void no_kafka_annotations_means_no_generation() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(JavaFileObjects.forSourceString(
                        "demo.OrderPlaced", "package demo; public record OrderPlaced(String orderId) {}"));

        assertThat(compilation).succeeded();
        // No KafkaTransportBootstrap should be emitted when no @KafkaSource / @KafkaSink exist.
    }
}
