package io.tiko.kafka.processor.validation;

import io.tiko.kafka.processor.model.KafkaSinkDescriptor;
import io.tiko.processor.core.Diagnostics;
import io.tiko.processor.core.KotlinConstructRules;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.Visibility;
import java.util.List;
import java.util.Optional;

/**
 * Validates that {@code @KafkaSink(partitionKey = "name")} resolves to a zero-arg, public,
 * non-{@code void} method (typically a record component accessor) on the bridge's return type —
 * the shape the generated {@code KafkaTransportBootstrap} key extractor calls. A non-public or
 * {@code void} accessor is reported on the sink method rather than left to surface as a raw javac
 * error inside generated code (#418). The key itself must be a Java identifier: the generator
 * writes it into the bootstrap as code.
 */
public final class PartitionKeyValidator {

    private static final String KEY_PREFIX = "@KafkaSink partitionKey '";

    private PartitionKeyValidator() {}

    public static boolean validate(Diagnostics diagnostics, List<KafkaSinkDescriptor> sinks) {
        boolean ok = true;
        for (KafkaSinkDescriptor s : sinks) {
            var problem = s.partitionKey().isEmpty() ? Optional.<String>empty() : problemWith(s);
            if (problem.isPresent()) {
                diagnostics.error(s.method().location(), problem.get());
                ok = false;
            }
        }
        return ok;
    }

    /** What is wrong with the sink's partition key, or empty when the generated bootstrap can call it. */
    private static Optional<String> problemWith(KafkaSinkDescriptor s) {
        var key = s.partitionKey();
        if (!KotlinConstructRules.isJavaIdentifier(key)) {
            // The generator writes the key as a method call, so it must be a name and nothing
            // else (SEC-5). A Kotlin function in backticks can carry a JVM name that is not one.
            return Optional.of(KEY_PREFIX + key
                    + "' is not a name Java can call: the generated bootstrap calls the"
                    + " accessor by that name. Point partitionKey at an accessor with a Java"
                    + " name, or give a Kotlin function one with @JvmName(\"...\").");
        }
        var returnDecl = s.producedPayloadType().declaration();
        if (returnDecl.isEmpty()) {
            return Optional.of(
                    "@KafkaSink(partitionKey = \"" + key + "\") cannot be applied to a non-declared return type.");
        }
        var payload = returnDecl.get().qualifiedName();
        Optional<MethodDecl> accessor = returnDecl.get().allMethods().stream()
                .filter(m -> m.parameters().isEmpty() && m.jvmName().equals(key))
                .findFirst();
        if (accessor.isEmpty()) {
            return Optional.of(KEY_PREFIX + key + "' does not resolve to a zero-arg method on " + payload + ".");
        }
        var resolvesTo = KEY_PREFIX + key + "' resolves to " + payload + "." + key + "(), which ";
        if (accessor.get().returnType().isVoid()) {
            return Optional.of(resolvesTo
                    + "returns void; a partition-key accessor must return a value the "
                    + "generated bootstrap can turn into a key. Point partitionKey at a non-void "
                    + "zero-arg accessor (e.g. a record component).");
        }
        if (accessor.get().visibility() != Visibility.PUBLIC) {
            return Optional.of(resolvesTo
                    + "is not public; the generated bootstrap calls it from another "
                    + "package. Make " + key + "() public (record components are public accessors).");
        }
        return Optional.empty();
    }
}
