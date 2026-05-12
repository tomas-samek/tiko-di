package io.tiko.kafka.processor.model;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Compile-time descriptor for one {@code @KafkaSource}-annotated bridge method.
 * Populated by the processor during round 1; consumed by the generator in round 1
 * write phase.
 *
 * @param enclosingClass         declaring {@code @Component} class
 * @param method                 the bridge method element
 * @param topic                  {@code @KafkaSource(topic)}
 * @param consumerGroup          {@code @KafkaSource(consumerGroup)}; empty means use YAML default
 * @param serializerClass        {@code @KafkaSource(serializer)} or {@code KafkaSerializer.Default.class}
 * @param eventName              {@code @EventTrigger(eventName)} on the same method
 * @param payloadType            the bridge method's first parameter type
 * @param producedEventType      the bridge method's return type
 * @param wantsKafkaContext      true if the method has a second parameter typed {@code KafkaContext}
 */
public record KafkaSourceDescriptor(
        TypeElement enclosingClass,
        ExecutableElement method,
        String topic,
        String consumerGroup,
        TypeMirror serializerClass,
        String eventName,
        TypeMirror payloadType,
        TypeMirror producedEventType,
        boolean wantsKafkaContext) {}
