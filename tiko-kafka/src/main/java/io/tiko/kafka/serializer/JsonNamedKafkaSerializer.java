package io.tiko.kafka.serializer;

import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.NamedKafkaSerializer;

/**
 * Binds the YAML name {@code "json"} to {@link JsonKafkaSerializer}. Discovered via
 * {@code ServiceLoader<NamedKafkaSerializer>}.
 */
public final class JsonNamedKafkaSerializer implements NamedKafkaSerializer {

    private static final JsonKafkaSerializer INSTANCE = new JsonKafkaSerializer();

    @Override
    public String name() {
        return "json";
    }

    @Override
    public KafkaSerializer serializer() {
        return INSTANCE;
    }
}
