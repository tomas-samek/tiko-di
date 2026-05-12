package io.tiko.kafka.annotations;

import io.tiko.kafka.KafkaSerializer;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a Kafka outbound bridge. The method must also carry
 * {@code @io.tiko.annotations.EventHandler} (validated at compile time). The runtime
 * subscribes a callback for the handler's event type: when a matching event is published
 * locally, the callback invokes this method, serialises the return value with the resolved
 * {@link KafkaSerializer}, and sends a {@code ProducerRecord} to the named topic.
 *
 * <p>Local handlers always run before any Kafka sink callback (sinks register after the
 * generated {@code EventRegistry} during {@code TransportBootstrap.start()}), so a sink
 * throw never blocks local processing.
 *
 * <p>For non-blocking sends, annotate the same method {@code @EventHandler(async = true)} —
 * the existing async-handler machinery moves the sink invocation onto the event executor.
 * There is no Kafka-specific async knob.
 *
 * <p>The enclosing class must be {@code @Component(scope = Scope.SINGLETON)}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface KafkaSink {

    /** Topic to send to. Required. */
    String topic();

    /**
     * Name of a record-component accessor (or zero-arg public method) on the return type
     * whose value is used as the Kafka message key (partition key). Empty string ({@code ""},
     * the default) sends with a {@code null} key — Kafka's default round-robin partitioning
     * applies. Validated at compile time.
     */
    String partitionKey() default "";

    /**
     * Serializer override. Default is the {@link KafkaSerializer.Default} marker, which
     * means "use the serializer named by {@code KafkaConfig.serializer}." Setting this to
     * a concrete class pins the serialiser for this sink regardless of YAML config.
     */
    Class<? extends KafkaSerializer<?>> serializer() default KafkaSerializer.Default.class;
}
