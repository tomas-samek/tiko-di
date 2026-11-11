package io.tiko.kafka.processor;

import io.tiko.annotations.EventTrigger;
import io.tiko.kafka.annotations.KafkaSink;
import io.tiko.kafka.annotations.KafkaSource;
import io.tiko.kafka.processor.generator.KafkaTopologyWriter;
import io.tiko.kafka.processor.generator.KafkaTransportBootstrapGenerator;
import io.tiko.kafka.processor.model.KafkaSinkDescriptor;
import io.tiko.kafka.processor.model.KafkaSourceDescriptor;
import io.tiko.kafka.processor.validation.BridgeMethodShapeValidator;
import io.tiko.kafka.processor.validation.KotlinBridgeValidator;
import io.tiko.kafka.processor.validation.KotlinDataClassPayloadValidator;
import io.tiko.kafka.processor.validation.PartitionKeyValidator;
import io.tiko.kafka.processor.validation.RequiredSiblingValidator;
import io.tiko.kafka.processor.validation.SingletonBridgeValidator;
import io.tiko.processor.core.Diagnostics;
import io.tiko.processor.core.ProcessingEnv;
import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.TypeRef;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * The Kafka processor's logic, independent of the compiler frontend: it collects the
 * {@code @KafkaSource} / {@code @KafkaSink} bridge methods, validates them, and emits
 * {@code io.tiko.generated.KafkaTransportBootstrap}, its
 * {@code META-INF/services/io.tiko.TransportBootstrap} entry and the
 * {@code META-INF/tiko/topology-kafka.json} fragment.
 *
 * <p>One instance lives for the whole compilation and holds the cross-round state: the bridges
 * collected so far, whether any of them failed validation, and whether the output was written.
 * Validation errors are tracked here, not through {@link Diagnostics#hasErrors()}, because a
 * frontend creates a fresh {@link ProcessingEnv} for every round.
 *
 * <p>Lifecycle: {@link #process} once per round, {@link #finish} at least once at the end.
 * {@code finish} is idempotent: the first call writes the output (unless a bridge failed
 * validation, or nothing was collected), every later call does nothing, and {@code process}
 * collects nothing more after it.
 *
 * <p>Timing per frontend: javac calls {@code finish} in the same round as {@code process}, the
 * first round that carries bridges, and again (as a no-op) when processing is over. Files written
 * in javac's final round are not processed further and draw a "created in the last round"
 * warning, which fails {@code -Xlint:processing -Werror} builds. KSP calls {@code finish} in the
 * first round whose new files carry no bridge after a round that did, else from
 * {@code SymbolProcessor.finish()}, whose output KSP still compiles.
 */
public final class KafkaPipeline {

    private static final String KAFKA_SOURCE = KafkaSource.class.getCanonicalName();
    private static final String KAFKA_SINK = KafkaSink.class.getCanonicalName();
    private static final String EVENT_TRIGGER = EventTrigger.class.getCanonicalName();

    /** The bridge annotations: a round carrying neither has nothing for this pipeline. */
    public static final Set<String> BRIDGE_ANNOTATIONS = Set.of(KAFKA_SOURCE, KAFKA_SINK);

    /**
     * Whether a round carries a bridge annotation ({@link #BRIDGE_ANNOTATIONS}): the one rule both
     * frontends use to decide which rounds go to {@link #process}.
     *
     * @param roundCarries whether the round carries the annotation with this qualified name
     */
    public static boolean isEntryPointRound(Predicate<String> roundCarries) {
        return BRIDGE_ANNOTATIONS.stream().anyMatch(roundCarries);
    }

    private final List<KafkaSourceDescriptor> sources = new ArrayList<>();
    private final List<KafkaSinkDescriptor> sinks = new ArrayList<>();
    private boolean failed;
    private boolean finished;

    /** Collects and validates this round's bridges. Validation errors are reported on {@code env}. */
    public void process(ProcessingEnv env) {
        if (finished) return;
        var diagnostics = env.diagnostics();
        var roundSources = new ArrayList<KafkaSourceDescriptor>();
        var roundSinks = new ArrayList<KafkaSinkDescriptor>();
        // Before collection: it rules out what collection assumes (a bridge has a declaring class).
        var rejected = KotlinBridgeValidator.rejected(env);
        boolean ok = rejected.isEmpty();
        for (MethodDecl method : env.methodsAnnotatedWith(KAFKA_SOURCE)) {
            if (rejected.contains(method)) continue;
            var source = sourceDescriptor(method, diagnostics);
            ok &= source.isPresent();
            source.ifPresent(roundSources::add);
        }
        for (MethodDecl method : env.methodsAnnotatedWith(KAFKA_SINK)) {
            if (rejected.contains(method)) continue;
            var sink = sinkDescriptor(method, diagnostics);
            ok &= sink.isPresent();
            sink.ifPresent(roundSinks::add);
        }
        if (!roundSources.isEmpty() || !roundSinks.isEmpty()) {
            ok &= SingletonBridgeValidator.validate(diagnostics, roundSources, roundSinks);
            ok &= RequiredSiblingValidator.validate(diagnostics, roundSources, roundSinks);
            ok &= BridgeMethodShapeValidator.validate(diagnostics, roundSources, roundSinks);
            ok &= PartitionKeyValidator.validate(diagnostics, roundSinks);
            ok &= KotlinDataClassPayloadValidator.validate(diagnostics, roundSources, roundSinks);
        }
        failed |= !ok;
        sources.addAll(roundSources);
        sinks.addAll(roundSinks);
    }

    /** Writes the output for the bridges collected so far; see the class Javadoc for timing. */
    public void finish(ProcessingEnv env) {
        if (finished || failed) return;
        finished = true;
        try {
            var originating = bridgeClasses();
            new KafkaTransportBootstrapGenerator(env.output()).generate(sources, sinks, originating);
            // Companion topology fragment (#312): lets the MCP topology store see the
            // transport edges the core Kafka-agnostic topology.json cannot.
            new KafkaTopologyWriter(env.output()).write(sources, sinks, originating);
        } catch (IOException ex) {
            env.diagnostics().error(null, "Failed to generate KafkaTransportBootstrap: " + ex.getMessage());
        }
    }

    /**
     * The bridges' declaring classes, once each and by qualified name: the output's originating
     * declarations (javac Filer originating elements; KSP incremental inputs).
     */
    private List<ClassDecl> bridgeClasses() {
        var classes = new TreeMap<String, ClassDecl>();
        sources.forEach(s -> classes.putIfAbsent(s.enclosingClass().qualifiedName(), s.enclosingClass()));
        sinks.forEach(s -> classes.putIfAbsent(s.enclosingClass().qualifiedName(), s.enclosingClass()));
        return List.copyOf(classes.values());
    }

    /** Whether a bridge failed validation in any round; {@link #finish} then writes nothing. */
    public boolean failed() {
        return failed;
    }

    /**
     * Whether {@link #finish} has run and written the output (if there was any to write). Stays
     * {@code false} after a validation failure, as {@code finish} then writes nothing and leaves
     * the pipeline unfinished.
     */
    public boolean finished() {
        return finished;
    }

    private static Optional<KafkaSourceDescriptor> sourceDescriptor(MethodDecl method, Diagnostics diagnostics) {
        var annotation = method.annotation(KAFKA_SOURCE).orElseThrow();
        var serializer = serializer(annotation, diagnostics);
        if (serializer.isEmpty()) return Optional.empty();
        var params = method.parameters();
        TypeRef payload = params.isEmpty() ? null : params.get(0).type();
        boolean wantsContext = params.size() >= 2
                && BridgeMethodShapeValidator.isKafkaContext(params.get(1).type());
        var eventName = method.annotationsByType(EVENT_TRIGGER).stream()
                .findFirst()
                .map(trigger -> trigger.string("eventName"))
                .orElse("");
        return Optional.of(new KafkaSourceDescriptor(
                owner(method),
                method,
                annotation.string("topic"),
                annotation.string("consumerGroup"),
                serializer.get(),
                eventName,
                payload,
                method.returnType(),
                wantsContext));
    }

    private static Optional<KafkaSinkDescriptor> sinkDescriptor(MethodDecl method, Diagnostics diagnostics) {
        var annotation = method.annotation(KAFKA_SINK).orElseThrow();
        var serializer = serializer(annotation, diagnostics);
        if (serializer.isEmpty()) return Optional.empty();
        var params = method.parameters();
        TypeRef eventType = params.isEmpty() ? null : params.get(0).type();
        return Optional.of(new KafkaSinkDescriptor(
                owner(method),
                method,
                annotation.string("topic"),
                annotation.string("partitionKey"),
                serializer.get(),
                eventType,
                method.returnType()));
    }

    /**
     * The {@code serializer} member, its declared default ({@code KafkaSerializer.Default})
     * included; empty, with an error reported, when the class literal does not resolve.
     */
    private static Optional<TypeRef> serializer(AnnotationRef annotation, Diagnostics diagnostics) {
        var serializer = annotation.type("serializer");
        if (serializer.isEmpty()) {
            var name = annotation
                    .annotationName()
                    .substring(annotation.annotationName().lastIndexOf('.') + 1);
            diagnostics.error(
                    annotation.location(),
                    "@" + name + " serializer class does not resolve, so the bridge cannot be generated. "
                            + "Fix the class literal (check its name and import), or remove serializer to use "
                            + "the serializer named by KafkaConfig.serializer.");
        }
        return serializer;
    }

    /** The declaring class; a Java method always has one, and {@link KotlinBridgeValidator} rejected the rest. */
    private static ClassDecl owner(MethodDecl method) {
        return method.owner()
                .orElseThrow(() -> new IllegalStateException("Kafka bridge without a declaring class: " + method));
    }
}
