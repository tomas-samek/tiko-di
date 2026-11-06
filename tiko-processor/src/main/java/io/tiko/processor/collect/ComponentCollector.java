package io.tiko.processor.collect;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.PostConstruct;
import io.tiko.annotations.PreDestroy;
import io.tiko.annotations.Produces;
import io.tiko.processor.core.ProcessingEnv;
import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.TypeRef;
import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.util.ErrorReporter;
import io.tiko.processor.util.ProcessorContext;
import io.tiko.processor.util.TypeUtil;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Builds a {@link ComponentModel} for every {@code @Component} class of the round, plus every
 * {@code @TestComponent} class when the optional test-support annotation is on the classpath, and
 * registers it with the {@link ProcessorContext}. A class whose model cannot be built is reported
 * and skipped.
 */
public final class ComponentCollector {

    /**
     * FQN of the test-classpath shadow annotation. Referenced as a string to keep
     * tiko-processor free of any compile-time dependency on the optional test
     * support module that defines it; the annotation is detected by name at
     * processing time and silently ignored when absent.
     */
    public static final String TEST_COMPONENT_FQN = "io.tiko.test.TestComponent";

    private static final String COMPONENT = Component.class.getCanonicalName();
    private static final String INJECT = Inject.class.getCanonicalName();
    private static final String PRODUCES = Produces.class.getCanonicalName();
    private static final String POST_CONSTRUCT = PostConstruct.class.getCanonicalName();
    private static final String PRE_DESTROY = PreDestroy.class.getCanonicalName();
    private static final String COMPONENTS_MANIFEST = "META-INF/tiko/components.txt";
    /** The {@code @TestComponent} member naming the shadowed type. */
    private static final String VALUE = "value";

    private final ProcessingEnv env;
    private final ProcessorContext context;
    private final ErrorReporter errors;
    private final TypeUtil typeUtil;

    private ComponentCollector(ProcessingEnv env, ProcessorContext context) {
        this.env = env;
        this.context = context;
        this.errors = context.getErrorReporter();
        this.typeUtil = new TypeUtil(env.types());
    }

    /** Collects the round's {@code @Component} and {@code @TestComponent} classes into {@code ctx}. */
    public static void collect(ProcessingEnv env, ProcessorContext ctx) {
        var collector = new ComponentCollector(env, ctx);
        for (var decl : env.classesAnnotatedWith(COMPONENT)) {
            if (!ctx.isRejected(decl)) {
                collector.register(collector.buildComponentModel(decl));
            }
        }
        // Empty when the test-support annotation is not on the classpath.
        for (var decl : env.classesAnnotatedWith(TEST_COMPONENT_FQN)) {
            if (!ctx.isRejected(decl)) {
                collector.registerTestComponent(decl);
            }
        }
    }

    /**
     * Reads {@code META-INF/tiko/components.txt} from the compile classpath and returns
     * the set of main-component FQNs declared there. Empty when no such manifest exists
     * (e.g. the round is a first-emit main-compile rather than a peer test-compile).
     * Lets a {@code @TestComponent} detect its shadow target even when the production
     * {@code @Component} sources are absent from the round but already live on the
     * classpath as compiled bytecode.
     */
    public static Set<String> mainComponentFqnsOnClasspath(ProcessingEnv env) {
        return env.classpath()
                .readResource(COMPONENTS_MANIFEST)
                .map(text -> {
                    Set<String> fqns = new LinkedHashSet<>();
                    text.lines()
                            .map(String::trim)
                            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                            .forEach(fqns::add);
                    return fqns;
                })
                .orElse(Set.of());
    }

    private void register(ComponentModel component) {
        if (component != null) {
            context.registerComponent(component);
        }
    }

    private void registerTestComponent(ClassDecl decl) {
        if (isInProductionSources(decl)) {
            var name = decl.className().simpleName();
            errors.error(
                    decl.location(),
                    "@TestComponent " + name + " is in production sources (src/main). Test components"
                            + " only apply in a container that opts into test wiring; compiled here"
                            + " they ship in the jar as test wiring (#497).",
                    "Move " + name + " under src/test/java",
                    "Use @Component if " + name + " is a real production implementation");
            return;
        }
        register(buildTestComponentModel(decl));
    }

