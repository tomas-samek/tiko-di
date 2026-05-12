package io.tiko.kafka.processor.validation;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.kafka.processor.KafkaAnnotationProcessor;
import io.tiko.processor.TikoAnnotationProcessor;
import org.junit.jupiter.api.Test;

class BridgeMethodShapeValidatorTest {

    @Test
    void void_sink_return_type_fails() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String id) {}"),
                        JavaFileObjects.forSourceString("demo.VoidSink", """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.kafka.annotations.KafkaSink;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class VoidSink {
                                    @KafkaSink(topic = "orders")
                                    public void toKafka(OrderPlaced e) {}
                                }
                                """));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@KafkaSink method must return a non-void payload");
    }

    @Test
    void kafka_context_in_wrong_position_fails() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String id) {}"),
                        JavaFileObjects.forSourceString("demo.WrongShape", """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.annotations.EventTrigger;
                                import io.tiko.kafka.annotations.KafkaSource;
                                import io.tiko.kafka.KafkaContext;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class WrongShape {
                                    @KafkaSource(topic = "orders")
                                    @EventTrigger(eventName = "OrderPlaced")
                                    public OrderPlaced fromKafka(KafkaContext ctx, OrderPlaced p) { return p; }
                                }
                                """));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("KafkaContext, if present, must be the second parameter");
    }

    @Test
    void zero_param_source_fails() {
        Compilation compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString(
                                "demo.OrderPlaced", "package demo; public record OrderPlaced(String id) {}"),
                        JavaFileObjects.forSourceString("demo.NoParam", """
                                package demo;
                                import io.tiko.annotations.Component;
                                import io.tiko.annotations.EventTrigger;
                                import io.tiko.kafka.annotations.KafkaSource;
                                import io.tiko.Scope;
                                @Component(scope = Scope.SINGLETON)
                                public class NoParam {
                                    @KafkaSource(topic = "orders")
                                    @EventTrigger(eventName = "OrderPlaced")
                                    public OrderPlaced fromKafka() { return null; }
                                }
                                """));
        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@KafkaSource method must declare at least one parameter");
    }
}
