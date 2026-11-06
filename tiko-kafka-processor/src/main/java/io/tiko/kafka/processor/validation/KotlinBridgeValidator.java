package io.tiko.kafka.processor.validation;

import io.tiko.kafka.annotations.KafkaSink;
import io.tiko.kafka.annotations.KafkaSource;
import io.tiko.processor.core.KotlinConstructRules;
import io.tiko.processor.core.KotlinConstructRules.Caller;
import io.tiko.processor.core.KotlinConstructRules.Violation;
import io.tiko.processor.core.ProcessingEnv;
import io.tiko.processor.core.model.MethodDecl;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Rejects the Kotlin bridges the generated bootstrap can't call (the Kotlin construct rule, the
 * same {@link KotlinConstructRules} the DI processor applies): a top-level function or one inside
 * an {@code object} / {@code companion object} (no component instance to call it on), an
 * extension function (a receiver nothing passes), and a {@code suspend} function (called outside
 * any coroutine; {@code suspend} sources come with K2). Each is a located error in the Tiko
 * format.
 *
 * <p>It runs on each round's bridges <em>before</em> collection, which assumes what it rules out
 * (a bridge always has a declaring class). On javac none of these constructs occur, so it reports
 * nothing for Java sources.
 */
public final class KotlinBridgeValidator {

    private static final String KAFKA_SOURCE = KafkaSource.class.getCanonicalName();
    private static final String KAFKA_SINK = KafkaSink.class.getCanonicalName();

    /** The generated code calling bridges. */
    private static final Caller BOOTSTRAP =
            new Caller("the generated Kafka bootstrap", "it passes only the payload (and the KafkaContext)");

    private KotlinBridgeValidator() {}

    /** Reports this round's unsupported Kotlin bridges and returns them, for collection to skip. */
    public static Set<MethodDecl> rejected(ProcessingEnv env) {
        var rejected = new HashSet<MethodDecl>();
        for (var annotation : List.of(KAFKA_SOURCE, KAFKA_SINK)) {
            for (var method : env.methodsAnnotatedWith(annotation)) {
                if (rejected.contains(method)) {
                    continue;
                }
                firstViolation(method, annotation).ifPresent(violation -> {
                    env.diagnostics().error(violation.location(), violation.message());
                    rejected.add(method);
                });
            }
        }
        return Set.copyOf(rejected);
    }

    private static Optional<Violation> firstViolation(MethodDecl method, String annotation) {
        return KotlinConstructRules.needsComponentInstance(method, annotation)
                .or(() -> KotlinConstructRules.extension(method, annotation, BOOTSTRAP))
                .or(() -> annotation.equals(KAFKA_SOURCE)
                        ? KotlinConstructRules.suspendUntilK2(method, annotation, "bridges")
                        : KotlinConstructRules.suspendCalledDirectly(method, annotation, BOOTSTRAP));
    }
}