    /**
     * {@code true} when the class's source file sits under a {@code src/main/} directory — the
     * Maven/Gradle production source root. Unknown locations (in-memory sources, a compiler that
     * doesn't report the file) count as not production, so the check never fires without evidence.
     */
    private static boolean isInProductionSources(ClassDecl decl) {
        return decl.sourcePath().filter(path -> path.contains("/src/main/")).isPresent();
    }

    /** Builds a ComponentModel from a {@code @Component} class; {@code null} when an error was reported. */
    private ComponentModel buildComponentModel(ClassDecl decl) {
        var annotation = decl.annotation(COMPONENT).orElseThrow();
        // expose = {...} restricts routing: an entry that did not resolve would silently widen it.
        if (annotation.isUnresolved("expose")) {
            errors.error(
                    decl.location(),
                    "@Component(expose = {...}) on " + decl.className().simpleName()
                            + ": an exposed type could not be resolved when Tiko collected the component,"
                            + " so the types it routes under cannot be restricted",
                    "Expose types compiled in this module or in a dependency; types generated by another"
                            + " annotation processor are not supported here yet",
                    "Check the expose list for a typo or a missing import");
            return null;
        }
        return buildComponentModel(
                decl,
                new ComponentAttributes(
                        Scope.valueOf(annotation.enumName("scope")),
                        annotation.string("name"),
                        annotation.strings("profiles"),
                        annotation.types("expose"),
                        annotation.bool("exposeSelf"),
                        false,
                        "@Component",
                        Set.of()));
    }

    /**
     * Builds a ComponentModel from a class carrying the test-classpath shadow annotation, read by
     * name so the processor needs no compile-time dependency on the support module that declares
     * it. The remaining ComponentModel fields use the shadow annotation's documented defaults (no
     * profile gating, permissive exposure).
     */
    private ComponentModel buildTestComponentModel(ClassDecl decl) {
        var annotation = decl.annotation(TEST_COMPONENT_FQN).orElse(null);
        if (annotation == null) {
            // Class claimed by the round but annotation missing — defensive guard, should not happen.
            return null;
        }

        var scope = readScope(annotation).orElse(Scope.SINGLETON);
        var name = annotation.value("name").orElse("") instanceof String s ? s : "";
        var extraKeys = computeTestExtraKeys(decl, annotation);
        if (extraKeys.isEmpty()) {
            return null; // an unresolved value() was reported
        }

        return buildComponentModel(
                decl,
                new ComponentAttributes(
                        scope, name, List.of(), List.of(), true, true, "@TestComponent", extraKeys.get()));
    }

