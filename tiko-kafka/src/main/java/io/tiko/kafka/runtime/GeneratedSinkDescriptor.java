package io.tiko.kafka.runtime;

import io.tiko.Container;
import io.tiko.kafka.KafkaSerializer;

/**
 * Runtime descriptor for one {@code @KafkaSink} bridge method.
 *
 * @param topic              destination topic
 * @param partitionKey       accessor name from the annotation; empty string means null Kafka key
 * @param eventType          first parameter type of the sink method (the local event)
 * @param serializerClass    KafkaSerializer.Default.class means "use named YAML default"
 * @param dispatcher         invokes the bridge; returns the payload to send
 * @param keyExtractor       resolves the Kafka record key from the payload; generated at
 *                           compile time from the validated {@code partitionKey} accessor —
 *                           never null, returns null when the record has no key
 */
public record GeneratedSinkDescriptor(
        String topic,
        String partitionKey,
        Class<?> eventType,
        Class<? extends KafkaSerializer> serializerClass,
        SinkDispatcher dispatcher,
        KeyExtractor keyExtractor) {

    @FunctionalInterface
    public interface SinkDispatcher {
        Object dispatch(Container container, Object event);
    }

    @FunctionalInterface
    public interface KeyExtractor {
        String extract(Object payload);
    }
}
