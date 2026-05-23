package io.tiko.processor.topology;

import io.tiko.processor.config.ConfigurationModel;
import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.model.EventHandlerModel;
import io.tiko.processor.model.EventTriggerModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.ProcessorContext;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.List;
import javax.annotation.processing.Filer;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * Emits {@code META-INF/tiko/topology.json} — a versioned, machine-readable description of every
 * {@code @Component}, {@code @Produces}, {@code @EventHandler}, {@code @EventTrigger}, and {@code
 * @Configuration} discovered in the round.
 *
 * <p>Schema is {@code schemaVersion: 1}, additive-only thereafter. New fields are optional; renames
 * or removals require a major bump. See {@code docs/topology-schema.md}.
 */
public final class TopologyWriter {

    private static final int SCHEMA_VERSION = 1;
    private static final String PATH = "META-INF/tiko/topology.json";

    private final ProcessorContext context;

    public TopologyWriter(ProcessorContext context) {
        this.context = context;
    }

    /** Writes the JSON resource via the supplied Filer. */
    public void write(Filer filer) throws IOException {
        FileObject f = filer.createResource(StandardLocation.CLASS_OUTPUT, "", PATH);
        try (Writer w = f.openWriter()) {
            renderTo(w);
        } catch (UncheckedIOException uncheckedIo) {
            throw uncheckedIo.getCause();
        }
    }

    /** Serializes to a String — used by unit tests so they don't need a Filer. */
    public String render() {
        var sw = new StringWriter();
        renderTo(sw);
        return sw.toString();
    }

    private void renderTo(Writer w) {
        try (var jw = new JsonWriter(w, true)) {
            jw.object();
            jw.field("schemaVersion").value(SCHEMA_VERSION);
            jw.field("module").value(context.getContainerClassName());
            writeComponents(jw);
            writeFactoryMethods(jw);
            writeEventHandlers(jw);
            writeEventTriggers(jw);
            writeConfigurations(jw);
            jw.endObject();
        }
    }

    private void writeComponents(JsonWriter jw) {
        jw.field("components").array();
        for (ComponentModel c : context.getActiveComponents()) {
            jw.object();
            jw.field("qualifiedName").value(c.getQualifiedName());
            jw.field("packageName").value(c.getPackageName());
            jw.field("simpleName").value(c.getClassName());
            jw.field("scope").value(c.getScope().name());
            jw.field("qualifier").value(c.getName().orElse(null));
            writeStringArray(jw, "profiles", c.getProfiles());
            jw.field("interfaces").array();
            for (TypeMirror iface : c.getTypeElement().getInterfaces()) {
                jw.value(iface.toString());
            }
            jw.endArray();
            jw.field("isTestComponent").value(c.isTestComponent());
            jw.field("requiresProxy").value(c.requiresProxy());
            jw.field("exposeSelf").value(c.isExposeSelf());
            jw.field("exposeTypes").array();
            for (TypeMirror t : c.getExposeTypes()) {
                jw.value(t.toString());
            }
            jw.endArray();
            writeDependencies(jw, c.getDependencies());
            writeLifecycle(jw, c.getPostConstructMethods(), c.getPreDestroyMethods(), c.isAutoCloseable());
            jw.endObject();
        }
        jw.endArray();
    }

    private void writeFactoryMethods(JsonWriter jw) {
        jw.field("factoryMethods").array();
        for (FactoryMethodModel f : context.getActiveFactoryMethods()) {
            jw.object();
            jw.field("declaringClass")
                    .value(f.getDeclaringClass().getQualifiedName().toString());
            jw.field("methodName").value(f.getMethodName());
            jw.field("returnType").value(f.getReturnTypeName());
            jw.field("scope").value(f.getScope().name());
            var qualifier = f.getName();
            jw.field("qualifier").value(qualifier == null || qualifier.isEmpty() ? null : qualifier);
            writeStringArray(jw, "profiles", f.getProfiles());
            jw.field("static").value(f.isStatic());
            jw.field("autoCloseable").value(f.isAutoCloseable());
            jw.field("requiresProxy").value(f.requiresProxy());
            writeDependencies(jw, f.getDependencies());
            jw.endObject();
        }
        jw.endArray();
    }

    private void writeEventHandlers(JsonWriter jw) {
        jw.field("eventHandlers").array();
        for (EventHandlerModel h : context.getEventHandlers()) {
            jw.object();
            jw.field("declaringClass")
                    .value(h.getDeclaringClass().getQualifiedName().toString());
            jw.field("methodName").value(h.getMethodName());
            jw.field("eventType").value(h.getEventTypeName());
            jw.field("async").value(h.isAsync());
            jw.field("hasEventWrapper").value(h.hasEventWrapper());
            jw.endObject();
        }
        jw.endArray();
    }

