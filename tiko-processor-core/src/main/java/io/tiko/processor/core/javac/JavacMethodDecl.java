package io.tiko.processor.core.javac;

import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.Location;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.ParamDecl;
import io.tiko.processor.core.model.TypeRef;
import io.tiko.processor.core.model.Visibility;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

/** A javac {@link ExecutableElement} (method or constructor) as a {@link MethodDecl}. */
record JavacMethodDecl(ExecutableElement element, JavacModel model) implements MethodDecl {

    @Override
    public String name() {
        return element.getSimpleName().toString();
    }

    /** Java has no name mangling: the JVM name is the source name. */
    @Override
    public String jvmName() {
        return name();
    }

    @Override
    public boolean isConstructor() {
        return element.getKind() == ElementKind.CONSTRUCTOR;
    }

    @Override
    public boolean isStatic() {
        return element.getModifiers().contains(Modifier.STATIC);
    }

    @Override
    public boolean isAbstract() {
        return element.getModifiers().contains(Modifier.ABSTRACT);
    }

    @Override
    public boolean isSuspend() {
        return false;
    }

    @Override
    public boolean isTopLevel() {
        return false;
    }

    @Override
    public Visibility visibility() {
        return JavacModel.visibility(element.getModifiers());
    }

    @Override
    public TypeRef returnType() {
        return model.typeRef(element.getReturnType());
    }

    @Override
    public List<ParamDecl> parameters() {
        return element.getParameters().stream()
                .<ParamDecl>map(parameter -> new JavacParamDecl(parameter, model))
                .toList();
    }

    @Override
    public List<TypeRef> thrownTypes() {
        return element.getThrownTypes().stream().map(model::typeRef).toList();
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
    public Optional<ClassDecl> owner() {
        return element.getEnclosingElement() instanceof TypeElement owner
                ? Optional.of(model.classDecl(owner))
                : Optional.empty();
    }

    @Override
    public Location location() {
        return JavacLocation.of(element);
    }

    /** Equal when wrapping the same element, whichever round's env made it. */
    @Override
    public boolean equals(Object other) {
        return other instanceof JavacMethodDecl that && element.equals(that.element);
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
