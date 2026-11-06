package io.tiko.kafka.processor.validation;

import io.tiko.kafka.processor.model.KafkaSinkDescriptor;
import io.tiko.processor.core.Diagnostics;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.Visibility;
import java.util.List;

/**
 * Validates that {@code @KafkaSink(partitionKey = "name")} resolves to a zero-arg, public,
 * non-{@code void} method (typically a record component accessor) on the bridge's return type —
 * the shape the generated {@code KafkaTransportBootstrap} key extractor calls. A non-public or
 * {@code void} accessor is reported on the sink method rather than left to surface as a raw javac
 * error inside generated code (#418).
 */
public final class PartitionKeyValidator {

    private static final String KEY_PREFIX = "@KafkaSink partitionKey '";

    private PartitionKeyValidator() {}

    public static boolean validate(Diagnostics diagnostics, List<KafkaSinkDescriptor> sinks) {
        boolean ok = true;
        for (KafkaSinkDescriptor s : sinks) {
            if (s.partitionKey().isEmpty()) continue;
            var returnDecl = s.producedPayloadType().declaration();
            if (returnDecl.isEmpty()) {
                diagnostics.error(
                        s.method().location(),
                        "@KafkaSink(partitionKey = \""
                                + s.partitionKey()
                                + "\") cannot be applied to a non-declared return type.");
                ok = false;
                continue;
            }
            var returnElement = returnDecl.get();
            MethodDecl accessor = null;
            for (MethodDecl m : returnElement.allMethods()) {
                if (m.parameters().isEmpty() && m.jvmName().equals(s.partitionKey())) {
                    accessor = m;
                    break;
                }
            }
            if (accessor == null) {
                diagnostics.error(
                        s.method().location(),
                        KEY_PREFIX
                                + s.partitionKey()
                                + "' does not resolve to a zero-arg "
                                + "method on "
                                + returnElement.qualifiedName()
                                + ".");
                ok = false;
            } else if (accessor.returnType().isVoid()) {
                diagnostics.error(
                        s.method().location(),
                        KEY_PREFIX
                                + s.partitionKey()
                                + "' resolves to "
                                + returnElement.qualifiedName()
                                + "."
                                + s.partitionKey()
                                + "(), which returns void; a partition-key accessor must return a value the "
                                + "generated bootstrap can turn into a key. Point partitionKey at a non-void "
                                + "zero-arg accessor (e.g. a record component).");
                ok = false;
            } else if (accessor.visibility() != Visibility.PUBLIC) {
                diagnostics.error(
                        s.method().location(),
                        KEY_PREFIX
                                + s.partitionKey()
                                + "' resolves to "
                                + returnElement.qualifiedName()
                                + "."
                                + s.partitionKey()
                                + "(), which is not public; the generated bootstrap calls it from another "
                                + "package. Make "
                                + s.partitionKey()
                                + "() public (record components are public accessors).");
                ok = false;
            }
        }
        return ok;
    }
}
