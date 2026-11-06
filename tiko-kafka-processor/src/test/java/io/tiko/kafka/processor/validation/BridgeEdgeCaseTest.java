package io.tiko.kafka.processor.validation;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.kafka.processor.KafkaAnnotationProcessor;
import io.tiko.processor.TikoAnnotationProcessor;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/** Bridges with unresolved or type-annotated types: located errors instead of crashes, no false rejections. */
class BridgeEdgeCaseTest {

    private static final JavaFileObject EVENT = JavaFileObjects.forSourceString(
            "demo.OrderPlaced", "package demo; public record OrderPlaced(String id) {}");

    private static Compilation compile(JavaFileObject... sources) {
        var all = new JavaFileObject[sources.length + 1];
        all[0] = EVENT;
        System.arraycopy(sources, 0, all, 1, sources.length);
        return Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
                .compile(all);
    }

    @Test
    void unresolvedSerializerIsLocatedErrorNotCrash() {
        var publisher = JavaFileObjects.forSourceString("demo.OrderPublisher", """
                package demo;
                import io.tiko.annotations.Component;
                import io.tiko.kafka.annotations.KafkaSink;
                import io.tiko.Scope;
                @Component(scope = Scope.SINGLETON)
                public class OrderPublisher {
                    @KafkaSink(topic = "orders", serializer = MissingSerializer.class)
                    public OrderPlaced toKafka(OrderPlaced e) { return e; }
                }
                """);

        var compilation = compile(publisher);

        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining("@KafkaSink serializer class does not resolve")
                .inFile(publisher)
                .onLine(7);
        assertThat(compilation).hadErrorContaining("remove serializer to use the serializer named by");
    }

    @Test
    void typeAnnotatedKafkaContextIsAccepted() {
        var typeUse = JavaFileObjects.forSourceString("demo.TypeUse", """
                package demo;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Target;
                @Target(ElementType.TYPE_USE)
                public @interface TypeUse {}
                """);
        var bridge = JavaFileObjects.forSourceString("demo.OrderBridge", """
                package demo;
                import io.tiko.annotations.Component;
                import io.tiko.annotations.EventTrigger;
                import io.tiko.kafka.KafkaContext;
                import io.tiko.kafka.annotations.KafkaSource;
                import io.tiko.Scope;
                @Component(scope = Scope.SINGLETON)
                public class OrderBridge {
                    @KafkaSource(topic = "orders")
                    @EventTrigger(eventName = "OrderPlaced")
                    public OrderPlaced fromKafka(OrderPlaced p, @TypeUse KafkaContext ctx) { return p; }
                }
                """);

        var compilation = compile(typeUse, bridge);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("io.tiko.generated.KafkaTransportBootstrap")
                .contentsAsUtf8String()
                .contains("bridge.fromKafka((OrderPlaced) payload, ctx)");
    }

    @Test
    void partitionKeyOnUnresolvedReturnTypeNamesTheType() {
        var publisher = JavaFileObjects.forSourceString("demo.OrderPublisher", """
                package demo;
                import io.tiko.annotations.Component;
                import io.tiko.kafka.annotations.KafkaSink;
                import io.tiko.Scope;
                @Component(scope = Scope.SINGLETON)
                public class OrderPublisher {
                    @KafkaSink(topic = "orders", partitionKey = "id")
                    public MissingPayload toKafka(OrderPlaced e) { return null; }
                }
                """);

        var compilation = compile(publisher);

        assertThat(compilation).failed();
        assertThat(compilation)
                .hadErrorContaining(
                        "@KafkaSink partitionKey 'id' does not resolve to a zero-arg method on MissingPayload.");
    }
}
