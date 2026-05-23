// tiko-processor/src/main/java/io/tiko/processor/config/ConfigBinderGenerator.java
package io.tiko.processor.config;

import com.palantir.javapoet.*;
import io.tiko.config.BindContext;
import io.tiko.config.ConfigBinder;
import io.tiko.config.internal.coercers.Coercers;
import io.tiko.config.internal.coercers.CompositeCoercers;
import io.tiko.config.internal.coercers.NestedRecordSupport;
import io.tiko.config.internal.coercers.TypeCoercer;
import io.tiko.processor.util.GeneratorAnnotations;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * Generates {@code <Record>ConfigBinder.java} for each {@code @Configuration} record and
 * {@code <Record>NestedCoercer_<hash>.java} for each non-{@code @Configuration} nested record
 * reachable from a configuration record's fields (#17).
 *
 * <p>Nested records compose with {@code Optional<X>}, {@code List<X>}, and {@code Map<String,X>}
 * via {@link CompositeCoercers}. The nested coercers are stateless — error path anchoring
 * happens by composition: inner failures throw {@link io.tiko.config.internal.coercers.CoercionException}
 * with the field name prepended; the outer {@link BindContext} anchors to its full path.
 */
public final class ConfigBinderGenerator {

    private static final String GENERATED_PACKAGE = "io.tiko.generated.config";

    private final Filer filer;
    private final Messager messager;

    /** Tracks emitted nested-coercer simple names so we don't emit the same class twice. */
    private final Set<String> emittedNestedCoercers = new HashSet<>();

    public ConfigBinderGenerator(Filer filer, Messager messager) {
        this.filer = filer;
        this.messager = messager;
    }

    /**
     * Returns {@code true} if a binder can be generated for {@code cfg}.
     *
     * <p>After #17, nested records are supported; this method is retained as the explicit
     * pre-flight hook in case future reasons to reject configs arise. It currently always
     * returns {@code true} — type-set membership and other constraints are checked by
     * {@link ConfigurationValidator}.
     */
    public boolean canGenerate(ConfigurationModel cfg) {
        return true;
    }

    /**
     * Generates a binder for {@code cfg} and any nested-record coercers reachable from
     * its fields (transitively, deduplicated across configurations).
     */
    public void generate(ConfigurationModel cfg) throws IOException {
        // Phase 1: emit nested coercers for any nested records reachable from this config.
        // Walks transitively so a nested record's own nested fields generate cascaded
        // coercers as well. Deduplication is at the generator-instance level.
        for (ConfigFieldModel f : cfg.fields()) {
            emitNestedCoercersFor(f.type());
        }

        // Phase 2: emit the top-level binder for this @Configuration record.
        emitTopLevelBinder(cfg);
    }

    // ---- Top-level @Configuration binder -----------------------------------

