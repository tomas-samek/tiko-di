package io.tiko.processor.core.ksp;

import com.google.devtools.ksp.UtilsKt;
import com.google.devtools.ksp.symbol.ClassKind;
import com.google.devtools.ksp.symbol.FileLocation;
import com.google.devtools.ksp.symbol.KSClassDeclaration;
import com.google.devtools.ksp.symbol.KSNode;
import com.google.devtools.ksp.symbol.KSPropertyAccessor;
import com.google.devtools.ksp.symbol.KSPropertyDeclaration;
import com.google.devtools.ksp.symbol.KSType;
import com.google.devtools.ksp.symbol.Modifier;
import com.palantir.javapoet.TypeVariableName;
import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.DeclKind;
import io.tiko.processor.core.model.Location;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.ParamDecl;
import io.tiko.processor.core.model.TypeRef;
import io.tiko.processor.core.model.Visibility;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import kotlin.sequences.SequencesKt;

/**
 * The getter or setter of a Kotlin property as a {@link MethodDecl}: the method javac sees on the
 * Kotlin-compiled class ({@code getX} / {@code isX} / {@code setX}, or the record accessor
 * {@code x()} for a component of a {@code @JvmRecord} class). It returns the property's
 * type (the getter) or {@code void} (the setter, which takes the value), and an extension
 * property's accessors take the receiver first, as on the JVM.
 *
 * <p>It carries no annotations: a DI annotation on an accessor ({@code @get:} / {@code @set:}) is
 * reported through {@link io.tiko.processor.core.ProcessingEnv#propertyAccessorsAnnotatedWith}
 * and rejected there, and the DI annotations are {@code SOURCE} retention, which javac would not
 * see on the compiled accessor either.
 *
 * <p>Found again by the property's position among its class's declared properties. Equal by that
 * position and the accessor kind, whichever round made it.
 */
final class KspAccessorDecl implements MethodDecl {

    private static final String JVM_FIELD = "kotlin.jvm.JvmField";
    private static final String JVM_STATIC = "kotlin.jvm.JvmStatic";

    private final KspRef<KSPropertyDeclaration> ref;
    private final boolean setter;
    private final String key;
    private final KspModel model;

    KspAccessorDecl(KspRef<KSPropertyDeclaration> ref, boolean setter, String key, KspModel model) {
        this.ref = ref;
        this.setter = setter;
        this.key = key;
        this.model = model;
    }

    /**
     * The accessors the JVM has for {@code property}, getter first: none for a Java field, a
     * {@code private}, {@code const} or {@code @JvmField} property, an annotation class member, or
     * a property of a built-in mapped to a Java class;
     * a setter only for a {@code var} whose setter is not {@code private}.
     */
    static List<Boolean> accessorKinds(KSPropertyDeclaration property, KspModel model) {
        if (KspModel.isJava(property)
                || isMappedBuiltIn(property, model)
                || property.getModifiers().contains(Modifier.CONST)
                || KspModel.visibility(property) == Visibility.PRIVATE
                || (property.getParentDeclaration() instanceof KSClassDeclaration owner
                        && owner.getClassKind() == ClassKind.ANNOTATION_CLASS)
                || hasAnnotation(property, JVM_FIELD, model)) {
            return List.of();
        }
        var setter = property.getSetter();
        if (!property.isMutable() || (setter != null && setter.getModifiers().contains(Modifier.PRIVATE))) {
            return List.of(false);
        }
        return List.of(false, true);
    }

    /**
     * A property of a Kotlin built-in that javac sees as a Java class ({@code kotlin.Enum} is
     * {@code java.lang.Enum}, {@code kotlin.collections.List} is {@code java.util.List}): its JVM
     * members are the Java class's ({@code name()}, {@code size()}), not accessors.
     */
    private static boolean isMappedBuiltIn(KSPropertyDeclaration property, KspModel model) {
        return property.getParentDeclaration() instanceof KSClassDeclaration owner
                && owner.getQualifiedName() != null
                && !model.javaName(owner.getQualifiedName().asString())
                        .equals(owner.getQualifiedName().asString());
    }

    private static boolean hasAnnotation(KSPropertyDeclaration property, String fqn, KspModel model) {
        return KspAnnotationRef.first(model.ref(property, resolver -> property), fqn, "", null, model)
                .isPresent();
    }

    private KSPropertyDeclaration property() {
        return ref.get();
    }

    /** The accessor KSP reports; {@code null} when it has none. */
    private KSPropertyAccessor accessor() {
        var property = property();
        return setter ? property.getSetter() : property.getGetter();
    }

    /**
     * The JVM accessor name without {@code @JvmName} or mangling: {@code getX}, {@code isX},
     * {@code setX}, or {@code x} for a {@code @JvmRecord} component, which Kotlin compiles to the
     * record accessor.
     */
    @Override
    public String name() {
        var name = property().getSimpleName().asString();
        if (isRecordComponentGetter()) {
            return name;
        }
        var isPrefixed = name.length() > 2 && name.startsWith("is") && !Character.isLowerCase(name.charAt(2));
        if (setter) {
            return "set" + (isPrefixed ? name.substring(2) : capitalize(name));
        }
        return isPrefixed ? name : "get" + capitalize(name);
    }

    /** The getter of a primary-constructor {@code val} of a Kotlin {@code @JvmRecord} class: a record component. */
    private boolean isRecordComponentGetter() {
        var property = property();
        if (setter
                || !(property.getParentDeclaration() instanceof KSClassDeclaration owner)
                || model.classDecl(owner).kind() != DeclKind.RECORD) {
            return false;
        }
        var primary = owner.getPrimaryConstructor();
        var name = property.getSimpleName().asString();
        return primary != null
                && primary.getParameters().stream()
                        .anyMatch(parameter -> parameter.isVal()
                                && parameter.getName() != null
                                && parameter.getName().asString().equals(name));
    }

