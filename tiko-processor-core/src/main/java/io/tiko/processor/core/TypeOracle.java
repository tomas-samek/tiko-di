package io.tiko.processor.core;

import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.TypeRef;
import java.util.Optional;

/** Type relations, answered by the frontend's own type system. */
public interface TypeOracle {

    /** Generics-aware, as javac's {@code Types.isAssignable}. */
    boolean isAssignable(TypeRef from, TypeRef to);

    boolean isSameType(TypeRef a, TypeRef b);

    TypeRef erasure(TypeRef t);

    Optional<ClassDecl> findClass(String qualifiedName);
}