    private void emitTopLevelBinder(ConfigurationModel cfg) throws IOException {
        ClassName recordType = ClassName.get(cfg.packageName(), cfg.simpleName());

        MethodSpec.Builder bind = MethodSpec.methodBuilder("bind")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(recordType)
                .addParameter(
                        ParameterizedTypeName.get(
                                ClassName.get(Map.class), ClassName.get(String.class), ClassName.get(Object.class)),
                        "root")
                .addParameter(ClassName.get(BindContext.class), "ctx");

        bind.addStatement(
                "$T<$T, $T> node = ctx.requireSection(root, $S)", Map.class, String.class, Object.class, cfg.prefix());

        Set<String> consumedKeys = new LinkedHashSet<>();
        StringBuilder ctorArgs = new StringBuilder();

        for (int i = 0; i < cfg.fields().size(); i++) {
            ConfigFieldModel f = cfg.fields().get(i);
            consumedKeys.add(f.yamlKey());

            String varName = f.fieldName();
            String fullPath = cfg.prefix() + "." + f.yamlKey();
            TypeMirror inner = unwrapOptional(f.type());

            CodeBlock coercer = coercerExpr(inner);
            TypeName javaType = TypeName.get(f.type());

            switch (f.cardinality()) {
                case OPTIONAL ->
                    bind.addStatement(
                            "$T $L = ctx.optionalScalar(node, $S, $S, $L)",
                            javaType,
                            varName,
                            f.yamlKey(),
                            fullPath,
                            coercer);
                case DEFAULTED ->
                    bind.addStatement(
                            "$T $L = ctx.scalarOrDefault(node, $S, $S, $L, $L.coerce($S))",
                            javaType,
                            varName,
                            f.yamlKey(),
                            fullPath,
                            coercer,
                            coercer,
                            f.defaultValue());
                case REQUIRED ->
                    bind.addStatement(
                            "$T $L = ctx.requireScalar(node, $S, $S, $L, $L)",
                            javaType,
                            varName,
                            f.yamlKey(),
                            fullPath,
                            coercer,
                            fallbackExpr(inner));
            }

            if (i > 0) ctorArgs.append(", ");
            ctorArgs.append(varName);
        }

        bind.addStatement(
                "ctx.checkUnknownKeys(node, $S, $T.of($L))", cfg.prefix(), Set.class, quotedJoin(consumedKeys));
        bind.addStatement("return new $T($L)", recordType, ctorArgs.toString());

        MethodSpec typeM = MethodSpec.methodBuilder("type")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(Class.class), recordType))
                .addStatement("return $T.class", recordType)
                .build();

        MethodSpec prefixM = MethodSpec.methodBuilder("prefix")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ClassName.get(String.class))
                .addStatement("return $S", cfg.prefix())
                .build();

        TypeSpec binderClass = TypeSpec.classBuilder(cfg.binderSimpleName())
                .addAnnotation(GeneratorAnnotations.generatedBy(ConfigBinderGenerator.class))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(ParameterizedTypeName.get(ClassName.get(ConfigBinder.class), recordType))
                .addMethod(typeM)
                .addMethod(prefixM)
                .addMethod(bind.build())
                .build();

        JavaFile.builder(GENERATED_PACKAGE, binderClass).build().writeTo(filer);
    }

    // ---- Nested-record coercer generation ----------------------------------

    /**
     * Emits a {@code <Record>NestedCoercer_<hash>} class for every nested record reachable
     * from {@code type} (directly, or through {@code Optional}/{@code List}/{@code Map}
     * type arguments). Recurses into the nested record's own field types so deeply-nested
     * structures all get coercers generated.
     *
     * <p>Deduplication: the {@link #emittedNestedCoercers} set tracks generated class names
     * so the same nested record appearing in multiple {@code @Configuration} records produces
     * exactly one generated coercer class.
     */
    private void emitNestedCoercersFor(TypeMirror type) throws IOException {
        if (type.getKind() != javax.lang.model.type.TypeKind.DECLARED) return;
        DeclaredType dt = (DeclaredType) type;
        TypeElement el = (TypeElement) dt.asElement();
        String fqn = el.getQualifiedName().toString();

        // Walk through container types into their value-type parameters.
        if (fqn.equals("java.util.Optional") || fqn.equals("java.util.List") || fqn.equals("java.util.Set")) {
            if (!dt.getTypeArguments().isEmpty()) {
                emitNestedCoercersFor(dt.getTypeArguments().get(0));
            }
            return;
        }
        if (fqn.equals("java.util.Map")) {
            if (dt.getTypeArguments().size() >= 2) {
                emitNestedCoercersFor(dt.getTypeArguments().get(1));
            }
            return;
        }

        if (el.getKind() == ElementKind.RECORD) {
            String coercerName = nestedCoercerSimpleName(el);
            if (emittedNestedCoercers.add(coercerName)) {
                emitNestedCoercerClass(el, coercerName);
                // Recurse into this nested record's own fields — deeply-nested structures
                // need cascaded coercers.
                for (var member : el.getEnclosedElements()) {
                    if (member.getKind() != ElementKind.RECORD_COMPONENT) continue;
                    emitNestedCoercersFor(member.asType());
                }
            }
        }
    }

    private void emitNestedCoercerClass(TypeElement record, String coercerName) throws IOException {
        ClassName recordType = ClassName.get(record);
        String recordSimpleName = record.getSimpleName().toString();
        ClassName coercerType = ClassName.get(GENERATED_PACKAGE, coercerName);

        TypeName coercerOfRecord = ParameterizedTypeName.get(ClassName.get(TypeCoercer.class), recordType);

        // doCoerce(Object raw) → Record builds the record imperatively so each line is a
        // top-level JavaPoet statement (avoids "$[ followed by $[" issues caused by
        // embedding a multi-statement body inside `addStatement`).
        MethodSpec.Builder doCoerce = MethodSpec.methodBuilder("doCoerce")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(recordType)
                .addParameter(Object.class, "raw");

        doCoerce.addStatement(
                "$T<$T, $T> node = $T.requireMap(raw, $S)",
                Map.class,
                String.class,
                Object.class,
                NestedRecordSupport.class,
                recordSimpleName);

        StringBuilder ctorArgs = new StringBuilder();

        java.util.List<VariableElement> components = enclosedRecordComponents(record);
        for (int i = 0; i < components.size(); i++) {
            VariableElement comp = components.get(i);
            String fieldName = comp.getSimpleName().toString();
            String yamlKey = readKeyAnnotation(record, comp).orElse(fieldName);

            TypeMirror raw = comp.asType();
            TypeMirror inner = unwrapOptional(raw);
            boolean isOptional = isOptional(raw);
            String defaultValue = readDefaultAnnotation(record, comp).orElse(null);

            CodeBlock coercer = coercerExpr(inner);
            TypeName javaType = TypeName.get(raw);
            String varName = "f_" + i;

            if (isOptional) {
                doCoerce.addStatement(
                        "$T $L = $T.optionalField(node, $S, $S, $L)",
                        javaType,
                        varName,
                        NestedRecordSupport.class,
                        yamlKey,
                        recordSimpleName,
                        coercer);
            } else if (defaultValue != null) {
                doCoerce.addStatement(
                        "$T $L = $T.fieldOrDefault(node, $S, $S, $L, $L.coerce($S))",
                        javaType,
                        varName,
                        NestedRecordSupport.class,
                        yamlKey,
                        recordSimpleName,
                        coercer,
                        coercer,
                        defaultValue);
            } else {
                doCoerce.addStatement(
                        "$T $L = $T.requireField(node, $S, $S, $L)",
                        javaType,
                        varName,
                        NestedRecordSupport.class,
                        yamlKey,
                        recordSimpleName,
                        coercer);
            }

            if (i > 0) ctorArgs.append(", ");
            ctorArgs.append(varName);
        }

        doCoerce.addStatement("$T.checkUnknownKeys(node, $S)", NestedRecordSupport.class, recordSimpleName);
        doCoerce.addStatement("return new $T($L)", recordType, ctorArgs.toString());

        // coercer() returns a method reference to doCoerce — a single statement that
        // JavaPoet handles trivially.
        MethodSpec coercerM = MethodSpec.methodBuilder("coercer")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(coercerOfRecord)
                .addStatement("return $T::doCoerce", coercerType)
                .build();

        TypeSpec coercerClass = TypeSpec.classBuilder(coercerName)
                .addAnnotation(GeneratorAnnotations.generatedBy(ConfigBinderGenerator.class))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addJavadoc("Generated nested-record coercer for $T (#17).\n", recordType)
                .addMethod(coercerM)
                .addMethod(doCoerce.build())
                .build();

        JavaFile.builder(GENERATED_PACKAGE, coercerClass).build().writeTo(filer);
    }

    /**
     * Returns {@code <SimpleName>NestedCoercer_<hash8>} where hash8 is a stable 8-char
     * hex of the record's fully qualified name. Hash suffix avoids collisions when two
     * nested records share a simple name across packages.
     */
    static String nestedCoercerSimpleName(TypeElement record) {
        String fqn = record.getQualifiedName().toString();
        String hash = String.format("%08x", fqn.hashCode());
        return record.getSimpleName() + "NestedCoercer_" + hash;
    }

    static String nestedCoercerQualifiedName(TypeElement record) {
        return GENERATED_PACKAGE + "." + nestedCoercerSimpleName(record);
    }

    private static java.util.List<VariableElement> enclosedRecordComponents(TypeElement record) {
        return record.getEnclosedElements().stream()
                .filter(e -> e.getKind() == ElementKind.RECORD_COMPONENT)
                .map(e -> (VariableElement) e)
                .toList();
    }

    /**
     * Reads {@code @Key("…")} from the record's canonical constructor parameter for the
     * given component. Mirrors {@link ConfigurationCollector#buildCanonicalCtorParamMap}
     * because {@code @Key} targets PARAMETER, not RECORD_COMPONENT.
     */
    private Optional<String> readKeyAnnotation(TypeElement record, VariableElement component) {
        VariableElement param = canonicalCtorParam(record, component);
        if (param == null) return Optional.empty();
        io.tiko.annotations.Key keyAnn = param.getAnnotation(io.tiko.annotations.Key.class);
        return keyAnn != null ? Optional.of(keyAnn.value()) : Optional.empty();
    }

    private Optional<String> readDefaultAnnotation(TypeElement record, VariableElement component) {
        VariableElement param = canonicalCtorParam(record, component);
        if (param == null) return Optional.empty();
        io.tiko.annotations.Default defAnn = param.getAnnotation(io.tiko.annotations.Default.class);
        return defAnn != null ? Optional.of(defAnn.value()) : Optional.empty();
    }

    private VariableElement canonicalCtorParam(TypeElement record, VariableElement component) {
        for (var member : record.getEnclosedElements()) {
            if (member.getKind() != ElementKind.CONSTRUCTOR) continue;
            javax.lang.model.element.ExecutableElement ctor = (javax.lang.model.element.ExecutableElement) member;
            for (var p : ctor.getParameters()) {
                if (p.getSimpleName().contentEquals(component.getSimpleName())) {
                    return p;
                }
            }
        }
        return null;
    }

    // ---- Coercer expression building ---------------------------------------

    private CodeBlock coercerExpr(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return primitiveCoercer(type.getKind().name());
        }
        if (type.getKind() == javax.lang.model.type.TypeKind.DECLARED) {
            TypeElement el = (TypeElement) ((DeclaredType) type).asElement();
            String fqn = el.getQualifiedName().toString();
            if (fqn.equals("java.util.List") || fqn.equals("java.util.Set") || fqn.equals("java.util.Map")) {
                DeclaredType dt = (DeclaredType) type;
                int valueArgIdx = fqn.equals("java.util.Map") ? 1 : 0;
                CodeBlock elemCoercer = coercerExpr(dt.getTypeArguments().get(valueArgIdx));
                ClassName helper = ClassName.get(CompositeCoercers.class);
                return switch (fqn) {
                    case "java.util.List" -> CodeBlock.of("$T.list($L)", helper, elemCoercer);
                    case "java.util.Set" -> CodeBlock.of("$T.set($L)", helper, elemCoercer);
                    default -> CodeBlock.of("$T.map($L)", helper, elemCoercer);
                };
            }
            if (el.getKind() == ElementKind.ENUM) {
                ClassName enumType = ClassName.get(el);
                return CodeBlock.of("$T.enumCoercer($T.class)", Coercers.class, enumType);
            }
            if (el.getKind() == ElementKind.RECORD) {
                // Nested record (#17): reference the generated nested coercer.
                ClassName nestedCoercerType = ClassName.get(GENERATED_PACKAGE, nestedCoercerSimpleName(el));
                return CodeBlock.of("$T.coercer()", nestedCoercerType);
            }
            return scalarCoercer(fqn);
        }
        throw new IllegalArgumentException("Unsupported field type: " + type);
    }

    private CodeBlock primitiveCoercer(String kind) {
        return switch (kind) {
            case "INT" -> CodeBlock.of("$T.intCoercer()", Coercers.class);
            case "LONG" -> CodeBlock.of("$T.longCoercer()", Coercers.class);
            case "BOOLEAN" -> CodeBlock.of("$T.booleanCoercer()", Coercers.class);
            case "DOUBLE" -> CodeBlock.of("$T.doubleCoercer()", Coercers.class);
            case "FLOAT" -> CodeBlock.of("$T.floatCoercer()", Coercers.class);
            case "SHORT" -> CodeBlock.of("$T.shortCoercer()", Coercers.class);
            case "BYTE" -> CodeBlock.of("$T.byteCoercer()", Coercers.class);
            case "CHAR" -> CodeBlock.of("$T.charCoercer()", Coercers.class);
            default -> throw new IllegalArgumentException("Unsupported primitive: " + kind);
        };
    }

    private CodeBlock scalarCoercer(String fqn) {
        return switch (fqn) {
            case "java.lang.Integer" -> CodeBlock.of("$T.intCoercer()", Coercers.class);
            case "java.lang.Long" -> CodeBlock.of("$T.longCoercer()", Coercers.class);
            case "java.lang.Boolean" -> CodeBlock.of("$T.booleanCoercer()", Coercers.class);
            case "java.lang.Double" -> CodeBlock.of("$T.doubleCoercer()", Coercers.class);
            case "java.lang.Float" -> CodeBlock.of("$T.floatCoercer()", Coercers.class);
            case "java.lang.Short" -> CodeBlock.of("$T.shortCoercer()", Coercers.class);
            case "java.lang.Byte" -> CodeBlock.of("$T.byteCoercer()", Coercers.class);
            case "java.lang.Character" -> CodeBlock.of("$T.charCoercer()", Coercers.class);
            case "java.lang.String" -> CodeBlock.of("$T.stringCoercer()", Coercers.class);
            case "java.time.Duration" -> CodeBlock.of("$T.durationCoercer()", Coercers.class);
            case "java.time.Instant" -> CodeBlock.of("$T.instantCoercer()", Coercers.class);
            case "java.time.LocalDate" -> CodeBlock.of("$T.localDateCoercer()", Coercers.class);
            case "java.time.LocalDateTime" -> CodeBlock.of("$T.localDateTimeCoercer()", Coercers.class);
            case "java.time.ZoneId" -> CodeBlock.of("$T.zoneIdCoercer()", Coercers.class);
            case "java.util.UUID" -> CodeBlock.of("$T.uuidCoercer()", Coercers.class);
            case "java.net.URI" -> CodeBlock.of("$T.uriCoercer()", Coercers.class);
            case "java.nio.file.Path" -> CodeBlock.of("$T.pathCoercer()", Coercers.class);
            case "java.nio.charset.Charset" -> CodeBlock.of("$T.charsetCoercer()", Coercers.class);
            case "java.math.BigDecimal" -> CodeBlock.of("$T.bigDecimalCoercer()", Coercers.class);
            case "java.util.regex.Pattern" -> CodeBlock.of("$T.patternCoercer()", Coercers.class);
            default -> throw new IllegalArgumentException("Unsupported scalar type: " + fqn);
        };
    }

    private CodeBlock fallbackExpr(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return switch (type.getKind()) {
                case INT -> CodeBlock.of("0");
                case LONG -> CodeBlock.of("0L");
                case SHORT -> CodeBlock.of("(short) 0");
                case BYTE -> CodeBlock.of("(byte) 0");
                case CHAR -> CodeBlock.of("'\\u0000'");
                case DOUBLE -> CodeBlock.of("0.0");
                case FLOAT -> CodeBlock.of("0.0f");
                case BOOLEAN -> CodeBlock.of("false");
                default -> CodeBlock.of("null");
            };
        }
        return CodeBlock.of("null");
    }

    private TypeMirror unwrapOptional(TypeMirror type) {
        if (type.getKind() != javax.lang.model.type.TypeKind.DECLARED) return type;
        DeclaredType dt = (DeclaredType) type;
        TypeElement el = (TypeElement) dt.asElement();
        if (!el.getQualifiedName().toString().equals("java.util.Optional")) return type;
        return dt.getTypeArguments().isEmpty() ? type : dt.getTypeArguments().get(0);
    }

    private boolean isOptional(TypeMirror type) {
        if (type.getKind() != javax.lang.model.type.TypeKind.DECLARED) return false;
        DeclaredType dt = (DeclaredType) type;
        TypeElement el = (TypeElement) dt.asElement();
        return el.getQualifiedName().toString().equals("java.util.Optional");
    }

    private static String quotedJoin(Set<String> keys) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String k : keys) {
            if (!first) sb.append(", ");
            sb.append("\"").append(k).append("\"");
            first = false;
        }
        return sb.toString();
    }
}
