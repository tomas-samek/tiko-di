package io.tiko.kafka.processor.validation;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.kafka.processor.KafkaAnnotationProcessor;
import io.tiko.processor.TikoAnnotationProcessor;
import org.junit.jupiter.api.Test;

class RequiredSiblingValidatorTest {

    @Test
    void source_without_event_trigger_fails() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String id) {}"),
                        JavaFileObjects.forSourceString("demo.OrderBridge", """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.kafka.annotations.KafkaSource;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class OrderBridge {
                                    @KafkaSource(topic = "orders")
                                    public OrderPlaced fromKafka(OrderPlaced p) { return p; }
                                }
                                """));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@KafkaSource requires a sibling @EventTrigger");
    }

    @Test
    void sink_without_event_handler_fails() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String id) {}"),
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
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@KafkaSink requires a sibling @EventHandler");
    }

    @Test
    void source_and_sink_on_same_method_fails() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String id) {}"),
                        JavaFileObjects.forSourceString("demo.BadBridge", """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.annotations.EventHandler;
                                import io.tiko.annotations.EventTrigger;
                                import io.tiko.kafka.annotations.KafkaSource;
                                import io.tiko.kafka.annotations.KafkaSink;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class BadBridge {
                                    @KafkaSource(topic = "a")
                                    @KafkaSink(topic = "b")
                                    @EventHandler
                                    @EventTrigger(eventName = "x")
                                    public OrderPlaced both(OrderPlaced e) { return e; }
                                }
                                """));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@KafkaSource and @KafkaSink cannot coexist");
    }
}
