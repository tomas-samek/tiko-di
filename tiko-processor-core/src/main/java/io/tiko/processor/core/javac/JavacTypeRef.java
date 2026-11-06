package io.tiko.processor.core.javac;

import com.palantir.javapoet.TypeName;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.Nullability;
import io.tiko.processor.core.model.TypeRef;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/** A javac {@link TypeMirror} as a {@link TypeRef}. Java types are platform types, so always non-null. */
record JavacTypeRef(TypeMirror mirror, JavacModel model) implements TypeRef {

    @Override
    public TypeName javaPoet() {
        return TypeName.get(mirror);
    }

    @Override
    public String erasedName() {
        return erasedName(mirror);
    }

    private String erasedName(TypeMirror type) {
        if (type.getKind().isPrimitive() || type.getKind() == TypeKind.VOID) {
            return TypeName.get(type).toString();
        }
        if (type.getKind() == TypeKind.TYPEVAR) {
            return erasedName(model.types().erasure(type));
        }
        if (type instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element) {
            return element.getQualifiedName().toString();
        }
        if (type instanceof ArrayType array) {
            return erasedName(array.getComponentType()) + "[]";
        }
        return type.toString();
    }

    @Override
    public String displayName() {
        return mirror.toString();
    }

    @Override
    public List<TypeRef> typeArguments() {
        if (mirror instanceof DeclaredType declared) {
            return declared.getTypeArguments().stream().map(model::typeRef).toList();
        }
        return List.of();
    }

    @Override
    public Nullability nullability() {
        return Nullability.NON_NULL;
    }

    @Override
    public boolean isPrimitive() {
        return mirror.getKind().isPrimitive();
    }

    @Override
    public boolean isError() {
        return mirror.getKind() == TypeKind.ERROR;
    }

    @Override
    public boolean isVoid() {
        return mirror.getKind() == TypeKind.VOID;
    }

    /** An unresolved type ({@link TypeKind#ERROR}, a {@code DeclaredType} on javac) keeps its erroneous class. */
    @Override
    public Optional<ClassDecl> declaration() {
        if ((mirror.getKind() == TypeKind.DECLARED || mirror.getKind() == TypeKind.ERROR)
                && mirror instanceof DeclaredType declared
                && declared.asElement() instanceof TypeElement element) {
            return Optional.of(model.classDecl(element));
        }
        return Optional.empty();
    }

    /** Equal when wrapping the same mirror, whichever round's env made it. */
    @Override
    public boolean equals(Object other) {
        return other instanceof JavacTypeRef that && mirror.equals(that.mirror);
    }

    @Override
    public int hashCode() {
        return mirror.hashCode();
    }

    @Override
    public String toString() {
        return mirror.toString();
    }
}
