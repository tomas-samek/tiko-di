package io.tiko.kafka.processor.generator;

import io.tiko.kafka.processor.model.KafkaSinkDescriptor;
import io.tiko.kafka.processor.model.KafkaSourceDescriptor;
import io.tiko.processor.core.JsonWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.annotation.processing.Filer;
import javax.lang.model.type.TypeMirror;
import javax.tools.StandardLocation;

/**
 * Emits {@code META-INF/tiko/topology-kafka.json} — the Kafka companion to the core
 * processor's {@code topology.json} (#312). The core {@code tiko-processor} owns
 * {@code topology.json} and is deliberately Kafka-agnostic; this module writes a sibling
 * fragment so the MCP {@code TopologyStore} can merge in the transport edges and
 * {@code trace_event_flow} can confirm a Kafka end-to-end path (ingest topic → event →
 * sink topic) without reading the generated {@code KafkaTransportBootstrap}.
 *
 * <p>Schema mirrors {@code topology.json}'s conventions ({@code schemaVersion: 1},
 * additive-only). Two arrays:
 * <ul>
 *   <li>{@code kafkaSources[]} — one per {@code @KafkaSource}; {@code eventType} is the
 *       FQN of the local event the bridge publishes (its return type), the key the MCP
 *       traces by.</li>
 *   <li>{@code kafkaSinks[]} — one per {@code @KafkaSink}; {@code eventType} is the FQN of
 *       the local event that triggers the send (its first parameter type).</li>
 * </ul>
 *
 * <p>Written through the shared {@code JsonWriter} in {@code tiko-processor-core}.
 */
public final class KafkaTopologyWriter {

    private static final int SCHEMA_VERSION = 1;
    private static final String PATH = "META-INF/tiko/topology-kafka.json";

    private final Filer filer;

    public KafkaTopologyWriter(Filer filer) {
        this.filer = filer;
    }

    /** Writes the fragment when there is at least one source or sink; a no-op otherwise. */
    public void write(List<KafkaSourceDescriptor> sources, List<KafkaSinkDescriptor> sinks) throws IOException {
        if (sources.isEmpty() && sinks.isEmpty()) return;

        var resource = filer.createResource(StandardLocation.CLASS_OUTPUT, "", PATH);
        try (Writer w = new OutputStreamWriter(resource.openOutputStream(), StandardCharsets.UTF_8)) {
            w.write(render(sources, sinks));
        }
    }

    /** Serializes to a String — also used by unit tests so they don't need a Filer. */
    public static String render(List<KafkaSourceDescriptor> sources, List<KafkaSinkDescriptor> sinks) {
        var out = new StringWriter();
        try (var jw = new JsonWriter(out, true)) {
            jw.object().field("schemaVersion").value(SCHEMA_VERSION);
            jw.field("kafkaSources").array();
            for (var s : sources) {
                writeSource(jw, s);
            }
            jw.endArray();
            jw.field("kafkaSinks").array();
            for (var s : sinks) {
                writeSink(jw, s);
            }
            jw.endArray().endObject();
        }
        return out + "\n";
    }

    private static void writeSource(JsonWriter jw, KafkaSourceDescriptor s) {
        jw.object()
                .field("declaringClass")
                .value(s.enclosingClass().getQualifiedName().toString())
                .field("methodName")
                .value(s.method().getSimpleName().toString())
                .field("topic")
                .value(s.topic())
                .field("consumerGroup")
                .value(s.consumerGroup())
                .field("serializer")
                .value(typeName(s.serializerClass()))
                .field("eventName")
                .value(s.eventName())
                .field("payloadType")
                .value(typeName(s.payloadType()))
                .field("eventType")
                .value(typeName(s.producedEventType()))
                .endObject();
    }

    private static void writeSink(JsonWriter jw, KafkaSinkDescriptor s) {
        jw.object()
                .field("declaringClass")
                .value(s.enclosingClass().getQualifiedName().toString())
                .field("methodName")
                .value(s.method().getSimpleName().toString())
                .field("topic")
                .value(s.topic())
                .field("partitionKey")
                .value(s.partitionKey())
                .field("serializer")
                .value(typeName(s.serializerClass()))
                .field("eventType")
                .value(typeName(s.eventType()))
                .field("payloadType")
                .value(typeName(s.producedPayloadType()))
                .endObject();
    }

    private static String typeName(TypeMirror t) {
        return t == null ? null : t.toString();
    }
}
