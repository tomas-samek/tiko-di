package io.tiko.kafka.serializer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.tiko.kafka.KafkaSerializer;
import java.io.IOException;
import java.util.stream.Collectors;

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
            throw new IllegalStateException(
                    "failed to serialize " + value.getClass().getName() + " to JSON: " + e.getMessage(), e);
        }
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> type) {
        try {
            return MAPPER.readValue(bytes, type);
        } catch (IOException e) {
            // Jackson's own message quotes the offending value, and the default error handler logs
            // the whole cause chain, so the payload must not reach either (SEC-4, #494).
            throw new IllegalStateException(
                    "failed to deserialize " + type.getSimpleName() + " from JSON: " + describeWithoutValue(e));
        }
    }

    /**
     * The kind of failure, the field path and the position in the document: everything about
     * the failure except the input itself.
     */
    private static String describeWithoutValue(IOException e) {
        var description = new StringBuilder(e.getClass().getSimpleName());
        if (e instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            description
                    .append(" at ")
                    .append(mapping.getPath().stream()
                            .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                            .collect(Collectors.joining(".")));
        }
        if (e instanceof JsonProcessingException processing && processing.getLocation() != null) {
            description
                    .append(" (line ")
                    .append(processing.getLocation().getLineNr())
                    .append(", column ")
                    .append(processing.getLocation().getColumnNr())
                    .append(')');
        }
        return description.toString();
    }
}
