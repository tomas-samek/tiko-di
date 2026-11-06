package io.tiko.processor.util;

import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.TypeRef;
import java.util.Optional;

/** Reads of a {@link TypeRef} that the processor's models and generators share. */
public final class TypeRefs {

    private TypeRefs() {}

    /**
     * The declaring class of a resolved declared type; empty for primitives, arrays, type
     * variables and types that do not resolve.
     */
    public static Optional<ClassDecl> declared(TypeRef type) {
        return type.isError() ? Optional.empty() : type.declaration();
    }

    /** The declared type's qualified name, or the type's rendering when it has no declaration. */
    public static String qualifiedName(TypeRef type) {
        return declared(type).map(ClassDecl::qualifiedName).orElseGet(type::displayName);
    }

    /** Whether the type is a resolved declared type named {@code qualifiedName}. */
    public static boolean isDeclared(TypeRef type, String qualifiedName) {
        return declared(type)
                .filter(decl -> decl.qualifiedName().equals(qualifiedName))
                .isPresent();
    }
}
