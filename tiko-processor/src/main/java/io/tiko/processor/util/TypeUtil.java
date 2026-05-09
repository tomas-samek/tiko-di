package io.tiko.processor.util;

import java.util.List;
import java.util.Optional;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Utility methods for working with types during annotation processing.
 */
public final class TypeUtil {

    private final Elements elementUtils;
    private final Types typeUtils;

    public TypeUtil(Elements elementUtils, Types typeUtils) {
        this.elementUtils = elementUtils;
        this.typeUtils = typeUtils;
    }

    /**
     * Returns the TypeElement for the given type mirror, if it's a declared type.
     */
    public Optional<TypeElement> asTypeElement(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return Optional.empty();
        }
        Element element = typeUtils.asElement(type);
        if (element instanceof TypeElement) {
            return Optional.of((TypeElement) element);
        }
        return Optional.empty();
    }

    /**
     * Returns the qualified name of the given type.
     */
    public String getQualifiedName(TypeMirror type) {
        return asTypeElement(type).map(te -> te.getQualifiedName().toString()).orElse(type.toString());
    }

    /**
     * Returns the simple name of the given type.
     */
    public String getSimpleName(TypeMirror type) {
        return asTypeElement(type).map(te -> te.getSimpleName().toString()).orElse(type.toString());
    }

    /**
     * Returns the package name of the given type.
     */
    public String getPackageName(TypeElement typeElement) {
        return elementUtils.getPackageOf(typeElement).getQualifiedName().toString();
    }

    /**
     * Checks if the given type is Provider<T>.
     */
    public boolean isProvider(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        TypeElement typeElement = asTypeElement(type).orElse(null);
        if (typeElement == null) {
            return false;
        }
        return typeElement.getQualifiedName().toString().equals("io.tiko.Provider");
    }

    /**
     * Extracts the type argument from Provider<T>.
     * Returns empty if not a Provider or no type argument.
     */
    public Optional<TypeMirror> unwrapProvider(TypeMirror type) {
        if (!isProvider(type)) {
            return Optional.empty();
        }
        if (type instanceof DeclaredType declaredType) {
            List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
            if (!typeArguments.isEmpty()) {
                return Optional.of(typeArguments.get(0));
            }
        }
        return Optional.empty();
    }

    /**
     * Returns all interfaces directly implemented by the given type.
     */
    public List<? extends TypeMirror> getInterfaces(TypeElement typeElement) {
        return typeElement.getInterfaces();
    }

    /**
     * Returns the first interface implemented by the given type, if any.
     *
     * <p>Lifecycle marker interfaces ({@link AutoCloseable}, {@link java.io.Closeable})
     * are skipped: they are JDK conventions for cleanup, not service contracts a
     * caller would dispatch against. This avoids treating two unrelated beans that
     * both implement {@code AutoCloseable} as ambiguous providers for the same type.</p>
     */
    public Optional<TypeMirror> getFirstInterface(TypeElement typeElement) {
        List<? extends TypeMirror> interfaces = typeElement.getInterfaces();
        for (TypeMirror iface : interfaces) {
            String name = getQualifiedName(iface);
            if (isLifecycleInterface(name)) continue;
            return Optional.of(iface);
        }
        return Optional.empty();
    }

    private static boolean isLifecycleInterface(String qualifiedName) {
        return "java.lang.AutoCloseable".equals(qualifiedName) || "java.io.Closeable".equals(qualifiedName);
    }

    /**
     * Checks if typeA is assignable to typeB.
     */
    public boolean isAssignable(TypeMirror typeA, TypeMirror typeB) {
        return typeUtils.isAssignable(typeA, typeB);
    }

    /**
     * Checks if the two types are the same.
     */
    public boolean isSameType(TypeMirror typeA, TypeMirror typeB) {
        return typeUtils.isSameType(typeA, typeB);
    }

    /**
     * Returns the erasure of the given type (removes type parameters).
     */
    public TypeMirror erasure(TypeMirror type) {
        return typeUtils.erasure(type);
    }

    /**
     * Gets a TypeElement by its fully qualified name.
     */
    public Optional<TypeElement> getTypeElement(String qualifiedName) {
        TypeElement element = elementUtils.getTypeElement(qualifiedName);
        return Optional.ofNullable(element);
    }

    /**
     * Checks if a type implements a specific interface.
     */
    public boolean implementsInterface(TypeElement typeElement, String interfaceQualifiedName) {
        TypeElement interfaceElement = elementUtils.getTypeElement(interfaceQualifiedName);
        if (interfaceElement == null) {
            return false;
        }
        return typeUtils.isAssignable(typeElement.asType(), interfaceElement.asType());
    }

    /**
     * Checks if a {@link TypeMirror} is assignable to a specific interface (by qualified
     * name). Useful for {@code @Produces} return types where the produced object's
     * declaring element may be a third-party type (data sources, HTTP clients).
     */
    public boolean isAssignableTo(TypeMirror type, String interfaceQualifiedName) {
        TypeElement interfaceElement = elementUtils.getTypeElement(interfaceQualifiedName);
        if (interfaceElement == null) {
            return false;
        }
        return typeUtils.isAssignable(type, interfaceElement.asType());
    }

    /**
     * Checks if the given type is a concrete class (not abstract, not interface).
     */
    public boolean isConcreteClass(TypeElement typeElement) {
        return typeElement.getKind().isClass()
                && !typeElement.getModifiers().contains(javax.lang.model.element.Modifier.ABSTRACT);
    }

    /**
     * Returns the binary name for code generation (handles nested classes).
     */
    public String getBinaryName(TypeElement typeElement) {
        return elementUtils.getBinaryName(typeElement).toString();
    }
}
