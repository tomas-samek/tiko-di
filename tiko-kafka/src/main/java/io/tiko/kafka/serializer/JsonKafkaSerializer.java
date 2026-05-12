package io.tiko.kafka.serializer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.tiko.kafka.KafkaSerializer;
import java.io.IOException;

/**
 * Default {@link KafkaSerializer} backed by Jackson. Configured for Java records,
 * JSR-310 date/time types, and lenient deserialisation (unknown properties are ignored
 * — schema evolution between producer and consumer should not crash consumers).
 *
 * <p>The Jackson dependency is shadow-bundled inside {@code tiko-kafka.jar} under
 * {@code io.tiko.kafka.internal.jackson}; the relocation happens at the {@code package}
 * phase via the maven-shade-plugin configuration in {@code tiko-kafka/pom.xml}. At source
 * level we reference the un-relocated package names — the relocator rewrites both the
 * class bytecode and the reachable transitive jars in one pass.
 *
 * <p>{@code deserialize} uses a method-level type parameter so that callers with a known
 * target type get a typed return value without an explicit cast. The {@link KafkaSerializer}
 * SPI is exposed through the {@link #asKafkaSerializer()} adapter, which wraps this
 * instance for use in the transport layer.
 */
public final class JsonKafkaSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** Serialize {@code value} to UTF-8 JSON bytes. */
    public byte[] serialize(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new RuntimeException(
                    "failed to serialize " + value.getClass().getName() + " to JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Deserialize bytes into an instance of {@code type}. The method-level type parameter
     * provides a typed return without requiring the caller to cast.
     */
    public <T> T deserialize(byte[] bytes, Class<T> type) {
        try {
            return MAPPER.readValue(bytes, type);
        } catch (IOException e) {
            throw new RuntimeException(
                    "failed to deserialize " + type.getSimpleName() + " from JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Returns a {@link KafkaSerializer}{@code <Object>} view of this instance, suitable
     * for registration in the transport layer.
     */
    @SuppressWarnings("unchecked")
    public KafkaSerializer<Object> asKafkaSerializer() {
        return new KafkaSerializer<>() {
            @Override
            public byte[] serialize(Object value) {
                return JsonKafkaSerializer.this.serialize(value);
            }

            @Override
            public Object deserialize(byte[] bytes, Class<Object> type) {
                return JsonKafkaSerializer.this.deserialize(bytes, (Class<?>) type);
            }
        };
    }
}
