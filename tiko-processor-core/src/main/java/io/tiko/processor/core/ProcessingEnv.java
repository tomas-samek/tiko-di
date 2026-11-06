package io.tiko.processor.core;

import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.PropertyAccessorDecl;
import java.util.List;
import java.util.Map;

/**
 * One processing round as the processors see it, independent of the compiler frontend. A
 * frontend adapter creates a fresh instance for each round.
 */
public interface ProcessingEnv {

    Diagnostics diagnostics();

    CodeOutput output();

    TypeOracle types();

    ClasspathProbe classpath();

    /** Processor options ({@code -A} keys on javac). */
    Map<String, String> options();

    SourceVersionInfo sourceVersion();

    /** Classes in this round carrying the annotation; empty when the annotation type is unknown. */
    List<ClassDecl> classesAnnotatedWith(String annotationFqn);

    /** Methods in this round carrying the annotation, Kotlin top-level functions included. */
    List<MethodDecl> methodsAnnotatedWith(String annotationFqn);

    /**
     * Kotlin property accessors in this round carrying the annotation through a {@code @get:} /
     * {@code @set:} use-site target, in source order. They are not methods of
     * {@link #methodsAnnotatedWith}; always empty on javac, as Java has no property accessors.
     */
    List<PropertyAccessorDecl> propertyAccessorsAnnotatedWith(String annotationFqn);
}
