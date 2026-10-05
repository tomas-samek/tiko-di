package io.tiko.kafka.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import java.util.List;
import javax.annotation.processing.Processor;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * javac's {@code -Xlint:processing} reports every annotation no processor claimed, which fails
 * {@code -Werror} builds. The Kafka processor claims {@code @KafkaSource} / {@code @KafkaSink}
 * in the round it generates and {@code @Generated} on its bootstrap in the round after.
 */
class AnnotationClaimTest {

    private static final String UNCLAIMED = "No processor claimed any of these annotations";

    private static Compilation compile(Iterable<String> options, Processor... processors) {
        JavaFileObject event = JavaFileObjects.forSourceString(
                "demo.OrderPlaced", "package demo; public record OrderPlaced(String orderId) {}");
        JavaFileObject publisher = JavaFileObjects.forSourceString("demo.OrderPublisher", """
                package demo;
                import io.tiko.annotations.Component;
                import io.tiko.kafka.annotations.KafkaSink;
                import io.tiko.Scope;
                @Component(scope = Scope.SINGLETON)
                public class OrderPublisher {
                    @KafkaSink(topic = "orders", partitionKey = "orderId")
                    public OrderPlaced toKafka(OrderPlaced e) { return e; }
                }
                """);
        return Compiler.javac().withProcessors(processors).withOptions(options).compile(event, publisher);
    }

    @Test
    void bridgeAppCompilesUnderProcessingLintWithWerror() {
        var compilation = compile(
                List.of("-Xlint:processing", "-Werror"), new TikoAnnotationProcessor(), new KafkaAnnotationProcessor());

        CompilationSubject.assertThat(compilation).succeeded();
        CompilationSubject.assertThat(compilation).generatedSourceFile("io.tiko.generated.KafkaTransportBootstrap");
    }

    @Test
    void kafkaProcessorClaimsItsOwnAnnotationsWithoutTheCoreProcessor() {
        // Without the core processor @Component stays unclaimed; only Kafka-owned names matter here.
        var compilation = compile(List.of("-Xlint:processing"), new KafkaAnnotationProcessor());

        CompilationSubject.assertThat(compilation).succeeded();
        assertThat(compilation.diagnostics())
                .map(d -> d.getMessage(null))
                .filteredOn(m -> m.contains(UNCLAIMED))
                .noneMatch(m -> m.contains("KafkaSink") || m.contains("Generated"));
    }
}
