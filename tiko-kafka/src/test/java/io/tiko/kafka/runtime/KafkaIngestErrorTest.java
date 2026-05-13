package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.kafka.KafkaIngestError;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.runtime.fixtures.OrderPlaced;
import io.tiko.kafka.runtime.fixtures.OrderRecorder;
import io.tiko.kafka.runtime.fixtures.ThrowingBridge;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class KafkaIngestErrorTest {

    @Test
    void bridge_throws_then_seek_back_replays_and_succeeds() throws Exception {
        FakeKafkaBroker broker = new FakeKafkaBroker();
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();
        TikoOptions opts = TikoOptions.builder().errorHandler(errors::add).build();

        List<GeneratedSourceDescriptor> sources = List.of(new GeneratedSourceDescriptor(
                "errors",
                "",
                "OrderPlaced",
                OrderPlaced.class,
                KafkaSerializer.Default.class,
                false,
                (container, payload, ctx) -> container.get(ThrowingBridge.class).fromKafka((OrderPlaced) payload)));

        try (Container container = Tiko.create(opts);
                TestKafkaBootstrap bootstrap = TestKafkaBootstrap.start(container, broker, sources, List.of())) {

            broker.produce("errors", "{\"orderId\":\"o-9\",\"amount\":7}".getBytes(StandardCharsets.UTF_8));

            ThrowingBridge bridge = container.get(ThrowingBridge.class);
            OrderRecorder recorder = container.get(OrderRecorder.class);

            await().atMost(Duration.ofSeconds(5)).until(() -> !recorder.received.isEmpty());

            assertThat(bridge.callCount.get()).isEqualTo(3);
            assertThat(recorder.received).containsExactly(new OrderPlaced("o-9", 7));
            assertThat(errors).hasSize(2);
            assertThat(errors.get(0)).isInstanceOfSatisfying(KafkaIngestError.class, e -> {
                assertThat(e.topic()).isEqualTo("errors");
                assertThat(e.cause()).hasMessageContaining("simulated bridge failure on call 1");
            });
        }
    }
}
