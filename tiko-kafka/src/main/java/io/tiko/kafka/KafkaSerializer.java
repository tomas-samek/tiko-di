package io.tiko.kafka;

/**
 * Serializer SPI for Kafka transport. MVP ships a single concrete impl,
 * {@link io.tiko.kafka.serializer.JsonKafkaSerializer JsonKafkaSerializer}; future
 * modules (e.g. {@code tiko-kafka-avro}) ship additional impls.
 *
 * <p>The interface is intentionally <em>not</em> parameterized at the class level: a
 * single serializer typically handles many payload types (JSON via Jackson; Avro via a
 * schema registry). Each call carries its own target type via {@link #deserialize}'s
 * method-level type parameter, so {@code JsonKafkaSerializer} can answer for
 * {@code OrderPlaced}, {@code PaymentReceived}, and any other class without one impl per
 * type.
 *
 * <p>Resolution order, per source/sink:
 * <ol>
 *   <li>Annotation parameter set to a concrete class other than {@link Default} →
 *       use that impl (instantiated reflectively as a no-arg POJO).</li>
 *   <li>Otherwise: the serializer named by {@code KafkaConfig.serializer} (default
 *       {@code "json"}) — looked up via {@code ServiceLoader<NamedKafkaSerializer>} by
 *       name.</li>
 *   <li>Unknown name at startup → container fails fast with a message naming the missing
 *       serializer and the YAML key.</li>
 * </ol>
 *
 * <p>Custom user serializers register themselves by shipping a
 * {@code NamedKafkaSerializer} via {@code META-INF/services}.
 */
public interface KafkaSerializer {

    /** Serialize the given value to bytes. Thread-safe. */
    byte[] serialize(Object value);

    /** Deserialize the given bytes into an instance of {@code type}. Thread-safe. */
    <T> T deserialize(byte[] bytes, Class<T> type);

    /**
     * Marker class used as the {@code serializer} annotation default. Means "use the
     * serializer named by {@code KafkaConfig.serializer}." Never instantiated.
     */
    final class Default implements KafkaSerializer {
        private Default() {
            throw new UnsupportedOperationException("marker only — never instantiated");
        }

        @Override
        public byte[] serialize(Object value) {
            throw new UnsupportedOperationException("marker only");
        }

        @Override
        public <T> T deserialize(byte[] bytes, Class<T> type) {
            throw new UnsupportedOperationException("marker only");
        }
    }
}
