package io.tiko.processor.core.ksp;

import com.google.devtools.ksp.UtilsKt;
import com.google.devtools.ksp.processing.Resolver;
import com.google.devtools.ksp.symbol.ClassKind;
import com.google.devtools.ksp.symbol.FileLocation;
import com.google.devtools.ksp.symbol.FunctionKind;
import com.google.devtools.ksp.symbol.KSAnnotated;
import com.google.devtools.ksp.symbol.KSClassDeclaration;
import com.google.devtools.ksp.symbol.KSDeclaration;
import com.google.devtools.ksp.symbol.KSFunctionDeclaration;
import com.google.devtools.ksp.symbol.KSPropertyDeclaration;
import com.google.devtools.ksp.symbol.KSType;
import com.google.devtools.ksp.symbol.Origin;
import com.google.devtools.ksp.symbol.Variance;
import com.palantir.javapoet.ClassName;
import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.SourceLanguage;
import io.tiko.processor.core.model.TypeRef;
import io.tiko.processor.core.model.Visibility;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import kotlin.sequences.SequencesKt;

/**
 * The KSP symbol to neutral-model mapping, plus the helpers the KSP model classes share. One
 * instance per compilation, shared by every round's {@link KspProcessingEnv}: the model objects it
 * makes find their symbols again through the {@link KspSession}, so they outlive their round.
 * Model equality never depends on it.
 *
 * <p>Types map to the Java types javac sees for the same JVM signature: Kotlin built-ins to their
 * Java counterparts ({@code kotlin.String} to {@code java.lang.String}, {@code
 * kotlin.collections.List} to {@code java.util.List}), a non-null {@code Int} outside type
 * arguments to {@code int}, {@code Unit} to {@code void} (see {@link KspTypeNames}).
 */
final class KspModel {

    private static final Pattern ERROR_TYPE = Pattern.compile("<ERROR TYPE: (.*)>");

    private final KspSession session;
    private final Map<String, String> javaNames = new HashMap<>();

    KspModel(KspSession session) {
        this.session = session;
    }

    KspSession session() {
        return session;
    }

    Resolver resolver() {
        return session.resolver();
    }

    <T> KspRef<T> ref(T node, Function<Resolver, T> locate) {
        return KspRef.of(session, node, locate);
    }

    // ----- classes -----

    /** A class; an unresolved one (no qualified name) becomes a member-less placeholder. */
    ClassDecl classDecl(KSClassDeclaration declaration) {
        if (declaration.getQualifiedName() == null) {
            return new KspErrorClassDecl(errorName(declaration), null);
        }
        var kotlinName = declaration.getQualifiedName().asString();
        return new KspClassDecl(ref(declaration, resolver -> findClass(resolver, kotlinName)), kotlinName, this);
    }

    /** The class with this Kotlin qualified name, or {@code null}. */
    static KSClassDeclaration findClass(Resolver resolver, String kotlinName) {
        return resolver.getClassDeclarationByName(resolver.getKSNameFromString(kotlinName));
    }

    /** The class javac knows by this Java qualified name ({@code java.lang.String} is {@code kotlin.String}). */
    Optional<ClassDecl> findJavaClass(String javaName) {
        if (javaName.contains("<") || javaName.contains("[")) {
            return Optional.empty(); // a parameterized or array type's text names no class
        }
        var resolver = resolver();
        var kotlinName = resolver.mapJavaNameToKotlin(resolver.getKSNameFromString(javaName));
        var found = findClass(resolver, kotlinName == null ? javaName : kotlinName.asString());
        return Optional.ofNullable(found).map(this::classDecl);
    }

    /** The Java qualified name javac uses for the class ({@code kotlin.Any} is {@code java.lang.Object}). */
    String javaName(String kotlinName) {
        return javaNames.computeIfAbsent(kotlinName, name -> {
            var resolver = resolver();
            var mapped = resolver.mapKotlinNameToJava(resolver.getKSNameFromString(name));
            return mapped == null ? name : mapped.asString();
        });
    }

    /** The JavaPoet name of the class: its Java name, nested classes as such. */
    ClassName className(KSClassDeclaration declaration) {
        var kotlinName = declaration.getQualifiedName().asString();
        var javaName = javaName(kotlinName);
        if (!javaName.equals(kotlinName)) {
            return ClassName.bestGuess(javaName);
        }
        var packageName = declaration.getPackageName().asString();
        var relative = packageName.isEmpty() ? kotlinName : kotlinName.substring(packageName.length() + 1);
        var simpleNames = relative.split("\\.");
        return ClassName.get(packageName, simpleNames[0], Arrays.copyOfRange(simpleNames, 1, simpleNames.length));
    }

