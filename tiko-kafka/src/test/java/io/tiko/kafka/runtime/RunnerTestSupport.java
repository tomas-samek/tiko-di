package io.tiko.kafka.runtime;

import io.tiko.kafka.KafkaConfig;
import io.tiko.kafka.KafkaSerializer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/** Shared building blocks for direct {@link ThreadPerTopicRunner} tests. */
final class RunnerTestSupport {

    /** Pass-through serializer: payload bytes are the UTF-8 string itself. */
    static final KafkaSerializer UTF8 = new KafkaSerializer() {
        @Override
        public byte[] serialize(Object value) {
            return value.toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public <T> T deserialize(byte[] bytes, Class<T> type) {
            return type.cast(new String(bytes, StandardCharsets.UTF_8));
        }
    };

    private RunnerTestSupport() {}

    static KafkaConfig config() {
        return new KafkaConfig(
                "unused:9092",
                "g",
                "json",
                "earliest",
                Duration.ofMillis(10),
                Duration.ofSeconds(2),
                Map.of(),
                Map.of());
    }

    /**
     * Source whose dispatcher maps the String payload through {@code toEvent}; the
     * payload "poison" always throws before dispatch.
     */
    static GeneratedSourceDescriptor stringSource(String topic, Function<String, Object> toEvent) {
        return new GeneratedSourceDescriptor(
                topic,
                "",
                "StringEvent",
                String.class,
                KafkaSerializer.Default.class,
                false,
                (container, payload, ctx) -> {
                    if ("poison".equals(payload)) {
                        throw new IllegalStateException("poison payload");
                    }
                    return toEvent.apply((String) payload);
                });
    }

    static ConsumerRecord<String, byte[]> consumerRecord(String topic, int partition, long offset, String payload) {
        return new ConsumerRecord<>(topic, partition, offset, null, payload.getBytes(StandardCharsets.UTF_8));
    }
}
