package io.tiko.kafka;

/**
 * Auxiliary SPI: binds a YAML config name (e.g. {@code "json"}) to a {@link KafkaSerializer}
 * impl. Discovered via {@code ServiceLoader<NamedKafkaSerializer>} at container startup so
 * the value of {@code tiko.kafka.serializer} can resolve to an impl without reflection on
 * the runtime hot path.
 *
 * <p>MVP ships one impl: {@code io.tiko.kafka.serializer.JsonNamedKafkaSerializer} registered
 * with name {@code "json"}. Future modules ({@code tiko-kafka-avro}, ...) ship their own
 * impl + {@code META-INF/services} entry.
 */
public interface NamedKafkaSerializer {

    /** YAML-config name this serializer answers to, e.g. {@code "json"}. */
    String name();

    /** The serializer impl. The runtime caches the returned instance for the container's lifetime. */
    KafkaSerializer<?> serializer();
}
