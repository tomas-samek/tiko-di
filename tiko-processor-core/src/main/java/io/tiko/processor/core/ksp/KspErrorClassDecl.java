package io.tiko.processor.core.ksp;

import com.palantir.javapoet.ClassName;
import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.DeclKind;
import io.tiko.processor.core.model.FieldDecl;
import io.tiko.processor.core.model.Location;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.SourceLanguage;
import io.tiko.processor.core.model.TypeRef;
import io.tiko.processor.core.model.Visibility;
import java.util.List;
import java.util.Optional;

/**
 * The placeholder declaration of a type that does not resolve (for example a class another
 * processor has yet to generate): named as written, with no members, like javac's erroneous class.
 * It holds no KSP symbol of its own, so it never goes stale.
 */
record KspErrorClassDecl(String name, TypeRef type) implements ClassDecl {

    @Override
    public String qualifiedName() {
        return name;
    }

    @Override
    public ClassName className() {
        var lastDot = name.lastIndexOf('.');
        return lastDot < 0
                ? ClassName.get("", name)
                : ClassName.get(name.substring(0, lastDot), name.substring(lastDot + 1));
    }

    @Override
    public DeclKind kind() {
        return DeclKind.CLASS;
    }

    @Override
    public Visibility visibility() {
        return Visibility.PUBLIC;
    }

    @Override
    public boolean isAbstract() {
        return false;
    }

    @Override
    public boolean isFinal() {
        return false;
    }

    @Override
    public boolean isValueClass() {
        return false;
    }

    @Override
    public boolean isStatic() {
        return false;
    }

    @Override
    public SourceLanguage language() {
        return SourceLanguage.CLASSPATH;
    }

    @Override
    public List<AnnotationRef> annotations() {
        return List.of();
    }

    @Override
    public Optional<AnnotationRef> annotation(String annotationFqn) {
        return Optional.empty();
    }

    @Override
    public List<AnnotationRef> annotationsByType(String annotationFqn) {
        return List.of();
    }

    @Override
    public List<MethodDecl> constructors() {
        return List.of();
    }

    @Override
    public Optional<MethodDecl> primaryConstructor() {
        return Optional.empty();
    }

    @Override
    public List<MethodDecl> methods() {
        return List.of();
    }

    @Override
    public List<MethodDecl> allMethods() {
        return List.of();
    }

    @Override
    public List<FieldDecl> fields() {
        return List.of();
    }

    @Override
    public List<FieldDecl> recordComponents() {
        return List.of();
    }

    @Override
    public boolean recordComponentsKnown() {
        return true;
    }

    @Override
    public List<String> enumConstants() {
        return List.of();
    }

    @Override
    public List<TypeRef> directSupertypes() {
        return List.of();
    }

    @Override
    public Optional<TypeRef> superclass() {
        return Optional.empty();
    }

    @Override
    public List<TypeRef> interfaces() {
        return List.of();
    }

    @Override
    public Optional<String> sourcePath() {
        return Optional.empty();
    }

    /** The erroneous type this placeholder was reached from. */
    @Override
    public TypeRef asType() {
        if (type == null) {
            throw new UnsupportedOperationException("Unresolved type " + name + " has no type to hand out");
        }
        return type;
    }

    @Override
    public Location location() {
        return new KspLocation(null, name);
    }

    @Override
    public Optional<ClassDecl> enclosingClass() {
        return Optional.empty();
    }

    /** Equal by name, as javac's erroneous classes of one name are one symbol. */
    @Override
    public boolean equals(Object other) {
        return other instanceof KspErrorClassDecl that && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
