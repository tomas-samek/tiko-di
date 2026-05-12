package io.tiko.kafka.processor.validation;

import io.tiko.annotations.EventHandler;
import io.tiko.annotations.EventTrigger;
import io.tiko.kafka.annotations.KafkaSink;
import io.tiko.kafka.annotations.KafkaSource;
import io.tiko.kafka.processor.model.KafkaSinkDescriptor;
import io.tiko.kafka.processor.model.KafkaSourceDescriptor;
import java.util.List;
import javax.annotation.processing.Messager;
import javax.lang.model.element.ExecutableElement;
import javax.tools.Diagnostic;

/**
 * Validates the required sibling annotations on each bridge method:
 * <ul>
 *   <li>{@code @KafkaSource} must coexist with {@code @EventTrigger} (else the message
 *       has nowhere to go).</li>
 *   <li>{@code @KafkaSink} must coexist with {@code @EventHandler} (else the runtime has
 *       no event to subscribe to).</li>
 *   <li>{@code @KafkaSource} and {@code @KafkaSink} cannot coexist on the same method.</li>
 * </ul>
 */
public final class RequiredSiblingValidator {

    private RequiredSiblingValidator() {}

    public static boolean validate(
            Messager messager, List<KafkaSourceDescriptor> sources, List<KafkaSinkDescriptor> sinks) {
        boolean ok = true;
        for (KafkaSourceDescriptor s : sources) {
            ExecutableElement m = s.method();
            if (m.getAnnotation(KafkaSink.class) != null) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR, "@KafkaSource and @KafkaSink cannot coexist on the same method.", m);
                ok = false;
                continue;
            }
            if (m.getAnnotation(EventTrigger.class) == null) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@KafkaSource requires a sibling @EventTrigger(eventName = \"...\") on the same method "
                                + "so the deserialized event has a name to publish under.",
                        m);
                ok = false;
            }
        }
        for (KafkaSinkDescriptor s : sinks) {
            ExecutableElement m = s.method();
            if (m.getAnnotation(KafkaSource.class) != null) {
                // already reported above
                continue;
            }
            if (m.getAnnotation(EventHandler.class) == null) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@KafkaSink requires a sibling @EventHandler on the same method so the framework "
                                + "knows which local event triggers the send.",
                        m);
                ok = false;
            }
        }
        return ok;
    }
}
