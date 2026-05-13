package io.tiko.kafka.runtime;

import io.tiko.Container;
import io.tiko.kafka.KafkaSerializer;

/**
 * Runtime descriptor for one {@code @KafkaSink} bridge method.
 *
 * @param topic              destination topic
 * @param partitionKey       empty string means null Kafka key
 * @param eventType          first parameter type of the sink method (the local event)
 * @param serializerClass    KafkaSerializer.Default.class means "use named YAML default"
 * @param dispatcher         invokes the bridge; returns the payload to send
 */
public record GeneratedSinkDescriptor(
        String topic,
        String partitionKey,
        Class<?> eventType,
        Class<? extends KafkaSerializer> serializerClass,
        SinkDispatcher dispatcher) {

    @FunctionalInterface
    public interface SinkDispatcher {
        Object dispatch(Container container, Object event);
    }
}
