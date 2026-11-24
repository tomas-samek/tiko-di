package io.tiko.kafka.serializer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.tiko.UndecodablePayloadException;
import io.tiko.kafka.KafkaSerializer;
import java.io.IOException;
import java.util.Map;
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
        if (bytes == null) {
            // Nothing to decode, now and on every redelivery, so it is typed like other undecodable
            // input (#751). The consumer loop never gets here: it consumes a record without a value.
            throw new UndecodablePayloadException(
                    type, "failed to deserialize " + type.getSimpleName() + " from JSON: the record has no value");
        }
        try {
            return MAPPER.readValue(bytes, type);
        } catch (IOException e) {
            // Jackson's own message quotes the offending value, and the default error handler logs
            // the whole cause chain, so the payload must not reach either (SEC-4, #494). Decoding is
            // local and deterministic, so the failure is typed as undecodable (#549): the same
            // bytes fail on every redelivery.
            throw new UndecodablePayloadException(
                    type, "failed to deserialize " + type.getSimpleName() + " from JSON: " + describeWithoutValue(e));
        }
    }

    /**
     * The kind of failure, the field path and the position in the document: everything about
     * the failure except the input itself. A name the payload chose is input too, a key of a
     * {@code Map} field or a property a {@code @JsonAnySetter} collects, so the path says
     * {@code [key]} for it.
     */
    private static String describeWithoutValue(IOException e) {
        var description = new StringBuilder(e.getClass().getSimpleName());
        if (e instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            description
                    .append(" at ")
                    .append(mapping.getPath().stream()
                            .map(JsonKafkaSerializer::pathSegment)
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

    /** A declared property's name, an element's index, or {@code [key]} for a name the payload chose. */
    private static String pathSegment(JsonMappingException.Reference ref) {
        if (ref.getFieldName() == null) {
            return "[" + ref.getIndex() + "]";
        }
        return declaresProperty(ref.getFrom(), ref.getFieldName()) ? ref.getFieldName() : "[key]";
    }

    /**
     * Whether {@code name} is a property the type declares, as Jackson names it: not a key of a
     * {@code Map}, and not a property a {@code @JsonAnySetter} collects (#880). Anything it cannot
     * tell counts as the payload's.
     */
    private static boolean declaresProperty(Object from, String name) {
        if (from == null) {
            return false;
        }
        Class<?> type = from instanceof Class<?> declared ? declared : from.getClass();
        if (Map.class.isAssignableFrom(type)) {
            return false;
        }
        var description = MAPPER.getDeserializationConfig().introspect(MAPPER.constructType(type));
        return description.findProperties().stream()
                .anyMatch(property -> property.getName().equals(name));
    }
}