    /** The {@code scope} member as a {@link Scope}; empty when absent or not a known constant. */
    private static Optional<Scope> readScope(AnnotationRef annotation) {
        if (!(annotation.value("scope").orElse(null) instanceof String constant)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Scope.valueOf(constant));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    /**
     * The extra routable keys the test component shadows; empty when its {@code value()} did not
     * resolve, which is reported.
     */
    private Optional<Set<String>> computeTestExtraKeys(ClassDecl testClass, AnnotationRef annotation) {
        if (annotation.value(VALUE).isPresent() && annotation.isUnresolved(VALUE)) {
            // An unresolved value would silently fall back to the implicit walk below.
            errors.error(
                    testClass.location(),
                    "@TestComponent(value = ...) on " + testClass.qualifiedName()
                            + ": the shadowed type could not be resolved when Tiko collected the test"
                            + " component, so its shadow target cannot be determined",
                    "Point value() at a type compiled in this module or in a dependency; types generated by"
                            + " another annotation processor are not supported here yet",
                    "Check the value class literal for a typo or a missing import");
            return Optional.empty();
        }
        var valueAttr = annotation.value(VALUE).isPresent() ? annotation.type(VALUE) : Optional.<TypeRef>empty();
        if (valueAttr.isPresent() && !isVoid(valueAttr.get())) {
            // Explicit value() wins over implicit walk. Verify the annotated class is
            // actually assignable to the declared value type, otherwise shadowing is
            // structurally impossible.
            var valueType = valueAttr.get();
            if (!typeUtil.isAssignable(testClass.asType(), valueType)) {
                errors.testComponentValueNotAssignable(
                        testClass.location(), testClass.qualifiedName(), valueType.displayName());
                return Optional.of(Set.of());
            }
            return Optional.of(Set.of(valueType.erasedName()));
        }
        return Optional.of(implicitShadowKeys(testClass));
    }

    /**
     * Implicit walk: walk the superclass chain, returning routable types of the first
     * ancestor that is a main component. We check both (a) the @Component annotation
     * (visible when the main is being compiled in the same round) and (b) the
     * META-INF/tiko/components.txt manifest on the classpath (visible when the main
     * already lives in target/classes and only the test sources are being compiled).
     * The latter path is essential for the natural Maven layout where production
     * components and tests build in separate rounds.
     */
    private Set<String> implicitShadowKeys(ClassDecl testClass) {
        var mainComponentFqns = mainComponentFqnsOnClasspath(env);
        var superType = testClass.superclass().flatMap(TypeRef::declaration);
        while (superType.isPresent()) {
            var decl = superType.get();
            if (decl.qualifiedName().equals("java.lang.Object")) break;

            boolean isMainComponent =
                    decl.annotation(COMPONENT).isPresent() || mainComponentFqns.contains(decl.qualifiedName());
            if (isMainComponent) {
                Set<String> keys = new LinkedHashSet<>();
                keys.add(decl.qualifiedName());
                for (var iface : decl.interfaces()) {
                    keys.add(iface.displayName());
                }
                return keys;
            }
            superType = decl.superclass().flatMap(TypeRef::declaration);
        }
        return Set.of();
    }

    /** A class literal of {@code java.lang.Void}: the annotation's "unset" default. */
    private static boolean isVoid(TypeRef type) {
        return type.erasedName().equals("java.lang.Void");
    }

    /**
     * Shared model assembly for both {@code @Component} and the test-classpath shadow
     * annotation. Caller supplies annotation-derived attributes; this method handles
     * constructor / static-factory selection, lifecycle method discovery, proxy
     * decision, and builder population.
     */
    private ComponentModel buildComponentModel(ClassDecl decl, ComponentAttributes attributes) {
        // Detect a self-@Produces method: a static @Produces method on this class
        // returning this class with the same qualifier name. When present, it acts
        // as the bean's instantiation strategy (replaces the constructor call).
        var staticFactoryMethod = findSelfStaticFactory(decl, attributes.name());

        MethodDecl constructor;
        Optional<List<DependencyModel>> dependencies;
        if (staticFactoryMethod != null) {
            // Static factory governs instantiation; constructor is optional.
            constructor = findInjectConstructor(decl); // may be null when private/multiple
            dependencies = DependencyResolver.resolve(staticFactoryMethod, typeUtil, errors);
        } else {
            // Standard path: require a usable constructor.
            constructor = findInjectConstructor(decl);
            if (constructor == null) {
                errors.error(
                        decl.location(),
                        attributes.annotationLabel()
                                + " must have an @Inject-annotated constructor or a single public constructor",
                        "Add @Inject annotation to a constructor",
                        "Ensure only one public constructor exists if not using @Inject",
                        "If instantiation goes through a static @Produces factory, no usable constructor is required");
                return null;
            }
            dependencies = DependencyResolver.resolve(constructor, typeUtil, errors);
        }
        if (dependencies.isEmpty()) {
            return null; // an unresolved @Pick was reported
        }

        var postConstructMethods = annotatedMethods(decl, POST_CONSTRUCT, context);
        var preDestroyMethods = annotatedMethods(decl, PRE_DESTROY, context);

        // Implicit AutoCloseable cleanup: when the component implements AutoCloseable and
        // the user did not declare an explicit @PreDestroy, the container codegen calls
        // close() at scope teardown. Explicit @PreDestroy always wins to avoid double-cleanup.
        boolean autoCloseable =
                preDestroyMethods.isEmpty() && typeUtil.implementsInterface(decl, "java.lang.AutoCloseable");

        // Check if component implements an interface (for proxy generation)
        var implementedInterface = typeUtil.getFirstInterface(decl);

        var builder = ComponentModel.builder()
                .decl(decl)
                .packageName(typeUtil.getPackageName(decl))
                .className(decl.className().simpleName())
                .qualifiedName(decl.qualifiedName())
                .scope(attributes.scope())
                .name(attributes.name())
                .profiles(attributes.profiles())
                .dependencies(dependencies.get())
                .postConstructMethods(postConstructMethods)
                .preDestroyMethods(preDestroyMethods)
                .autoCloseable(autoCloseable)
                .exposeTypes(attributes.exposeTypes())
                .exposeSelf(attributes.exposeSelf())
                .testComponent(attributes.testComponent())
                .testExtraKeys(attributes.testExtraKeys());

        if (constructor != null) {
            builder.constructor(constructor);
        }
        if (staticFactoryMethod != null) {
            builder.staticFactoryMethod(staticFactoryMethod);
        }

        implementedInterface.ifPresent(builder::implementedInterface);

        // Determine if proxy is needed (EVENT scope with interface)
        boolean needsProxy = attributes.scope() == Scope.EVENT && implementedInterface.isPresent();
        builder.requiresProxy(needsProxy);

        return builder.build();
    }

    /**
     * Finds the constructor to use for injection. Selection rule:
     *   1. an @Inject-annotated constructor (any visibility) wins; or
     *   2. a Kotlin class's primary constructor when generated code can call it (public or
     *      internal), with all its parameters: a default value never makes a dependency optional; or
     *   3. the sole public constructor when no @Inject is present.
     * Constructors that are not public and not @Inject-annotated are ignored — their
     * parameters are not injection points (issue #7). Java classes have no primary constructor,
     * so rule 2 never applies to them.
     */
    private MethodDecl findInjectConstructor(ClassDecl decl) {
        var publicConstructors = new ArrayList<MethodDecl>();
        MethodDecl injectConstructor = null;

        for (var constructor : decl.constructors()) {
            if (constructor.annotation(INJECT).isPresent()) {
                if (injectConstructor != null) {
                    errors.error(
                            decl.location(),
                            "Multiple constructors annotated with @Inject",
                            "Annotate exactly one constructor with @Inject",
                            "Remove @Inject from the extra constructor(s)");
                    return null;
                }
                injectConstructor = constructor;
            }

            if (TypeUtil.isCallableFromGeneratedCode(constructor.visibility())) {
                publicConstructors.add(constructor);
            }
        }

        if (injectConstructor != null) {
            return injectConstructor;
        }
        var primary = decl.primaryConstructor()
                .filter(constructor -> TypeUtil.isCallableFromGeneratedCode(constructor.visibility()));
        if (primary.isPresent()) {
            return primary.get();
        }
        return publicConstructors.size() == 1 ? publicConstructors.get(0) : null;
    }

    /** The methods declared on {@code decl} that carry the annotation and were not rejected, in declaration order. */
    private static List<MethodDecl> annotatedMethods(ClassDecl decl, String annotationFqn, ProcessorContext context) {
        return decl.methods().stream()
                .filter(method -> method.annotation(annotationFqn).isPresent())
                .filter(method -> !context.isRejected(method))
                .toList();
    }

    /**
     * Returns the static @Produces method on {@code decl} that returns the same
     * type with a qualifier matching {@code componentName}, if present. Used so the
     * factory method becomes the component's instantiation strategy instead of a
     * competing provider.
     */
    private MethodDecl findSelfStaticFactory(ClassDecl decl, String componentName) {
        return decl.methods().stream()
                .filter(method -> isSelfStaticFactory(method, decl, componentName))
                .findFirst()
                .orElse(null);
    }

    /** A static {@code @Produces} method returning {@code decl}'s type under {@code componentName}. */
    private boolean isSelfStaticFactory(MethodDecl method, ClassDecl decl, String componentName) {
        var produces = method.annotation(PRODUCES);
        return produces.isPresent()
                && method.isStatic()
                && typeUtil.isSameType(method.returnType(), decl.asType())
                && Objects.equals(produces.get().string("name"), componentName);
    }

    /** What the component annotation contributes to the model. */
    private record ComponentAttributes(
            Scope scope,
            String name,
            List<String> profiles,
            List<TypeRef> exposeTypes,
            boolean exposeSelf,
            boolean testComponent,
            String annotationLabel,
            Set<String> testExtraKeys) {}
}
