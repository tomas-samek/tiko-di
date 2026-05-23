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
                jw.field("default").value(f.defaultValue());
                jw.endObject();
            }
            jw.endArray();
            jw.endObject();
        }
        jw.endArray();
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
