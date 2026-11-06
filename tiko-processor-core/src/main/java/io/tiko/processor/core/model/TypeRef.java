package io.tiko.processor.core.model;

import com.palantir.javapoet.TypeName;
import java.util.List;
import java.util.Optional;

/** A use of a type: a declared class (possibly parameterized), a primitive, an array or a type variable. */
public interface TypeRef {

    /** The JavaPoet form generators emit. */
    TypeName javaPoet();

    /** Fully qualified name without type arguments; {@code "int"} for primitives. */
    String erasedName();

    List<TypeRef> typeArguments();

    Nullability nullability();

    boolean isPrimitive();

    /** The declaring class; empty for primitives, arrays and type variables. */
    Optional<ClassDecl> declaration();
}
