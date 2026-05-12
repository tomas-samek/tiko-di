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
                                import io.tiko.annotations.EventHandler;
                                import io.tiko.kafka.annotations.KafkaSink;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class Publisher {
                                    @EventHandler
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
                                import io.tiko.annotations.EventHandler;
                                import io.tiko.kafka.annotations.KafkaSink;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class Publisher {
                                    @EventHandler
                                    @KafkaSink(topic = "orders", partitionKey = "missingField")
                                    public OrderPlaced toKafka(OrderPlaced e) { return e; }
                                }
                                """));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("partitionKey 'missingField' does not resolve");
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
                                import io.tiko.annotations.EventHandler;
                                import io.tiko.kafka.annotations.KafkaSink;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class Publisher {
                                    @EventHandler
                                    @KafkaSink(topic = "orders")
                                    public OrderPlaced toKafka(OrderPlaced e) { return e; }
                                }
                                """));
        assertThat(compilation).succeeded();
    }
}
