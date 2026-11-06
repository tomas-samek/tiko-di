package io.tiko.kafka.processor.validation;

import io.tiko.annotations.EventHandler;
import io.tiko.annotations.EventTrigger;
import io.tiko.kafka.annotations.KafkaSink;
import io.tiko.kafka.annotations.KafkaSource;
import io.tiko.kafka.processor.model.KafkaSinkDescriptor;
import io.tiko.kafka.processor.model.KafkaSourceDescriptor;
import io.tiko.processor.core.Diagnostics;
import io.tiko.processor.core.model.MethodDecl;
import java.util.List;

/**
 * Validates the required sibling annotations on each bridge method:
 * <ul>
 *   <li>{@code @KafkaSource} must coexist with exactly one {@code @EventTrigger} (else the
 *       message has nowhere to go, or repeats that carry no distinct meaning).</li>
 *   <li>{@code @KafkaSink} must NOT coexist with {@code @EventHandler}: the runtime
 *       subscribes its own callback for the sink event type via {@code EventBus.subscribe()};
 *       adding {@code @EventHandler} would double-fire the method — once as a local handler
 *       and once via the Kafka egress callback.</li>
 *   <li>{@code @KafkaSource} and {@code @KafkaSink} cannot coexist on the same method.</li>
 * </ul>
 */
public final class RequiredSiblingValidator {

    private static final String KAFKA_SOURCE = KafkaSource.class.getCanonicalName();
    private static final String KAFKA_SINK = KafkaSink.class.getCanonicalName();
    private static final String EVENT_TRIGGER = EventTrigger.class.getCanonicalName();
    private static final String EVENT_HANDLER = EventHandler.class.getCanonicalName();

    private RequiredSiblingValidator() {}

    public static boolean validate(
            Diagnostics diagnostics, List<KafkaSourceDescriptor> sources, List<KafkaSinkDescriptor> sinks) {
        boolean ok = true;
        for (KafkaSourceDescriptor s : sources) {
            MethodDecl m = s.method();
            if (m.annotation(KAFKA_SINK).isPresent()) {
                diagnostics.error(m.location(), "@KafkaSource and @KafkaSink cannot coexist on the same method.");
                ok = false;
                continue;
            }
            // Direct and @EventTriggers-contained triggers alike (repeating the annotation wraps it).
            int triggers = m.annotationsByType(EVENT_TRIGGER).size();
            if (triggers == 0) {
                diagnostics.error(
                        m.location(),
                        "@KafkaSource requires a sibling @EventTrigger(eventName = \"...\") on the same method "
                                + "so the deserialized event has a name to publish under.");
                ok = false;
            } else if (triggers > 1) {
                diagnostics.error(
                        m.location(),
                        "more than one @EventTrigger on a @KafkaSource is not supported: the bridge publishes "
                                + "its return value to the local bus once, and the name is only a label, "
                                + "so repeating it would not create distinct events.\n\n"
                                + "Suggested fixes:\n"
                                + "1. Keep a single @EventTrigger on this method.\n"
                                + "2. For genuinely distinct downstream events, return distinct types "
                                + "(chain from an @EventHandler on the bridge's event).");
                ok = false;
            }
        }
        for (KafkaSinkDescriptor s : sinks) {
            MethodDecl m = s.method();
            if (m.annotation(KAFKA_SOURCE).isPresent()) {
                // already reported above
                continue;
            }
            if (m.annotation(EVENT_HANDLER).isPresent()) {
                diagnostics.error(
                        m.location(),
                        "@KafkaSink must NOT be combined with @EventHandler. The Kafka runtime subscribes its "
                                + "own EventBus callback for the sink event type; adding @EventHandler would "
                                + "double-fire the method (once as a local handler, once as a Kafka egress "
                                + "callback). Remove @EventHandler from this @KafkaSink method.");
                ok = false;
            }
        }
        return ok;
    }
}
