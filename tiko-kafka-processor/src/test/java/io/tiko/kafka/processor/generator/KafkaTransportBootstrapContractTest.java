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
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Pins the two generated-code contract fixes from #307: the emitted
 * {@code KafkaTransportBootstrap} carries {@code @javax.annotation.processing.Generated}
 * (like every other generated top-level type, so IDE/coverage/Sonar excludes recognise it),
 * and {@code start()} is idempotent — a second call must not replace a running
 * {@code KafkaBootstrapSupport} with a fresh instance, orphaning live consumer threads and
 * an open producer.
 */
class KafkaTransportBootstrapContractTest {

    @Test
    void bootstrapCarriesGeneratedAnnotation() throws IOException {
        String source = generateBootstrapSource();

        assertThat(source)
                .as("generated top-level types carry the Generated annotation naming their generator")
                .contains("@Generated(\"io.tiko.kafka.processor.generator.KafkaTransportBootstrapGenerator\")");
    }

    @Test
    void secondStartIsNoOp() throws IOException {
        String normalized = generateBootstrapSource().replaceAll("\\s", "");

        assertThat(normalized)
                .as("start() must bail out when support already exists, before creating a new one")
                .contains("if(this.support!=null){return;}this.support=new");
    }

    private String generateBootstrapSource() throws IOException {
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
                                """));

        CompilationSubject.assertThat(compilation).succeeded();

        JavaFileObject bootstrap = compilation.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("KafkaTransportBootstrap"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("KafkaTransportBootstrap was not generated"));

        return new String(bootstrap.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
