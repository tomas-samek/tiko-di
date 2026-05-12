package io.tiko.kafka.annotations;

import io.tiko.kafka.KafkaSerializer;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a Kafka outbound bridge. The enclosing class must be
 * {@code @Component(scope = Scope.SINGLETON)}.
 *
 * <p>The runtime subscribes an {@code EventBus} callback for the method's first parameter
 * type: when a matching event is published locally, the callback invokes this method,
 * serialises the return value with the resolved {@link KafkaSerializer}, and sends a
 * {@code ProducerRecord} to the named topic.
 *
 * <p><strong>Do NOT also annotate this method with {@code @EventHandler}.</strong>
 * The runtime subscription via {@code EventBus.subscribe()} is the exclusive hook; adding
 * {@code @EventHandler} would double-fire the method (once as a local handler, once as the
 * Kafka egress callback). This constraint is enforced at compile time by
 * {@code tiko-kafka-processor}.
 *
 * <p>Local {@code @EventHandler} methods always run before any Kafka sink callback (sinks
 * register after the generated {@code EventRegistry} during {@code TransportBootstrap.start()}),
 * so a sink throw never blocks local processing.
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
    Class<? extends KafkaSerializer> serializer() default KafkaSerializer.Default.class;
}
