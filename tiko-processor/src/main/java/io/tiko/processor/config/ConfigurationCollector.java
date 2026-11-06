package io.tiko.processor.config;

import io.tiko.annotations.Configuration;
import io.tiko.annotations.Default;
import io.tiko.annotations.Key;
import io.tiko.processor.core.ProcessingEnv;
import io.tiko.processor.core.TypeOracle;
import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.DeclKind;
import io.tiko.processor.core.model.FieldDecl;
import io.tiko.processor.core.model.ParamDecl;
import io.tiko.processor.util.ProcessorContext;
import io.tiko.processor.util.TypeRefs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Walks {@code @Configuration}-annotated types and builds {@link ConfigurationModel}s.
 * Non-record annotated types are flagged here (the simplest fail-fast point);
 * deeper validation is in {@link ConfigurationValidator}.
 */
public final class ConfigurationCollector {

    private static final String CONFIGURATION = Configuration.class.getCanonicalName();
    private static final String KEY = Key.class.getCanonicalName();
    private static final String DEFAULT = Default.class.getCanonicalName();

    private final ProcessorContext ctx;

    public ConfigurationCollector(ProcessorContext ctx) {
        this.ctx = ctx;
    }

    public void collect(ProcessingEnv env) {
        // @Configuration targets TYPE, so every annotated element is a type declaration.
        for (var type : env.classesAnnotatedWith(CONFIGURATION)) {
            if (type.kind() != DeclKind.RECORD) {
                ctx.getErrorReporter()
                        .error(
                                type.location(),
                                "@Configuration must be applied to a record",
                                "Change `class` to `record`");
                continue;
            }

            var prefix = type.annotation(CONFIGURATION).orElseThrow().string("prefix");
            var simple = type.className().simpleName();

            // @Configuration records must be top-level: the binder is keyed by package + simple
            // name, and nested records would collide. Guard against nesting so a nested record
            // yields a clear diagnostic instead of a ClassCastException from inside the processor (#105).
            var enclosing = type.enclosingClass();
            if (enclosing.isPresent()) {
                ctx.getErrorReporter()
                        .error(
                                type.location(),
                                "@Configuration record '" + simple
                                        + "' must be top-level (declared inside '"
                                        + enclosing.get().qualifiedName()
                                        + "'). Move it to its own .java file.",
                                "Move " + simple + " to its own top-level .java file");
                continue;
            }
            var pkg = type.className().packageName();
            var qualified = type.qualifiedName();

            // Build a map from component name → canonical constructor parameter
            // so we can read @Default / @Key (which target PARAMETER, not RECORD_COMPONENT).
            var ctorParams = buildCanonicalCtorParamMap(type, env.types());

            var fields = new ArrayList<ConfigFieldModel>();
            for (var component : type.recordComponents()) {
                fields.add(buildField(component, ctorParams.get(component.name())));
            }

            ctx.registerConfiguration(new ConfigurationModel(type, pkg, simple, qualified, prefix, fields));
        }
    }

    /**
     * Builds a map from record component name to its canonical constructor parameter.
     * @Default and @Key target ElementType.PARAMETER, so they appear on constructor params,
     * not on RECORD_COMPONENT elements.
     *
     * <p>Matches by both arity and per-parameter type to avoid picking a non-canonical
     * constructor that coincidentally has the same number of parameters but different types.
     */
    private static Map<String, ParamDecl> buildCanonicalCtorParamMap(ClassDecl type, TypeOracle types) {
        var components = type.recordComponents();

        for (var ctor : type.constructors()) {
            var params = ctor.parameters();

            // Must have the same arity as the record component list.
            if (params.size() != components.size()) continue;

            // Every (component, parameter) pair must have the same type.
            boolean allMatch = true;
            for (int i = 0; i < components.size(); i++) {
                if (!types.isSameType(components.get(i).type(), params.get(i).type())) {
                    allMatch = false;
                    break;
                }
            }
            if (!allMatch) continue;

            // This is the canonical constructor.
            var map = new HashMap<String, ParamDecl>();
            for (var param : params) {
                map.put(param.name(), param);
            }
            return map;
        }

        // Defensive fallback: no canonical constructor matched (should not happen for valid records).
        return new HashMap<>();
    }

    private static ConfigFieldModel buildField(FieldDecl comp, ParamDecl ctorParam) {
        var name = comp.name();
        // Read @Key and @Default from the canonical constructor parameter (PARAMETER target),
        // but fall back to the record component element in case they are propagated there too.
        var keyAnn = annotationOf(comp, ctorParam, KEY);
        var yamlKey = keyAnn.map(key -> key.string("value")).orElse(name);

        var defAnn = annotationOf(comp, ctorParam, DEFAULT);
        boolean isOptional = TypeRefs.isDeclared(comp.type(), "java.util.Optional");

        // Retain defaultValue even for Optional fields so the validator can detect the conflict.
        var defaultValue = defAnn.map(def -> def.string("value")).orElse(null);

        ConfigFieldModel.Cardinality cardinality;
        if (isOptional) {
            cardinality = ConfigFieldModel.Cardinality.OPTIONAL;
        } else if (defAnn.isPresent()) {
            cardinality = ConfigFieldModel.Cardinality.DEFAULTED;
        } else {
            cardinality = ConfigFieldModel.Cardinality.REQUIRED;
        }

        return new ConfigFieldModel(comp, name, yamlKey, comp.type(), cardinality, defaultValue);
    }

    /** The annotation on the constructor parameter when there is one, else on the component. */
    private static Optional<AnnotationRef> annotationOf(FieldDecl comp, ParamDecl ctorParam, String annotationFqn) {
        if (ctorParam != null) {
            return ctorParam.annotation(annotationFqn);
        }
        return comp.annotations().stream()
                .filter(annotation -> annotation.annotationName().equals(annotationFqn))
                .findFirst();
    }
}
