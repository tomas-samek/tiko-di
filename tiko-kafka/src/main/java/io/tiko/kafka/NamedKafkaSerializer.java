package io.tiko.kafka;

/**
 * Auxiliary SPI: binds a YAML config name (e.g. {@code "json"}) to a {@link KafkaSerializer}
 * impl. Discovered via {@code ServiceLoader<NamedKafkaSerializer>} at container startup.
 *
 * <p>MVP ships one impl: {@code io.tiko.kafka.serializer.JsonNamedKafkaSerializer} registered
 * with name {@code "json"}.
 */
public interface NamedKafkaSerializer {

    /** YAML-config name this serializer answers to, e.g. {@code "json"}. */
    String name();

    /** The serializer impl. Cached for the container's lifetime. */
    KafkaSerializer serializer();
}
