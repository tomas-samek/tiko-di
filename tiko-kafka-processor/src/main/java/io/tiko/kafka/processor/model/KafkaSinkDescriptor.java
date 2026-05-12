package io.tiko.kafka.processor.model;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * Compile-time descriptor for one {@code @KafkaSink}-annotated bridge method.
 *
 * @param enclosingClass         declaring {@code @Component} class
 * @param method                 the bridge method element
 * @param topic                  {@code @KafkaSink(topic)}
 * @param partitionKey           {@code @KafkaSink(partitionKey)}; empty means null key
 * @param serializerClass        {@code @KafkaSink(serializer)} or {@code KafkaSerializer.Default.class}
 * @param eventType              the bridge method's first parameter type (the local event)
 * @param producedPayloadType    the bridge method's return type (the Kafka payload)
 */
public record KafkaSinkDescriptor(
        TypeElement enclosingClass,
        ExecutableElement method,
        String topic,
        String partitionKey,
        TypeMirror serializerClass,
        TypeMirror eventType,
        TypeMirror producedPayloadType) {}
