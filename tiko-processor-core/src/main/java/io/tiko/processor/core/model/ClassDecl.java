package io.tiko.processor.core.model;

import com.palantir.javapoet.ClassName;
import java.util.List;
import java.util.Optional;

/**
 * A type declaration: class, interface, record, enum, annotation, or Kotlin object / companion.
 *
 * <p>Two instances are equal when they denote the same declaration, even when different
 * {@code ProcessingEnv} instances (rounds) produced them; {@code hashCode} follows.
 */
public interface ClassDecl {

    String qualifiedName();

    ClassName className();

    DeclKind kind();

    Visibility visibility();

    boolean isAbstract();

    boolean isFinal();

    boolean isStatic();

    SourceLanguage language();

    List<AnnotationRef> annotations();

    Optional<AnnotationRef> annotation(String annotationFqn);

    /**
     * Every occurrence of the annotation, with the semantics of
     * {@link java.lang.reflect.AnnotatedElement#getAnnotationsByType}: written directly or wrapped
     * in its {@link java.lang.annotation.Repeatable @Repeatable} container, in source order (a
     * container's contents at the container's position). Empty when the annotation is absent.
     */
    List<AnnotationRef> annotationsByType(String annotationFqn);

    List<MethodDecl> constructors();

    /** The Kotlin primary constructor; always empty for Java. */
    Optional<MethodDecl> primaryConstructor();

    /**
     * Methods declared directly on this type (not inherited ones). A Kotlin property contributes the
     * accessor methods the JVM has for it ({@code getX} / {@code isX} / {@code setX}, named by
     * {@link MethodDecl#jvmName()}), as javac sees a Kotlin-compiled class.
     */
    List<MethodDecl> methods();

    /**
     * Methods declared on this type plus those it inherits from its superclasses and
     * superinterfaces (JLS 8.4.8): an overridden method appears once, as the most specific
     * override; private methods of supertypes and static interface methods are not inherited and
     * do not appear; constructors never do. The root class's methods are included
     * ({@code java.lang.Object}'s on javac; on KSP Kotlin's {@code Any}, which declares only
     * {@code equals}, {@code hashCode} and {@code toString}). Kotlin properties contribute their
     * accessor methods, as in {@link #methods()}. The order is unspecified.
     */
    List<MethodDecl> allMethods();

    /** Fields declared directly on this type. */
    List<FieldDecl> fields();

    /** The record components in declaration order; empty for anything but a record. */
    List<FieldDecl> recordComponents();

    /** The names of the enum constants in declaration order; empty for anything but an enum. */
    List<String> enumConstants();

    /** The declared superclass (if any) followed by the declared interfaces. */
    List<TypeRef> directSupertypes();

    /**
     * The superclass: the declared one, or the implicit one ({@code java.lang.Object} for a
     * plain class, {@code java.lang.Record} for a record, {@code java.lang.Enum<E>} for an enum).
     * Empty for interfaces, annotations and {@code java.lang.Object} itself. A superclass that does
     * not resolve is still present (see {@link TypeRef#isError()}).
     */
    Optional<TypeRef> superclass();

    /**
     * The interfaces the declaration names in its {@code implements} clause (for an interface,
     * its {@code extends} clause), in declaration order.
     */
    List<TypeRef> interfaces();

    /**
     * The {@code /}-separated path of the file the declaration was read from (on javac the path
     * of the file object's URI): a source file of this compilation, or a class file for a
     * classpath declaration. Empty when the frontend does not report one, or when the file has no
     * hierarchical path (a class inside a jar, for example).
     */
    Optional<String> sourcePath();

    TypeRef asType();

    Location location();

    Optional<ClassDecl> enclosingClass();
}
