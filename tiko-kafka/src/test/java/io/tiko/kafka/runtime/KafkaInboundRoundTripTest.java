package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.runtime.fixtures.OrderKafkaConsumer;
import io.tiko.kafka.runtime.fixtures.OrderPlaced;
import io.tiko.kafka.runtime.fixtures.OrderRecorder;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.runtime.Tiko;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Drives an inbound record through the fake broker and asserts the local handler receives
 * it. Verifies serializer resolution, dispatcher invocation, and EventBus publish-by-class.
 */
class KafkaInboundRoundTripTest {

    @Test
    void payload_round_trips_to_local_handler() throws Exception {
        FakeKafkaBroker broker = new FakeKafkaBroker();

        List<GeneratedSourceDescriptor> sources = List.of(new GeneratedSourceDescriptor(
                "orders",
                "",
                "OrderPlaced",
                OrderPlaced.class,
                KafkaSerializer.Default.class,
                false,
                (container, payload, ctx) ->
                        container.get(OrderKafkaConsumer.class).fromKafka((OrderPlaced) payload)));

        try (Container container = Tiko.create();
                TestKafkaBootstrap bootstrap = TestKafkaBootstrap.start(container, broker, sources, List.of())) {

            byte[] payload = "{\"orderId\":\"o-1\",\"amount\":42}".getBytes(StandardCharsets.UTF_8);
            broker.produce("orders", payload);

            OrderRecorder recorder = container.get(OrderRecorder.class);
            await().atMost(Duration.ofSeconds(3)).until(() -> !recorder.received.isEmpty());

            assertThat(recorder.received).hasSize(1);
            assertThat(recorder.received.get(0)).isEqualTo(new OrderPlaced("o-1", 42));
        }
    }
}
