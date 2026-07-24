package io.tiko.kafka.processor.validation;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.kafka.processor.KafkaAnnotationProcessor;
import io.tiko.processor.TikoAnnotationProcessor;
import org.junit.jupiter.api.Test;

class PartitionKeyValidatorTest {

    @Test
    void partition_key_referencing_existing_record_component_compiles() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced",
                                "package demo; public record OrderPlaced(String orderId, int amount) {}"),
                        JavaFileObjects.forSourceString("demo.Publisher", """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.kafka.annotations.KafkaSink;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class Publisher {
                                    @KafkaSink(topic = "orders", partitionKey = "orderId")
                                    public OrderPlaced toKafka(OrderPlaced e) { return e; }
                                }
                                """));
        assertThat(compilation).succeeded();
    }

    @Test
    void partition_key_referencing_unknown_component_fails() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced",
                                "package demo; public record OrderPlaced(String orderId, int amount) {}"),
                        JavaFileObjects.forSourceString("demo.Publisher", """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.kafka.annotations.KafkaSink;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class Publisher {
                                    @KafkaSink(topic = "orders", partitionKey = "missingField")
                                    public OrderPlaced toKafka(OrderPlaced e) { return e; }
                                }
                                """));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("partitionKey 'missingField' does not resolve");
    }

    @Test
    void nonPublicPartitionKeyAccessorFailsWithDiagnosticOnSinkMethod() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString("demo.OrderPlaced", """
                                package demo;
                                public class OrderPlaced {
                                    private final String orderId;
                                    public OrderPlaced(String orderId) { this.orderId = orderId; }
                                    String orderId() { return orderId; }
                                }
                                """),
                        JavaFileObjects.forSourceString("demo.Publisher", """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.kafka.annotations.KafkaSink;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class Publisher {
                                    @KafkaSink(topic = "orders", partitionKey = "orderId")
                                    public OrderPlaced toKafka(OrderPlaced e) { return e; }
                                }
                                """));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("partitionKey 'orderId'");
        assertThat(compilation).hadErrorContaining("not public");
        // The clean diagnostic replaces the raw generated-code error (has private access).
        assertThat(compilation).hadErrorCount(1);
    }

    @Test
    void voidPartitionKeyAccessorFailsWithDiagnosticOnSinkMethod() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString("demo.OrderPlaced", """
                                package demo;
                                public class OrderPlaced {
                                    public void orderId() {}
                                }
                                """),
                        JavaFileObjects.forSourceString("demo.Publisher", """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.kafka.annotations.KafkaSink;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class Publisher {
                                    @KafkaSink(topic = "orders", partitionKey = "orderId")
                                    public OrderPlaced toKafka(OrderPlaced e) { return e; }
                                }
                                """));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("partitionKey 'orderId'");
        assertThat(compilation).hadErrorContaining("returns void");
        // The clean diagnostic replaces the raw generated-code error (void cannot be converted).
        assertThat(compilation).hadErrorCount(1);
    }

    @Test
    void empty_partition_key_compiles() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String orderId) {}"),
                        JavaFileObjects.forSourceString("demo.Publisher", """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.kafka.annotations.KafkaSink;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class Publisher {
                                    @KafkaSink(topic = "orders")
                                    public OrderPlaced toKafka(OrderPlaced e) { return e; }
                                }
                                """));
        assertThat(compilation).succeeded();
    }
}
