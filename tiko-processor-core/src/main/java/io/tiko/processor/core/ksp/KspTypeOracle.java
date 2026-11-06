package io.tiko.processor.core.ksp;

import io.tiko.processor.core.TypeOracle;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.TypeRef;
import java.util.Optional;

/**
 * {@link TypeOracle} over KSP's type system, answering for the Java types the model renders.
 * Nullability is left out: {@code String?} and {@code String} are the same Java type, and the
 * nullability rules read {@link TypeRef#nullability()} themselves. An unresolved type is never
 * assignable to or the same as anything.
 */
final class KspTypeOracle implements TypeOracle {

    private final KspModel model;

    KspTypeOracle(KspModel model) {
        this.model = model;
    }

    /** KSP's {@code isAssignableFrom}; a raw target accepts every parameterization. */
    @Override
    public boolean isAssignable(TypeRef from, TypeRef to) {
        var source = KspModel.type(from);
        var target = KspModel.type(to);
        if (source.isError() || target.isError()) {
            return false;
        }
        if (to instanceof KspTypeRef ksp && ksp.isRaw()) {
            target = target.starProjection();
        }
        return target.makeNullable().isAssignableFrom(source.makeNotNullable());
    }

    /** The same Java type: equal renderings ({@code int} and {@code Integer} differ, as on javac). */
    @Override
    public boolean isSameType(TypeRef a, TypeRef b) {
        if (a.isError() || b.isError()) {
            return false;
        }
        return a.javaPoet().equals(b.javaPoet());
    }

    @Override
    public TypeRef erasure(TypeRef t) {
        if (t instanceof KspTypeRef ksp) {
            return ksp.erased();
        }
        throw new IllegalArgumentException("Not a KSP type: " + t);
    }

    /** By Java qualified name, as javac's {@code getTypeElement}: {@code java.lang.String} finds {@code kotlin.String}. */
    @Override
    public Optional<ClassDecl> findClass(String qualifiedName) {
        return model.findJavaClass(qualifiedName);
    }
}
