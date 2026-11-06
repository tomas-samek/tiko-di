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

    /** Methods declared directly on this type (not inherited ones). */
    List<MethodDecl> methods();

    /**
     * Methods declared on this type plus those it inherits from its superclasses and
     * superinterfaces, {@code java.lang.Object}'s included, as javac's
     * {@code Elements.getAllMembers} sees them: an overridden method appears once, as the most
     * specific override. The order is unspecified.
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

    TypeRef asType();

    Location location();

    Optional<ClassDecl> enclosingClass();
}