    /** The name an unresolved type was written as: KSP names its placeholder {@code <ERROR TYPE: Name>}. */
    static String errorName(KSDeclaration declaration) {
        var simpleName = declaration.getSimpleName().asString();
        var matcher = ERROR_TYPE.matcher(simpleName);
        return matcher.matches() ? matcher.group(1) : simpleName;
    }

    // ----- methods -----

    /**
     * A function, found again in later rounds by its position among its declaring class's
     * functions (or among the same-named top-level functions), which source order keeps stable.
     */
    MethodDecl methodDecl(KSFunctionDeclaration function) {
        if (function.getFunctionKind() == FunctionKind.TOP_LEVEL) {
            return topLevelFunction(function);
        }
        var parent = (KSClassDeclaration) function.getParentDeclaration();
        var ownerName = parent.getQualifiedName().asString();
        var declared = functionsOf(parent);
        var index = indexOf(declared, function);
        if (index >= 0) {
            return new KspMethodDecl(
                    ref(
                            function,
                            resolver ->
                                    functionsOf(findClass(resolver, ownerName)).get(index)),
                    ownerName + "#" + index,
                    this);
        }
        // Not declared on its parent: a member KSP synthesizes, such as the substitution override
        // a subclass of Base<String> sees. javac's getAllMembers hands out the declaration itself,
        // so map it to the function it stands for, keeping one identity per declaration.
        if (function.findOverridee() instanceof KSFunctionDeclaration original
                && original != function
                && !original.equals(function)
                && original.getParentDeclaration() instanceof KSClassDeclaration originalOwner
                && indexOf(functionsOf(originalOwner), original) >= 0) {
            return methodDecl(original);
        }
        // Otherwise address it among the parent's members.
        var all = SequencesKt.toList(parent.getAllFunctions());
        var allIndex = indexOf(all, function);
        if (allIndex < 0) {
            // Nowhere to find it again: usable in this round only, and its key is unique to it.
            return new KspMethodDecl(
                    ref(function, resolver -> null),
                    ownerName + "#?" + function.getSimpleName().asString() + "@" + System.identityHashCode(function),
                    this);
        }
        return new KspMethodDecl(
                ref(
                        function,
                        resolver -> SequencesKt.toList(
                                        findClass(resolver, ownerName).getAllFunctions())
                                .get(allIndex)),
                ownerName + "#all" + allIndex,
                this);
    }

    private MethodDecl topLevelFunction(KSFunctionDeclaration function) {
        var name = function.getQualifiedName().asString();
        var index = indexOf(topLevelFunctions(resolver(), name), function);
        return new KspMethodDecl(
                ref(function, resolver -> topLevelFunctions(resolver, name).get(index)), name + "#" + index, this);
    }

    private static List<KSFunctionDeclaration> topLevelFunctions(Resolver resolver, String name) {
        return SequencesKt.toList(resolver.getFunctionDeclarationsByName(resolver.getKSNameFromString(name), true));
    }

    /** The functions a class declares, constructors included, in source order. */
    static List<KSFunctionDeclaration> functionsOf(KSClassDeclaration declaration) {
        var functions =
                new ArrayList<KSFunctionDeclaration>(SequencesKt.toList(UtilsKt.getDeclaredFunctions(declaration)));
        var primary = declaration.getPrimaryConstructor();
        if (primary != null && indexOf(functions, primary) < 0) {
            functions.add(0, primary);
        }
        return functions;
    }

    /**
     * The position of {@code function} in the list. KSP caches its symbols, so this is mostly an
     * identity match; a source position settles the rest.
     */
    private static <T extends KSDeclaration> int indexOf(List<T> functions, T function) {
        for (var i = 0; i < functions.size(); i++) {
            if (functions.get(i) == function || functions.get(i).equals(function)) {
                return i;
            }
        }
        if (function.getLocation() instanceof FileLocation location) {
            for (var i = 0; i < functions.size(); i++) {
                var candidate = functions.get(i);
                if (candidate.getSimpleName().equals(function.getSimpleName())
                        && location.equals(candidate.getLocation())) {
                    return i;
                }
            }
        }
        return -1;
    }

    // ----- property accessors -----

    /** The JVM accessors of the properties the class declares, in source order (see {@link KspAccessorDecl}). */
    List<MethodDecl> declaredAccessors(KSClassDeclaration declaration) {
        return SequencesKt.toList(UtilsKt.getDeclaredProperties(declaration)).stream()
                .flatMap(property -> accessorDecls(property).stream())
                .toList();
    }

