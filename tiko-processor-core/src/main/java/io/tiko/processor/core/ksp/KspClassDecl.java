package io.tiko.processor.core.ksp;

import com.google.devtools.ksp.UtilsKt;
import com.google.devtools.ksp.processing.Resolver;
import com.google.devtools.ksp.symbol.ClassKind;
import com.google.devtools.ksp.symbol.KSClassDeclaration;
import com.google.devtools.ksp.symbol.KSDeclaration;
import com.google.devtools.ksp.symbol.KSFunctionDeclaration;
import com.google.devtools.ksp.symbol.KSPropertyDeclaration;
import com.google.devtools.ksp.symbol.KSType;
import com.google.devtools.ksp.symbol.KSTypeReference;
import com.google.devtools.ksp.symbol.Modifier;
import com.google.devtools.ksp.symbol.Origin;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;
import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.DeclKind;
import io.tiko.processor.core.model.FieldDecl;
import io.tiko.processor.core.model.Location;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.SourceLanguage;
import io.tiko.processor.core.model.TypeRef;
import io.tiko.processor.core.model.Visibility;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import kotlin.sequences.SequencesKt;

/**
 * A {@link KSClassDeclaration} as a {@link ClassDecl}, found again by its Kotlin qualified name in
 * later rounds. Names are the Java ones ({@code kotlin.Any} is {@code java.lang.Object}). Equal by
 * qualified name, whichever round made it.
 */
final class KspClassDecl implements ClassDecl {

    private static final String RECORD = "java.lang.Record";
    private static final String JVM_RECORD = "kotlin.jvm.JvmRecord";
    private static final String ANY = "kotlin.Any";

    private final KspRef<KSClassDeclaration> ref;
    private final String kotlinName;
    private final KspModel model;

    KspClassDecl(KspRef<KSClassDeclaration> ref, String kotlinName, KspModel model) {
        this.ref = ref;
        this.kotlinName = kotlinName;
        this.model = model;
    }

    KSClassDeclaration declaration() {
        return ref.get();
    }

    @Override
    public String qualifiedName() {
        return model.javaName(kotlinName);
    }

    @Override
    public ClassName className() {
        return model.className(declaration());
    }

    @Override
    public DeclKind kind() {
        var declaration = declaration();
        return switch (declaration.getClassKind()) {
            case INTERFACE -> DeclKind.INTERFACE;
            case CLASS -> isRecord(declaration) || isJvmRecord(declaration) ? DeclKind.RECORD : DeclKind.CLASS;
            case ENUM_CLASS -> DeclKind.ENUM;
            case ANNOTATION_CLASS -> DeclKind.ANNOTATION;
            case OBJECT -> declaration.isCompanionObject() ? DeclKind.KOTLIN_COMPANION : DeclKind.KOTLIN_OBJECT;
            case ENUM_ENTRY -> throw new IllegalStateException("Not a type declaration: enum entry " + kotlinName);
        };
    }

    /** KSP has no record kind: a Java record is a class extending {@code java.lang.Record}. */
    private static boolean isRecord(KSClassDeclaration declaration) {
        return KspModel.isJava(declaration)
                && SequencesKt.any(declaration.getSuperTypes(), superType -> {
                    var resolved = superType.resolve().getDeclaration().getQualifiedName();
                    return resolved != null && resolved.asString().equals(RECORD);
                });
    }

    /**
     * A Kotlin {@code @JvmRecord} class, which Kotlin compiles to a Java record: javac sees a
     * record whose components are the primary constructor's properties.
     */
    private boolean isJvmRecord(KSClassDeclaration declaration) {
        return !KspModel.isJava(declaration)
                && KspAnnotationRef.first(ref, JVM_RECORD, key(), qualifiedName(), model)
                        .isPresent();
    }

    @Override
    public Visibility visibility() {
        return KspModel.visibility(declaration());
    }

    /** Interfaces count as abstract, as on javac. */
    @Override
    public boolean isAbstract() {
        return UtilsKt.isAbstract(declaration());
    }

    /** Kotlin classes are final unless {@code open}, {@code abstract} or {@code sealed}. */
    @Override
    public boolean isFinal() {
        return !UtilsKt.isOpen(declaration());
    }

    /**
     * A nested class without an outer instance: a Kotlin nested class that is not {@code inner},
     * a Java {@code static} one, or a nested interface, enum, annotation or record.
     */
    @Override
    public boolean isStatic() {
        var declaration = declaration();
        if (!(declaration.getParentDeclaration() instanceof KSClassDeclaration)) {
            return false;
        }
        if (!KspModel.isJava(declaration)) {
            return !declaration.getModifiers().contains(Modifier.INNER);
        }
        return declaration.getModifiers().contains(Modifier.JAVA_STATIC) || kind() != DeclKind.CLASS;
    }

