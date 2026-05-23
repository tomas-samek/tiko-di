package io.tiko.processor.topology;

import io.tiko.processor.config.ConfigFieldModel;
import io.tiko.processor.config.ConfigurationModel;
import io.tiko.processor.util.ProcessorContext;
import java.io.IOException;
import java.io.Writer;
import javax.annotation.processing.Filer;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * Emits {@code META-INF/tiko/config-schema.json} — a JSON Schema draft 2020-12
 * document describing every {@code @Configuration} record. IntelliJ IDEA can be
 * wired to this file for YAML autocomplete and validation.
 */
public final class ConfigSchemaWriter {

    private static final String PATH = "META-INF/tiko/config-schema.json";

    private final ProcessorContext context;
    private final JsonSchemaTypeMapper typeMapper;

    public ConfigSchemaWriter(ProcessorContext context) {
        this.context = context;
        this.typeMapper = new JsonSchemaTypeMapper();
    }

    public void write(Filer filer) throws IOException {
        FileObject f = filer.createResource(StandardLocation.CLASS_OUTPUT, "", PATH);
        try (Writer w = f.openWriter()) {
            renderTo(w);
        }
    }

    private void renderTo(Writer w) {
        try (var jw = new JsonWriter(w, true)) {
            jw.object();
            jw.field("$schema").value("https://json-schema.org/draft/2020-12/schema");
            jw.field("$id").value("tiko://config-schema");
            jw.field("type").value("object");
            jw.field("title").value("Tiko @Configuration union");
            jw.field("properties").object();
            for (ConfigurationModel cfg : context.getConfigurations()) {
                writeConfigBlock(jw, cfg);
            }
            jw.endObject();
            // Top-level allows framework-reserved keys (tiko.shutdownTimeout, etc.)
            jw.field("additionalProperties").value(true);
            jw.endObject();
        }
    }

    private void writeConfigBlock(JsonWriter jw, ConfigurationModel cfg) {
        jw.field(cfg.prefix()).object();
        jw.field("type").value("object");
        jw.field("title").value(cfg.qualifiedName());
        jw.field("properties").object();
        for (ConfigFieldModel field : cfg.fields()) {
            jw.field(field.yamlKey()).raw(decorateWithDefault(typeMapper.mapType(field.type()), field));
        }
        jw.endObject();
        jw.field("required").array();
        for (ConfigFieldModel field : cfg.fields()) {
            if (field.cardinality() == ConfigFieldModel.Cardinality.REQUIRED) {
                jw.value(field.fieldName());
            }
        }
        jw.endArray();
        jw.field("additionalProperties").value(false);
        jw.endObject();
    }

    /**
     * Splices the {@code "default": "..."} field into a JSON Schema fragment when
     * the source carries {@code @Default(...)}. Naive string splice — the fragment
     * shapes are tightly controlled by {@link JsonSchemaTypeMapper}.
     */
    private String decorateWithDefault(String fragment, ConfigFieldModel field) {
        if (field.cardinality() != ConfigFieldModel.Cardinality.DEFAULTED || field.defaultValue() == null) {
            return fragment;
        }
        if (!fragment.startsWith("{") || !fragment.endsWith("}")) {
            return fragment;
        }
        var inner = fragment.substring(1, fragment.length() - 1);
        var raw = field.defaultValue().trim();
        String def;
        if (fragment.contains("\"type\":\"integer\"")) {
            try {
                Long.parseLong(raw);
                def = "\"default\":" + raw;
            } catch (NumberFormatException e) {
                def = "\"default\":\"" + escapeJson(field.defaultValue()) + "\"";
            }
        } else if (fragment.contains("\"type\":\"number\"")) {
            try {
                Double.parseDouble(raw);
                def = "\"default\":" + raw;
            } catch (NumberFormatException e) {
                def = "\"default\":\"" + escapeJson(field.defaultValue()) + "\"";
            }
        } else if (fragment.contains("\"type\":\"boolean\"") && ("true".equals(raw) || "false".equals(raw))) {
            def = "\"default\":" + raw;
        } else {
            def = "\"default\":\"" + escapeJson(field.defaultValue()) + "\"";
        }
        return "{" + inner + (inner.isEmpty() ? "" : ",") + def + "}";
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
