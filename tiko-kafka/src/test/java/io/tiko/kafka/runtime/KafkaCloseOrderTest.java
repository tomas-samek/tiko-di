package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.kafka.KafkaEgressError;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.runtime.fixtures.OrderKafkaConsumer;
import io.tiko.kafka.runtime.fixtures.OrderKafkaPublisher;
import io.tiko.kafka.runtime.fixtures.OrderPlaced;
import io.tiko.kafka.runtime.fixtures.OrderRecorder;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * #721: a closing container first stops what comes in from Kafka and keeps the producer open for
 * the work it still has; an event that reaches a sink after the transport closed says why it was
 * not sent.
 */
class KafkaCloseOrderTest {

    private static final String IN = "orders";
    private static final String OUT = "orders-out";

    private static final List<GeneratedSourceDescriptor> SOURCES = List.of(new GeneratedSourceDescriptor(
            IN,
            "",
            "OrderPlaced",
            OrderPlaced.class,
            KafkaSerializer.Default.class,
            false,
            (container, payload, ctx) -> container.get(OrderKafkaConsumer.class).fromKafka((OrderPlaced) payload)));

    private static final List<GeneratedSinkDescriptor> SINKS = List.of(new GeneratedSinkDescriptor(
            OUT,
            "",
            OrderPlaced.class,
            KafkaSerializer.Default.class,
            (container, event) -> container.get(OrderKafkaPublisher.class).toKafka((OrderPlaced) event),
            payload -> null));

    private static KafkaBootstrapSupport support(Container container, FakeKafkaBroker broker) {
        return new KafkaBootstrapSupport(
                container,
                SOURCES,
                SINKS,
                (config, group) -> broker.consumerClient(group),
                config -> broker.producerClient());
    }

    private static byte[] order(String id) {
        return ("{\"orderId\":\"" + id + "\",\"amount\":1}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void stopIngestStopsTheConsumersAndLeavesTheProducerOpen() {
        var broker = new FakeKafkaBroker();
        try (Container container = Tiko.create()) {
            var kafka = support(container, broker);
            kafka.start();
            try {
                var recorder = container.get(OrderRecorder.class);
                broker.produce(IN, order("consumed"));
                await().atMost(Duration.ofSeconds(3)).until(() -> recorder.received.size() == 1);

                kafka.stopIngest();
                kafka.stopIngest();
                broker.produce(IN, order("arrives-too-late"));
                container.getEventBus().publish(new OrderPlaced("published-while-closing", 1));

                assertThat(broker.produced(OUT))
                        .as("the producer is still open")
                        .hasSize(2);
                await().during(Duration.ofMillis(300))
                        .atMost(Duration.ofSeconds(2))
                        .until(() -> recorder.received.stream()
                                .noneMatch(received -> received.orderId().equals("arrives-too-late")));
            } finally {
                kafka.shutdown();
            }
        }
    }

    @Test
    void eventReachingASinkAfterTheTransportClosedIsRoutedWithTheReason() {
        var broker = new FakeKafkaBroker();
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();
        try (Container container =
                Tiko.create(TikoOptions.builder().errorHandler(errors::add).build())) {
            var kafka = support(container, broker);
            kafka.start();
            kafka.shutdown();
            kafka.shutdown();

            container.getEventBus().publish(new OrderPlaced("too-late", 1));

            assertThat(broker.produced(OUT)).isEmpty();
            assertThat(errors).singleElement().isInstanceOfSatisfying(KafkaEgressError.class, egress -> {
                assertThat(egress.topic()).isEqualTo(OUT);
                assertThat(egress.cause())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("the container is shutting down");
            });
        }
    }
}
