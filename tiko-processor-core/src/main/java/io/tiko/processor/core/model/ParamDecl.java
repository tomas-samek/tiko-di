package io.tiko.processor.core.model;

import java.util.List;
import java.util.Optional;

/**
 * A method or constructor parameter.
 *
 * <p>Two instances are equal when they denote the same declaration, even when different
 * {@code ProcessingEnv} instances (rounds) produced them; {@code hashCode} follows.
 */
public interface ParamDecl {

    String name();

    /** The declared type; for a vararg parameter the array type, as the JVM signature (and javac) has it. */
    TypeRef type();

    /** A Kotlin default argument; always false for Java. */
    boolean hasDefaultValue();

    List<AnnotationRef> annotations();

    Optional<AnnotationRef> annotation(String fqn);

    /**
     * Every occurrence of the annotation, with the semantics of
     * {@link java.lang.reflect.AnnotatedElement#getAnnotationsByType}: written directly or wrapped
     * in its {@link java.lang.annotation.Repeatable @Repeatable} container, in source order (a
     * container's contents at the container's position). Empty when the annotation is absent.
     */
    List<AnnotationRef> annotationsByType(String annotationFqn);

    Location location();
}
