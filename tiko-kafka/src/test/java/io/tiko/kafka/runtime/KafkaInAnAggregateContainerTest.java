package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.kafka.KafkaConfig;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.runtime.fixtures.OrderKafkaConsumer;
import io.tiko.kafka.runtime.fixtures.OrderKafkaPublisher;
import io.tiko.kafka.runtime.fixtures.OrderPlaced;
import io.tiko.kafka.runtime.fixtures.OrderRecorder;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #848: an application whose container is an aggregate boots with {@code tiko-kafka} on the
 * classpath, and its bridges run. The test fixtures are one module; a second module, compiled
 * here with the DI processor, makes the boot a multi-module one. {@link KafkaConfig} is bound
 * through {@code tiko-kafka}'s own binder, so no module lists it.
 */
class KafkaInAnAggregateContainerTest {

    @Test
    void anAggregateBootsReadsTikoKafkaFromTheConfigAndRunsItsBridges(@TempDir Path peerModule) throws Exception {
        compilePeerModule(peerModule);
        var broker = new FakeKafkaBroker();
        var sources = List.of(new GeneratedSourceDescriptor(
                "orders",
                "",
                "OrderPlaced",
                OrderPlaced.class,
                KafkaSerializer.Default.class,
                false,
                (container, payload, ctx) ->
                        container.get(OrderKafkaConsumer.class).fromKafka((OrderPlaced) payload)));
        var sinks = List.of(new GeneratedSinkDescriptor(
                "orders-out",
                "orderId",
                OrderPlaced.class,
                KafkaSerializer.Default.class,
                (container, event) -> container.get(OrderKafkaPublisher.class).toKafka((OrderPlaced) event),
                event -> ((OrderPlaced) event).orderId()));
        var options = TikoOptions.builder()
                .configSource(ConfigSources.fromMap(
                        Map.of("tiko", Map.of("kafka", Map.of("consumer-group", "from-the-application")))))
                .build();

        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try (var loader = new URLClassLoader(new URL[] {peerModule.toUri().toURL()}, previous)) {
            thread.setContextClassLoader(loader);
            try (Container container = Tiko.create(options);
                    var bootstrap = TestKafkaBootstrap.start(container, broker, sources, sinks)) {
                assertThat(container.get(loader.loadClass("peer.PeerService")))
                        .as("the peer module's component: the boot is an aggregate")
                        .isNotNull();
                assertThat(container.get(KafkaConfig.class).consumerGroup()).isEqualTo("from-the-application");

                broker.produce("orders", "{\"orderId\":\"o-1\",\"amount\":42}".getBytes(StandardCharsets.UTF_8));
                var recorder = container.get(OrderRecorder.class);
                await().atMost(Duration.ofSeconds(3)).until(() -> !recorder.received.isEmpty());
                assertThat(recorder.received).containsExactly(new OrderPlaced("o-1", 42));

                container.getEventBus().publish(new OrderPlaced("o-5", 21));
                // The sink sends every OrderPlaced: the one the source published, then this one.
                assertThat(broker.produced("orders-out"))
                        .extracting(ProducerRecord::key)
                        .containsExactly("o-1", "o-5");
            }
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** Compiles a module of one component with the DI processor, which the test classpath carries. */
    private static void compilePeerModule(Path output) throws Exception {
        var source = output.resolve("src/peer/PeerService.java");
        Files.createDirectories(source.getParent());
        Files.createDirectories(output.resolve("generated"));
        Files.writeString(source, """
                package peer;

                import io.tiko.Scope;
                import io.tiko.annotations.Component;

                @Component(scope = Scope.SINGLETON)
                public class PeerService {}
                """);
        int status = ToolProvider.getSystemJavaCompiler()
                .run(
                        null,
                        null,
                        null,
                        "--release",
                        "21",
                        "-classpath",
                        System.getProperty("java.class.path"),
                        "-d",
                        output.toString(),
                        "-s",
                        output.resolve("generated").toString(),
                        source.toString());
        assertThat(status).as("javac exit status for the peer module").isZero();
        assertThat(output.resolve("META-INF/tiko/container.properties"))
                .as("the peer module's container descriptor")
                .exists();
    }
}
