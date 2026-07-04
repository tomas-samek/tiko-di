package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.runtime.GeneratedSinkDescriptor.KeyExtractor;
import io.tiko.kafka.runtime.fixtures.OrderKafkaPublisher;
import io.tiko.kafka.runtime.fixtures.OrderPlaced;
import io.tiko.kafka.serializer.JsonKafkaSerializer;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.runtime.Tiko;
import java.util.List;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class KafkaOutboundRoundTripTest {

    @Test
    @SuppressWarnings("unchecked")
    void publishing_locally_sends_a_kafka_record_with_partition_key() {
        FakeKafkaBroker broker = new FakeKafkaBroker();

        List<GeneratedSinkDescriptor> sinks = List.of(descriptor(p -> {
            var v = ((OrderPlaced) p).orderId();
            return v == null ? null : String.valueOf(v);
        }));

        try (Container container = Tiko.create();
                TestKafkaBootstrap bootstrap = TestKafkaBootstrap.start(container, broker, List.of(), sinks)) {

            container.getEventBus().publish(new OrderPlaced("o-5", 21));

            List<ProducerRecord<String, byte[]>> produced = broker.produced("orders-out");
            assertThat(produced).hasSize(1);
            ProducerRecord<String, byte[]> rec = produced.get(0);
            assertThat(rec.key()).isEqualTo("o-5");

            OrderPlaced roundTripped = new JsonKafkaSerializer().deserialize(rec.value(), OrderPlaced.class);
            assertThat(roundTripped).isEqualTo(new OrderPlaced("o-5", 21));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keyShapes")
    void partitionKeyComesFromTheDescriptorExtractor(String name, KeyExtractor extractor, String expectedKey) {
        FakeKafkaBroker broker = new FakeKafkaBroker();

        List<GeneratedSinkDescriptor> sinks = List.of(descriptor(extractor));

        try (Container container = Tiko.create();
                TestKafkaBootstrap bootstrap = TestKafkaBootstrap.start(container, broker, List.of(), sinks)) {

            container.getEventBus().publish(new OrderPlaced("o-5", 21));

            List<ProducerRecord<String, byte[]>> produced = broker.produced("orders-out");
            assertThat(produced).hasSize(1);
            assertThat(produced.get(0).key()).isEqualTo(expectedKey);
        }
    }

    private static Stream<Arguments> keyShapes() {
        return Stream.of(
                Arguments.of("string accessor", (KeyExtractor) p -> String.valueOf(((OrderPlaced) p).orderId()), "o-5"),
                Arguments.of("numeric accessor", (KeyExtractor) p -> String.valueOf(((OrderPlaced) p).amount()), "21"),
                Arguments.of("null key", (KeyExtractor) p -> null, null));
    }

    private static GeneratedSinkDescriptor descriptor(KeyExtractor extractor) {
        return new GeneratedSinkDescriptor(
                "orders-out",
                "orderId",
                OrderPlaced.class,
                KafkaSerializer.Default.class,
                (container, event) -> container.get(OrderKafkaPublisher.class).toKafka((OrderPlaced) event),
                extractor);
    }
}