    /** The {@code value} (or legacy {@code inline}) modifier, also on a classpath Kotlin class. */
    @Override
    public boolean isValueClass() {
        var modifiers = declaration().getModifiers();
        return modifiers.contains(Modifier.VALUE) || modifiers.contains(Modifier.INLINE);
    }

    @Override
    public SourceLanguage language() {
        return KspModel.language(declaration().getOrigin());
    }

    @Override
    public List<AnnotationRef> annotations() {
        return model.annotations(ref, key(), qualifiedName());
    }

    @Override
    public Optional<AnnotationRef> annotation(String annotationFqn) {
        return KspAnnotationRef.first(ref, annotationFqn, key(), qualifiedName(), model);
    }

    @Override
    public List<AnnotationRef> annotationsByType(String annotationFqn) {
        return KspAnnotationRef.byType(ref, annotationFqn, key(), qualifiedName(), model);
    }

    @Override
    public List<MethodDecl> constructors() {
        return KspModel.functionsOf(declaration()).stream()
                .filter(UtilsKt::isConstructor)
                .map(model::methodDecl)
                .toList();
    }

    @Override
    public Optional<MethodDecl> primaryConstructor() {
        var declaration = declaration();
        if (KspModel.isJava(declaration)) {
            return Optional.empty();
        }
        return Optional.ofNullable(declaration.getPrimaryConstructor()).map(model::methodDecl);
    }

    /**
     * The declared functions (without constructors), then the JVM accessors of the declared
     * properties ({@code getX} / {@code isX} / {@code setX}, see {@link KspAccessorDecl}): the
     * methods javac sees on the Kotlin-compiled class.
     */
    @Override
    public List<MethodDecl> methods() {
        var declaration = declaration();
        var methods = new ArrayList<MethodDecl>();
        KspModel.functionsOf(declaration).stream()
                .filter(function -> !UtilsKt.isConstructor(function))
                .map(model::methodDecl)
                .forEach(methods::add);
        methods.addAll(model.declaredAccessors(declaration));
        return List.copyOf(methods);
    }

    /**
     * Declared and inherited member functions as KSP's {@code getAllFunctions} sees them, without
     * constructors (an override appears once, private members of supertypes are left out), then
     * the JVM accessors of the declared and inherited properties ({@code getAllProperties}, each
     * override once).
     */
    @Override
    public List<MethodDecl> allMethods() {
        var declaration = declaration();
        var functions = SequencesKt.toList(declaration.getAllFunctions()).stream()
                .filter(function -> !UtilsKt.isConstructor(function))
                .filter(function -> !isStaticInterfaceMember(function))
                .collect(Collectors.toCollection(ArrayList::new));
        functions.addAll(inheritedClassStatics(declaration, functions));
        var methods = new ArrayList<MethodDecl>();
        functions.stream().map(model::methodDecl).forEach(methods::add);
        SequencesKt.toList(declaration.getAllProperties()).stream()
                .flatMap(property -> model.accessorDecls(property).stream())
                .forEach(methods::add);
        return methods.stream().distinct().toList();
    }

    /**
     * Static methods of Java superclasses, which JLS 8.4.8 counts as inherited (unless private or
     * hidden by a static of the same signature further down) but KSP's member scope leaves out.
     */
    private List<KSFunctionDeclaration> inheritedClassStatics(
            KSClassDeclaration declaration, List<KSFunctionDeclaration> present) {
        var signatures = present.stream().map(this::signature).collect(Collectors.toCollection(HashSet::new));
        var statics = new ArrayList<KSFunctionDeclaration>();
        var superclass = superclassDeclaration(declaration);
        while (superclass != null) {
            for (var function : SequencesKt.toList(UtilsKt.getDeclaredFunctions(superclass))) {
                if (function.getModifiers().contains(Modifier.JAVA_STATIC)
                        && !function.getModifiers().contains(Modifier.PRIVATE)
                        && signatures.add(signature(function))) {
                    statics.add(function);
                }
            }
            superclass = superclassDeclaration(superclass);
        }
        return statics;
    }

    /** The declared superclass's declaration; {@code null} for none, an interface or an unresolved one. */
    private static KSClassDeclaration superclassDeclaration(KSClassDeclaration declaration) {
        for (var superType : superTypes(declaration)) {
            if (!superType.isError()
                    && superType.getDeclaration() instanceof KSClassDeclaration superclass
                    && superclass.getClassKind() != ClassKind.INTERFACE) {
                return superclass;
            }
        }
        return null;
    }

    /** Name and erased parameter types: what hiding and overriding compare. */
    private String signature(KSFunctionDeclaration function) {
        var parameters = function.getParameters().stream()
                .map(parameter -> KspTypeNames.erasedName(parameter.getType().resolve(), false, model))
                .toList();
        return function.getSimpleName().asString() + parameters;
    }

