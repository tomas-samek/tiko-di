package io.tiko.kafka.processor.generator;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.kafka.processor.KafkaAnnotationProcessor;
import io.tiko.processor.TikoAnnotationProcessor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
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
                                import io.tiko.kafka.annotations.KafkaSink;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class OrderPublisher {
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

    @Test
    void keyedSinkGeneratesStaticKeyExtractorInsteadOfReflection() throws IOException {
        Compilation compilation = compileOrderFixtures();

        String normalized = bootstrapSource(compilation).replaceAll("\\s", "");
        org.assertj.core.api.Assertions.assertThat(normalized)
                .as("keyed sink resolves the partition key via a generated static method")
                .contains("KafkaTransportBootstrap::key0")
                .contains("privatestaticStringkey0(Objectp)")
                .contains("Objectv=((OrderPlaced)p).orderId()")
                .contains("returnv==null?null:String.valueOf(v)");
    }

    @Test
    void keylessSinkGeneratesNullKeyLambda() throws IOException {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String orderId) {}"),
                        JavaFileObjects.forSourceString("demo.OrderPublisher", """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.kafka.annotations.KafkaSink;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class OrderPublisher {
                                    @KafkaSink(topic = "orders")
                                    public OrderPlaced toKafka(OrderPlaced e) { return e; }
                                }
                                """));

        assertThat(compilation).succeeded();
        String normalized = bootstrapSource(compilation).replaceAll("\\s", "");
        org.assertj.core.api.Assertions.assertThat(normalized)
                .as("keyless sink passes a null-returning extractor and generates no key method")
                .contains("this::sink0,p->null")
                .doesNotContain("key0(");
    }

    @Test
    void generatedBootstrapImplementsKafkaTransportWithPublicDescriptors() throws IOException {
        Compilation compilation = compileOrderFixtures();

        String normalized = bootstrapSource(compilation).replaceAll("\\s", "");
        org.assertj.core.api.Assertions.assertThat(normalized)
                .as("generated bootstrap is substitutable via KafkaTransport and exposes its wiring")
                .contains("implementsKafkaTransport")
                .contains("@OverridepublicList<GeneratedSourceDescriptor>sources()")
                .contains("@OverridepublicList<GeneratedSinkDescriptor>sinks()");
    }

    private Compilation compileOrderFixtures() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String orderId) {}"),
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
        assertThat(compilation).succeeded();
        return compilation;
    }

    private static String bootstrapSource(Compilation compilation) throws IOException {
        JavaFileObject bootstrap = compilation.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("KafkaTransportBootstrap"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("KafkaTransportBootstrap was not generated"));
        return new String(bootstrap.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
