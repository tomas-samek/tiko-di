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

    TypeRef type();

    /** A Kotlin default argument; always false for Java. */
    boolean hasDefaultValue();

    List<AnnotationRef> annotations();

    Optional<AnnotationRef> annotation(String fqn);

    Location location();
}
