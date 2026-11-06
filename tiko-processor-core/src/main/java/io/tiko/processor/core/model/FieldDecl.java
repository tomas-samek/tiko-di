package io.tiko.processor.core.model;

import java.util.List;

/** A field (or Kotlin backing field). */
public interface FieldDecl {

    String name();

    TypeRef type();

    List<AnnotationRef> annotations();

    Location location();
}
