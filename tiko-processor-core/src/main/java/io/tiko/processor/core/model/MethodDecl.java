package io.tiko.processor.core.model;

import com.palantir.javapoet.TypeVariableName;
import java.util.List;
import java.util.Optional;

/**
 * A method or constructor.
 *
 * <p>Equality is declaration identity, across rounds: two instances are equal exactly when they
 * denote the same method or constructor, whichever {@code ProcessingEnv} produced them, of the
 * same round or of different rounds; {@code hashCode} follows. A method a subclass inherits (see
 * {@link ClassDecl#allMethods()}) is the declaration it inherits, and overloads are distinct
 * declarations.
 */
public interface MethodDecl {

    String name();

    /** The name generated Java must call; differs from {@link #name()} for Kotlin internal / {@code @JvmName}. */
    String jvmName();

    boolean isConstructor();

    boolean isStatic();

    boolean isAbstract();

    /** An interface method with a body: Java {@code default}, a Kotlin interface member with a body. */
    boolean isDefault();

    boolean isSuspend();

    /** A Kotlin top-level function; always false for Java. */
    boolean isTopLevel();

    /** A Kotlin extension function: it declares a receiver type ({@code fun Config.clock()}); always false for Java. */
    boolean isExtension();

    /**
     * A Kotlin function or property accessor whose JVM parameter types kotlinc chooses: a parameter
     * with declaration-site variance becomes a wildcard type ({@code List<Item>} compiles to
     * {@code List<? extends Item>}), so {@link #parameters()} may not show the JVM signature. True
     * for Kotlin declarations read by KSP, from source or from a library; always false on javac,
     * which reads the JVM signature itself.
     */
    boolean hasKotlinJvmSignature();

    Visibility visibility();

    TypeRef returnType();

    List<ParamDecl> parameters();

    List<TypeRef> thrownTypes();

    /** The method's own type parameters, in declaration order, as generated Java declares them. */
    List<TypeVariableName> typeVariables();

    List<AnnotationRef> annotations();

    Optional<AnnotationRef> annotation(String annotationFqn);

    /**
     * Every occurrence of the annotation, with the semantics of
     * {@link java.lang.reflect.AnnotatedElement#getAnnotationsByType}: written directly or wrapped
     * in its {@link java.lang.annotation.Repeatable @Repeatable} container, in source order (a
     * container's contents at the container's position). Empty when the annotation is absent.
     */
    List<AnnotationRef> annotationsByType(String annotationFqn);

    /** The declaring class; empty only for Kotlin top-level functions. */
    Optional<ClassDecl> owner();

    Location location();
}
