package io.tiko.processor.core.model;

import java.util.List;

/**
 * A field: a Java field, a record component (see {@link ClassDecl#recordComponents()}), a Kotlin
 * property with a backing field, or an enum constant.
 *
 * <p>Equality is declaration identity, across rounds: two instances are equal exactly when they
 * denote the same field, record component or enum constant of the same class, whichever
 * {@code ProcessingEnv} produced them, of the same round or of different rounds; {@code hashCode}
 * follows. A record component and the field that stores it are distinct declarations.
 */
public interface FieldDecl {

    String name();

    TypeRef type();

    /** A {@code static} field (enum constants included); always false for a record component. */
    boolean isStatic();

    List<AnnotationRef> annotations();

    Location location();
}
