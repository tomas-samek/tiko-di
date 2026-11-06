package io.tiko.processor.core.ksp;

import com.google.devtools.ksp.symbol.KSClassDeclaration;
import com.google.devtools.ksp.symbol.KSType;
import com.google.devtools.ksp.symbol.KSTypeAlias;
import com.google.devtools.ksp.symbol.KSTypeArgument;
import com.google.devtools.ksp.symbol.KSTypeParameter;
import com.google.devtools.ksp.symbol.Nullability;
import com.google.devtools.ksp.symbol.Variance;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeVariableName;
import com.palantir.javapoet.WildcardTypeName;
import java.util.Map;
import kotlin.sequences.SequencesKt;

/**
 * Renders a {@link KSType} as the Java type javac sees for the same JVM signature, as JavaPoet
 * names it.
 *
 * <ul>
 *   <li>Kotlin built-ins become their Java counterparts through the resolver's Kotlin-to-Java
 *       name map ({@code kotlin.String} is {@code java.lang.String}, {@code kotlin.Any} is
 *       {@code java.lang.Object}, read-only and mutable collections are {@code java.util} ones).
 *   <li>A Kotlin primitive ({@code Int}, {@code Boolean}, ...) is a Java primitive when it is
 *       non-null and not a type argument; nullable, a type argument or a Java platform type, it is
 *       the box.
 *   <li>{@code Unit} in the same position is {@code void}; {@code Nothing} is {@code Void}.
 *   <li>{@code Array<T>} is {@code T[]}, {@code IntArray} and its siblings are primitive arrays.
 *   <li>Use-site variance becomes a wildcard ({@code out T} is {@code ? extends T}, {@code *} is
 *       {@code ?}). Declaration-site variance is not turned into wildcards, which javac would add
 *       for some Kotlin parameter types.
 *   <li>An unresolved type is named as written; a type alias by what it expands to.
 * </ul>
 */
final class KspTypeNames {

    private static final Map<String, TypeName> PRIMITIVES = Map.of(
            "kotlin.Boolean", TypeName.BOOLEAN,
            "kotlin.Byte", TypeName.BYTE,
            "kotlin.Short", TypeName.SHORT,
            "kotlin.Int", TypeName.INT,
            "kotlin.Long", TypeName.LONG,
            "kotlin.Char", TypeName.CHAR,
            "kotlin.Float", TypeName.FLOAT,
            "kotlin.Double", TypeName.DOUBLE);

    private static final Map<String, TypeName> PRIMITIVE_ARRAYS = Map.of(
            "kotlin.BooleanArray", TypeName.BOOLEAN,
            "kotlin.ByteArray", TypeName.BYTE,
            "kotlin.ShortArray", TypeName.SHORT,
            "kotlin.IntArray", TypeName.INT,
            "kotlin.LongArray", TypeName.LONG,
            "kotlin.CharArray", TypeName.CHAR,
            "kotlin.FloatArray", TypeName.FLOAT,
            "kotlin.DoubleArray", TypeName.DOUBLE);

    static final String UNIT = "kotlin.Unit";
    static final String ARRAY = "kotlin.Array";

    private KspTypeNames() {}

    /**
     * The JavaPoet name of {@code type}. {@code boxed} marks a type-argument position, {@code raw}
     * drops the type arguments (an erasure, a class literal).
     */
    static TypeName of(KSType type, boolean boxed, boolean raw, KspModel model) {
        if (type.isError()) {
            return errorName(KspModel.errorName(type.getDeclaration()));
        }
        var declaration = type.getDeclaration();
        if (declaration instanceof KSTypeParameter parameter) {
            return TypeVariableName.get(parameter.getName().asString());
        }
        if (declaration instanceof KSTypeAlias alias) {
            return of(alias.getType().resolve(), boxed, raw, model);
        }
        var classDeclaration = (KSClassDeclaration) declaration;
        var name = classDeclaration.getQualifiedName().asString();
        var primitive = PRIMITIVES.get(name);
        var unboxable = !boxed && type.getNullability() == Nullability.NOT_NULL;
        if (primitive != null) {
            return unboxable ? primitive : primitive.box();
        }
        if (name.equals(UNIT) && unboxable) {
            return TypeName.VOID;
        }
        if (name.equals("kotlin.Nothing")) {
            return ClassName.get(Void.class);
        }
        var primitiveArray = PRIMITIVE_ARRAYS.get(name);
        if (primitiveArray != null) {
            return ArrayTypeName.of(primitiveArray);
        }
        if (name.equals(ARRAY)) {
            return ArrayTypeName.of(arrayComponent(type, raw, model));
        }
        var className = model.className(classDeclaration);
        if (raw || type.getArguments().isEmpty()) {
            return className;
        }
        var arguments = type.getArguments().stream()
                .map(argument -> argument(argument, model))
                .toArray(TypeName[]::new);
        return ParameterizedTypeName.get(className, arguments);
    }

    /** {@code T} of {@code Array<T>}, boxed as Java arrays of generics hold references. */
    private static TypeName arrayComponent(KSType array, boolean raw, KspModel model) {
        var argument = array.getArguments().get(0);
        if (argument.getVariance() == Variance.STAR || argument.getType() == null) {
            return ClassName.OBJECT;
        }
        return of(argument.getType().resolve(), true, raw, model);
    }

    static TypeName argument(KSTypeArgument argument, KspModel model) {
        if (argument.getVariance() == Variance.STAR || argument.getType() == null) {
            return WildcardTypeName.subtypeOf(Object.class);
        }
        var name = of(argument.getType().resolve(), true, false, model);
        return switch (argument.getVariance()) {
            case COVARIANT -> WildcardTypeName.subtypeOf(name);
            case CONTRAVARIANT -> WildcardTypeName.supertypeOf(name);
            default -> name;
        };
    }

    /** The erasure's name: the raw class, a type variable's first bound, an array of erasures. */
    static String erasedName(KSType type, boolean boxed, KspModel model) {
        if (!type.isError() && type.getDeclaration() instanceof KSTypeParameter parameter) {
            var bound = SequencesKt.firstOrNull(parameter.getBounds());
            return bound == null ? "java.lang.Object" : erasedName(bound.resolve(), true, model);
        }
        return erase(of(type, boxed, true, model)).toString();
    }

    private static TypeName erase(TypeName name) {
        return switch (name) {
            case ParameterizedTypeName parameterized -> parameterized.rawType();
            case ArrayTypeName array -> ArrayTypeName.of(erase(array.componentType()));
            case TypeVariableName ignored -> ClassName.OBJECT;
            case WildcardTypeName wildcard -> erase(wildcard.upperBounds().get(0));
            default -> name;
        };
    }

    /** As written: {@code a.b.C} or a bare {@code C}. */
    private static TypeName errorName(String written) {
        var lastDot = written.lastIndexOf('.');
        try {
            return lastDot < 0
                    ? ClassName.get("", written)
                    : ClassName.get(written.substring(0, lastDot), written.substring(lastDot + 1));
        } catch (IllegalArgumentException e) {
            // Not a Java name at all (KSP's own placeholder text): keep it readable.
            return ClassName.get("", "ErrorType");
        }
    }
}
