package io.tiko.kafka.serializer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.tiko.kafka.KafkaSerializer;
import java.io.IOException;

/**
 * Default {@link KafkaSerializer} backed by Jackson. Configured for Java records,
 * JSR-310 date/time types, and lenient deserialisation (unknown properties are ignored).
 *
 * <p>The Jackson dependency is shadow-bundled inside {@code tiko-kafka.jar} under
 * {@code io.tiko.kafka.internal.jackson}; the relocation happens at the {@code package}
 * phase via the maven-shade-plugin configuration in {@code tiko-kafka/pom.xml}.
 */
public final class JsonKafkaSerializer implements KafkaSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Override
    public byte[] serialize(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new RuntimeException(
                    "failed to serialize " + value.getClass().getName() + " to JSON: " + e.getMessage(), e);
        }
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> type) {
        try {
            return MAPPER.readValue(bytes, type);
        } catch (IOException e) {
            throw new RuntimeException(
                    "failed to deserialize " + type.getSimpleName() + " from JSON: " + e.getMessage(), e);
        }
    }
}
