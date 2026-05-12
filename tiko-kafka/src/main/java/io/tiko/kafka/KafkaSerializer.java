package io.tiko.kafka;

/**
 * Serializer SPI for Kafka transport. MVP ships a single concrete impl,
 * {@link io.tiko.kafka.serializer.JsonKafkaSerializer JsonKafkaSerializer}; future
 * modules (e.g. {@code tiko-kafka-avro}) ship additional impls.
 *
 * <p>Resolution order, per source/sink:
 * <ol>
 *   <li>Annotation parameter set to a concrete class other than {@link Default} →
 *       use that impl (looked up by class via {@code container.get(...)} if registered
 *       as a {@code @Component}, otherwise instantiated reflectively as a no-arg POJO).</li>
 *   <li>Otherwise: the serializer named by {@code KafkaConfig.serializer} (default
 *       {@code "json"}) — looked up via {@code ServiceLoader<NamedKafkaSerializer>} by
 *       name.</li>
 *   <li>Unknown name at startup → container fails fast with a message naming the missing
 *       serializer and the YAML key.</li>
 * </ol>
 *
 * <p>Custom user serializers register themselves the same way as the bundled JSON one —
 * by shipping a {@code NamedKafkaSerializer} via {@code META-INF/services}.
 */
public interface KafkaSerializer<T> {

    /**
     * Serialize the given value to bytes. Implementations must be thread-safe — the
     * runtime calls this from publisher threads (sinks) and the consumer thread (rare;
     * only for round-trips that re-serialize).
     */
    byte[] serialize(T value);

    /**
     * Deserialize the given bytes into an instance of {@code type}. Called from the
     * consumer thread per record. Implementations must be thread-safe.
     */
    T deserialize(byte[] bytes, Class<T> type);

    /**
     * Marker class used as the {@code serializer} annotation default. Means "use the
     * serializer named by {@code KafkaConfig.serializer}." Never instantiated; the
     * runtime checks the class literal and substitutes the resolved impl.
     */
    final class Default implements KafkaSerializer<Object> {
        private Default() {
            throw new UnsupportedOperationException("marker only — never instantiated");
        }

        @Override
        public byte[] serialize(Object value) {
            throw new UnsupportedOperationException("marker only");
        }

        @Override
        public Object deserialize(byte[] bytes, Class<Object> type) {
            throw new UnsupportedOperationException("marker only");
        }
    }
}
