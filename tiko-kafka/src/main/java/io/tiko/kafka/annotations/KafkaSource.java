package io.tiko.kafka.annotations;

import io.tiko.kafka.CommitMode;
import io.tiko.kafka.KafkaSerializer;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a Kafka inbound bridge. The runtime polls the named topic, deserialises
 * each record into the method's first parameter type via the resolved {@link KafkaSerializer},
 * invokes the method (with an optional {@link io.tiko.kafka.KafkaContext} as the second
 * parameter), and publishes the return value to the local {@code EventBus}.
 *
 * <p>A method that returns {@code null} consumes the record without publishing anything: the
 * offset is committed and the record is not redelivered (in Kotlin, declare the return type
 * nullable). A method that throws takes the ingest-error path instead
 * ({@code tiko.kafka.poison-record-policy}, or a registered {@code KafkaIngestErrorDecider}).
 * A record without a value (a tombstone) is not handed to the method: its offset is committed
 * and nothing is published.
 *
 * <p>A sibling {@code @io.tiko.annotations.EventTrigger(eventName = "...")} on the same
 * method is required and declares the tracing name for the published event. A second
 * {@code @EventTrigger} on the method is a compile error. The actual
 * dispatch on the local bus is by return-type class, not by name. See the spec
 * "Trigger semantics on bridge methods — MVP scope cut".
 *
 * <p>The enclosing class must be {@code @Component(scope = Scope.SINGLETON)}, validated at
 * compile time by {@code tiko-kafka-processor}. Each record is handled in its own unit of work,
 * opened around the call to this method.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface KafkaSource {

    /** Topic to subscribe to. Required. */
    String topic();

    /**
     * Consumer group id. Empty string ({@code ""}, the default) means "use
     * {@code KafkaConfig.consumerGroup} from YAML." Per-source override enables future
     * topic-vs-queue patterns by splitting/sharing consumer groups across handlers.
     */
    String consumerGroup() default "";

    /**
     * Serializer override. Default is the {@link KafkaSerializer.Default} marker, which
     * means "use the serializer named by {@code KafkaConfig.serializer}." Setting this to
     * a concrete class pins the deserialiser for this source regardless of YAML config.
     */
    Class<? extends KafkaSerializer> serializer() default KafkaSerializer.Default.class;

    /** Commit strategy. MVP ships {@link CommitMode#PER_RECORD} only. */
    CommitMode commitMode() default CommitMode.PER_RECORD;
}
