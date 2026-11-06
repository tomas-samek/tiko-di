package io.tiko.kafka.processor.model;

import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.TypeRef;

/**
 * Compile-time descriptor for one {@code @KafkaSource}-annotated bridge method.
 * Built by {@code KafkaPipeline.process}; consumed by the generators in
 * {@code KafkaPipeline.finish}.
 *
 * @param enclosingClass         declaring {@code @Component} class
 * @param method                 the bridge method
 * @param topic                  {@code @KafkaSource(topic)}
 * @param consumerGroup          {@code @KafkaSource(consumerGroup)}; empty means use YAML default
 * @param serializerClass        {@code @KafkaSource(serializer)} or {@code KafkaSerializer.Default.class}
 * @param eventName              {@code @EventTrigger(eventName)} on the same method
 * @param payloadType            the bridge method's first parameter type
 * @param producedEventType      the bridge method's return type
 * @param wantsKafkaContext      true if the method has a second parameter typed {@code KafkaContext}
 */
public record KafkaSourceDescriptor(
        ClassDecl enclosingClass,
        MethodDecl method,
        String topic,
        String consumerGroup,
        TypeRef serializerClass,
        String eventName,
        TypeRef payloadType,
        TypeRef producedEventType,
        boolean wantsKafkaContext) {}
