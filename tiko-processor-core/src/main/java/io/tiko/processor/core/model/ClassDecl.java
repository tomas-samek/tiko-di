package io.tiko.processor.core.model;

import com.palantir.javapoet.ClassName;
import java.util.List;
import java.util.Optional;

/** A type declaration: class, interface, record, enum, annotation, or Kotlin object / companion. */
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

    List<MethodDecl> constructors();

    /** The Kotlin primary constructor; always empty for Java. */
    Optional<MethodDecl> primaryConstructor();

    /** Methods declared directly on this type (not inherited ones). */
    List<MethodDecl> methods();

    /** Fields declared directly on this type. */
    List<FieldDecl> fields();

    /** The declared superclass (if any) followed by the declared interfaces. */
    List<TypeRef> directSupertypes();

    TypeRef asType();

    Location location();

    Optional<ClassDecl> enclosingClass();
}
