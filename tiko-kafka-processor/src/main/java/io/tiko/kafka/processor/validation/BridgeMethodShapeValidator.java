package io.tiko.kafka.processor.validation;

import io.tiko.kafka.processor.model.KafkaSinkDescriptor;
import io.tiko.kafka.processor.model.KafkaSourceDescriptor;
import java.util.List;
import javax.annotation.processing.Messager;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;

/**
 * Validates bridge method shapes:
 * <ul>
 *   <li>{@code @KafkaSource}: at least one parameter (the payload). Optional second
 *       parameter must be {@code io.tiko.kafka.KafkaContext} (exact type, no subtype).</li>
 *   <li>{@code @KafkaSink}: non-void return type. Exactly one parameter (the local event).</li>
 * </ul>
 */
public final class BridgeMethodShapeValidator {

    private static final String KAFKA_CONTEXT_FQN = "io.tiko.kafka.KafkaContext";

    private BridgeMethodShapeValidator() {}

    public static boolean validate(
            Messager messager, List<KafkaSourceDescriptor> sources, List<KafkaSinkDescriptor> sinks) {
        boolean ok = true;
        for (KafkaSourceDescriptor s : sources) {
            ExecutableElement m = s.method();
            List<? extends VariableElement> params = m.getParameters();
            if (params.isEmpty()) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@KafkaSource method must declare at least one parameter (the deserialized payload).",
                        m);
                ok = false;
                continue;
            }
            if (params.size() >= 2) {
                if (!params.get(1).asType().toString().equals(KAFKA_CONTEXT_FQN)) {
                    messager.printMessage(
                            Diagnostic.Kind.ERROR,
                            "KafkaContext, if present, must be the second parameter and typed exactly "
                                    + KAFKA_CONTEXT_FQN + " (no subtype).",
                            m);
                    ok = false;
                }
                if (params.size() > 2) {
                    messager.printMessage(
                            Diagnostic.Kind.ERROR,
                            "@KafkaSource method accepts at most two parameters (payload[, KafkaContext]).",
                            m);
                    ok = false;
                }
            }
            if (m.getReturnType().getKind() == TypeKind.VOID) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@KafkaSource method must return the local event payload (non-void).",
                        m);
                ok = false;
            }
        }
        for (KafkaSinkDescriptor s : sinks) {
            ExecutableElement m = s.method();
            if (m.getParameters().size() != 1) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "@KafkaSink method must declare exactly one parameter (the local event).",
                        m);
                ok = false;
            }
            if (m.getReturnType().getKind() == TypeKind.VOID) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR, "@KafkaSink method must return a non-void payload to send to Kafka.", m);
                ok = false;
            }
        }
        return ok;
    }
}
