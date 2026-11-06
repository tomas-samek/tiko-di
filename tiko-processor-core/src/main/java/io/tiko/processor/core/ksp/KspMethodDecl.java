package io.tiko.processor.core.ksp;

import com.google.devtools.ksp.UtilsKt;
import com.google.devtools.ksp.symbol.ClassKind;
import com.google.devtools.ksp.symbol.FunctionKind;
import com.google.devtools.ksp.symbol.KSClassDeclaration;
import com.google.devtools.ksp.symbol.KSFunctionDeclaration;
import com.google.devtools.ksp.symbol.KSType;
import com.google.devtools.ksp.symbol.KSTypeParameter;
import com.google.devtools.ksp.symbol.KSTypeReference;
import com.google.devtools.ksp.symbol.Modifier;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeVariableName;
import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.Location;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.ParamDecl;
import io.tiko.processor.core.model.TypeRef;
import io.tiko.processor.core.model.Visibility;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import kotlin.sequences.SequencesKt;

/**
 * A {@link KSFunctionDeclaration} (function or constructor) as a {@link MethodDecl}, found again by
 * its position among its declaring class's functions in later rounds (see {@link
 * KspModel#methodDecl}). Equal by that position, whichever round made it.
 */
final class KspMethodDecl implements MethodDecl {

    private static final String JVM_STATIC = "kotlin.jvm.JvmStatic";
    private static final String CONSTRUCTOR = "<init>";

    private final KspRef<KSFunctionDeclaration> ref;
    private final String key;
    private final KspModel model;

    KspMethodDecl(KspRef<KSFunctionDeclaration> ref, String key, KspModel model) {
        this.ref = ref;
        this.key = key;
        this.model = model;
    }

    KSFunctionDeclaration function() {
        return ref.get();
    }

    @Override
    public String name() {
        return function().getSimpleName().asString();
    }

    /** {@code Resolver.getJvmName}: mangled for {@code internal}, renamed by {@code @JvmName}. */
    @Override
    public String jvmName() {
        if (isConstructor()) {
            return CONSTRUCTOR;
        }
        var jvmName = model.resolver().getJvmName(function());
        return jvmName == null ? name() : jvmName;
    }

    @Override
    public boolean isConstructor() {
        return UtilsKt.isConstructor(function());
    }

    /**
     * Static on the JVM: a Java {@code static} method, a Kotlin top-level function (a static of its
     * file class), or a {@code @JvmStatic} member.
     */
    @Override
    public boolean isStatic() {
        var function = function();
        return function.getFunctionKind() == FunctionKind.STATIC
                || function.getFunctionKind() == FunctionKind.TOP_LEVEL
                || function.getModifiers().contains(Modifier.JAVA_STATIC)
                || KspAnnotationRef.first(ref, JVM_STATIC, key, enclosingClass(), model)
                        .isPresent();
    }

    @Override
    public boolean isAbstract() {
        return function().isAbstract();
    }

    /** A Java {@code default} method, or a Kotlin interface member with a body. */
    @Override
    public boolean isDefault() {
        var function = function();
        if (function.getModifiers().contains(Modifier.JAVA_DEFAULT)) {
            return true;
        }
        return function.getParentDeclaration() instanceof KSClassDeclaration owner
                && owner.getClassKind() == ClassKind.INTERFACE
                && !KspModel.isJava(owner)
                && !function.isAbstract();
    }

    @Override
    public boolean isSuspend() {
        return function().getModifiers().contains(Modifier.SUSPEND);
    }

    @Override
    public boolean isTopLevel() {
        return function().getFunctionKind() == FunctionKind.TOP_LEVEL;
    }

    @Override
    public boolean isExtension() {
        return function().getExtensionReceiver() != null;
    }

    @Override
    public Visibility visibility() {
        return KspModel.visibility(function());
    }

    /** {@code void} for constructors (as javac reports them) and {@code Unit} functions. */
    @Override
    public TypeRef returnType() {
        if (isConstructor()) {
            return model.typeRef(model.ref(
                    model.resolver().getBuiltIns().getUnitType(),
                    resolver -> resolver.getBuiltIns().getUnitType()));
        }
        return model.typeRef(ref.derive(KspMethodDecl::returnTypeOf), KspModel.isJava(function()));
    }

    private static KSType returnTypeOf(KSFunctionDeclaration function) {
        return function.getReturnType().resolve();
    }

    @Override
    public List<ParamDecl> parameters() {
        return IntStream.range(0, function().getParameters().size())
                .<ParamDecl>mapToObj(index -> new KspParamDecl(
                        ref.derive(function -> function.getParameters().get(index)),
                        key + "(" + index,
                        enclosingClass(),
                        model))
                .toList();
    }

    /** {@code @Throws} on Kotlin, {@code throws} on Java, as {@code Resolver.getJvmCheckedException} reads them. */
    @Override
    public List<TypeRef> thrownTypes() {
        var thrown = SequencesKt.toList(model.resolver().getJvmCheckedException(function()));
        return IntStream.range(0, thrown.size())
                .mapToObj(index -> model.typeRef(ref.derive(
                        function -> SequencesKt.toList(model.resolver().getJvmCheckedException(function))
                                .get(index))))
                .toList();
    }

    @Override
    public List<TypeVariableName> typeVariables() {
        return function().getTypeParameters().stream().map(this::typeVariable).toList();
    }

    /** A type parameter with its bounds, leaving out Kotlin's implicit {@code Any?}. */
    private TypeVariableName typeVariable(KSTypeParameter parameter) {
        var bounds = SequencesKt.toList(parameter.getBounds()).stream()
                .map(KSTypeReference::resolve)
                .map(bound -> KspTypeNames.of(bound, true, false, model))
                .filter(bound -> !bound.equals(ClassName.OBJECT))
                .toArray(TypeName[]::new);
        return TypeVariableName.get(parameter.getName().asString(), bounds);
    }

    @Override
    public List<AnnotationRef> annotations() {
        return model.annotations(ref, key, enclosingClass());
    }

    @Override
    public Optional<AnnotationRef> annotation(String annotationFqn) {
        return KspAnnotationRef.first(ref, annotationFqn, key, enclosingClass(), model);
    }

    @Override
    public List<AnnotationRef> annotationsByType(String annotationFqn) {
        return KspAnnotationRef.byType(ref, annotationFqn, key, enclosingClass(), model);
    }

    @Override
    public Optional<ClassDecl> owner() {
        return function().getParentDeclaration() instanceof KSClassDeclaration owner
                ? Optional.of(model.classDecl(owner))
                : Optional.empty();
    }

    @Override
    public Location location() {
        return new KspLocation(ref, enclosingClass());
    }

    /** The Java name of the declaring class; {@code null} for a top-level function. */
    private String enclosingClass() {
        return owner().map(ClassDecl::qualifiedName).orElse(null);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KspMethodDecl that && key.equals(that.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return key;
    }
}
