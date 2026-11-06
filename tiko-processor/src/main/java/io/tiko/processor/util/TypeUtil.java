package io.tiko.processor.util;

import io.tiko.processor.core.TypeOracle;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.DeclKind;
import io.tiko.processor.core.model.TypeRef;
import io.tiko.processor.core.model.Visibility;
import java.util.List;
import java.util.Optional;

/**
 * Utility methods for working with types during annotation processing. Type relations are
 * answered by the round's {@link TypeOracle}.
 */
public final class TypeUtil {

    private final TypeOracle types;

    public TypeUtil(TypeOracle types) {
        this.types = types;
    }

    /**
     * Returns the class declaring the given type, if it's a resolved declared type.
     */
    public Optional<ClassDecl> asClassDecl(TypeRef type) {
        return TypeRefs.declared(type);
    }

    /**
     * Returns the qualified name of the given type.
     */
    public String getQualifiedName(TypeRef type) {
        return TypeRefs.qualifiedName(type);
    }

    /**
     * Whether generated code (in {@code io.tiko.generated}) can call a member with this visibility:
     * public, or Kotlin {@code internal}, which is public on the JVM and visible to the module the
     * generated code is compiled into. Java members are never internal.
     */
    public static boolean isCallableFromGeneratedCode(Visibility visibility) {
        return visibility == Visibility.PUBLIC || visibility == Visibility.KOTLIN_INTERNAL;
    }

    /**
     * Returns the simple name of the given type.
     */
    public String getSimpleName(TypeRef type) {
        return asClassDecl(type).map(decl -> decl.className().simpleName()).orElseGet(type::displayName);
    }

    /**
     * Returns the package name of the given class.
     */
    public String getPackageName(ClassDecl decl) {
        return decl.className().packageName();
    }

    /**
     * Checks if the given type is Provider<T>.
     */
    public boolean isProvider(TypeRef type) {
        return TypeRefs.isDeclared(type, "io.tiko.Provider");
    }

    /**
     * Extracts the type argument from Provider<T>.
     * Returns empty if not a Provider or no type argument.
     */
    public Optional<TypeRef> unwrapProvider(TypeRef type) {
        if (!isProvider(type)) {
            return Optional.empty();
        }
        return firstTypeArgument(type);
    }

    /**
     * Checks if the given type is Picker<T>.
     */
    public boolean isPicker(TypeRef type) {
        return TypeRefs.isDeclared(type, "io.tiko.Picker");
    }

    /**
     * Extracts the type argument from Picker<T>.
     * Returns empty if not a Picker or no type argument.
     */
    public Optional<TypeRef> unwrapPicker(TypeRef type) {
        if (!isPicker(type)) {
            return Optional.empty();
        }
        return firstTypeArgument(type);
    }

    private static Optional<TypeRef> firstTypeArgument(TypeRef type) {
        return type.typeArguments().stream().findFirst();
    }

    /**
     * Returns all interfaces directly implemented by the given class.
     */
    public List<TypeRef> getInterfaces(ClassDecl decl) {
        return decl.interfaces();
    }

    /**
     * Returns the first interface implemented by the given class, if any.
     *
     * <p>Lifecycle marker interfaces ({@link AutoCloseable}, {@link java.io.Closeable})
     * are skipped: they are JDK conventions for cleanup, not service contracts a
     * caller would dispatch against. This avoids treating two unrelated beans that
     * both implement {@code AutoCloseable} as ambiguous providers for the same type.</p>
     */
    public Optional<TypeRef> getFirstInterface(ClassDecl decl) {
        for (var iface : decl.interfaces()) {
            if (isLifecycleMarkerInterface(getQualifiedName(iface))) continue;
            return Optional.of(iface);
        }
        return Optional.empty();
    }

    /**
     * Whether the given fully-qualified interface name is a JDK lifecycle marker
     * ({@code java.lang.AutoCloseable} / {@code java.io.Closeable}) — a cleanup
     * convention, not a service contract a caller dispatches against. Shared by
     * {@link #getFirstInterface(ClassDecl)} (proxy/interface keying) and the
     * container's routable-type computation so the two never disagree (#301).
     */
    public static boolean isLifecycleMarkerInterface(String qualifiedName) {
        return "java.lang.AutoCloseable".equals(qualifiedName) || "java.io.Closeable".equals(qualifiedName);
    }

    /**
     * Checks if typeA is assignable to typeB.
     */
    public boolean isAssignable(TypeRef typeA, TypeRef typeB) {
        return types.isAssignable(typeA, typeB);
    }

    /**
     * Checks if the two types are the same.
     */
    public boolean isSameType(TypeRef typeA, TypeRef typeB) {
        return types.isSameType(typeA, typeB);
    }

    /**
     * Returns the erasure of the given type (removes type parameters).
     */
    public TypeRef erasure(TypeRef type) {
        return types.erasure(type);
    }

    /**
     * Finds a class by its fully qualified name.
     */
    public Optional<ClassDecl> findClass(String qualifiedName) {
        return types.findClass(qualifiedName);
    }

    /**
     * Checks if a class implements a specific interface.
     */
    public boolean implementsInterface(ClassDecl decl, String interfaceQualifiedName) {
        return isAssignableTo(decl.asType(), interfaceQualifiedName);
    }

    /**
     * Checks if a type is assignable to a specific interface (by qualified
     * name). Useful for {@code @Produces} return types where the produced object's
     * declaring class may be a third-party type (data sources, HTTP clients).
     */
    public boolean isAssignableTo(TypeRef type, String interfaceQualifiedName) {
        return findClass(interfaceQualifiedName)
                .map(iface -> types.isAssignable(type, iface.asType()))
                .orElse(false);
    }

    /**
     * Checks if the given class is a concrete class (not abstract, not interface).
     */
    public boolean isConcreteClass(ClassDecl decl) {
        var kind = decl.kind();
        boolean isClass = kind == DeclKind.CLASS || kind == DeclKind.ENUM || kind == DeclKind.RECORD;
        return isClass && !decl.isAbstract();
    }

    /**
     * Returns the binary name for code generation (handles nested classes).
     */
    public String getBinaryName(ClassDecl decl) {
        return decl.className().reflectionName();
    }
}
