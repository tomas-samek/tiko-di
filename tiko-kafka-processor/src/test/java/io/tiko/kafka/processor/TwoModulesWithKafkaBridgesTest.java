package io.tiko.kafka.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.kafka.KafkaTransport;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.kafka.test.FakeKafkaTransport;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #869: an application of two modules, each compiled on its own with its own Kafka bridges, runs the
 * bridges of both. Each module is a separate class-output directory on the application's class path,
 * as two jars would be.
 */
class TwoModulesWithKafkaBridgesTest {

    @Test
    void theBridgesOfBothModulesConsumeAndPublish(@TempDir Path moduleA, @TempDir Path moduleB) throws Exception {
        compileModule(moduleA, "a");
        compileModule(moduleB, "b");
        var broker = new FakeKafkaBroker();
        var options = TikoOptions.builder()
                .configSource(ConfigSources.fromMap(Map.of(
                        "tiko", Map.of("kafka", Map.of("poll-timeout", "PT0.05S", "shutdown-timeout", "PT0.3S")))))
                .replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker))
                .build();

        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try (var loader = new URLClassLoader(
                new URL[] {moduleA.toUri().toURL(), moduleB.toUri().toURL()}, previous)) {
            thread.setContextClassLoader(loader);
            try (Container container = Tiko.create(options)) {
                broker.produce("a-in", "{\"id\":\"a-1\"}".getBytes(StandardCharsets.UTF_8));
                broker.produce("b-in", "{\"id\":\"b-1\"}".getBytes(StandardCharsets.UTF_8));

                await().alias("module a's source fed module a's sink")
                        .atMost(Duration.ofSeconds(10))
                        .until(() -> broker.produced("a-out").size() == 1);
                await().alias("module b's source fed module b's sink")
                        .atMost(Duration.ofSeconds(10))
                        .until(() -> broker.produced("b-out").size() == 1);
            }
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** One module: a record, and a component that bridges {@code <pkg>-in} to an event and the event to {@code <pkg>-out}. */
    private static void compileModule(Path output, String pkg) throws Exception {
        var event = pkg.toUpperCase() + "Placed";
        var sources = output.resolve("src/" + pkg);
        Files.createDirectories(sources);
        Files.createDirectories(output.resolve("generated"));
        var record = Files.writeString(
                sources.resolve(event + ".java"), "package " + pkg + "; public record " + event + "(String id) {}");
        var bridge = Files.writeString(sources.resolve("Bridge.java"), """
                package %1$s;

                import io.tiko.Scope;
                import io.tiko.annotations.Component;
                import io.tiko.annotations.EventTrigger;
                import io.tiko.kafka.annotations.KafkaSink;
                import io.tiko.kafka.annotations.KafkaSource;

                @Component(scope = Scope.SINGLETON)
                public class Bridge {
                    @KafkaSource(topic = "%1$s-in")
                    @EventTrigger(eventName = "%2$s")
                    public %2$s fromKafka(%2$s placed) { return placed; }

                    @KafkaSink(topic = "%1$s-out")
                    public %2$s toKafka(%2$s placed) { return placed; }
                }
                """.formatted(pkg, event));
        int status = ToolProvider.getSystemJavaCompiler()
                .run(
                        null,
                        null,
                        null,
                        "--release",
                        "21",
                        "-classpath",
                        System.getProperty("java.class.path"),
                        // Named: since JDK 23 javac runs no processor it only finds on the classpath.
                        "-processor",
                        "io.tiko.processor.TikoAnnotationProcessor,io.tiko.kafka.processor.KafkaAnnotationProcessor",
                        "-d",
                        output.toString(),
                        "-s",
                        output.resolve("generated").toString(),
                        record.toString(),
                        bridge.toString());
        assertThat(status).as("javac exit status for module %s", pkg).isZero();
    }
}
