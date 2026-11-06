package io.tiko.processor.core.ksp;

import com.google.devtools.ksp.UtilsKt;
import com.google.devtools.ksp.symbol.KSClassDeclaration;
import com.google.devtools.ksp.symbol.KSType;
import com.google.devtools.ksp.symbol.KSTypeAlias;
import com.google.devtools.ksp.symbol.KSTypeParameter;
import com.google.devtools.ksp.symbol.Variance;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.WildcardTypeName;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.Nullability;
import io.tiko.processor.core.model.TypeRef;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import kotlin.sequences.SequencesKt;

/**
 * A {@link KSType} use as a {@link TypeRef}, rendered as the Java type javac sees for it (see
 * {@link KspTypeNames}).
 *
 * <p>{@code variance} is the use-site variance of a type argument ({@code INVARIANT} elsewhere),
 * {@code typeArgument} marks a type-argument position (where Kotlin primitives are boxed),
 * {@code raw} drops the type arguments (an erasure, a class literal), and {@code javaDeclared}
 * marks a type a Java declaration uses (a platform type, non-null whatever its annotations say).
 * Nullability never shows in the Java rendering; it is {@link #nullability()} alone.
 * {@link #displayName()} is javac's {@code TypeMirror.toString()} rendering (see
 * {@link KspTypeNames#javacText}). Identity equality: compare types with
 * {@link io.tiko.processor.core.TypeOracle#isSameType} or {@link #erasedName()}.
 */
final class KspTypeRef implements TypeRef {

    private final KspRef<KSType> type;
    private final Variance variance;
    private final boolean typeArgument;
    private final boolean raw;
    private final boolean javaDeclared;
    private final KspModel model;

    KspTypeRef(
            KspRef<KSType> type,
            Variance variance,
            boolean typeArgument,
            boolean raw,
            boolean javaDeclared,
            KspModel model) {
        this.type = type;
        this.variance = variance;
        this.typeArgument = typeArgument;
        this.raw = raw;
        this.javaDeclared = javaDeclared;
        this.model = model;
    }

    KSType ksType() {
        return type.get();
    }

    boolean isRaw() {
        return raw;
    }

    @Override
    public TypeName javaPoet() {
        var name = KspTypeNames.of(ksType(), typeArgument, raw, model);
        return switch (variance) {
            case COVARIANT -> WildcardTypeName.subtypeOf(name);
            case CONTRAVARIANT -> WildcardTypeName.supertypeOf(name);
            case STAR -> WildcardTypeName.subtypeOf(Object.class);
            case INVARIANT -> name;
        };
    }

    @Override
    public String erasedName() {
        return KspTypeNames.erasedName(ksType(), typeArgument, model);
    }

    @Override
    public String displayName() {
        return KspTypeNames.javacText(javaPoet());
    }

    /** A class type's arguments; empty for raw types, arrays, primitives and type variables. */
    @Override
    public List<TypeRef> typeArguments() {
        var current = ksType();
        if (raw || current.isError() || !(current.getDeclaration() instanceof KSClassDeclaration declaration)) {
            return List.of();
        }
        var name = declaration.getQualifiedName().asString();
        if (name.equals(KspTypeNames.ARRAY)) {
            return List.of();
        }
        return IntStream.range(0, current.getArguments().size())
                .<TypeRef>mapToObj(index -> argument(current, index))
                .toList();
    }

    private TypeRef argument(KSType owner, int index) {
        var argumentVariance = owner.getArguments().get(index).getVariance();
        return new KspTypeRef(
                type.derive(t -> argumentType(t, index)), argumentVariance, true, false, javaDeclared, model);
    }

    /** A star projection reads as its bound, {@code Any?}. */
    private KSType argumentType(KSType owner, int index) {
        var argument = owner.getArguments().get(index);
        return argument.getType() == null
                ? model.resolver().getBuiltIns().getAnyType().makeNullable()
                : argument.getType().resolve();
    }

    /** {@code NULLABLE} iff the Kotlin type is marked nullable; a Java declaration's types are always {@code NON_NULL}. */
    @Override
    public Nullability nullability() {
        return !javaDeclared && ksType().isMarkedNullable() ? Nullability.NULLABLE : Nullability.NON_NULL;
    }

    @Override
    public boolean isPrimitive() {
        return javaPoet().isPrimitive();
    }

    @Override
    public boolean isError() {
        return ksType().isError();
    }

    @Override
    public boolean isVoid() {
        return TypeName.VOID.equals(KspTypeNames.of(ksType(), typeArgument, raw, model));
    }

    /** The class behind a class type, a placeholder for an unresolved one, an alias's target. */
    @Override
    public Optional<ClassDecl> declaration() {
        var current = ksType();
        if (current.isError()) {
            return Optional.of(new KspErrorClassDecl(KspModel.errorName(current.getDeclaration()), this));
        }
        var rendered = KspTypeNames.of(current, typeArgument, true, model);
        if (rendered.isPrimitive() || TypeName.VOID.equals(rendered) || rendered instanceof ArrayTypeName) {
            return Optional.empty();
        }
        return switch (current.getDeclaration()) {
            case KSClassDeclaration declaration -> Optional.of(model.classDecl(declaration));
            case KSTypeAlias alias -> Optional.of(model.classDecl(UtilsKt.findActualType(alias)));
            default -> Optional.empty();
        };
    }

    /** This type with its arguments dropped; a type variable becomes its first bound. */
    KspTypeRef erased() {
        return new KspTypeRef(type.derive(this::erasure), Variance.INVARIANT, typeArgument, true, javaDeclared, model);
    }

    private KSType erasure(KSType type) {
        if (!type.isError() && type.getDeclaration() instanceof KSTypeParameter parameter) {
            var bound = SequencesKt.firstOrNull(parameter.getBounds());
            return bound == null ? model.resolver().getBuiltIns().getAnyType() : erasure(bound.resolve());
        }
        return type;
    }

    @Override
    public String toString() {
        return displayName();
    }
}
