package io.tiko.kafka.processor.validation;

import io.tiko.kafka.processor.model.KafkaSinkDescriptor;
import io.tiko.kafka.processor.model.KafkaSourceDescriptor;
import io.tiko.processor.core.Diagnostics;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.TypeRef;
import java.util.List;

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
            Diagnostics diagnostics, List<KafkaSourceDescriptor> sources, List<KafkaSinkDescriptor> sinks) {
        boolean ok = true;
        for (KafkaSourceDescriptor s : sources) {
            MethodDecl m = s.method();
            var params = m.parameters();
            if (params.isEmpty()) {
                diagnostics.error(
                        m.location(),
                        "@KafkaSource method must declare at least one parameter (the deserialized payload).");
                ok = false;
                continue;
            }
            if (params.size() >= 2) {
                if (!isKafkaContext(params.get(1).type())) {
                    diagnostics.error(
                            m.location(),
                            "KafkaContext, if present, must be the second parameter and typed exactly "
                                    + KAFKA_CONTEXT_FQN + " (no subtype).");
                    ok = false;
                }
                if (params.size() > 2) {
                    diagnostics.error(
                            m.location(),
                            "@KafkaSource method accepts at most two parameters (payload[, KafkaContext]).");
                    ok = false;
                }
            }
            if (m.returnType().isVoid()) {
                diagnostics.error(m.location(), "@KafkaSource method must return the local event payload (non-void).");
                ok = false;
            }
        }
        for (KafkaSinkDescriptor s : sinks) {
            MethodDecl m = s.method();
            if (m.parameters().size() != 1) {
                diagnostics.error(
                        m.location(), "@KafkaSink method must declare exactly one parameter (the local event).");
                ok = false;
            }
            if (m.returnType().isVoid()) {
                diagnostics.error(m.location(), "@KafkaSink method must return a non-void payload to send to Kafka.");
                ok = false;
            }
        }
        return ok;
    }

    /** Exactly {@code KafkaContext}: a declared type, so neither a subtype nor a type variable bounded by it. */
    public static boolean isKafkaContext(TypeRef type) {
        return type.declaration().isPresent() && type.erasedName().equals(KAFKA_CONTEXT_FQN);
    }
}
