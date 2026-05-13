package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.runtime.fixtures.OrderKafkaPublisher;
import io.tiko.kafka.runtime.fixtures.OrderPlaced;
import io.tiko.kafka.serializer.JsonKafkaSerializer;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.runtime.Tiko;
import java.util.List;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

class KafkaOutboundRoundTripTest {

    @Test
    @SuppressWarnings("unchecked")
    void publishing_locally_sends_a_kafka_record_with_partition_key() {
        FakeKafkaBroker broker = new FakeKafkaBroker();

        List<GeneratedSinkDescriptor> sinks = List.of(new GeneratedSinkDescriptor(
                "orders-out",
                "orderId",
                OrderPlaced.class,
                KafkaSerializer.Default.class,
                (container, event) -> container.get(OrderKafkaPublisher.class).toKafka((OrderPlaced) event)));

        try (Container container = Tiko.create();
                TestKafkaBootstrap bootstrap = TestKafkaBootstrap.start(container, broker, List.of(), sinks)) {

            container.getEventBus().publish(new OrderPlaced("o-5", 21));

            List<ProducerRecord<String, byte[]>> produced = broker.produced("orders-out");
            assertThat(produced).hasSize(1);
            ProducerRecord<String, byte[]> rec = produced.get(0);
            assertThat(rec.key()).isEqualTo("o-5");

            OrderPlaced roundTripped =
                new JsonKafkaSerializer().deserialize(rec.value(), OrderPlaced.class);
            assertThat(roundTripped).isEqualTo(new OrderPlaced("o-5", 21));
        }
    }
}