    /** Interface static methods are not inherited (JLS 8.4.8). */
    private boolean isStaticInterfaceMember(KSFunctionDeclaration function) {
        return function.getParentDeclaration() != declaration()
                && KspModel.isInterface(function.getParentDeclaration())
                && function.getModifiers().contains(Modifier.JAVA_STATIC);
    }

    /** Properties with a backing field, Java fields, and enum entries (static fields on the JVM). */
    @Override
    public List<FieldDecl> fields() {
        var fields = new ArrayList<FieldDecl>();
        for (var member : SequencesKt.toList(declaration().getDeclarations())) {
            if (isField(member)) {
                fields.add(field(member.getSimpleName().asString()));
            }
        }
        return List.copyOf(fields);
    }

    private static boolean isField(KSDeclaration member) {
        return switch (member) {
            case KSPropertyDeclaration property -> property.getHasBackingField() || KspModel.isJava(property);
            case KSClassDeclaration nested -> nested.getClassKind() == ClassKind.ENUM_ENTRY;
            default -> false;
        };
    }

    FieldDecl field(String name) {
        var fieldRef = ref.derive(owner -> SequencesKt.toList(owner.getDeclarations()).stream()
                .filter(KspClassDecl::isField)
                .filter(member -> member.getSimpleName().asString().equals(name))
                .findFirst()
                .orElseThrow());
        return new KspFieldDecl(fieldRef, key() + "." + name, qualifiedName(), this, model);
    }

    /**
     * A record's components, in header order. KSP shows a Java record as its accessor functions
     * and constructors, without fields, so the components are read off the canonical constructor
     * (see {@link #canonicalConstructor}); a Kotlin {@code @JvmRecord} class's are its primary
     * constructor's parameters, which Kotlin makes the canonical constructor.
     *
     * @throws IllegalStateException when the canonical constructor cannot be told apart (see
     *     {@link #canonicalConstructor}): guessing would silently bind the wrong components
     */
    @Override
    public List<FieldDecl> recordComponents() {
        if (kind() != DeclKind.RECORD) {
            return List.of();
        }
        var declaration = declaration();
        var canonical =
                KspModel.isJava(declaration) ? canonicalConstructor(declaration) : declaration.getPrimaryConstructor();
        if (canonical == null) {
            return List.of();
        }
        return model.methodDecl(canonical).parameters().stream()
                .<FieldDecl>map(parameter -> new KspRecordComponent(parameter, key() + "." + parameter.name()))
                .toList();
    }

    /**
     * The record's canonical constructor, whose parameters are the components in header order.
     *
     * <ul>
     *   <li>When the record does not declare it, KSP synthesizes it, and it is the one
     *       constructor of {@code SYNTHETIC} origin.
     *   <li>When the record declares it (compact or full), it has origin {@code JAVA} like every
     *       other constructor. It is then the widest constructor whose every parameter matches an
     *       accessor by name and type; a shorter one (a subset of the components) never is.
     * </ul>
     *
     * KSP does not expose the header order itself, so a declared canonical constructor next to
     * another constructor taking the same components in a different order (legal when their types
     * differ) cannot be told apart. That case, and a record with no matching constructor, fail with
     * {@link IllegalStateException} rather than guess.
     */
    private KSFunctionDeclaration canonicalConstructor(KSClassDeclaration recordDecl) {
        var functions = KspModel.functionsOf(recordDecl);
        var constructors = functions.stream().filter(UtilsKt::isConstructor).toList();
        var synthesized = constructors.stream()
                .filter(constructor -> constructor.getOrigin() == Origin.SYNTHETIC)
                .toList();
        if (synthesized.size() == 1) {
            return synthesized.get(0);
        }
        var accessors = new HashMap<String, TypeName>();
        for (var function : functions) {
            if (!UtilsKt.isConstructor(function) && function.getParameters().isEmpty()) {
                accessors.put(function.getSimpleName().asString(), typeName(function.getReturnType()));
            }
        }
        var matching = constructors.stream()
                .filter(constructor -> takesOnlyComponents(constructor, accessors))
                .toList();
        var widest = matching.stream()
                .mapToInt(constructor -> constructor.getParameters().size())
                .max()
                .orElse(-1);
        var candidates = matching.stream()
                .filter(constructor -> constructor.getParameters().size() == widest)
                .toList();
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        throw new IllegalStateException("Cannot tell the canonical constructor of record " + qualifiedName()
                + " on KSP: "
                + (candidates.isEmpty()
                        ? "no constructor takes only its components"
                        : candidates.size() + " constructors take its components, in different orders")
                + ". Let the compiler supply the canonical constructor, or give the other constructors"
                + " parameters that are not all components.");
    }

