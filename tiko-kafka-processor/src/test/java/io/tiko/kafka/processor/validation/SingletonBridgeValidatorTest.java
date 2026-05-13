package io.tiko.kafka.processor.validation;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.kafka.processor.KafkaAnnotationProcessor;
import io.tiko.processor.TikoAnnotationProcessor;
import org.junit.jupiter.api.Test;

class SingletonBridgeValidatorTest {

    @Test
    void source_on_singleton_compiles() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String id) {}"),
                        JavaFileObjects.forSourceString("demo.OrderBridge", """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.annotations.EventTrigger;
                                import io.tiko.kafka.annotations.KafkaSource;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class OrderBridge {
                                    @KafkaSource(topic = "orders")
                                    @EventTrigger(eventName = "OrderPlaced")
                                    public OrderPlaced fromKafka(OrderPlaced p) { return p; }
                                }
                                """));
        assertThat(compilation).succeeded();
    }

    @Test
    void source_on_request_scope_fails() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String id) {}"),
                        JavaFileObjects.forSourceString("demo.OrderBridge", """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.annotations.EventTrigger;
                                import io.tiko.kafka.annotations.KafkaSource;
                                import io.tiko.Scope;
                                @Component(scope = Scope.REQUEST)
                                public class OrderBridge {
                                    @KafkaSource(topic = "orders")
                                    @EventTrigger(eventName = "OrderPlaced")
                                    public OrderPlaced fromKafka(OrderPlaced p) { return p; }
                                }
                                """));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@KafkaSource");
        assertThat(compilation).hadErrorContaining("Scope.SINGLETON");
    }
}
