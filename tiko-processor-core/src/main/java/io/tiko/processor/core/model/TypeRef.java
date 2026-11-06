package io.tiko.processor.core.model;

import com.palantir.javapoet.TypeName;
import java.util.List;
import java.util.Optional;

/**
 * A use of a type: a declared class (possibly parameterized), a primitive, an array or a type variable.
 *
 * <p>{@code equals} is <em>not</em> semantic type equality: two refs to the same type may differ.
 * Compare types with {@link io.tiko.processor.core.TypeOracle#isSameType} or {@link #erasedName()}.
 */
public interface TypeRef {

    /** The JavaPoet form generators emit. */
    TypeName javaPoet();

    /** Fully qualified name without type arguments; {@code "int"} for primitives, {@code "void"} for void. */
    String erasedName();

    /**
     * The Java-source rendering of the type, with type arguments: on javac exactly
     * {@code TypeMirror.toString()}, on KSP the same text for the Java type javac sees
     * ({@code kotlin.String} renders as {@code java.lang.String}). It is identical across
     * frontends for the same Java type, and nullability never shows in it. DI uses it as the
     * dependency-key and class-lookup text, besides messages and topology output. It is not
     * semantic type equality: compare types with
     * {@link io.tiko.processor.core.TypeOracle#isSameType} or {@link #erasedName()}.
     */
    String displayName();

    List<TypeRef> typeArguments();

    Nullability nullability();

    boolean isPrimitive();

    /**
     * A type that does not resolve: on javac {@code TypeKind.ERROR}, for example a class another
     * processor has yet to generate. Its {@link #declaration()} is the placeholder.
     */
    boolean isError();

    /** A {@code void} return type (on KSP, also a Kotlin {@code Unit} return type). */
    boolean isVoid();

    /**
     * The declaring class; empty for primitives, arrays and type variables. A type that does not
     * resolve still has one: a placeholder named as written, with no members.
     */
    Optional<ClassDecl> declaration();
}
