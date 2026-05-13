package io.tiko.kafka.serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JsonKafkaSerializerTest {

    record OrderPlaced(String orderId, BigDecimal amount, Instant placedAt) {}

    @Test
    void round_trips_a_simple_record() {
        JsonKafkaSerializer json = new JsonKafkaSerializer();
        OrderPlaced original = new OrderPlaced("o-1", new BigDecimal("19.99"), Instant.parse("2026-05-12T10:00:00Z"));

        byte[] bytes = json.serialize(original);
        OrderPlaced roundTripped = json.deserialize(bytes, OrderPlaced.class);

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void deserialize_unknown_property_does_not_fail() {
        JsonKafkaSerializer json = new JsonKafkaSerializer();
        byte[] bytes =
                "{\"orderId\":\"o-1\",\"amount\":\"19.99\",\"placedAt\":\"2026-05-12T10:00:00Z\",\"extra\":\"ignored\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        OrderPlaced result = json.deserialize(bytes, OrderPlaced.class);

        assertThat(result.orderId()).isEqualTo("o-1");
    }

    @Test
    void deserialize_malformed_input_throws_with_clear_message() {
        JsonKafkaSerializer json = new JsonKafkaSerializer();
        byte[] bytes = "not json at all".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThatThrownBy(() -> json.deserialize(bytes, OrderPlaced.class))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("OrderPlaced");
    }
}
