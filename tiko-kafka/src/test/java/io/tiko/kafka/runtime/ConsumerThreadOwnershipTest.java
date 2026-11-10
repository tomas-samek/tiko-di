package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.KafkaTransport;
import io.tiko.kafka.runtime.fixtures.OrderKafkaConsumer;
import io.tiko.kafka.runtime.fixtures.OrderPlaced;
import io.tiko.kafka.runtime.fixtures.OrderRecorder;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.kafka.test.FakeKafkaTransport;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/**
 * A consumer thread belongs to the container that started it (#635): one that outlives
 * {@code close()} is reported by name, like any other thread the container started.
 */
class ConsumerThreadOwnershipTest {

    private static final String CONSUMER_THREAD = "tiko-kafka-consumer-orders";

    private static KafkaTransport transport(CountDownLatch inBridge, CountDownLatch release) {
        var sources = List.of(new GeneratedSourceDescriptor(
                "orders",
                "",
                "OrderPlaced",
                OrderPlaced.class,
                KafkaSerializer.Default.class,
                false,
                (container, payload, ctx) -> {
                    inBridge.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return container.get(OrderKafkaConsumer.class).fromKafka((OrderPlaced) payload);
                }));
        return new KafkaTransport() {
            @Override
            public List<GeneratedSourceDescriptor> sources() {
                return sources;
            }

            @Override
            public List<GeneratedSinkDescriptor> sinks() {
                return List.of();
            }

            @Override
            public void start(Container container) {
                throw new AssertionError("the replaced transport must never be started");
            }

            @Override
            public void shutdown() {
                /* no-op test fixture */
            }
        };
    }

    private static TikoOptions options(List<Thread> reported) {
        var kafka = Map.<String, Object>of("shutdown-timeout", "PT0.2S");
        return TikoOptions.builder()
                .configSource(ConfigSources.fromMap(Map.of("tiko", Map.of("kafka", kafka))))
                .onThreadsOutlivingClose(reported::addAll)
                .build();
    }

    private static boolean consumerThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.isAlive() && CONSUMER_THREAD.equals(t.getName()));
    }

    @Test
    void consumerStuckInABridgePastShutdownTimeoutIsReported() {
        var reported = new CopyOnWriteArrayList<Thread>();
        var inBridge = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var broker = new FakeKafkaBroker();
        try {
            var container = Tiko.create(options(reported));
            var fake = FakeKafkaTransport.over(transport(inBridge, release), broker);
            fake.start(container);
            broker.produce("orders", "{\"orderId\":\"o-1\",\"amount\":42}".getBytes(StandardCharsets.UTF_8));
            await().atMost(Duration.ofSeconds(5)).until(() -> inBridge.getCount() == 0);

            fake.shutdown();
            container.close();

            assertThat(reported).extracting(Thread::getName).contains(CONSUMER_THREAD);
        } finally {
            release.countDown();
            // The released consumer exits on its own; wait so it cannot leak into the next test.
            await().atMost(Duration.ofSeconds(5)).until(() -> !consumerThreadAlive());
        }
    }

    @Test
    void cleanCloseLeavesNoConsumerThread() {
        var reported = new CopyOnWriteArrayList<Thread>();
        var release = new CountDownLatch(0);
        var broker = new FakeKafkaBroker();
        var container = Tiko.create(options(reported));
        var fake = FakeKafkaTransport.over(transport(new CountDownLatch(1), release), broker);
        fake.start(container);
        broker.produce("orders", "{\"orderId\":\"o-1\",\"amount\":42}".getBytes(StandardCharsets.UTF_8));
        var recorder = container.get(OrderRecorder.class);
        await().atMost(Duration.ofSeconds(5)).until(() -> !recorder.received.isEmpty());

        fake.shutdown();
        container.close();

        assertThat(reported).isEmpty();
        assertThat(consumerThreadAlive()).isFalse();
    }
}