    private static String capitalize(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * {@code Resolver.getJvmName}: renamed by {@code @get:JvmName} / {@code @set:JvmName}, mangled
     * for {@code internal}; the record accessor name for a {@code @JvmRecord} component.
     */
    @Override
    public String jvmName() {
        var accessor = accessor();
        var jvmName = accessor == null ? null : model.resolver().getJvmName(accessor);
        return jvmName == null ? name() : jvmName;
    }

    @Override
    public boolean isConstructor() {
        return false;
    }

    /** Static on the JVM only as a {@code @JvmStatic} property. */
    @Override
    public boolean isStatic() {
        return hasAnnotation(property(), JVM_STATIC, model);
    }

    @Override
    public boolean isAbstract() {
        return UtilsKt.isAbstract(property());
    }

    /** An interface property with a body, as {@link KspMethodDecl#isDefault()} counts interface members. */
    @Override
    public boolean isDefault() {
        var property = property();
        return property.getParentDeclaration() instanceof KSClassDeclaration owner
                && owner.getClassKind() == ClassKind.INTERFACE
                && !UtilsKt.isAbstract(property);
    }

    @Override
    public boolean isSuspend() {
        return false;
    }

    /** Only class members become accessor methods here. */
    @Override
    public boolean isTopLevel() {
        return false;
    }

    @Override
    public boolean isExtension() {
        return property().getExtensionReceiver() != null;
    }

    /** The accessor's own visibility when it narrows the property's ({@code protected set}). */
    @Override
    public Visibility visibility() {
        var accessor = accessor();
        if (accessor != null) {
            var modifiers = accessor.getModifiers();
            if (modifiers.contains(Modifier.PROTECTED)) {
                return Visibility.PROTECTED;
            }
            if (modifiers.contains(Modifier.INTERNAL)) {
                return Visibility.KOTLIN_INTERNAL;
            }
        }
        return KspModel.visibility(property());
    }

    /** The property's type for the getter, {@code void} for the setter. */
    @Override
    public TypeRef returnType() {
        if (setter) {
            return model.typeRef(model.ref(
                    model.resolver().getBuiltIns().getUnitType(),
                    resolver -> resolver.getBuiltIns().getUnitType()));
        }
        return model.typeRef(ref.derive(KspAccessorDecl::propertyType));
    }

    private static KSType propertyType(KSPropertyDeclaration property) {
        return property.getType().resolve();
    }

    /** The receiver of an extension property, then (for the setter) the new value. */
    @Override
    public List<ParamDecl> parameters() {
        var parameters = new ArrayList<ParamDecl>();
        if (isExtension()) {
            parameters.add(new KspAccessorParam(
                    "receiver",
                    model.typeRef(ref.derive(
                            property -> property.getExtensionReceiver().resolve())),
                    key + "(receiver",
                    location()));
        }
        if (setter) {
            parameters.add(new KspAccessorParam(
                    "value", model.typeRef(ref.derive(KspAccessorDecl::propertyType)), key + "(value", location()));
        }
        return List.copyOf(parameters);
    }

    /** {@code @Throws} on the accessor, as {@code Resolver.getJvmCheckedException} reads it. */
    @Override
    public List<TypeRef> thrownTypes() {
        var accessor = accessor();
        if (accessor == null) {
            return List.of();
        }
        var count = SequencesKt.count(model.resolver().getJvmCheckedException(accessor));
        return IntStream.range(0, count)
                .mapToObj(index -> model.typeRef(ref.derive(property -> SequencesKt.toList(model.resolver()
                                .getJvmCheckedException(setter ? property.getSetter() : property.getGetter()))
                        .get(index))))
                .toList();
    }

    /** An extension property's type parameters. */
    @Override
    public List<TypeVariableName> typeVariables() {
        return property().getTypeParameters().stream()
                .map(parameter -> KspMethodDecl.typeVariable(parameter, model))
                .toList();
    }

    @Override
    public List<AnnotationRef> annotations() {
        return List.of();
    }

    @Override
    public Optional<AnnotationRef> annotation(String annotationFqn) {
        return Optional.empty();
    }

    @Override
    public List<AnnotationRef> annotationsByType(String annotationFqn) {
        return List.of();
    }

    @Override
    public Optional<ClassDecl> owner() {
        return property().getParentDeclaration() instanceof KSClassDeclaration owner
                ? Optional.of(model.classDecl(owner))
                : Optional.empty();
    }

    /** The accessor when it is written out, else its property. */
    @Override
    public Location location() {
        var enclosingClass = owner().map(ClassDecl::qualifiedName).orElse(null);
        return new KspLocation(
                ref.derive(property -> {
                    var accessor = setter ? property.getSetter() : property.getGetter();
                    return accessor != null && accessor.getLocation() instanceof FileLocation
                            ? (KSNode) accessor
                            : property;
                }),
                enclosingClass);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KspAccessorDecl that && key.equals(that.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return key;
    }

    /** A parameter the JVM accessor has and the Kotlin property doesn't declare: the receiver, the new value. */
    private record KspAccessorParam(String name, TypeRef type, String key, Location location) implements ParamDecl {

        @Override
        public boolean hasDefaultValue() {
            return false;
        }

        @Override
        public List<AnnotationRef> annotations() {
            return List.of();
        }

        @Override
        public Optional<AnnotationRef> annotation(String fqn) {
            return Optional.empty();
        }

        @Override
        public List<AnnotationRef> annotationsByType(String annotationFqn) {
            return List.of();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof KspAccessorParam that && key.equals(that.key);
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }
    }
}
