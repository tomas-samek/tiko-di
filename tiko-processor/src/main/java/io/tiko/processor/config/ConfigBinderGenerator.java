// tiko-processor/src/main/java/io/tiko/processor/config/ConfigBinderGenerator.java
package io.tiko.processor.config;

import com.palantir.javapoet.*;
import io.tiko.config.BindContext;
import io.tiko.config.ConfigBinder;
import io.tiko.config.internal.coercers.Coercers;
import io.tiko.config.internal.coercers.CompositeCoercers;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Generates {@code <Record>ConfigBinder.java} for each {@code @Configuration} record.
 *
 * <p>Nested record fields are not yet supported by the codegen (v1 scope limit). When such a
 * field is encountered the generator emits a compile-time ERROR — compilation fails with a clear
 * message rather than succeeding silently and then crashing at runtime.
 */
public final class ConfigBinderGenerator {

    private static final String GENERATED_PACKAGE = "io.tiko.generated.config";

    private final Filer filer;
    private final Messager messager;

    public ConfigBinderGenerator(Filer filer, Messager messager) {
        this.filer = filer;
        this.messager = messager;
    }

    /**
     * Returns {@code true} if a binder can be generated for {@code cfg}, {@code false} if any
     * field uses an unsupported type (e.g. a nested record). In the latter case, a compile-time
     * ERROR is emitted per offending field so the developer gets a clear message.
     */
    public boolean canGenerate(ConfigurationModel cfg) {
        boolean ok = true;
        for (ConfigFieldModel f : cfg.fields()) {
            TypeMirror inner = unwrapOptional(f.type());
            if (inner.getKind() == javax.lang.model.type.TypeKind.DECLARED) {
                TypeElement el = (TypeElement) ((DeclaredType) inner).asElement();
                if (el.getKind() == ElementKind.RECORD) {
                    messager.printMessage(Diagnostic.Kind.ERROR,
                        "@Configuration record '" + cfg.simpleName() + "' uses nested record types"
                            + " in fields, which are not yet supported in v1.\n"
                            + "  Field '" + f.fieldName() + "' has type '" + el.getSimpleName() + "' (a record).\n"
                            + "Suggested fixes:\n"
                            + "  1. Mark the nested record as a separate @Configuration"
                                + " with its own prefix.\n"
                            + "  2. Wait for nested-record codegen support in a follow-up release.",
                        f.element());
                    ok = false;
                }
            }
        }
        return ok;
    }

    /**
     * Generates a binder for {@code cfg}. Callers must invoke {@link #canGenerate} first;
     * this method assumes all fields are supported.
     */
    public void generate(ConfigurationModel cfg) throws IOException {
        ClassName recordType = ClassName.get(cfg.packageName(), cfg.simpleName());

        // bind(Map<String, Object> root, BindContext ctx)
        MethodSpec.Builder bind = MethodSpec.methodBuilder("bind")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(recordType)
            .addParameter(ParameterizedTypeName.get(
                ClassName.get(Map.class),
                ClassName.get(String.class),
                ClassName.get(Object.class)), "root")
            .addParameter(ClassName.get(BindContext.class), "ctx");

        bind.addStatement("$T<$T, $T> node = ctx.requireSection(root, $S)",
            Map.class, String.class, Object.class, cfg.prefix());

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
                case OPTIONAL -> bind.addStatement("$T $L = ctx.optionalScalar(node, $S, $S, $L)",
                    javaType, varName, f.yamlKey(), fullPath, coercer);
                case DEFAULTED -> bind.addStatement(
                    "$T $L = ctx.scalarOrDefault(node, $S, $S, $L, $L.coerce($S))",
                    javaType, varName, f.yamlKey(), fullPath, coercer, coercer, f.defaultValue());
                case REQUIRED -> bind.addStatement(
                    "$T $L = ctx.requireScalar(node, $S, $S, $L, $L)",
                    javaType, varName, f.yamlKey(), fullPath, coercer, fallbackExpr(inner));
            }

