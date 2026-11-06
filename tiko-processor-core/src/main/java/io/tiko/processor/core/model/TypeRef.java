package io.tiko.processor.core.model;

import com.palantir.javapoet.TypeName;
import java.util.List;
import java.util.Optional;

/**
 * A use of a type: a declared class (possibly parameterized), a primitive, an array or a type variable.
 *
 * <p>{@code equals} is <em>not</em> semantic type equality: two refs to the same type may differ.
 * Compare types with {@link io.tiko.processor.core.TypeOracle#isSameType} or {@link #erasedName()}.
 */
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
