package io.tiko.processor.core.model;

import java.util.List;

/**
 * A field (or Kotlin backing field).
 *
 * <p>Two instances are equal when they denote the same declaration, even when different
 * {@code ProcessingEnv} instances (rounds) produced them; {@code hashCode} follows.
 */
public interface FieldDecl {

    String name();

    TypeRef type();

    /** A {@code static} field (enum constants included); always false for a record component. */
    boolean isStatic();

    List<AnnotationRef> annotations();

    Location location();
}