    /** Whether every parameter matches an accessor (a zero-argument function) by name and type. */
    private boolean takesOnlyComponents(KSFunctionDeclaration constructor, Map<String, TypeName> accessors) {
        return constructor.getParameters().stream()
                .allMatch(parameter -> parameter.getName() != null
                        && typeName(parameter.getType())
                                .equals(accessors.get(parameter.getName().asString())));
    }

    private TypeName typeName(KSTypeReference reference) {
        return KspTypeNames.of(reference.resolve(), false, false, model);
    }

    @Override
    public List<String> enumConstants() {
        return SequencesKt.toList(declaration().getDeclarations()).stream()
                .filter(member ->
                        member instanceof KSClassDeclaration nested && nested.getClassKind() == ClassKind.ENUM_ENTRY)
                .map(member -> member.getSimpleName().asString())
                .toList();
    }

    @Override
    public List<TypeRef> directSupertypes() {
        var supertypes = new ArrayList<TypeRef>();
        superclass().ifPresent(supertypes::add);
        supertypes.addAll(interfaces());
        return List.copyOf(supertypes);
    }

    /**
     * The declared superclass, or the implicit one: {@code java.lang.Object} for a class or object
     * (Kotlin's implicit {@code Any}), {@code java.lang.Enum<E>} for an enum, {@code
     * java.lang.Record} for a Java record or a Kotlin {@code @JvmRecord} class. Empty for interfaces, annotations and {@code Any}
     * itself. An unresolved supertype counts as the superclass.
     */
    @Override
    public Optional<TypeRef> superclass() {
        var declaration = declaration();
        var kind = declaration.getClassKind();
        if (kind == ClassKind.INTERFACE || kind == ClassKind.ANNOTATION_CLASS || kotlinName.equals(ANY)) {
            return Optional.empty();
        }
        // A @JvmRecord class extends no class, so its listed Any stands for java.lang.Record.
        if (isJvmRecord(declaration)) {
            return Optional.of(model.typeRef(model.ref(recordType(model.resolver()), KspClassDecl::recordType)));
        }
        var superTypes = superTypes(declaration);
        for (var i = 0; i < superTypes.size(); i++) {
            if (!isInterface(superTypes.get(i))) {
                return Optional.of(supertype(i));
            }
        }
        return Optional.of(model.typeRef(model.ref(
                model.resolver().getBuiltIns().getAnyType(),
                resolver -> resolver.getBuiltIns().getAnyType())));
    }

    private static KSType recordType(Resolver resolver) {
        return KspModel.findClass(resolver, RECORD).asType(List.of());
    }

    /** The interfaces named in the supertype list, in declaration order; {@code Any} is never one. */
    @Override
    public List<TypeRef> interfaces() {
        var superTypes = superTypes(declaration());
        return IntStream.range(0, superTypes.size())
                .filter(i -> isInterface(superTypes.get(i)))
                .mapToObj(this::supertype)
                .toList();
    }

    private static List<KSType> superTypes(KSClassDeclaration declaration) {
        return SequencesKt.toList(declaration.getSuperTypes()).stream()
                .map(KSTypeReference::resolve)
                .toList();
    }

    private static boolean isInterface(KSType type) {
        return !type.isError() && KspModel.isInterface(type.getDeclaration());
    }

    private TypeRef supertype(int index) {
        return model.typeRef(ref.derive(owner -> superTypes(owner).get(index)), KspModel.isJava(declaration()));
    }

    /** The containing file's path with {@code /} separators; empty for classpath declarations. */
    @Override
    public Optional<String> sourcePath() {
        return Optional.ofNullable(declaration().getContainingFile())
                .map(file -> file.getFilePath().replace('\\', '/'));
    }

    /**
     * The declared type, with the class's own type parameters as arguments ({@code Box<T>}, as
     * javac's {@code asType()}): KSP2's {@code asType} with no arguments applies them.
     */
    @Override
    public TypeRef asType() {
        return model.typeRef(ref.derive(owner -> owner.asType(List.of())), KspModel.isJava(declaration()));
    }

    @Override
    public Location location() {
        return new KspLocation(ref, qualifiedName());
    }

    @Override
    public Optional<ClassDecl> enclosingClass() {
        return declaration().getParentDeclaration() instanceof KSClassDeclaration enclosing
                ? Optional.of(model.classDecl(enclosing))
                : Optional.empty();
    }

    String key() {
        return kotlinName;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KspClassDecl that && kotlinName.equals(that.kotlinName);
    }

    @Override
    public int hashCode() {
        return kotlinName.hashCode();
    }

    @Override
    public String toString() {
        return qualifiedName();
    }
}