    private void writeEventTriggers(JsonWriter jw) {
        jw.field("eventTriggers").array();
        for (EventHandlerModel h : context.getEventHandlers()) {
            for (EventTriggerModel t : h.getEventTriggers()) {
                jw.object();
                jw.field("handlerClass")
                        .value(h.getDeclaringClass().getQualifiedName().toString());
                jw.field("handlerMethod").value(h.getMethodName());
                jw.field("eventName").value(t.getEventName());
                jw.field("eventType").value(triggerReturnTypeFqn(h.getMethodElement()));
                jw.field("async").value(t.isAsync());
                jw.field("spread").value(t.isSpread());
                jw.field("guards").array();
                for (TypeMirror g : t.getGuardClasses()) {
                    jw.value(g.toString());
                }
                jw.endArray();
                jw.endObject();
            }
        }
        jw.endArray();
    }

    /**
     * Return-type FQN of an {@code @EventTrigger}-bearing handler — the type the runtime
     * event bus actually dispatches by. {@code void} returns become {@code null} (no
     * spread payload); {@code @EventTrigger(eventName = "...")} is just a user label
     * and isn't a reliable join key on its own.
     */
    private static String triggerReturnTypeFqn(ExecutableElement method) {
        var ret = method.getReturnType();
        if (ret == null || ret.getKind() == javax.lang.model.type.TypeKind.VOID) {
            return null;
        }
        return ret.toString();
    }

    private void writeConfigurations(JsonWriter jw) {
        jw.field("configurations").array();
        for (ConfigurationModel cfg : context.getConfigurations()) {
            jw.object();
            jw.field("qualifiedName").value(cfg.qualifiedName());
            jw.field("prefix").value(cfg.prefix());
            jw.field("fields").array();
            for (var f : cfg.fields()) {
                jw.object();
                jw.field("name").value(f.fieldName());
                jw.field("yamlKey").value(f.yamlKey());
                jw.field("type").value(f.type().toString());
                jw.field("cardinality").value(f.cardinality().name());
                writeDefaultValue(jw, f.type().toString(), f.defaultValue());
                jw.endObject();
            }
            jw.endArray();
            jw.endObject();
        }
        jw.endArray();
    }

    /**
     * Emits the {@code default} field with the value typed to match the record-component
     * type, so {@code int poolSize} with {@code @Default("10")} writes {@code "default": 10}
     * (not the string {@code "10"}). Mirrors the typing rules in
     * {@link ConfigSchemaWriter#decorateWithDefault} so both build artifacts agree.
     */
    private static void writeDefaultValue(JsonWriter jw, String typeFqn, String raw) {
        jw.field("default");
        if (raw == null) {
            jw.nullValue();
            return;
        }
        var trimmed = raw.trim();
        if (isIntegerType(typeFqn)) {
            try {
                jw.value(Long.parseLong(trimmed));
                return;
            } catch (NumberFormatException ignored) {
                // fall through to string fallback
            }
        } else if (isNumberType(typeFqn)) {
            try {
                jw.value(Double.parseDouble(trimmed));
                return;
            } catch (NumberFormatException ignored) {
                // fall through to string fallback
            }
        } else if (isBooleanType(typeFqn) && ("true".equals(trimmed) || "false".equals(trimmed))) {
            jw.value(Boolean.parseBoolean(trimmed));
            return;
        }
        jw.value(raw);
    }

    private static boolean isIntegerType(String fqn) {
        return switch (fqn) {
            case "int",
                    "long",
                    "short",
                    "byte",
                    "java.lang.Integer",
                    "java.lang.Long",
                    "java.lang.Short",
                    "java.lang.Byte" -> true;
            default -> false;
        };
    }

    private static boolean isNumberType(String fqn) {
        return switch (fqn) {
            case "float", "double", "java.lang.Float", "java.lang.Double" -> true;
            default -> false;
        };
    }

    private static boolean isBooleanType(String fqn) {
        return "boolean".equals(fqn) || "java.lang.Boolean".equals(fqn);
    }

    private void writeDependencies(JsonWriter jw, List<DependencyModel> deps) {
        jw.field("constructorDependencies").array();
        for (DependencyModel d : deps) {
            jw.object();
            jw.field("type").value(d.getTypeName());
            jw.field("qualifier").value(d.getQualifier().orElse(null));
            jw.field("kind").value(d.isProvider() ? "PROVIDER" : d.isPicker() ? "PICKER" : "DIRECT");
            jw.field("pickedType").value(d.getPickedTypeName().orElse(null));
            jw.endObject();
        }
        jw.endArray();
    }

    private void writeLifecycle(
            JsonWriter jw,
            List<ExecutableElement> postConstruct,
            List<ExecutableElement> preDestroy,
            boolean autoCloseable) {
        jw.field("lifecycle").object();
        writeMethodNameArray(jw, "postConstruct", postConstruct);
        writeMethodNameArray(jw, "preDestroy", preDestroy);
        jw.field("autoCloseable").value(autoCloseable);
        jw.endObject();
    }

    private void writeMethodNameArray(JsonWriter jw, String fieldName, List<ExecutableElement> methods) {
        jw.field(fieldName).array();
        for (ExecutableElement m : methods) {
            jw.value(m.getSimpleName().toString());
        }
        jw.endArray();
    }

    private void writeStringArray(JsonWriter jw, String fieldName, List<String> values) {
        jw.field(fieldName).array();
        for (String s : values) {
            jw.value(s);
        }
        jw.endArray();
    }
}
