package io.tiko.processor.core.model;

import com.palantir.javapoet.TypeVariableName;
import java.util.List;
import java.util.Optional;

/**
 * A method or constructor.
 *
 * <p>Two instances are equal when they denote the same declaration, even when different
 * {@code ProcessingEnv} instances (rounds) produced them; {@code hashCode} follows.
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
