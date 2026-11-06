// tiko-processor/src/main/java/io/tiko/processor/config/ConfigBinderGenerator.java
package io.tiko.processor.config;

import com.palantir.javapoet.*;
import io.tiko.config.BindContext;
import io.tiko.config.ConfigBinder;
import io.tiko.config.internal.coercers.Coercers;
import io.tiko.config.internal.coercers.CompositeCoercers;
import io.tiko.config.internal.coercers.NestedRecordSupport;
import io.tiko.config.internal.coercers.TypeCoercer;
import io.tiko.processor.core.CodeOutput;
import io.tiko.processor.core.GeneratorAnnotations;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.DeclKind;
import io.tiko.processor.core.model.FieldDecl;
import io.tiko.processor.core.model.ParamDecl;
import io.tiko.processor.core.model.TypeRef;
import io.tiko.processor.util.TypeRefs;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.Modifier;

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

    private static final String KEY = io.tiko.annotations.Key.class.getCanonicalName();
    private static final String DEFAULT = io.tiko.annotations.Default.class.getCanonicalName();

    private final CodeOutput output;

    /** Tracks emitted nested-coercer simple names so we don't emit the same class twice. */
    private final Set<String> emittedNestedCoercers = new HashSet<>();

    public ConfigBinderGenerator(CodeOutput output) {
        this.output = output;
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
            TypeRef inner = unwrapOptional(f.type());

            CodeBlock coercer = coercerExpr(inner, fullPath);
            TypeName javaType = f.type().javaPoet();

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

        output.writeJava(JavaFile.builder(GENERATED_PACKAGE, binderClass).build(), List.of(), false);
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
    private void emitNestedCoercersFor(TypeRef type) throws IOException {
        var declared = TypeRefs.declared(type);
        if (declared.isEmpty()) return;
        ClassDecl el = declared.get();
        String fqn = el.qualifiedName();
        var typeArguments = type.typeArguments();

        // Walk through container types into their value-type parameters.
        if (fqn.equals("java.util.Optional") || fqn.equals("java.util.List") || fqn.equals("java.util.Set")) {
            if (!typeArguments.isEmpty()) {
                emitNestedCoercersFor(typeArguments.get(0));
            }
            return;
        }
        if (fqn.equals("java.util.Map")) {
            if (typeArguments.size() >= 2) {
                emitNestedCoercersFor(typeArguments.get(1));
            }
            return;
        }

        if (el.kind() == DeclKind.RECORD) {
            String coercerName = nestedCoercerSimpleName(el);
            if (emittedNestedCoercers.add(coercerName)) {
                emitNestedCoercerClass(el, coercerName);
                // Recurse into this nested record's own fields — deeply-nested structures
                // need cascaded coercers.
                for (var member : el.recordComponents()) {
                    emitNestedCoercersFor(member.type());
                }
            }
        }
    }

    private void emitNestedCoercerClass(ClassDecl record, String coercerName) throws IOException {
        ClassName recordType = record.className();
        String recordSimpleName = record.className().simpleName();
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
        Set<String> knownKeys = new LinkedHashSet<>();

        List<FieldDecl> components = record.recordComponents();
        for (int i = 0; i < components.size(); i++) {
            FieldDecl comp = components.get(i);
            String fieldName = comp.name();
            String yamlKey = readKeyAnnotation(record, comp).orElse(fieldName);
            knownKeys.add(yamlKey);

            TypeRef raw = comp.type();
            TypeRef inner = unwrapOptional(raw);
            boolean isOptional = isOptional(raw);
            String defaultValue = readDefaultAnnotation(record, comp).orElse(null);

            CodeBlock coercer = coercerExpr(inner, recordSimpleName + "." + yamlKey);
            TypeName javaType = raw.javaPoet();
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

        doCoerce.addStatement(
                "$T.checkUnknownKeys(node, $S, $T.of($L))",
                NestedRecordSupport.class,
                recordSimpleName,
                Set.class,
                quotedJoin(knownKeys));
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

        output.writeJava(JavaFile.builder(GENERATED_PACKAGE, coercerClass).build(), List.of(), false);
    }

    /**
     * Returns {@code <SimpleName>NestedCoercer_<hash8>} where hash8 is a stable 8-char
     * hex of the record's fully qualified name. Hash suffix avoids collisions when two
     * nested records share a simple name across packages.
     */
    static String nestedCoercerSimpleName(ClassDecl record) {
        String fqn = record.qualifiedName();
        String hash = String.format("%08x", fqn.hashCode());
        return record.className().simpleName() + "NestedCoercer_" + hash;
    }

    static String nestedCoercerQualifiedName(ClassDecl record) {
        return GENERATED_PACKAGE + "." + nestedCoercerSimpleName(record);
    }

    /**
     * Reads {@code @Key("…")} from the record's canonical constructor parameter for the
     * given component. Mirrors {@link ConfigurationCollector#buildCanonicalCtorParamMap}
     * because {@code @Key} targets PARAMETER, not RECORD_COMPONENT.
     */
    private Optional<String> readKeyAnnotation(ClassDecl record, FieldDecl component) {
        return readParamAnnotationValue(record, component, KEY);
    }

    private Optional<String> readDefaultAnnotation(ClassDecl record, FieldDecl component) {
        return readParamAnnotationValue(record, component, DEFAULT);
    }

    private Optional<String> readParamAnnotationValue(ClassDecl record, FieldDecl component, String annotationFqn) {
        ParamDecl param = canonicalCtorParam(record, component);
        if (param == null) return Optional.empty();
        return param.annotation(annotationFqn).map(annotation -> annotation.string("value"));
    }

    private ParamDecl canonicalCtorParam(ClassDecl record, FieldDecl component) {
        for (var ctor : record.constructors()) {
            for (var p : ctor.parameters()) {
                if (p.name().equals(component.name())) {
                    return p;
                }
            }
        }
        return null;
    }

    // ---- Coercer expression building ---------------------------------------

    /**
     * @param field how the field is named in coercer diagnostics: the dot-path for a top-level
     *     {@code @Configuration} field, {@code Record.key} inside a nested record (#485)
     */
    private CodeBlock coercerExpr(TypeRef type, String field) {
        if (type.isPrimitive()) {
            return primitiveCoercer(type.erasedName().toUpperCase(Locale.ROOT));
        }
        var declared = TypeRefs.declared(type);
        if (declared.isPresent()) {
            ClassDecl el = declared.get();
            String fqn = el.qualifiedName();
            if (fqn.equals("java.util.List") || fqn.equals("java.util.Set") || fqn.equals("java.util.Map")) {
                int valueArgIdx = fqn.equals("java.util.Map") ? 1 : 0;
                CodeBlock elemCoercer = coercerExpr(type.typeArguments().get(valueArgIdx), field);
                ClassName helper = ClassName.get(CompositeCoercers.class);
                return switch (fqn) {
                    case "java.util.List" -> CodeBlock.of("$T.list($L)", helper, elemCoercer);
                    case "java.util.Set" -> CodeBlock.of("$T.set($S, $L)", helper, field, elemCoercer);
                    default -> CodeBlock.of("$T.map($L)", helper, elemCoercer);
                };
            }
            if (el.kind() == DeclKind.ENUM) {
                ClassName enumType = el.className();
                return CodeBlock.of("$T.enumCoercer($T.class)", Coercers.class, enumType);
            }
            if (el.kind() == DeclKind.RECORD) {
                // Nested record (#17): reference the generated nested coercer.
                ClassName nestedCoercerType = ClassName.get(GENERATED_PACKAGE, nestedCoercerSimpleName(el));
                return CodeBlock.of("$T.coercer()", nestedCoercerType);
            }
            return scalarCoercer(fqn);
        }
        throw new IllegalArgumentException("Unsupported field type: " + type.displayName());
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

    private CodeBlock fallbackExpr(TypeRef type) {
        if (type.isPrimitive()) {
            return switch (type.erasedName()) {
                case "int" -> CodeBlock.of("0");
                case "long" -> CodeBlock.of("0L");
                case "short" -> CodeBlock.of("(short) 0");
                case "byte" -> CodeBlock.of("(byte) 0");
                case "char" -> CodeBlock.of("'\\u0000'");
                case "double" -> CodeBlock.of("0.0");
                case "float" -> CodeBlock.of("0.0f");
                case "boolean" -> CodeBlock.of("false");
                default -> CodeBlock.of("null");
            };
        }
        return CodeBlock.of("null");
    }

    private TypeRef unwrapOptional(TypeRef type) {
        if (!isOptional(type)) return type;
        return type.typeArguments().isEmpty() ? type : type.typeArguments().get(0);
    }

    private boolean isOptional(TypeRef type) {
        return TypeRefs.isDeclared(type, "java.util.Optional");
    }

    /** The keys as comma-separated string literals, each escaped by JavaPoet's {@code $S} (#479). */
    private static CodeBlock quotedJoin(Set<String> keys) {
        return keys.stream().map(k -> CodeBlock.of("$S", k)).collect(CodeBlock.joining(", "));
    }
}
