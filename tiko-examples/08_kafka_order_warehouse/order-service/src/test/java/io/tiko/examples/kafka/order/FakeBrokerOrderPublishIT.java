package io.tiko.examples.kafka.order;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.examples.kafka.events.OrderPlaced;
import io.tiko.kafka.KafkaTransport;
import io.tiko.kafka.serializer.JsonKafkaSerializer;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.kafka.test.FakeKafkaTransport;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The #414 reference recipe, outbound half: the app's generated {@code @KafkaSink} wiring
 * publishes to a {@link FakeKafkaBroker} — no Docker, no real Kafka.
 */
class FakeBrokerOrderPublishIT {

    @Test
    void placedOrderIsProducedToTheOrdersTopicWithTheOrderIdKey() {
        FakeKafkaBroker broker = new FakeKafkaBroker();

        try (Container container = Tiko.create(TikoOptions.builder()
                .configSource(ConfigSources.classpath("application.yaml"))
                .replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker))
                .build())) {

            OrderPlaced order = new OrderPlaced("o-42", new BigDecimal("19.99"), Instant.now());
            container.getEventBus().publish(order);

            assertThat(broker.produced("orders")).hasSize(1);
            assertThat(broker.produced("orders").get(0).key()).isEqualTo("o-42");

            OrderPlaced roundTripped = new JsonKafkaSerializer()
                    .deserialize(broker.produced("orders").get(0).value(), OrderPlaced.class);
            assertThat(roundTripped.orderId()).isEqualTo("o-42");
        }
    }
}
