package io.tiko.processor.core.model;

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

    boolean isSuspend();

    /** A Kotlin top-level function; always false for Java. */
    boolean isTopLevel();

    Visibility visibility();

    TypeRef returnType();

    List<ParamDecl> parameters();

    List<TypeRef> thrownTypes();

    List<AnnotationRef> annotations();

    Optional<AnnotationRef> annotation(String annotationFqn);

    /** The declaring class; empty only for Kotlin top-level functions. */
    Optional<ClassDecl> owner();

    Location location();
}
