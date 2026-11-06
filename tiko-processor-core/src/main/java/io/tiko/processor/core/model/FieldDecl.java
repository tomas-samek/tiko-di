package io.tiko.processor.core.model;

import java.util.List;

/**
 * A field: a Java field, a record component (see {@link ClassDecl#recordComponents()}), a Kotlin
 * property with a backing field, or an enum constant.
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
