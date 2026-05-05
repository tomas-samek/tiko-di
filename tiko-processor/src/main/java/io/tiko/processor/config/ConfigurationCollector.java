package io.tiko.processor.config;

import io.tiko.annotations.Configuration;
import io.tiko.annotations.Default;
import io.tiko.annotations.Key;
import io.tiko.processor.util.ProcessorContext;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks {@code @Configuration}-annotated elements and builds {@link ConfigurationModel}s.
 * Non-record annotated elements are flagged here (the simplest fail-fast point);
 * deeper validation is in {@link ConfigurationValidator}.
 */
public final class ConfigurationCollector {

    private final ProcessorContext ctx;

    public ConfigurationCollector(ProcessorContext ctx) {
        this.ctx = ctx;
    }

    public void collect(RoundEnvironment roundEnv) {
        for (Element el : roundEnv.getElementsAnnotatedWith(Configuration.class)) {
            if (!(el instanceof TypeElement type)) {
                ctx.getErrorReporter().error(el, "@Configuration can only be applied to types");
                continue;
            }
            if (type.getKind() != ElementKind.RECORD) {
                ctx.getErrorReporter().error(type,
                    "@Configuration must be applied to a record",
                    "Change `class` to `record`");
                continue;
            }

            Configuration ann = type.getAnnotation(Configuration.class);
            String prefix = ann.prefix();
            String pkg = ((PackageElement) type.getEnclosingElement()).getQualifiedName().toString();
            String simple = type.getSimpleName().toString();
            String qualified = type.getQualifiedName().toString();

            // Build a map from component name → canonical constructor parameter
            // so we can read @Default / @Key (which target PARAMETER, not RECORD_COMPONENT).
            Map<String, VariableElement> ctorParams = buildCanonicalCtorParamMap(type);

            List<ConfigFieldModel> fields = new ArrayList<>();
            for (Element member : type.getEnclosedElements()) {
                if (member.getKind() != ElementKind.RECORD_COMPONENT) continue;
                VariableElement comp = (VariableElement) member;
                VariableElement ctorParam = ctorParams.get(comp.getSimpleName().toString());
                fields.add(buildField(comp, ctorParam));
            }

            ctx.registerConfiguration(new ConfigurationModel(type, pkg, simple, qualified, prefix, fields));
        }
    }

    /**
     * Builds a map from record component name to its canonical constructor parameter.
     * @Default and @Key target ElementType.PARAMETER, so they appear on constructor params,
     * not on RECORD_COMPONENT elements.
     */
    private Map<String, VariableElement> buildCanonicalCtorParamMap(TypeElement type) {
        Map<String, VariableElement> map = new HashMap<>();
        for (Element member : type.getEnclosedElements()) {
            if (member.getKind() != ElementKind.CONSTRUCTOR) continue;
            ExecutableElement ctor = (ExecutableElement) member;
            // The canonical constructor has the same parameter count as the record components.
            long componentCount = type.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.RECORD_COMPONENT)
                .count();
            if (ctor.getParameters().size() != componentCount) continue;
            for (VariableElement param : ctor.getParameters()) {
                map.put(param.getSimpleName().toString(), param);
            }
            break; // only one canonical constructor
        }
        return map;
    }

    private ConfigFieldModel buildField(VariableElement comp, VariableElement ctorParam) {
        String name = comp.getSimpleName().toString();
        // Read @Key and @Default from the canonical constructor parameter (PARAMETER target),
        // but fall back to the record component element in case they are propagated there too.
        Key keyAnn = ctorParam != null ? ctorParam.getAnnotation(Key.class) : comp.getAnnotation(Key.class);
        String yamlKey = keyAnn != null ? keyAnn.value() : name;

        Default defAnn = ctorParam != null ? ctorParam.getAnnotation(Default.class) : comp.getAnnotation(Default.class);
        boolean isOptional = isOptional(comp.asType());

        // Retain defaultValue even for Optional fields so the validator can detect the conflict.
        String defaultValue = defAnn != null ? defAnn.value() : null;

        ConfigFieldModel.Cardinality cardinality;
        if (isOptional) {
            cardinality = ConfigFieldModel.Cardinality.OPTIONAL;
        } else if (defAnn != null) {
            cardinality = ConfigFieldModel.Cardinality.DEFAULTED;
        } else {
            cardinality = ConfigFieldModel.Cardinality.REQUIRED;
        }

        return new ConfigFieldModel(comp, name, yamlKey, comp.asType(), cardinality, defaultValue);
    }

    private boolean isOptional(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) return false;
        DeclaredType dt = (DeclaredType) type;
        return ((TypeElement) dt.asElement()).getQualifiedName().toString().equals("java.util.Optional");
    }
}
