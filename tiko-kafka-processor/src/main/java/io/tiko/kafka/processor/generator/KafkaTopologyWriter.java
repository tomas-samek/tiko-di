package io.tiko.kafka.processor.generator;

import io.tiko.kafka.processor.model.KafkaSinkDescriptor;
import io.tiko.kafka.processor.model.KafkaSourceDescriptor;
import java.io.IOException;
import java.io.OutputStreamWriter;
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
 * <p>Hand-rolled JSON rather than reusing {@code tiko-processor}'s {@code JsonWriter}:
 * this module deliberately has no compile dependency on {@code tiko-processor}.
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
        var sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schemaVersion\": ").append(SCHEMA_VERSION).append(",\n");
        sb.append("  \"kafkaSources\": [");
        appendSources(sb, sources);
        sb.append("],\n");
        sb.append("  \"kafkaSinks\": [");
        appendSinks(sb, sinks);
        sb.append("]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendSources(StringBuilder sb, List<KafkaSourceDescriptor> sources) {
        for (int i = 0; i < sources.size(); i++) {
            KafkaSourceDescriptor s = sources.get(i);
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append("    {\n");
            field(sb, "declaringClass", s.enclosingClass().getQualifiedName().toString(), true);
            field(sb, "methodName", s.method().getSimpleName().toString(), true);
            field(sb, "topic", s.topic(), true);
            field(sb, "consumerGroup", s.consumerGroup(), true);
            field(sb, "serializer", typeName(s.serializerClass()), true);
            field(sb, "eventName", s.eventName(), true);
            field(sb, "payloadType", typeName(s.payloadType()), true);
            field(sb, "eventType", typeName(s.producedEventType()), false);
            sb.append("    }");
        }
        if (!sources.isEmpty()) sb.append("\n  ");
    }

    private static void appendSinks(StringBuilder sb, List<KafkaSinkDescriptor> sinks) {
        for (int i = 0; i < sinks.size(); i++) {
            KafkaSinkDescriptor s = sinks.get(i);
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append("    {\n");
            field(sb, "declaringClass", s.enclosingClass().getQualifiedName().toString(), true);
            field(sb, "methodName", s.method().getSimpleName().toString(), true);
            field(sb, "topic", s.topic(), true);
            field(sb, "partitionKey", s.partitionKey(), true);
            field(sb, "serializer", typeName(s.serializerClass()), true);
            field(sb, "eventType", typeName(s.eventType()), true);
            field(sb, "payloadType", typeName(s.producedPayloadType()), false);
            sb.append("    }");
        }
        if (!sinks.isEmpty()) sb.append("\n  ");
    }

    private static void field(StringBuilder sb, String key, String value, boolean trailingComma) {
        sb.append("      \"").append(key).append("\": ");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"').append(escape(value)).append('"');
        }
        sb.append(trailingComma ? ",\n" : "\n");
    }

    private static String typeName(TypeMirror t) {
        return t == null ? null : t.toString();
    }

    private static String escape(String s) {
        var sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