            if (i > 0) ctorArgs.append(", ");
            ctorArgs.append(varName);
        }

        bind.addStatement("ctx.checkUnknownKeys(node, $S, $T.of($L))",
            cfg.prefix(), Set.class, quotedJoin(consumedKeys));
        bind.addStatement("return new $T($L)", recordType, ctorArgs.toString());

        // type() override
        MethodSpec typeM = MethodSpec.methodBuilder("type")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(ParameterizedTypeName.get(ClassName.get(Class.class), recordType))
            .addStatement("return $T.class", recordType)
            .build();

        // prefix() override
        MethodSpec prefixM = MethodSpec.methodBuilder("prefix")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(ClassName.get(String.class))
            .addStatement("return $S", cfg.prefix())
            .build();

        TypeSpec binderClass = TypeSpec.classBuilder(cfg.binderSimpleName())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(ParameterizedTypeName.get(ClassName.get(ConfigBinder.class), recordType))
            .addMethod(typeM)
            .addMethod(prefixM)
            .addMethod(bind.build())
            .build();

        JavaFile.builder(GENERATED_PACKAGE, binderClass).build().writeTo(filer);
    }

    private CodeBlock coercerExpr(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return primitiveCoercer(type.getKind().name());
        }
        if (type.getKind() == javax.lang.model.type.TypeKind.DECLARED) {
            TypeElement el = (TypeElement) ((DeclaredType) type).asElement();
            String fqn = el.getQualifiedName().toString();
            if (fqn.equals("java.util.List") || fqn.equals("java.util.Map")) {
                DeclaredType dt = (DeclaredType) type;
                int valueArgIdx = fqn.equals("java.util.Map") ? 1 : 0;
                CodeBlock elemCoercer = coercerExpr(dt.getTypeArguments().get(valueArgIdx));
                ClassName helper = ClassName.get(CompositeCoercers.class);
                return fqn.equals("java.util.List")
                    ? CodeBlock.of("$T.list($L)", helper, elemCoercer)
                    : CodeBlock.of("$T.map($L)", helper, elemCoercer);
            }
            if (el.getKind() == ElementKind.ENUM) {
                ClassName enumType = ClassName.get(el);
                return CodeBlock.of("$T.enumCoercer($T.class)", Coercers.class, enumType);
            }
            if (el.getKind() == ElementKind.RECORD) {
                throw new IllegalArgumentException(
                    "nested record type '" + el.getSimpleName() + "' (codegen v1 does not support nested records)");
            }
            return scalarCoercer(fqn);
        }
        throw new IllegalArgumentException("Unsupported field type: " + type);
    }

    private CodeBlock primitiveCoercer(String kind) {
        return switch (kind) {
            case "INT"     -> CodeBlock.of("$T.intCoercer()", Coercers.class);
            case "LONG"    -> CodeBlock.of("$T.longCoercer()", Coercers.class);
            case "BOOLEAN" -> CodeBlock.of("$T.booleanCoercer()", Coercers.class);
            case "DOUBLE"  -> CodeBlock.of("$T.doubleCoercer()", Coercers.class);
            case "FLOAT"   -> CodeBlock.of("$T.floatCoercer()", Coercers.class);
            case "SHORT"   -> CodeBlock.of("$T.shortCoercer()", Coercers.class);
            case "BYTE"    -> CodeBlock.of("$T.byteCoercer()", Coercers.class);
            case "CHAR"    -> CodeBlock.of("$T.charCoercer()", Coercers.class);
            default -> throw new IllegalArgumentException("Unsupported primitive: " + kind);
        };
    }

    private CodeBlock scalarCoercer(String fqn) {
        return switch (fqn) {
            case "java.lang.Integer"       -> CodeBlock.of("$T.intCoercer()", Coercers.class);
            case "java.lang.Long"          -> CodeBlock.of("$T.longCoercer()", Coercers.class);
            case "java.lang.Boolean"       -> CodeBlock.of("$T.booleanCoercer()", Coercers.class);
            case "java.lang.Double"        -> CodeBlock.of("$T.doubleCoercer()", Coercers.class);
            case "java.lang.Float"         -> CodeBlock.of("$T.floatCoercer()", Coercers.class);
            case "java.lang.Short"         -> CodeBlock.of("$T.shortCoercer()", Coercers.class);
            case "java.lang.Byte"          -> CodeBlock.of("$T.byteCoercer()", Coercers.class);
            case "java.lang.Character"     -> CodeBlock.of("$T.charCoercer()", Coercers.class);
            case "java.lang.String"        -> CodeBlock.of("$T.stringCoercer()", Coercers.class);
            case "java.time.Duration"      -> CodeBlock.of("$T.durationCoercer()", Coercers.class);
            case "java.time.Instant"       -> CodeBlock.of("$T.instantCoercer()", Coercers.class);
            case "java.time.LocalDate"     -> CodeBlock.of("$T.localDateCoercer()", Coercers.class);
            case "java.time.LocalDateTime" -> CodeBlock.of("$T.localDateTimeCoercer()", Coercers.class);
            case "java.time.ZoneId"        -> CodeBlock.of("$T.zoneIdCoercer()", Coercers.class);
            case "java.util.UUID"          -> CodeBlock.of("$T.uuidCoercer()", Coercers.class);
            case "java.net.URI"            -> CodeBlock.of("$T.uriCoercer()", Coercers.class);
            case "java.nio.file.Path"      -> CodeBlock.of("$T.pathCoercer()", Coercers.class);
            case "java.nio.charset.Charset"-> CodeBlock.of("$T.charsetCoercer()", Coercers.class);
            case "java.math.BigDecimal"    -> CodeBlock.of("$T.bigDecimalCoercer()", Coercers.class);
            case "java.util.regex.Pattern" -> CodeBlock.of("$T.patternCoercer()", Coercers.class);
            default -> throw new IllegalArgumentException("Unsupported scalar type: " + fqn);
        };
    }

    private CodeBlock fallbackExpr(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return switch (type.getKind()) {
                case INT, LONG, SHORT, BYTE, CHAR -> CodeBlock.of("0");
                case DOUBLE, FLOAT -> CodeBlock.of("0.0");
                case BOOLEAN -> CodeBlock.of("false");
                default -> CodeBlock.of("null");
            };
        }
        // Object types — null fallback (the error has already been accumulated)
        return CodeBlock.of("null");
    }

    private TypeMirror unwrapOptional(TypeMirror type) {
        if (type.getKind() != javax.lang.model.type.TypeKind.DECLARED) return type;
        DeclaredType dt = (DeclaredType) type;
        TypeElement el = (TypeElement) dt.asElement();
        if (!el.getQualifiedName().toString().equals("java.util.Optional")) return type;
        return dt.getTypeArguments().isEmpty() ? type : dt.getTypeArguments().get(0);
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
