package io.tiko.processor.core.javac;

import io.tiko.processor.core.TypeOracle;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.TypeRef;
import java.util.Optional;

/** {@link TypeOracle} delegating to javac's {@code Types} and {@code Elements}. */
final class JavacTypeOracle implements TypeOracle {

    private final JavacModel model;

    JavacTypeOracle(JavacModel model) {
        this.model = model;
    }

    @Override
    public boolean isAssignable(TypeRef from, TypeRef to) {
        return model.types().isAssignable(JavacModel.mirror(from), JavacModel.mirror(to));
    }

    @Override
    public boolean isSameType(TypeRef a, TypeRef b) {
        return model.types().isSameType(JavacModel.mirror(a), JavacModel.mirror(b));
    }

    @Override
    public TypeRef erasure(TypeRef t) {
        return model.typeRef(model.types().erasure(JavacModel.mirror(t)));
    }

    @Override
    public Optional<ClassDecl> findClass(String qualifiedName) {
        return Optional.ofNullable(model.elements().getTypeElement(qualifiedName))
                .map(model::classDecl);
    }
}
