package io.tiko.kafka.processor.model;

import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.TypeRef;

/**
 * Compile-time descriptor for one {@code @KafkaSink}-annotated bridge method.
 *
 * @param enclosingClass         declaring {@code @Component} class
 * @param method                 the bridge method
 * @param topic                  {@code @KafkaSink(topic)}
 * @param partitionKey           {@code @KafkaSink(partitionKey)}; empty means null key
 * @param serializerClass        {@code @KafkaSink(serializer)} or {@code KafkaSerializer.Default.class}
 * @param eventType              the bridge method's first parameter type (the local event)
 * @param producedPayloadType    the bridge method's return type (the Kafka payload)
 */
public record KafkaSinkDescriptor(
        ClassDecl enclosingClass,
        MethodDecl method,
        String topic,
        String partitionKey,
        TypeRef serializerClass,
        TypeRef eventType,
        TypeRef producedPayloadType) {}