    /**
     * The JVM accessors of a member property, getter first, found again in later rounds by the
     * property's position among its declaring class's properties. A property KSP synthesizes for a
     * subclass (a substitution override) maps to the declaration it stands for.
     */
    List<MethodDecl> accessorDecls(KSPropertyDeclaration property) {
        var kinds = KspAccessorDecl.accessorKinds(property, this);
        if (kinds.isEmpty() || !(property.getParentDeclaration() instanceof KSClassDeclaration parent)) {
            return List.of();
        }
        var ownerName = parent.getQualifiedName().asString();
        var index = indexOf(declaredProperties(parent), property);
        KspRef<KSPropertyDeclaration> ref;
        String key;
        if (index >= 0) {
            ref = ref(
                    property,
                    resolver ->
                            declaredProperties(findClass(resolver, ownerName)).get(index));
            key = ownerName + "#property" + index;
        } else if (property.findOverridee() instanceof KSPropertyDeclaration original
                && !original.equals(property)
                && original.getParentDeclaration() instanceof KSClassDeclaration originalOwner
                && indexOf(declaredProperties(originalOwner), original) >= 0) {
            return accessorDecls(original);
        } else {
            // Nowhere to find it again: usable in this round only, and its key is unique to it.
            ref = ref(property, resolver -> null);
            key = ownerName + "#?" + property.getSimpleName().asString() + "@" + System.identityHashCode(property);
        }
        return kinds.stream()
                .<MethodDecl>map(setter -> accessor(ref, Boolean.TRUE.equals(setter), key))
                .toList();
    }

    private MethodDecl accessor(KspRef<KSPropertyDeclaration> ref, boolean setter, String key) {
        return new KspAccessorDecl(ref, setter, key + (setter ? ":set" : ":get"), this);
    }

    private static List<KSPropertyDeclaration> declaredProperties(KSClassDeclaration declaration) {
        return SequencesKt.toList(UtilsKt.getDeclaredProperties(declaration));
    }

    // ----- types and annotations -----

    /** A type at a top-level position: a parameter, return or field type, or a class's own type. */
    TypeRef typeRef(KspRef<KSType> type) {
        return typeRef(type, false);
    }

    /** As {@link #typeRef(KspRef)}; {@code javaDeclared} for a type a Java declaration uses (always non-null). */
    TypeRef typeRef(KspRef<KSType> type, boolean javaDeclared) {
        return new KspTypeRef(type, Variance.INVARIANT, false, false, javaDeclared, this);
    }

    /** The annotations on {@code annotated}, keyed under {@code ownerKey} for equality. */
    List<AnnotationRef> annotations(KspRef<? extends KSAnnotated> annotated, String ownerKey, String enclosingClass) {
        return KspAnnotationRef.all(annotated, ownerKey, enclosingClass, this);
    }

    // ----- modifiers -----

    static Visibility visibility(KSDeclaration declaration) {
        return switch (UtilsKt.getVisibility(declaration)) {
            case PUBLIC -> Visibility.PUBLIC;
            case PROTECTED -> Visibility.PROTECTED;
            case INTERNAL -> Visibility.KOTLIN_INTERNAL;
            case JAVA_PACKAGE -> Visibility.PACKAGE;
            case PRIVATE, LOCAL -> Visibility.PRIVATE;
        };
    }

    static SourceLanguage language(Origin origin) {
        return switch (origin) {
            case KOTLIN, SYNTHETIC -> SourceLanguage.KOTLIN;
            case JAVA -> SourceLanguage.JAVA;
            case KOTLIN_LIB, JAVA_LIB -> SourceLanguage.CLASSPATH;
        };
    }

    static boolean isJava(KSDeclaration declaration) {
        return declaration.getOrigin() == Origin.JAVA || declaration.getOrigin() == Origin.JAVA_LIB;
    }

    static boolean isInterface(KSDeclaration declaration) {
        return declaration instanceof KSClassDeclaration cls && cls.getClassKind() == ClassKind.INTERFACE;
    }

    /** The KSP type behind a model type. */
    static KSType type(TypeRef type) {
        if (type instanceof KspTypeRef ksp) {
            return ksp.ksType();
        }
        throw new IllegalArgumentException("Not a KSP type: " + type);
    }

    /** The KSP class behind a model class; empty for an unresolved placeholder. */
    static Optional<KSClassDeclaration> declaration(ClassDecl decl) {
        if (decl instanceof KspClassDecl ksp) {
            return Optional.of(ksp.declaration());
        }
        if (decl instanceof KspErrorClassDecl) {
            return Optional.empty();
        }
        throw new IllegalArgumentException("Not a KSP class: " + decl);
    }
}
