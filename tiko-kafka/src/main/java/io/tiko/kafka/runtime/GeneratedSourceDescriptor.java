package io.tiko.kafka.runtime;

import io.tiko.Container;
import io.tiko.kafka.KafkaContext;
import io.tiko.kafka.KafkaSerializer;

/**
 * Runtime descriptor for one {@code @KafkaSource} bridge method, populated by the
 * generated {@code KafkaTransportBootstrap}. The dispatcher field is a method reference
 * to a generated private method on the bootstrap class that invokes the user's bridge.
 *
 * @param topic              source topic
 * @param consumerGroup      empty string means "use KafkaConfig.consumerGroup"
 * @param eventName          tracing label (not used for dispatch in MVP)
 * @param payloadType        first parameter type of the bridge method
 * @param serializerClass    KafkaSerializer.Default.class means "use named YAML default"
 * @param wantsKafkaContext  true if the bridge declares the optional 2nd KafkaContext parameter
 * @param dispatcher         invokes the bridge method; returns the local event payload to publish
 */
public record GeneratedSourceDescriptor(
        String topic,
        String consumerGroup,
        String eventName,
        Class<?> payloadType,
        Class<? extends KafkaSerializer> serializerClass,
        boolean wantsKafkaContext,
        SourceDispatcher dispatcher) {

    @FunctionalInterface
    public interface SourceDispatcher {
        Object dispatch(Container container, Object payload, KafkaContext ctx);
    }
}
