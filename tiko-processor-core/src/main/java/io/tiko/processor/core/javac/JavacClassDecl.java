package io.tiko.processor.core.javac;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.ElementFilter;

/** A javac {@link TypeElement} as a {@link ClassDecl}. */
record JavacClassDecl(TypeElement element, JavacModel model) implements ClassDecl {

    @Override
    public String qualifiedName() {
        return element.getQualifiedName().toString();
    }

    @Override
    public ClassName className() {
        return ClassName.get(element);
    }

    @Override
    public DeclKind kind() {
        return switch (element.getKind()) {
            case CLASS -> DeclKind.CLASS;
            case INTERFACE -> DeclKind.INTERFACE;
            case RECORD -> DeclKind.RECORD;
            case ENUM -> DeclKind.ENUM;
            case ANNOTATION_TYPE -> DeclKind.ANNOTATION;
            default -> throw new IllegalStateException("Not a type declaration: " + element.getKind());
        };
    }

    @Override
    public Visibility visibility() {
        return JavacModel.visibility(element.getModifiers());
    }

    @Override
    public boolean isAbstract() {
        return element.getModifiers().contains(Modifier.ABSTRACT);
    }

    @Override
    public boolean isFinal() {
        return element.getModifiers().contains(Modifier.FINAL);
    }

    @Override
    public boolean isStatic() {
        return element.getModifiers().contains(Modifier.STATIC);
    }

    @Override
    public SourceLanguage language() {
        return model.language(element);
    }

    @Override
    public List<AnnotationRef> annotations() {
        return model.annotations(element);
    }

    @Override
    public Optional<AnnotationRef> annotation(String annotationFqn) {
        return model.annotation(element, annotationFqn);
    }

    @Override
    public List<AnnotationRef> annotationsByType(String annotationFqn) {
        return model.annotationsByType(element, annotationFqn);
    }

    @Override
    public List<MethodDecl> constructors() {
        return ElementFilter.constructorsIn(element.getEnclosedElements()).stream()
                .map(model::methodDecl)
                .toList();
    }

    @Override
    public Optional<MethodDecl> primaryConstructor() {
        return Optional.empty();
    }

    @Override
    public List<MethodDecl> methods() {
        return ElementFilter.methodsIn(element.getEnclosedElements()).stream()
                .map(model::methodDecl)
                .toList();
    }

    @Override
    public List<MethodDecl> allMethods() {
        return ElementFilter.methodsIn(model.elements().getAllMembers(element)).stream()
                .map(model::methodDecl)
                .toList();
    }

    @Override
    public List<FieldDecl> fields() {
        return ElementFilter.fieldsIn(element.getEnclosedElements()).stream()
                .<FieldDecl>map(field -> new JavacFieldDecl(field, model))
                .toList();
    }

    @Override
    public List<FieldDecl> recordComponents() {
        return ElementFilter.recordComponentsIn(element.getEnclosedElements()).stream()
                .<FieldDecl>map(component -> new JavacFieldDecl(component, model))
                .toList();
    }

    @Override
    public List<String> enumConstants() {
        return element.getEnclosedElements().stream()
                .filter(member -> member.getKind() == ElementKind.ENUM_CONSTANT)
                .map(constant -> constant.getSimpleName().toString())
                .toList();
    }

    @Override
    public List<TypeRef> directSupertypes() {
        var supertypes = new ArrayList<TypeRef>();
        if (element.getSuperclass().getKind() != TypeKind.NONE) {
            supertypes.add(model.typeRef(element.getSuperclass()));
        }
        element.getInterfaces().forEach(type -> supertypes.add(model.typeRef(type)));
        return List.copyOf(supertypes);
    }

    @Override
    public TypeRef asType() {
        return model.typeRef(element.asType());
    }

    @Override
    public Location location() {
        return JavacLocation.of(element);
    }

    @Override
    public Optional<ClassDecl> enclosingClass() {
        return element.getEnclosingElement() instanceof TypeElement enclosing
                ? Optional.of(model.classDecl(enclosing))
                : Optional.empty();
    }

    /** Equal when wrapping the same element, whichever round's env made it. */
    @Override
    public boolean equals(Object other) {
        return other instanceof JavacClassDecl that && element.equals(that.element);
    }

    @Override
    public int hashCode() {
        return element.hashCode();
    }

    @Override
    public String toString() {
        return element.toString();
    }
}
