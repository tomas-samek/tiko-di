package io.tiko.processor.core.ksp;

import com.google.devtools.ksp.processing.Resolver;
import com.google.devtools.ksp.symbol.KSClassDeclaration;
import com.google.devtools.ksp.symbol.KSType;
import com.google.devtools.ksp.symbol.KSTypeAlias;
import com.google.devtools.ksp.symbol.KSTypeArgument;
import com.google.devtools.ksp.symbol.KSTypeParameter;
import com.google.devtools.ksp.symbol.KSValueParameter;
import com.google.devtools.ksp.symbol.Modifier;
import com.google.devtools.ksp.symbol.Nullability;
import com.google.devtools.ksp.symbol.Variance;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeVariableName;
import com.palantir.javapoet.WildcardTypeName;
import java.util.List;
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
 *       for some Kotlin parameter types. JavaPoet has one form for {@code ? extends Object} and
 *       {@code ?}, so {@code out Any} renders as {@code ?}.
 *   <li>An {@code inner} class of a generic class is its outer type, then the class with its own
 *       arguments ({@code Outer<B>.Inner<A>}), as Java writes it.
 *   <li>A vararg parameter is the array of its JVM signature (see {@link #varargArray}).
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
                .toList();
        return parameterized(classDeclaration, className, arguments);
    }

    /**
     * The class with its type arguments. KSP lists an {@code inner} class's own arguments first and
     * its outer classes' after them; Java writes the outer type first ({@code Outer<B>.Inner<A>}).
     */
    private static TypeName parameterized(
            KSClassDeclaration declaration, ClassName className, List<TypeName> arguments) {
        var own = declaration.getTypeParameters().size();
        if (own < arguments.size()
                && declaration.getModifiers().contains(Modifier.INNER)
                && declaration.getParentDeclaration() instanceof KSClassDeclaration outer
                && className.enclosingClassName() != null
                && parameterized(outer, className.enclosingClassName(), arguments.subList(own, arguments.size()))
                        instanceof ParameterizedTypeName enclosing) {
            return enclosing.nestedClass(className.simpleName(), arguments.subList(0, own));
        }
        return ParameterizedTypeName.get(className, arguments.toArray(TypeName[]::new));
    }

    /**
     * The type a vararg parameter has in its JVM signature, which is what javac reports for it: a
     * primitive array for a non-null primitive element ({@code vararg n: Int} is {@code int[]}),
     * {@code Array<out T>} otherwise ({@code T[]}). KSP gives the parameter its element type.
     */
    static KSType varargArray(KSValueParameter parameter, Resolver resolver) {
        var element = parameter.getType();
        var resolved = element.resolve();
        if (!resolved.isError()
                && !resolved.isMarkedNullable()
                && resolved.getDeclaration() instanceof KSClassDeclaration declaration
                && PRIMITIVES.containsKey(declaration.getQualifiedName().asString())) {
            var arrayName = declaration.getQualifiedName().asString() + "Array";
            return KspModel.findClass(resolver, arrayName).asStarProjectedType();
        }
        return resolver.getBuiltIns()
                .getArrayType()
                .replace(List.of(resolver.getTypeArgument(element, Variance.COVARIANT)));
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

    /**
     * Erases a raw rendering ({@link #of} with {@code raw}), which has no type arguments left: only
     * a type variable, possibly as an array component ({@code Array<T>}), remains to erase.
     */
    private static TypeName erase(TypeName name) {
        return switch (name) {
            case ArrayTypeName array -> ArrayTypeName.of(erase(array.componentType()));
            case TypeVariableName ignored -> ClassName.OBJECT;
            default -> name;
        };
    }

    /**
     * The type as javac's {@code TypeMirror.toString()} renders it, which DI keys and messages
     * compare as text: type arguments joined by {@code ","} without a space, {@code ?},
     * {@code ? extends X} and {@code ? super X} for wildcards, {@code X[]} for arrays, and nested
     * classes by their canonical name ({@code a.Outer.Inner}). JavaPoet's own {@code toString()}
     * differs (it joins arguments with {@code ", "}), so it is never used for this text. An inner
     * class of a generic class follows its parameterized outer type, as javac writes it:
     * {@code a.Outer<T>.Inner<A>}.
     */
    static String javacText(TypeName name) {
        return switch (name) {
            case ParameterizedTypeName parameterized -> {
                var arguments = parameterized.typeArguments().stream()
                        .map(KspTypeNames::javacText)
                        .toList();
                var raw = parameterized.enclosingType() == null
                        ? parameterized.rawType().canonicalName()
                        : javacText(parameterized.enclosingType()) + "."
                                + parameterized.rawType().simpleName();
                yield arguments.isEmpty() ? raw : raw + "<" + String.join(",", arguments) + ">";
            }
            case ClassName className -> className.canonicalName();
            case ArrayTypeName array -> javacText(array.componentType()) + "[]";
            case WildcardTypeName wildcard -> wildcardText(wildcard);
            case TypeVariableName variable -> variable.name();
            default -> name.toString(); // primitives and void
        };
    }

    private static String wildcardText(WildcardTypeName wildcard) {
        if (!wildcard.lowerBounds().isEmpty()) {
            return "? super " + javacText(wildcard.lowerBounds().get(0));
        }
        var upper = wildcard.upperBounds().get(0);
        return ClassName.OBJECT.equals(upper) ? "?" : "? extends " + javacText(upper);
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
