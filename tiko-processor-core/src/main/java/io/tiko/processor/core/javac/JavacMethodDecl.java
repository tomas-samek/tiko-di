package io.tiko.processor.core.javac;

import com.palantir.javapoet.TypeVariableName;
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
    public boolean isDefault() {
        return element.getModifiers().contains(Modifier.DEFAULT);
    }

    @Override
    public boolean isSuspend() {
        return false;
    }

    @Override
    public boolean isTopLevel() {
        return false;
    }

    /** Java has no extension functions. */
    @Override
    public boolean isExtension() {
        return false;
    }

    /** javac reads the JVM signature, whatever language the method was written in. */
    @Override
    public boolean hasKotlinJvmSignature() {
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
    public List<TypeVariableName> typeVariables() {
        return element.getTypeParameters().stream().map(TypeVariableName::get).toList();
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
    public Optional<ClassDecl> owner() {
        return element.getEnclosingElement() instanceof TypeElement owner
                ? Optional.of(model.classDecl(owner))
                : Optional.empty();
    }

    @Override
    public Location location() {
        return JavacLocation.of(element);
    }

    /**
     * Equal when it is the same method or constructor, whichever round's env made it: javac makes
     * a class's members afresh every round, so the same declaration is matched by class, name and
     * erased parameter types (see {@link JavacIdentity}).
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof JavacMethodDecl that && JavacIdentity.same(element, that.element, model.types());
    }

    @Override
    public int hashCode() {
        return JavacIdentity.hash(element);
    }

    @Override
    public String toString() {
        return element.toString();
    }
}
