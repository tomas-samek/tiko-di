package io.tiko.kafka.processor.validation;

import io.tiko.kafka.processor.model.KafkaSinkDescriptor;
import io.tiko.kafka.processor.model.KafkaSourceDescriptor;
import io.tiko.processor.core.Diagnostics;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.TypeRef;
import io.tiko.processor.core.model.Visibility;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rejects the bridges the generated bootstrap could not compile against (#753). The bootstrap
 * lives in {@code io.tiko.generated}: it looks the bridge class up, calls the bridge method, and
 * names the payload and event types as class literals and in casts. So each of them must be
 * visible from another package, and a type used as a class literal must not be parameterized.
 *
 * <p>Without this check each of these shapes surfaced as a raw javac error in the generated
 * {@code KafkaTransportBootstrap}, pointing at code the user did not write.
 */
public final class BridgeAccessValidator {

    private static final String BOOTSTRAP = "the generated Kafka bootstrap (package io.tiko.generated)";
    private static final String NOT_PUBLIC = ", which is not public: ";
    private static final String MAKE = "Make ";

    private BridgeAccessValidator() {}

    public static boolean validate(
            Diagnostics diagnostics, List<KafkaSourceDescriptor> sources, List<KafkaSinkDescriptor> sinks) {
        boolean ok = true;
        for (KafkaSourceDescriptor s : sources) {
            var bridge = new Bridge("@KafkaSource", s.enclosingClass(), s.method());
            ok &= report(diagnostics, bridge, firstProblem(bridge, s.payloadType(), Role.SOURCE_PAYLOAD, List.of()));
        }
        for (KafkaSinkDescriptor s : sinks) {
            var bridge = new Bridge("@KafkaSink", s.enclosingClass(), s.method());
            // The produced type is named only where the bootstrap reads a partition key off it.
            List<TypeRef> named = s.partitionKey().isEmpty() ? List.of() : List.of(s.producedPayloadType());
            ok &= report(diagnostics, bridge, firstProblem(bridge, s.eventType(), Role.SINK_EVENT, named));
        }
        return ok;
    }

    /** A bridge method, as messages name it: {@code @KafkaSource 'OrderConsumer.on'}. */
    private record Bridge(String annotation, ClassDecl owner, MethodDecl method) {
        String label() {
            return annotation + " '" + owner.className().simpleName() + "." + method.name() + "'";
        }
    }

    /** What is wrong and how to fix it. */
    private record Problem(String problem, String fix) {}

    /** The type the bootstrap uses as a class literal: what the bridge calls it, and why it needs a plain class. */
    private enum Role {
        SOURCE_PAYLOAD(
                "payload type",
                "the serializer is handed a Class to decode into, and a Class carries no type arguments",
                "Declare a class of your own for the payload"),
        SINK_EVENT(
                "event type",
                "an event is routed by its class, and a class carries no type arguments",
                "Publish an event class of your own");

        private final String noun;
        private final String reason;
        private final String fix;

        Role(String noun, String reason, String fix) {
            this.noun = noun;
            this.reason = reason;
            this.fix = fix;
        }
    }

    private static boolean report(Diagnostics diagnostics, Bridge bridge, Optional<Problem> problem) {
        problem.ifPresent(found -> diagnostics.error(
                bridge.method().location(), found.problem() + "\n\nSuggested fixes:\n1. " + found.fix()));
        return problem.isEmpty();
    }

    /**
     * The first reason the bootstrap could not compile against this bridge, outermost first: its
     * class, its method, the class-literal type, then the other types the bootstrap names.
     *
     * @param literal the payload or event type, used as a class literal; {@code null} when the
     *     method has no parameter, which {@link BridgeMethodShapeValidator} reports
     */
    private static Optional<Problem> firstProblem(Bridge bridge, TypeRef literal, Role role, List<TypeRef> named) {
        var hiddenOwner = firstHidden(bridge.owner());
        if (hiddenOwner.isPresent()) {
            var name = hiddenOwner.get().qualifiedName();
            return Optional.of(new Problem(
                    bridge.label() + " is declared in " + name + NOT_PUBLIC + BOOTSTRAP + " looks the bridge up by it.",
                    MAKE + name + " public."));
        }
        if (!isVisible(bridge.method().visibility())) {
            return Optional.of(new Problem(
                    bridge.label() + " is not public: " + BOOTSTRAP + " calls it.",
                    MAKE + "'" + bridge.method().name() + "' public."));
        }
        var types = new ArrayList<TypeRef>();
        if (literal != null) {
            if (!literal.typeArguments().isEmpty()) {
                return Optional.of(parameterized(bridge, literal, role));
            }
            types.add(literal);
        }
        types.addAll(named);
        return types.stream()
                .flatMap(type -> firstHidden(type).stream())
                .findFirst()
                .map(hidden -> new Problem(
                        bridge.label() + " uses " + hidden.qualifiedName() + NOT_PUBLIC + BOOTSTRAP + " names it.",
                        MAKE + hidden.qualifiedName() + " public."));
    }

    private static Problem parameterized(Bridge bridge, TypeRef literal, Role role) {
        return new Problem(
                bridge.label() + " takes the " + role.noun + " " + literal.displayName() + ", which is parameterized: "
                        + role.reason + ".",
                role.fix + " (a record with a " + literal.displayName() + " component) and take that.");
    }

    /** The first class in the type, type arguments included, that another package cannot name. */
    private static Optional<ClassDecl> firstHidden(TypeRef type) {
        var hidden = type.declaration().flatMap(BridgeAccessValidator::firstHidden);
        if (hidden.isPresent()) {
            return hidden;
        }
        return type.typeArguments().stream()
                .flatMap(argument -> firstHidden(argument).stream())
                .findFirst();
    }

    /** The class itself or the first class it is nested in that another package cannot name. */
    private static Optional<ClassDecl> firstHidden(ClassDecl decl) {
        for (var current = Optional.of(decl);
                current.isPresent();
                current = current.get().enclosingClass()) {
            if (!isVisible(current.get().visibility())) {
                return current;
            }
        }
        return Optional.empty();
    }

    /**
     * Public, or Kotlin {@code internal}: public on the JVM and visible to the module the bootstrap
     * is compiled into. The DI processor applies the same rule to what its generated code calls.
     */
    private static boolean isVisible(Visibility visibility) {
        return visibility == Visibility.PUBLIC || visibility == Visibility.KOTLIN_INTERNAL;
    }
}
