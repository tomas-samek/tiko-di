package io.tiko.processor.generator;

import com.palantir.javapoet.*;
import io.tiko.Container;
import io.tiko.EventBus;
import io.tiko.Scope;
import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.CodeLiterals;
import io.tiko.processor.util.GeneratorAnnotations;
import io.tiko.processor.util.ProcessorContext;
import io.tiko.processor.util.TypeUtil;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.lang.model.element.Modifier;

/**
 * Generates the TikoContainerImpl class - the main DI container implementation.
 *
 * This generator creates:
 * - Singleton storage
 * - EVENT scope storage (ThreadLocal)
 * - Factory instances for each component
 * - Getter methods for each component (respecting scope)
 * - Scope management methods (runInEventScope)
 * - Lifecycle management (start, shutdown)
 */
public final class ContainerGenerator {

    private static final String GENERATED_PACKAGE = "io.tiko.generated";
    private static final String MAIN_DESCRIPTOR = "META-INF/tiko/container.properties";
    private static final String TEST_DESCRIPTOR = "META-INF/tiko/test-container.properties";

    private final ProcessorContext context;

    /**
     * The components participating in the container currently being emitted. Set by
     * {@link #generateOne} before any sub-generator helper runs, so the helpers can
     * consult this field instead of the full {@link ProcessorContext#getActiveComponents()}
     * view — that view mixes {@code @Component} and {@code @TestComponent} entries which
     * must be separated when emitting the main vs. test container.
     */
    private List<ComponentModel> currentComponents = List.of();

    /**
     * Class-name prefix applied to factory and proxy types referenced by the container
     * currently being emitted. Empty for the main container; {@code "Test_"} for the
     * standalone test container, so its factories (re-emitted with the test container's
     * own typed reference) don't collide with the main container's factory classes.
     */
    private String currentFactoryPrefix = "";

    public ContainerGenerator(ProcessorContext context) {
        this.context = context;
    }

    /**
     * Generates the {@code TikoContainerImpl_<hash>} class plus
     * {@code META-INF/tiko/container.properties}. When any {@code @TestComponent} is
     * present in the round, ALSO generates a parallel
     * {@code TestTikoContainerImpl_<hash>} plus
     * {@code META-INF/tiko/test-container.properties} — both end up on the test
     * classpath, where {@code Tiko.createInternal} prefers the test descriptor.
     *
     * <p>The main container's wiring is uncontaminated by {@code @TestComponent} entries
     * (see {@link ProcessorContext#getActiveMainComponents()}), so production builds
     * that never see test sources are unaffected by this dual emission.
     */
    public void generate() throws IOException {
        String containerClassName = context.getContainerClassName();

        // The container name carries the standalone-test-compile signal:
        // TikoAnnotationProcessor.computeContainerClassName() picks the prefix based on
        // whether an existing main descriptor was found on the classpath.
        boolean standaloneTestMode = containerClassName.startsWith("TestContainerImpl_");

        if (standaloneTestMode) {
            // Test-compile mode: an existing main container lives on the classpath; emit a
            // standalone test container + shadow declarations only. Factories, proxies, and
            // the event registry have already been emitted (typed against this container
            // name) by TikoAnnotationProcessor's upfront pass, so we just write the
            // container itself, its descriptor, components list, and the shadows file.
            var testSideComponents = context.getAllActiveComponents();
            generateOne(containerClassName, testSideComponents, TEST_DESCRIPTOR, "");
            generateComponentsListFile(testSideComponents);
            writeTestShadowsFile(containerClassName);
            return;
        }

        // Standard emission path: main container, plus (if test components are visible in
        // the same round, as in single-compile harness tests) a peer standalone test
        // container with Test_-prefixed factories so the two factory sets do not collide.
        // Real Maven projects with test sources go down the standalone branch above instead;
        // this dual-emission path exists for the compile-testing harness, which presents
        // prod and test together to a single processing round.
        var mainComponents = context.getActiveMainComponents();
        generateOne(containerClassName, mainComponents, MAIN_DESCRIPTOR, "");
        generateComponentsListFile(mainComponents);

        if (context.hasTestComponents()) {
            var testSideComponents = context.getAllActiveComponents();
            String testContainerClassName = "TestContainerImpl_" + computeHash(testSideComponents);
            ComponentFactoryGenerator factoryGenerator = new ComponentFactoryGenerator(context);
            for (ComponentModel component : testSideComponents) {
                factoryGenerator.generate(component, testContainerClassName, "Test_");
            }
            generateOne(testContainerClassName, testSideComponents, TEST_DESCRIPTOR, "Test_");
            writeTestShadowsFile(testContainerClassName);
        }
    }

    /**
     * Writes {@code META-INF/tiko/test-shadows.properties} declaring which routable keys
     * the test container shadows. Each entry maps
     * {@code shadowedKey=testContainerFqn|testComponentFqn}: the value is consumed by
     * {@code AggregatingContainer} to install an override that calls
     * {@code testContainer.get(testComponentClass)} — addressing the test component by its
     * own class so the override on the shadowed key does not recurse into itself.
     */
    private void writeTestShadowsFile(String testContainerClassName) throws IOException {
        var shadows = context.getShadowedMainKeys();
        if (shadows.isEmpty()) {
            return;
        }
        String testFqn = GENERATED_PACKAGE + "." + testContainerClassName;
        try (var writer = context.getFiler()
                .createResource(javax.tools.StandardLocation.CLASS_OUTPUT, "", "META-INF/tiko/test-shadows.properties")
                .openWriter()) {
            writer.write("# Generated by tiko-processor - test-component shadow declarations\n");
            writer.write("# Format: shadowedKey=testContainerFqn|testComponentFqn\n");
            for (String shadowedKey : shadows) {
                ComponentModel testComponent = context.getTestComponentShadowing(shadowedKey);
                if (testComponent == null) continue;
                writer.write(shadowedKey);
                writer.write("=");
                writer.write(testFqn);
                writer.write("|");
                writer.write(testComponent.getQualifiedName());
                writer.write("\n");
            }
        }
    }

    /**
     * Emits one container class + its descriptor file from the given active-component view.
     * Stashes the view on {@link #currentComponents} so the sub-generator helpers — which
     * are private to this class and walk components implicitly — see exactly the slice
     * passed in.
     */
    private void generateOne(
            String containerClassName,
            List<ComponentModel> activeComponents,
            String descriptorPath,
            String factoryPrefix)
            throws IOException {
        this.currentComponents = activeComponents;
        this.currentFactoryPrefix = factoryPrefix;

        TypeSpec.Builder containerBuilder = TypeSpec.classBuilder(containerClassName)
                .addAnnotation(GeneratorAnnotations.generatedBy(ContainerGenerator.class));
        containerBuilder.addModifiers(Modifier.PUBLIC, Modifier.FINAL);
        containerBuilder.addSuperinterface(Container.class);

        // Add fields
        containerBuilder.addField(createSingletonStorageField());
        containerBuilder.addField(createSingletonLockField());
        containerBuilder.addField(createEventScopeField());
        containerBuilder.addField(createUnitFrameOpenField());
        containerBuilder.addField(createEventBusField());
        containerBuilder.addField(createErrorHandlerField());
        containerBuilder.addField(createEventExecutorField());
        containerBuilder.addField(createOwnsEventExecutorField());
        containerBuilder.addField(createShutdownTimeoutField());
        containerBuilder.addField(createStartedAtField());
        containerBuilder.addField(createConfigSingletonsField());
        containerBuilder.addField(createShutdownInvokedField());
        containerBuilder.addField(createStoppedField());
        containerBuilder.addField(createInFlightGetsField());
        containerBuilder.addField(createInShutdownThreadField());
        containerBuilder.addField(createStartInvokedField());
        containerBuilder.addField(createPublishLifecycleEventsField());
        containerBuilder.addField(createOptionsField());
        containerBuilder.addFields(createFactoryFields());

        // Canonical 6-arg constructor (#48): (EventBus, ErrorHandler,
        // ExecutorService, boolean, Duration, TikoOptions). The legacy 4-arg shim is gone —
        // Tiko.createInternal and AggregatingContainer now always pass the full 6-arg set.
        containerBuilder.addMethod(createConstructor());

        // Add component getter methods
        containerBuilder.addMethods(createComponentGetters());

        // Add scope management methods
        containerBuilder.addMethod(createRunInEventScopeMethod());
        containerBuilder.addMethod(createSupplyInEventScopeMethod());
        containerBuilder.addMethod(createPublishUnitLifecycleMethod());
        containerBuilder.addMethod(createCloseEventScopeMethod());
        containerBuilder.addMethod(createGetOrCreateSingletonMethod());

        // Add lifecycle methods
        containerBuilder.addMethod(createStartMethod());
        containerBuilder.addMethod(createShutdownMethod());
        containerBuilder.addMethod(createIsStoppedMethod());

        // Add Provider methods
        containerBuilder.addMethod(createGetProviderMethod());
        containerBuilder.addMethod(createGetProviderWithNameMethod());

        // Add get methods
        containerBuilder.addMethod(createGetMethod());
        containerBuilder.addMethod(createGetWithNameMethod());
        containerBuilder.addMethod(createGetAllMethod());

        // Add config injection method
        containerBuilder.addMethod(createInjectConfigsMethod());

        // Add EventBus getter
        containerBuilder.addMethod(createGetEventBusMethod());

        // Add ErrorHandler getter
        containerBuilder.addMethod(createGetErrorHandlerMethod());

        // Add EventExecutor getter
        containerBuilder.addMethod(createGetEventExecutorMethod());

        // Add package-private options() accessor so generated factories in the same
        // io.tiko.generated package can consult per-call-site overrides without a
        // Container-interface change. Used by ComponentFactoryGenerator to wrap each
        // direct dependency resolution with an override-aware ternary keyed on the
        // parameter's declared type (#128).
        containerBuilder.addMethod(createOptionsAccessor());

        TypeSpec containerClass = containerBuilder.build();

        JavaFile javaFile = JavaFile.builder(GENERATED_PACKAGE, containerClass).build();

        javaFile.writeTo(context.getFiler());

        // Descriptor pointing at this container — main and test paths use the same shape
        // (just a different filename) so the runtime preference logic in Tiko.createInternal
        // is a one-line resource-name swap.
        writeContainerDescriptor(descriptorPath, containerClassName);
    }

    /**
     * Deterministic hash suffix for a {@link ComponentModel} list. Mirrors
     * {@code TikoAnnotationProcessor#computeContainerClassName} — kept private here so
     * the test container's hash is computed from its own component slice rather than the
     * main one, guaranteeing the two container class names cannot collide.
     */
    private static String computeHash(List<ComponentModel> components) {
        var keys = components.stream()
                .map(ComponentModel::getComponentKey)
                .sorted()
                .toList();
        int hash = Objects.hash(keys.toArray());
        return Integer.toHexString(hash & 0x7FFFFFFF);
    }

    /**
     * Returns the component view for the container currently being emitted. Helpers
     * call this instead of {@code context.getActiveComponents()} so dual emission
     * (main + test) picks up the correct slice on each pass.
     */
    private List<ComponentModel> activeComponents() {
        return currentComponents;
    }

    /**
     * Creates the singleton storage field: Map<String, Object>.
     */
    private FieldSpec createSingletonStorageField() {
        ParameterizedTypeName mapType = ParameterizedTypeName.get(
                ClassName.get(Map.class), ClassName.get(String.class), ClassName.get(Object.class));

        return FieldSpec.builder(mapType, "singletons", scopeStorageModifiers())
                .initializer("new $T<>()", ConcurrentHashMap.class)
                .build();
    }

    /**
     * Field-modifier set for the per-container scope-storage and override-source fields.
     * Always {@code private final} — the standalone test container emitted alongside the
     * main container in test rounds does not extend it, so the main container's scope
     * storage stays fully encapsulated.
     */
    private Modifier[] scopeStorageModifiers() {
        return new Modifier[] {Modifier.PRIVATE, Modifier.FINAL};
    }

    /**
     * Creates the EVENT scope storage field: ThreadLocal<Map<String, Object>>.
     * Uses {@link LinkedHashMap} for ordered teardown — same rationale as the singleton map.
     */
    private FieldSpec createEventScopeField() {
        ParameterizedTypeName mapType = ParameterizedTypeName.get(
                ClassName.get(Map.class), ClassName.get(String.class), ClassName.get(Object.class));

        ParameterizedTypeName threadLocalType = ParameterizedTypeName.get(ClassName.get(ThreadLocal.class), mapType);

        return FieldSpec.builder(threadLocalType, "eventScoped", scopeStorageModifiers())
                .initializer("$T.withInitial($T::new)", ThreadLocal.class, LinkedHashMap.class)
                .build();
    }

    /**
     * Tracks whether the current thread is inside a {@code runInEventScope}/{@code supplyInEventScope}
     * frame. Used by the single-frame nesting guard in those methods, and by the resolution
     * guard in every EVENT-scoped getter (#302) — an EVENT bean may only materialize while a
     * frame is open, so the frame's teardown is guaranteed to drain it.
     */
    private FieldSpec createUnitFrameOpenField() {
        ParameterizedTypeName threadLocalType =
                ParameterizedTypeName.get(ClassName.get(ThreadLocal.class), ClassName.get(Boolean.class));
        return FieldSpec.builder(threadLocalType, "__unitFrameOpen", scopeStorageModifiers())
                .initializer("$T.withInitial(() -> $T.FALSE)", ThreadLocal.class, Boolean.class)
                .build();
    }

    /**
     * Creates the EventBus field.
     */
    private FieldSpec createEventBusField() {
        return FieldSpec.builder(EventBus.class, "eventBus", Modifier.PRIVATE, Modifier.FINAL)
                .build();
    }

    /**
     * Creates the ErrorHandler field.
     */
    private FieldSpec createErrorHandlerField() {
        return FieldSpec.builder(
                        ClassName.get("io.tiko", "ErrorHandler"), "errorHandler", Modifier.PRIVATE, Modifier.FINAL)
                .build();
    }

    /**
     * Creates the ExecutorService eventExecutor field.
     */
    private FieldSpec createEventExecutorField() {
        return FieldSpec.builder(
                        ClassName.get("java.util.concurrent", "ExecutorService"),
                        "eventExecutor",
                        Modifier.PRIVATE,
                        Modifier.FINAL)
                .build();
    }

    /**
     * Creates the boolean ownsEventExecutor field (true when the container created the executor itself).
     */
    private FieldSpec createOwnsEventExecutorField() {
        return FieldSpec.builder(TypeName.BOOLEAN, "ownsEventExecutor", Modifier.PRIVATE, Modifier.FINAL)
                .build();
    }

    /**
     * Creates the {@code Duration shutdownTimeout} field — the graceful-wait window
     * used by {@link #createShutdownMethod()} when the container owns the executor (#48).
     */
    private FieldSpec createShutdownTimeoutField() {
        return FieldSpec.builder(Duration.class, "shutdownTimeout", Modifier.PRIVATE, Modifier.FINAL)
                .build();
    }

    /**
     * Tracks when start() ran so shutdown() can publish ApplicationEndingEvent with uptime.
     */
    private FieldSpec createStartedAtField() {
        return FieldSpec.builder(Instant.class, "startedAt", Modifier.PRIVATE, Modifier.VOLATILE)
                .build();
    }

    /**
     * Field: Map&lt;Class&lt;?&gt;, Object&gt; configSingletons — populated by injectConfigs().
     */
    private FieldSpec createConfigSingletonsField() {
        ParameterizedTypeName mapType = ParameterizedTypeName.get(
                ClassName.get(Map.class),
                ParameterizedTypeName.get(ClassName.get(Class.class), WildcardTypeName.subtypeOf(Object.class)),
                ClassName.get(Object.class));
        return FieldSpec.builder(mapType, "configSingletons", Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T<>()", ConcurrentHashMap.class)
                .build();
    }

    /**
     * Field: AtomicBoolean shutdownInvoked — CAS guard so shutdown() is idempotent (#47).
     */
    private FieldSpec createShutdownInvokedField() {
        return FieldSpec.builder(
                        ClassName.get("java.util.concurrent.atomic", "AtomicBoolean"),
                        "shutdownInvoked",
                        Modifier.PRIVATE,
                        Modifier.FINAL)
                .initializer("new $T(false)", ClassName.get("java.util.concurrent.atomic", "AtomicBoolean"))
                .build();
    }

    /**
     * Field: Object singletonLock — reentrant monitor for singleton creation (#338).
     * {@code __getOrCreateSingleton} synchronizes on it instead of running factories
     * inside a {@code ConcurrentHashMap} mapping function, so dependency chains can
     * create nested singletons on the same thread without tripping CHM's
     * recursive-update detection when chain keys share a hash bin.
     */
    private FieldSpec createSingletonLockField() {
        return FieldSpec.builder(Object.class, "singletonLock", Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T()", Object.class)
                .build();
    }

    /**
     * Field: AtomicBoolean stopped — gates get() once @PreDestroy has started (#47).
     */
    private FieldSpec createStoppedField() {
        return FieldSpec.builder(
                        ClassName.get("java.util.concurrent.atomic", "AtomicBoolean"),
                        "stopped",
                        Modifier.PRIVATE,
                        Modifier.FINAL)
                .initializer("new $T(false)", ClassName.get("java.util.concurrent.atomic", "AtomicBoolean"))
                .build();
    }

    /**
     * Field: AtomicInteger inFlightGets — drain barrier for shutdown to wait on (#47).
     */
    private FieldSpec createInFlightGetsField() {
        return FieldSpec.builder(
                        ClassName.get("java.util.concurrent.atomic", "AtomicInteger"),
                        "inFlightGets",
                        Modifier.PRIVATE,
                        Modifier.FINAL)
                .initializer("new $T(0)", ClassName.get("java.util.concurrent.atomic", "AtomicInteger"))
                .build();
    }

    /**
     * Field: ThreadLocal&lt;Boolean&gt; inShutdownThread — bypass marker so @PreDestroy methods
     * can call container.get(...) during shutdown without tripping the stopped gate (#47).
     */
    private FieldSpec createInShutdownThreadField() {
        ParameterizedTypeName tlType =
                ParameterizedTypeName.get(ClassName.get(ThreadLocal.class), ClassName.get(Boolean.class));
        return FieldSpec.builder(tlType, "inShutdownThread", Modifier.PRIVATE, Modifier.FINAL)
                .initializer("$T.withInitial(() -> $T.FALSE)", ThreadLocal.class, Boolean.class)
                .build();
    }

    /**
     * Field: AtomicBoolean startInvoked — CAS guard so start() is idempotent (#45).
     */
    private FieldSpec createStartInvokedField() {
        return FieldSpec.builder(
                        ClassName.get("java.util.concurrent.atomic", "AtomicBoolean"),
                        "startInvoked",
                        Modifier.PRIVATE,
                        Modifier.FINAL)
                .initializer("new $T(false)", ClassName.get("java.util.concurrent.atomic", "AtomicBoolean"))
                .build();
    }

    /**
     * Field: boolean publishLifecycleEvents — when false, this container does NOT publish
     * its own {@code ApplicationStartedEvent} / {@code ApplicationEndingEvent}. The
     * {@code AggregatingContainer} sets this to {@code false} on per-module containers so
     * the aggregator can publish exactly once on the shared bus (#45).
     */
    private FieldSpec createPublishLifecycleEventsField() {
        return FieldSpec.builder(TypeName.BOOLEAN, "publishLifecycleEvents", Modifier.PRIVATE, Modifier.FINAL)
                .build();
    }

    /**
     * Field: TikoOptions options — held so the dispatcher heads ({@code get(Class)},
     * {@code get(Class, String)}) and factory call sites can consult
     * {@link io.tiko.runtime.TikoOptions#getOverride} when resolving by the declared/
     * requested type. After #128 the per-component getters are pure factory caches;
     * override consultation no longer happens inside them.
     */
    private FieldSpec createOptionsField() {
        return FieldSpec.builder(ClassName.get("io.tiko.runtime", "TikoOptions"), "options", scopeStorageModifiers())
                .build();
    }

    /**
     * public void injectConfigs(Map&lt;Class&lt;?&gt;, Object&gt; configs) — populates the configSingletons map.
     */
    private MethodSpec createInjectConfigsMethod() {
        ParameterizedTypeName mapType = ParameterizedTypeName.get(
                ClassName.get(Map.class),
                ParameterizedTypeName.get(ClassName.get(Class.class), WildcardTypeName.subtypeOf(Object.class)),
                ClassName.get(Object.class));
        return MethodSpec.methodBuilder("injectConfigs")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(mapType, "configs")
                .addStatement("this.configSingletons.putAll(configs)")
                .build();
    }

    /**
     * Creates factory fields for each component.
     */
    private List<FieldSpec> createFactoryFields() {
        List<FieldSpec> fields = new ArrayList<>();

        for (ComponentModel component : activeComponents()) {
            String factoryClassName = currentFactoryPrefix + component.getClassName() + "Factory";
            String fieldName = getFactoryFieldName(component.getClassName());

            fields.add(FieldSpec.builder(
                            ClassName.get(GENERATED_PACKAGE, factoryClassName),
                            fieldName,
                            Modifier.PRIVATE,
                            Modifier.FINAL)
                    .build());

            // Add proxy field for components that need proxies
            if (component.requiresProxy()) {
                String proxyFieldName = getProxyFieldName(component.getClassName());
                String proxyClassName = component.getClassName() + "Proxy";

                fields.add(FieldSpec.builder(
                                ClassName.get(GENERATED_PACKAGE, proxyClassName),
                                proxyFieldName,
                                Modifier.PRIVATE,
                                Modifier.FINAL)
                        .build());
            }
        }

        return fields;
    }

    /**
     * Creates the canonical 6-arg constructor that initializes factories, event bus,
     * error handler, the shutdown timeout (#48), and the {@link io.tiko.runtime.TikoOptions}
     * reference used by override-aware getters.
     * <p>The {@code publishLifecycleEvents} flag (#45) controls whether this container
     * publishes its own {@code ApplicationStartedEvent} / {@code ApplicationEndingEvent}.
     * Single-module setups pass {@code true}; per-module containers run under an
     * {@code AggregatingContainer} pass {@code false} so the aggregator can publish
     * exactly once on the shared bus.
     * <p>The {@code shutdownTimeout} controls how long {@link #createShutdownMethod()}
     * waits for the framework-owned event executor to drain before forcing
     * {@code shutdownNow()} (#48).
     * <p>{@code options} is held for override lookups inside scoped getter methods;
     * production callers can pass {@code TikoOptions.builder().build()}.
     */
    private MethodSpec createConstructor() {
        MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(EventBus.class, "eventBus")
                .addParameter(ClassName.get("io.tiko", "ErrorHandler"), "errorHandler")
                .addParameter(ClassName.get("java.util.concurrent", "ExecutorService"), "userEventExecutor")
                .addParameter(TypeName.BOOLEAN, "publishLifecycleEvents")
                .addParameter(Duration.class, "shutdownTimeout")
                .addParameter(ClassName.get("io.tiko.runtime", "TikoOptions"), "options")
                .addStatement("this.eventBus = eventBus")
                .addStatement("this.errorHandler = errorHandler")
                .addStatement("this.eventExecutor = userEventExecutor != null ? userEventExecutor : "
                        + "io.tiko.runtime.DefaultEventExecutorFactory.create()")
                .addStatement("this.ownsEventExecutor = (userEventExecutor == null)")
                .addStatement("this.publishLifecycleEvents = publishLifecycleEvents")
                .addStatement("this.shutdownTimeout = shutdownTimeout")
                .addStatement("this.options = options");

        // Initialize factory fields
        for (ComponentModel component : activeComponents()) {
            String factoryClassName = currentFactoryPrefix + component.getClassName() + "Factory";
            String fieldName = getFactoryFieldName(component.getClassName());

            constructor.addStatement("this.$L = new $L(this)", fieldName, factoryClassName);
        }

        // Initialize proxy fields
        for (ComponentModel component : activeComponents()) {
            if (component.requiresProxy()) {
                String proxyFieldName = getProxyFieldName(component.getClassName());
                String proxyClassName = component.getClassName() + "Proxy";

                constructor.addStatement("this.$L = new $L(this)", proxyFieldName, proxyClassName);
            }
        }

        return constructor.build();
    }

    /**
     * Creates getter methods for all components.
     */
    private List<MethodSpec> createComponentGetters() {
        List<MethodSpec> methods = new ArrayList<>();

        for (ComponentModel component : activeComponents()) {
            methods.add(createComponentGetter(component));

            // getCurrentXxx exists solely as the proxy's delegate target; for non-proxied
            // EVENT components it would be a dead byte-identical twin of the plain getter (#308).
            if (component.getScope() == Scope.EVENT && component.requiresProxy()) {
                methods.add(createCurrentScopedGetter(component));
            }
        }

        for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
            methods.add(createFactoryInvocationHelper(factory));
            methods.add(createFactoryMethodGetter(factory));
        }

        return methods;
    }

    /**
     * Creates a public getter for a @Produces factory method. The getter applies the
     * method's scope (singleton cache / event / prototype) around a call to
     * the factory method itself (instance or static).
     */
    private MethodSpec createFactoryMethodGetter(FactoryMethodModel factory) {
        TypeName returnType = TypeName.get(factory.getReturnType());
        String methodName = factoryGetterName(factory);
        String storageKey = factory.getComponentKey();
        String callExpr = buildFactoryCallExpression(factory);

        MethodSpec.Builder method = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC)
                .returns(returnType);

        // SINGLETON/EVENT getters cast the scope map's Object back to the produced type.
        // That cast is checked: generic return types are rejected up front by
        // ProducesSignatureValidator (#327), so the produced type is always a concrete,
        // non-generic type and no unchecked suppression is needed here. (The get/getAll
        // dispatchers still suppress — their casts are to the type variable T.)
        switch (factory.getScope()) {
            case SINGLETON ->
                method.addStatement(
                        "return ($T) __getOrCreateSingleton($S, () -> $L)", returnType, storageKey, callExpr);
            case EVENT ->
                emitScopedGetOrCreateNoOverride(method, returnType, "eventScoped.get()", storageKey, callExpr);
            case PROTOTYPE -> method.addStatement("return $L", callExpr);
        }

        return method.build();
    }

    /**
     * Builds the expression the scoped getter ({@link #createFactoryMethodGetter}) uses
     * to obtain a fresh factory output. Routes every factory call through the
     * per-factory {@code invokeFactory_<id>()} helper emitted by
     * {@link #createFactoryInvocationHelper} so the try/catch +
     * {@link io.tiko.ProduceFailure} routing + sneaky-throw lives in exactly one place.
     */
    private String buildFactoryCallExpression(FactoryMethodModel factory) {
        return "invokeFactory_" + factory.getFactoryIdentifier() + "()";
    }

    /**
     * Builds the raw user-method invocation expression: instance methods are called on
     * the declaring {@code @Component} singleton; static methods are called directly.
     * Used inside {@link #createFactoryInvocationHelper} only.
     */
    private String buildRawUserMethodCall(FactoryMethodModel factory) {
        List<String> args = new ArrayList<>();
        for (DependencyModel dep : factory.getDependencies()) {
            args.add(generateContainerGetCall(dep));
        }
        String argList = String.join(", ", args);
        String declaringClassName = factory.getDeclaringClass().getSimpleName().toString();

        if (factory.isStatic()) {
            return String.format("%s.%s(%s)", declaringClassName, factory.getMethodName(), argList);
        } else {
            return String.format("get%s().%s(%s)", declaringClassName, factory.getMethodName(), argList);
        }
    }

    /**
     * Creates the per-factory invocation helper. The helper resolves the factory's
     * dependencies, invokes the user's {@code @Produces} method (instance or static),
     * and wraps the call in a {@link Throwable} catch that publishes
     * {@link io.tiko.ProduceFailure} and sneaky-throws the original cause via
     * {@link io.tiko.runtime.Unchecked}. Each scoped accessor
     * ({@link #createFactoryMethodGetter}) calls this helper from its single-expression
     * lambda body so the lambda stays one line.
     */
    private MethodSpec createFactoryInvocationHelper(FactoryMethodModel factory) {
        TypeName returnType = TypeName.get(factory.getReturnType());
        String helperName = "invokeFactory_" + factory.getFactoryIdentifier();
        String userCallExpr = buildRawUserMethodCall(factory);

        return MethodSpec.methodBuilder(helperName)
                .addModifiers(Modifier.PRIVATE)
                .returns(returnType)
                .beginControlFlow("try")
                .addStatement("return $L", userCallExpr)
                .nextControlFlow("catch ($T __t)", Exception.class)
                .addStatement(
                        "getErrorHandler().onError(new $T($T.class, $S, __t))",
                        ClassName.get("io.tiko", "ProduceFailure"),
                        ClassName.get(factory.getDeclaringClass()),
                        factory.getMethodName())
                .addStatement(
                        "throw $T.<$T>sneakyThrow(__t)",
                        ClassName.get("io.tiko.runtime", "Unchecked"),
                        RuntimeException.class)
                .endControlFlow()
                .build();
    }

    /**
     * Resolves a dependency to an inline expression callable from within the container class.
     * Mirrors {@link ComponentFactoryGenerator#generateContainerGetCall} but emits unqualified
     * calls (no "container." prefix) since we are inside the container itself.
     */
    private String generateContainerGetCall(DependencyModel dependency) {
        // Picker<T> is constructed inline via the generic ContainerPicker — no provider
        // lookup. Inside the container we pass `this` (we ARE the container).
        if (dependency.isPicker()) {
            String baseType = dependency.getUnwrappedType().orElseThrow().toString();
            return "new io.tiko.runtime.ContainerPicker<>(this, " + baseType + ".class)";
        }

        // EventBus is a built-in, container-provided dependency (#314). We ARE the container,
        // so call getEventBus() unqualified; Provider<EventBus> wraps it in a lazy lambda.
        if (dependency.isEventBus()) {
            return dependency.isProvider() ? "() -> getEventBus()" : "getEventBus()";
        }

        String typeName = dependency.isProvider()
                ? dependency.getUnwrappedType().orElseThrow().toString()
                : dependency.getTypeName();

        // @Pick: route to the picked impl's getter. When @Named is also present, the
        // qualifier narrows the lookup to a specific provider via the (impl#name) key —
        // typically one of several @Produces methods that all return the picked class.
        // Mirrors ComponentFactoryGenerator without the "container." prefix.
        if (dependency.isPicked()) {
            String pickedFqn = dependency.getPickedTypeName().orElseThrow();
            Object pickedProvider = dependency
                    .getQualifier()
                    .map(q ->
                            context.findComponentOrFactory(pickedFqn + "#" + q).orElse(null))
                    .orElseGet(() -> context.findByImplClass(pickedFqn).orElse(null));
            String pickedCall;
            if (pickedProvider instanceof FactoryMethodModel pickedFactory) {
                pickedCall = factoryGetterName(pickedFactory) + "()";
            } else if (pickedProvider instanceof ComponentModel pickedComponent) {
                pickedCall = "get" + pickedComponent.getClassName() + "()";
            } else {
                pickedCall = "get" + simpleClassName(pickedFqn) + "()";
            }
            return dependency.isProvider() ? "() -> " + pickedCall : pickedCall;
        }

        Object provider =
                context.findComponentOrFactory(dependency.getDependencyKey()).orElse(null);

        String call;
        if (provider instanceof FactoryMethodModel factoryDep) {
            call = factoryGetterName(factoryDep) + "()";
        } else if (provider instanceof ComponentModel component) {
            // Named lookup routes through the typed get(Class, name) dispatcher; per-class getters
            // are no-arg and cannot take a qualifier (#242).
            call = dependency
                    .getQualifier()
                    .map(q -> "get(" + typeName + ".class, \"" + CodeLiterals.javaString(q) + "\")")
                    .orElse("get" + component.getClassName() + "()");
        } else if (provider instanceof io.tiko.processor.config.ConfigurationModel) {
            // @Configuration records are stored in configSingletons and retrieved via get(Class)
            call = "get(" + typeName + ".class)";
        } else {
            call = dependency
                    .getQualifier()
                    .map(q -> "get(" + typeName + ".class, \"" + CodeLiterals.javaString(q) + "\")")
                    .orElse("get" + simpleClassName(typeName) + "()");
        }

        return dependency.isProvider() ? "() -> " + call : call;
    }

    private static String factoryGetterName(FactoryMethodModel factory) {
        return "produce_" + factory.getFactoryIdentifier();
    }

    private static String simpleClassName(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot >= 0 ? qualifiedName.substring(lastDot + 1) : qualifiedName;
    }

    /**
     * Creates a getter method for a single component.
     */
    private MethodSpec createComponentGetter(ComponentModel component) {
        String methodName = "get" + component.getClassName();
        TypeName returnType = ClassName.get(component.getTypeElement());

        // If component requires proxy, return proxy instead
        if (component.requiresProxy()) {
            String proxyClassName = component.getClassName() + "Proxy";
            returnType = ClassName.get(GENERATED_PACKAGE, proxyClassName);
        }

        MethodSpec.Builder method = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC)
                .returns(returnType);

        String storageKey = component.getComponentKey();
        String factoryFieldName = getFactoryFieldName(component.getClassName());

        switch (component.getScope()) {
            case SINGLETON -> {
                // Return from singleton cache, create if not exists. Per-component getters are
                // pure factory caches — override consultation happens at the dispatcher entry
                // points (get(Class) / get(Class, String)) and at factory call sites where the
                // declared parameter type is known. Consulting overrides here would force the
                // cast to the concrete class, defeating interface-keyed override lookup.
                if (component.requiresProxy()) {
                    // Proxies are created eagerly in constructor, just return the field
                    String proxyFieldName = getProxyFieldName(component.getClassName());
                    method.addStatement("return $L", proxyFieldName);
                } else {
                    method.addStatement(
                            "return ($1T) __getOrCreateSingleton($2S, () -> $3L.create())",
                            returnType,
                            storageKey,
                            factoryFieldName);
                }
            }
            case EVENT -> {
                // Return from EVENT scope storage. Per-component getters are pure factory
                // caches after #128: override consultation happens upstream at dispatcher
                // heads (get(Class), get(Class, String)) and at factory call sites.
                if (component.requiresProxy()) {
                    // Proxies are created eagerly in constructor, just return the field
                    String proxyFieldName = getProxyFieldName(component.getClassName());
                    method.addStatement("return $L", proxyFieldName);
                } else {
                    emitScopedGetOrCreate(
                            method, returnType, "eventScoped.get()", storageKey, factoryFieldName + ".create()");
                }
            }
            case PROTOTYPE -> // Always create new instance
                method.addStatement("return $L.create()", factoryFieldName);
        }

        return method.build();
    }

    /**
     * Creates getCurrentXxx method for EVENT-scoped components (used by proxies).
     */
    private MethodSpec createCurrentScopedGetter(ComponentModel component) {
        String methodName = "getCurrent" + component.getClassName();
        TypeName returnType = ClassName.get(component.getTypeElement());
        String storageKey = component.getComponentKey();
        String factoryFieldName = getFactoryFieldName(component.getClassName());

        MethodSpec.Builder method = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC)
                .returns(returnType);

        emitScopedGetOrCreate(method, returnType, "eventScoped.get()", storageKey, factoryFieldName + ".create()");

        return method.build();
    }

    /**
     * Emits a reentrant-safe get-or-create for a scope map. Plain
     * {@code computeIfAbsent} on {@link LinkedHashMap} throws
     * {@link java.util.ConcurrentModificationException} when the create lambda
     * recursively pulls another bean from the same map (e.g., dependency chains
     * like EVENT {@code EventContext} depending on another EVENT-scoped {@code Connection}
     * in {@code tiko-examples/10_persistence_jdbc}). The map is single-threaded
     * (per-thread {@code ThreadLocal}) so this non-atomic get/put pair is safe —
     * and produces the desired insertion order: dependencies are put first,
     * dependents last, which is exactly what scope teardown's reverse iteration
     * relies on for LIFO destruction.
     *
     * <p>This is intentionally different from the SINGLETON case above, which routes
     * through {@code __getOrCreateSingleton}: {@code singletons} is a
     * {@link java.util.concurrent.ConcurrentHashMap} (chosen for thread safety, since
     * SINGLETON beans are reachable from any thread), so its creation path needs the
     * reentrant lock-then-put shape instead — nested {@code computeIfAbsent} on CHM
     * throws {@code IllegalStateException("Recursive update")} when chain keys share a
     * bin (#338). The EVENT map here is per-thread, so the plain get/put pair suffices;
     * see closed issue #100 for the original analysis.
     *
     * <p>Per-component getters are pure factory caches: the override consultation
     * happens upstream at the dispatcher heads ({@code get(Class)},
     * {@code get(Class, String)}) and at factory call sites for direct dependencies
     * and {@code Provider} lambdas. By the time this helper runs, the caller has
     * already decided to invoke the production factory — see issue #128.
     *
     * <p>Resolution is guarded on an open unit-of-work frame (#302): teardown only runs
     * inside {@code runInEventScope}/{@code supplyInEventScope} {@code finally} blocks, so
     * an instance materialized outside a frame would never be drained — it would leak, or
     * hand stale per-unit data to the next unit scheduled on the same pooled thread. All
     * EVENT resolution paths (component getter, {@code getCurrent*} proxy delegate,
     * {@code produce_*} factory getter) funnel through this guard.
     */
    private void emitScopedGetOrCreate(
            MethodSpec.Builder method, TypeName returnType, String mapExpr, String storageKey, String createExpr) {
        emitUnitFrameGuard(method, storageKey);
        method.addStatement("$T __existing = ($T) $L.get($S)", returnType, returnType, mapExpr, storageKey);
        method.beginControlFlow(IF_EXISTING_NULL);
        method.addStatement("__existing = $L", createExpr);
        method.addStatement("$L.put($S, __existing)", mapExpr, storageKey);
        method.endControlFlow();
        method.addStatement(RETURN_EXISTING);
    }

    /**
     * Variant of {@link #emitScopedGetOrCreate} used by {@code @Produces} factory-method
     * getters. After #128, both helpers have identical bodies; this one stays as a
     * distinct entry point so {@code @Produces} callers remain a separately greppable
     * group should their emission ever need to diverge again.
     */
    private void emitScopedGetOrCreateNoOverride(
            MethodSpec.Builder method, TypeName returnType, String mapExpr, String storageKey, String createExpr) {
        emitUnitFrameGuard(method, storageKey);
        method.addStatement("$T __existing = ($T) $L.get($S)", returnType, returnType, mapExpr, storageKey);
        method.beginControlFlow(IF_EXISTING_NULL);
        method.addStatement("__existing = $L", createExpr);
        method.addStatement("$L.put($S, __existing)", mapExpr, storageKey);
        method.endControlFlow();
        method.addStatement(RETURN_EXISTING);
    }

    /**
     * Emits the unit-of-work frame check shared by every EVENT-scoped getter (#302):
     * resolving an EVENT bean while {@code __unitFrameOpen} is false throws
     * {@link io.tiko.NoActiveEventScopeException} instead of storing an instance the
     * frame teardown will never see.
     */
    private static void emitUnitFrameGuard(MethodSpec.Builder method, String storageKey) {
        method.beginControlFlow("if (!$T.TRUE.equals(__unitFrameOpen.get()))", Boolean.class);
        method.addStatement(THROW_TYPE_WITH_MESSAGE, NO_ACTIVE_EVENT_SCOPE, storageKey);
        method.endControlFlow();
    }

    /** JavaPoet statement format for {@code throw new <Type>("<literal>")} guards. */
    private static final String THROW_TYPE_WITH_MESSAGE = "throw new $T($S)";

    private static final String EVENTS_PACKAGE = "io.tiko.events";
    private static final ClassName EVENT_STARTED = ClassName.get(EVENTS_PACKAGE, "EventStartedEvent");
    private static final ClassName EVENT_ENDING = ClassName.get(EVENTS_PACKAGE, "EventEndingEvent");
    private static final ClassName APP_STARTED = ClassName.get(EVENTS_PACKAGE, "ApplicationStartedEvent");
    private static final ClassName APP_ENDING = ClassName.get(EVENTS_PACKAGE, "ApplicationEndingEvent");
    private static final ClassName BOUNDED_EXECUTION = ClassName.get("io.tiko.runtime", "BoundedExecution");
    private static final ClassName CONTAINER_SHUT_DOWN = ClassName.get("io.tiko", "ContainerShutDownException");
    /** Shared get-or-create statement fragments (S1192) — used by scoped and singleton emission. */
    private static final String IF_EXISTING_NULL = "if (__existing == null)";

    /** Lifecycle-publish gate shared by start/shutdown and both scope brackets (#45, #339). */
    private static final String IF_PUBLISH_LIFECYCLE = "if (publishLifecycleEvents)";

    private static final String RETURN_EXISTING = "return __existing";

    private static final ClassName NO_SUCH_COMPONENT = ClassName.get("io.tiko", "NoSuchComponentException");
    private static final ClassName NO_ACTIVE_EVENT_SCOPE = ClassName.get("io.tiko", "NoActiveEventScopeException");

    /** {@code Supplier<?>} — the type of a single-lookup override read (#309). */
    private static final TypeName SUPPLIER_WILDCARD = ParameterizedTypeName.get(
            ClassName.get(java.util.function.Supplier.class), WildcardTypeName.subtypeOf(Object.class));

    /** Shared {@code @SuppressWarnings("unchecked")} spec for dispatchers and scoped produce getters (#309). */
    private static final AnnotationSpec SUPPRESS_UNCHECKED = AnnotationSpec.builder(SuppressWarnings.class)
            .addMember("value", "$S", "unchecked")
            .build();

    /**
     * Creates runInEventScope method.
     */
    private MethodSpec createRunInEventScopeMethod() {
        MethodSpec.Builder method = MethodSpec.methodBuilder("runInEventScope")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addParameter(Runnable.class, "task");
        method.beginControlFlow("if ($T.TRUE.equals(__unitFrameOpen.get()))", Boolean.class);
        method.addStatement(
                THROW_TYPE_WITH_MESSAGE,
                IllegalStateException.class,
                "runInEventScope called while a unit of work is already open. "
                        + "EVENT is single-frame in 0.x.0; nesting is not supported.");
        method.endControlFlow();
        method.addStatement("__unitFrameOpen.set($T.TRUE)", Boolean.class)
                .addStatement("$T __eventId = $T.randomUUID().toString()", String.class, UUID.class)
                .addStatement("$T __eventStart = $T.now()", Instant.class, Instant.class);
        emitGatedUnitStartedPublish(method);
        method.beginControlFlow("try")
                .addStatement("task.run()")
                .nextControlFlow("finally")
                .addStatement("$T __eventEnd = $T.now()", Instant.class, Instant.class);
        emitGatedUnitEndingPublish(method);
        method.addStatement("__closeEventScope()").endControlFlow();
        return method.build();
    }

    /**
     * Creates supplyInEventScope method.
     */
    private MethodSpec createSupplyInEventScopeMethod() {
        TypeVariableName typeVar = TypeVariableName.get("T");
        ParameterizedTypeName supplierType =
                ParameterizedTypeName.get(ClassName.get(java.util.function.Supplier.class), typeVar);

        MethodSpec.Builder method = MethodSpec.methodBuilder("supplyInEventScope")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addTypeVariable(typeVar)
                .addParameter(supplierType, "supplier")
                .returns(typeVar);
        method.beginControlFlow("if ($T.TRUE.equals(__unitFrameOpen.get()))", Boolean.class);
        method.addStatement(
                THROW_TYPE_WITH_MESSAGE,
                IllegalStateException.class,
                "supplyInEventScope called while a unit of work is already open. "
                        + "EVENT is single-frame in 0.x.0; nesting is not supported.");
        method.endControlFlow();
        method.addStatement("__unitFrameOpen.set($T.TRUE)", Boolean.class)
                .addStatement("$T __eventId = $T.randomUUID().toString()", String.class, UUID.class)
                .addStatement("$T __eventStart = $T.now()", Instant.class, Instant.class);
        emitGatedUnitStartedPublish(method);
        method.beginControlFlow("try")
                .addStatement("return supplier.get()")
                .nextControlFlow("finally")
                .addStatement("$T __eventEnd = $T.now()", Instant.class, Instant.class);
        emitGatedUnitEndingPublish(method);
        method.addStatement("__closeEventScope()").endControlFlow();
        return method.build();
    }

    /**
     * Emits the {@code publishLifecycleEvents}-gated EventStartedEvent publish shared by
     * both scope brackets (#339): per-module containers under an AggregatingContainer are
     * constructed with the flag off, so one unit of work yields exactly one pair —
     * published by the aggregator inside the innermost frame — instead of one pair per
     * nested module frame.
     */
    private void emitGatedUnitStartedPublish(MethodSpec.Builder method) {
        method.beginControlFlow(IF_PUBLISH_LIFECYCLE);
        method.addStatement("__publishUnitLifecycle(new $T(__eventId, __eventStart))", EVENT_STARTED);
        method.endControlFlow();
    }

    /** EventEndingEvent counterpart of {@link #emitGatedUnitStartedPublish} (#339). */
    private void emitGatedUnitEndingPublish(MethodSpec.Builder method) {
        method.beginControlFlow(IF_PUBLISH_LIFECYCLE);
        method.addStatement(
                "__publishUnitLifecycle(new $T(__eventId, __eventEnd, $T.between(__eventStart, __eventEnd)))",
                EVENT_ENDING,
                Duration.class);
        method.endControlFlow();
    }

    /**
     * Creates the private {@code __publishUnitLifecycle(Object)} helper that both scope
     * methods use for their {@code EventStartedEvent}/{@code EventEndingEvent} publishes.
     * Isolates {@code Throwable} — a throwing publish (user bus decorator, or an
     * {@code Error} escaping a sync handler) must never skip {@code __closeEventScope()}
     * or leave {@code __unitFrameOpen} stuck on the thread (#336). Mirrors the isolation
     * the {@code ApplicationStarted}/{@code ApplicationEnding} publishes already have.
     */
    private MethodSpec createPublishUnitLifecycleMethod() {
        ClassName loggerLevel = ClassName.get("java.lang", "System", "Logger", "Level");
        MethodSpec.Builder method = MethodSpec.methodBuilder("__publishUnitLifecycle")
                .addModifiers(Modifier.PRIVATE)
                .addParameter(Object.class, "event");
        method.beginControlFlow("try")
                .addStatement("eventBus.publish(event)")
                .nextControlFlow("catch ($T __t)", Throwable.class)
                .addComment("Bus/handler defect; the unit-of-work bracket must stay intact (#336)")
                .addStatement(
                        "$T.getLogger($S).log($T.WARNING, $S, __t)",
                        ClassName.get("java.lang", "System"),
                        EVENTS_PACKAGE,
                        loggerLevel,
                        "Unit-of-work lifecycle publish threw")
                .endControlFlow();
        return method.build();
    }

    /**
     * Creates the private {@code __getOrCreateSingleton(String, Supplier)} helper every
     * SINGLETON getter routes through (#338). Double-checked: lock-free fast path for the
     * created case, then a reentrant {@code synchronized} block for creation — the factory
     * runs <em>outside</em> any {@code ConcurrentHashMap} mapping function, so a factory
     * resolving further singletons re-enters the same monitor instead of nesting
     * {@code computeIfAbsent} calls (which throw {@code IllegalStateException("Recursive
     * update")} whenever two chain keys share a hash bin).
     */
    private MethodSpec createGetOrCreateSingletonMethod() {
        ParameterizedTypeName supplierOfObject = ParameterizedTypeName.get(
                ClassName.get(java.util.function.Supplier.class), ClassName.get(Object.class));
        MethodSpec.Builder method = MethodSpec.methodBuilder("__getOrCreateSingleton")
                .addModifiers(Modifier.PRIVATE)
                .returns(Object.class)
                .addParameter(String.class, "key")
                .addParameter(supplierOfObject, "factory");
        method.addStatement("$T __existing = singletons.get(key)", Object.class);
        method.beginControlFlow("if (__existing != null)");
        method.addStatement(RETURN_EXISTING);
        method.endControlFlow();
        method.beginControlFlow("synchronized (singletonLock)");
        method.addStatement("__existing = singletons.get(key)");
        method.beginControlFlow(IF_EXISTING_NULL);
        method.addStatement("__existing = factory.get()");
        method.addStatement("singletons.put(key, __existing)");
        method.endControlFlow();
        method.addStatement(RETURN_EXISTING);
        method.endControlFlow();
        return method.build();
    }

    /**
     * Creates the package-visible {@code __isStopped()} accessor read by the generated
     * event registry's dispatchers (#337). Plain {@code stopped.get()} — deliberately
     * without the {@code inShutdownThread} bypass the lookup gate has: that bypass exists
     * so {@code @PreDestroy} hooks can resolve beans during teardown, but handler dispatch
     * during/after Phase 4 would run on already-destroyed singletons.
     */
    private MethodSpec createIsStoppedMethod() {
        return MethodSpec.methodBuilder("__isStopped")
                .returns(boolean.class)
                .addStatement("return stopped.get()")
                .build();
    }

    /**
     * Creates the private {@code __closeEventScope()} helper that both
     * {@code runInEventScope} and {@code supplyInEventScope} call from their
     * {@code finally} blocks: the destroy-hook walk, the scope-map clear, and the
     * frame-flag reset. Emitted once so the ~70-line teardown chain is not duplicated
     * verbatim in each scope method (#308).
     */
    private MethodSpec createCloseEventScopeMethod() {
        MethodSpec.Builder method =
                MethodSpec.methodBuilder("__closeEventScope").addModifiers(Modifier.PRIVATE);
        emitScopedTeardown(method, Scope.EVENT, "eventScoped.get()");
        method.addStatement("eventScoped.get().clear()");
        method.addStatement("__unitFrameOpen.set($T.FALSE)", Boolean.class);
        return method.build();
    }

    /**
     * Emits the destroy-hook walk for an EVENT scope teardown. Iterates the
     * scope map's values in reverse insertion order (= reverse-creation, LIFO),
     * dispatches by concrete component type, and invokes either the explicit
     * {@code @PreDestroy} method(s) or the implicit {@code AutoCloseable.close()}.
     * Each invocation is wrapped in a try/catch so a failing hook does not prevent
     * the rest of the scope from being torn down.
     *
     * <p>Entirely no-op (zero generated code) when no component in this scope has a
     * destroy hook — the static gate keeps the hot path free for the common case.
     *
     * <p>Factory-produced beans living in the same scope map are silently skipped
     * (they don't match any instanceof case).
     */
    private void emitScopedTeardown(MethodSpec.Builder method, Scope scope, String scopeMapExpr) {
        List<ComponentModel> withHooks = new ArrayList<>();
        for (ComponentModel c : activeComponents()) {
            if (c.getScope() == scope && c.hasDestroyHook()) {
                withHooks.add(c);
            }
        }
        boolean hasFactoryAutoCloseable = false;
        for (FactoryMethodModel f : context.getActiveFactoryMethods()) {
            if (f.getScope() == scope && f.isAutoCloseable()) {
                hasFactoryAutoCloseable = true;
                break;
            }
        }
        if (withHooks.isEmpty() && !hasFactoryAutoCloseable) return;

        method.addStatement(
                "$T<$T> __toDestroy = new $T<>($L.values())",
                ClassName.get(java.util.List.class),
                ClassName.get(Object.class),
                ClassName.get(java.util.ArrayList.class),
                scopeMapExpr);
        method.beginControlFlow("for (int __i = __toDestroy.size() - 1; __i >= 0; __i--)");
        method.addStatement("$T __inst = __toDestroy.get(__i)", Object.class);

        boolean first = true;
        for (ComponentModel c : withHooks) {
            TypeName componentType = ClassName.get(c.getTypeElement());
            String varName = "__" + Character.toLowerCase(c.getClassName().charAt(0))
                    + c.getClassName().substring(1);
            if (first) {
                method.beginControlFlow("if (__inst instanceof $T $L)", componentType, varName);
                first = false;
            } else {
                method.nextControlFlow("else if (__inst instanceof $T $L)", componentType, varName);
            }
            method.beginControlFlow("try");
            boolean isAutoCloseOnly =
                    c.isAutoCloseable() && c.getPreDestroyMethods().isEmpty();
            if (isAutoCloseOnly) {
                method.addStatement("$L.close()", varName);
            } else {
                for (var preDestroy : c.getPreDestroyMethods()) {
                    method.addStatement("$L.$L()", varName, preDestroy.getSimpleName());
                }
            }
            method.nextControlFlow("catch ($T __t)", Throwable.class);
            // Failures route solely through ErrorHandler (#116) — no catch-site log. AutoCloseable
            // and @PreDestroy emit distinct permits so observability code can discriminate.
            ClassName failureType = isAutoCloseOnly
                    ? ClassName.get("io.tiko", "AutoCloseFailure")
                    : ClassName.get("io.tiko", "PreDestroyFailure");
            method.addStatement("errorHandler.onError(new $T($T.class, __t))", failureType, componentType);
            method.endControlFlow(); // try/catch
        }

        // Catch-all for factory-produced AutoCloseable beans living in this scope. Components
        // already handled by the cases above never reach this branch (if/else-if).
        if (hasFactoryAutoCloseable) {
            if (first) {
                method.beginControlFlow("if (__inst instanceof $T __ac)", AutoCloseable.class);
                first = false;
            } else {
                method.nextControlFlow("else if (__inst instanceof $T __ac)", AutoCloseable.class);
            }
            method.beginControlFlow("try");
            method.addStatement("__ac.close()");
            method.nextControlFlow("catch ($T __t)", Throwable.class);
            method.addStatement(
                    "errorHandler.onError(new $T(__ac.getClass(), __t))", ClassName.get("io.tiko", "AutoCloseFailure"));
            method.endControlFlow(); // try/catch
        }

        method.endControlFlow(); // last if/else-if chain
        method.endControlFlow(); // for loop
    }

    /**
     * Creates start method (#45): idempotent CAS, eagerly initialises SINGLETON
     * components, then publishes ApplicationStartedEvent (gated on publishLifecycleEvents
     * so per-module containers under an AggregatingContainer can stay silent — the
     * aggregator publishes once on the shared bus).
     */
    private MethodSpec createStartMethod() {
        MethodSpec.Builder method =
                MethodSpec.methodBuilder("start").addModifiers(Modifier.PUBLIC).addAnnotation(Override.class);

        method.addComment("Idempotency CAS (#45)");
        method.beginControlFlow("if (!startInvoked.compareAndSet(false, true))");
        method.addStatement("return");
        method.endControlFlow();

        method.addComment("Initialize all SINGLETON components");

        for (ComponentModel component : activeComponents()) {
            if (component.getScope() == Scope.SINGLETON && !component.requiresProxy()) {
                String getterName = "get" + component.getClassName();
                method.addStatement("$L()", getterName);
            }
        }

        method.addStatement("this.startedAt = $T.now()", Instant.class);
        method.beginControlFlow(IF_PUBLISH_LIFECYCLE);
        method.addStatement("eventBus.publish(new $T(this.startedAt))", APP_STARTED);
        method.endControlFlow();

        return method.build();
    }

    /**
     * Creates shutdown method (#47): idempotent, drains in-flight get() calls, then runs
     * @PreDestroy and shuts down the event executor.
     *
     * <p>Phase order:
     * <ol>
     *   <li>CAS shutdownInvoked false → true. If already true, return immediately.</li>
     *   <li>Publish ApplicationEndingEvent. get() still works so handlers can read state.</li>
     *   <li>Set stopped, drain in-flight gets — bounded by the configured
     *       {@code shutdownTimeout} (same knob as the Phase 5 executor drain), parking
     *       between polls instead of busy-spinning (#305).</li>
     *   <li>Run @PreDestroy on each SINGLETON. Thread-local bypass lets PreDestroy
     *       methods call container.get(...) without tripping the gate.</li>
     *   <li>Shut down the framework-owned event executor (#43 logic, unchanged).</li>
     * </ol>
     */
    private MethodSpec createShutdownMethod() {
        MethodSpec.Builder method = MethodSpec.methodBuilder("shutdown")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class);

        ClassName loggerLevel = ClassName.get("java.lang", "System", "Logger", "Level");
        ClassName timeUnit = ClassName.get("java.util.concurrent", "TimeUnit");

        method.addComment("Phase 1: idempotency CAS (#47)");
        method.beginControlFlow("if (!shutdownInvoked.compareAndSet(false, true))");
        method.addStatement("return");
        method.endControlFlow();

        method.addComment(
                "Phase 2: publish ApplicationEndingEvent. get() still works here so handlers can read state.");
        method.addComment(
                "Gated on publishLifecycleEvents (#45): per-module containers under an AggregatingContainer skip this; aggregator publishes once.");
        method.addStatement("$T __endTimestamp = $T.now()", Instant.class, Instant.class);
        method.addStatement(
                "$T __uptime = (this.startedAt != null) ? $T.between(this.startedAt, __endTimestamp) : $T.ZERO",
                Duration.class,
                Duration.class,
                Duration.class);
        method.beginControlFlow(IF_PUBLISH_LIFECYCLE);
        method.beginControlFlow("try");
        method.addStatement("eventBus.publish(new $T(__endTimestamp, __uptime))", APP_ENDING);
        method.nextControlFlow("catch ($T __t)", Throwable.class);
        method.addComment("Bus-impl defect; @PreDestroy must still run (handler exceptions are isolated by #44)");
        method.addStatement(
                "$T.getLogger($S).log($T.WARNING, $S, __t)",
                ClassName.get("java.lang", "System"),
                EVENTS_PACKAGE,
                loggerLevel,
                "ApplicationEndingEvent publish threw");
        method.endControlFlow();
        method.endControlFlow();

        method.addComment("Phase 3: gate new get() calls and drain in-flight ones. Lookups register in");
        method.addComment("inFlightGets BEFORE reading the stopped gate (#305), so once this thread observes");
        method.addComment("inFlightGets == 0 after stopped.set(true), every later lookup is rejected — none");
        method.addComment("can run against the singletons Phase 4 tears down. The wait is bounded by the");
        method.addComment("configured shutdownTimeout and parks 1ms between polls instead of busy-spinning.");
        method.addStatement("stopped.set(true)");
        method.addStatement("long __deadlineNanos = $T.nanoTime() + this.shutdownTimeout.toNanos()", System.class);
        method.beginControlFlow("while (inFlightGets.get() > 0 && $T.nanoTime() < __deadlineNanos)", System.class);
        method.addStatement("$T.parkNanos(1_000_000L)", ClassName.get("java.util.concurrent.locks", "LockSupport"));
        method.endControlFlow();
        method.beginControlFlow("if (inFlightGets.get() > 0)");
        method.addStatement(
                "$T.getLogger($S).log($T.WARNING, $S + inFlightGets.get())",
                ClassName.get("java.lang", "System"),
                EVENTS_PACKAGE,
                loggerLevel,
                "Container shutdown drain timed out with in-flight get() calls: ");
        method.endControlFlow();

        // Unified topo-sort across SINGLETON @Component beans AND SINGLETON @Produces factory
        // beans, ordered so deps come BEFORE dependents. Reverse iteration then destroys
        // dependents first — true LIFO of the dep graph (#151, #189). Crossing kinds matters:
        // a Component injecting a factory-produced bean, OR a factory whose method takes a
        // Component, both must honour LIFO. Two separate snapshot+emit loops can't express
        // the interleaving required for the latter direction.
        List<ShutdownTarget> shutdownTargets = topoSortShutdownTargets();

        // The whole phase — bypass set + try/finally — is skipped when no target has a
        // destroy hook: the bypass thread-local only exists so PreDestroy bodies can call
        // get(), and an empty try/finally pair is dead weight in every hook-less module (#308).
        boolean hasDestroyWork = shutdownTargets.stream().anyMatch(target -> switch (target) {
            case ShutdownTarget.Component(var component) -> component.hasDestroyHook();
            case ShutdownTarget.Factory(var factory) -> factory.isAutoCloseable();
        });

        if (hasDestroyWork) {
            method.addComment("Phase 4: @PreDestroy on SINGLETON components, reverse-creation (LIFO) order. "
                    + "Thread-local bypass so they can call get(). Each hook is isolated so one failure "
                    + "does not skip the rest.");
            method.addStatement("inShutdownThread.set($T.TRUE)", Boolean.class);
            method.beginControlFlow("try");

            for (int i = shutdownTargets.size() - 1; i >= 0; i--) {
                ShutdownTarget target = shutdownTargets.get(i);
                switch (target) {
                    case ShutdownTarget.Component(var component) -> {
                        if (component.hasDestroyHook()) {
                            emitComponentDestroy(method, component);
                        }
                    }
                    case ShutdownTarget.Factory(var factory) -> {
                        if (factory.isAutoCloseable()) {
                            emitFactoryDestroy(method, factory);
                        }
                    }
                }
            }

            method.nextControlFlow("finally");
            method.addStatement("inShutdownThread.remove()");
            method.endControlFlow();
        }

        method.addComment(
                "Phase 5: shut down framework-owned event executor (#43); user-supplied executors are not touched");
        method.beginControlFlow("if (this.ownsEventExecutor)");
        method.addStatement("this.eventExecutor.shutdown()");
        method.beginControlFlow("try");
        method.beginControlFlow(
                "if (!this.eventExecutor.awaitTermination(this.shutdownTimeout.toNanos(), $T.NANOSECONDS))", timeUnit);
        method.addStatement("this.eventExecutor.shutdownNow()");
        method.endControlFlow();
        method.nextControlFlow("catch ($T __ie)", InterruptedException.class);
        method.addStatement("$T.currentThread().interrupt()", Thread.class);
        method.addStatement("this.eventExecutor.shutdownNow()");
        method.endControlFlow();
        method.endControlFlow();

        return method.build();
    }

    /**
     * Emits the shared entry sequence of every lookup head ({@code get(Class)},
     * {@code get(Class, String)}, {@code getAll(Class)}): register in {@code inFlightGets},
     * open the {@code try} whose {@code finally} deregisters, then read the post-shutdown
     * gate (#47) inside it.
     *
     * <p>Ordering is load-bearing (#305): incrementing BEFORE reading {@code stopped} means
     * a lookup is either visible to shutdown's drain loop (it incremented before the loop
     * observed zero) or it reads the gate after {@code stopped.set(true)} and is rejected.
     * Check-then-increment had a window where a caller passed the gate unregistered and ran
     * against singletons Phase 4 was tearing down. {@code @PreDestroy} methods on the
     * shutdown thread still bypass via the thread-local.
     */
    private void emitLookupEntry(MethodSpec.Builder method) {
        method.addStatement("inFlightGets.incrementAndGet()");
        method.beginControlFlow("try");
        method.beginControlFlow("if (stopped.get() && !inShutdownThread.get())");
        method.addStatement("throw new $T()", CONTAINER_SHUT_DOWN);
        method.endControlFlow();
    }

    /**
     * Creates get(Class) method.
     */
    private MethodSpec createGetMethod() {
        TypeVariableName typeVar = TypeVariableName.get("T");
        ParameterizedTypeName classType = ParameterizedTypeName.get(ClassName.get(Class.class), typeVar);

        MethodSpec.Builder method = MethodSpec.methodBuilder("get")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addAnnotation(SUPPRESS_UNCHECKED)
                .addTypeVariable(typeVar)
                .addParameter(classType, "type")
                .returns(typeVar);

        emitLookupEntry(method);

        // Runtime override consulted first on the lookup type (#128) — applies to any
        // routable key (interface or concrete class), so override(Interface.class, mock)
        // wins regardless of whether the caller asks for the interface or the impl.
        // Placed after the post-shutdown gate and in-flight counter so overrides are a
        // wiring mechanism, not a lifecycle bypass — a closed container still refuses.
        // Single lookup + class-token cast (#309): getOverride returns null when absent.
        method.addStatement("$T __override = options.getOverride(type)", SUPPLIER_WILDCARD);
        method.beginControlFlow("if (__override != null)");
        method.addStatement("return type.cast(__override.get())");
        method.endControlFlow();

        // Check config singletons first — config records take precedence over DI components
        method.beginControlFlow("if (configSingletons.containsKey(type))");
        method.addStatement("return type.cast(configSingletons.get(type))");
        method.endControlFlow();

        // Generate if-else chain for each component, dispatching under its effective
        // routable types — that is the explicit expose list (or every directly-implemented
        // interface for the permissive default), plus the concrete class when exposeSelf
        // is true. Named components only match by the concrete-class entry of that set
        // (their interface entries are reachable via get(Class, String) instead).
        boolean first = true;
        for (ComponentModel component : activeComponents()) {
            TypeName componentType = ClassName.get(component.getTypeElement());
            String getterName = "get" + component.getClassName();
            List<TypeName> keys;
            if (component.getName().isPresent()) {
                keys = component.isExposeSelf() ? List.of(componentType) : List.of();
            } else {
                keys = effectiveRoutableTypes(component);
            }
            if (keys.isEmpty()) continue;

            String predicate = renderTypeOrPredicate("type", keys);
            Object[] args = keys.toArray();
            if (first) {
                method.beginControlFlow("if (" + predicate + ")", args);
            } else {
                method.nextControlFlow("else if (" + predicate + ")", args);
            }
            first = false;
            method.addStatement("return (T) $L()", getterName);
        }

        // Unnamed factory-produced components (named ones are only reachable via get(Class, String))
        for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
            if (factory.getName() != null && !factory.getName().isEmpty()) continue;
            TypeName producedType = TypeName.get(factory.getReturnType());

            if (first) {
                method.beginControlFlow("if (type == $T.class)", producedType);
                first = false;
            } else {
                method.nextControlFlow("else if (type == $T.class)", producedType);
            }
            method.addStatement("return (T) $L()", factoryGetterName(factory));
        }

        if (!first) {
            method.endControlFlow();
        }

        // If no match found, throw exception
        method.addStatement("throw new $T(type)", NO_SUCH_COMPONENT);

        method.nextControlFlow("finally");
        method.addStatement("inFlightGets.decrementAndGet()");
        method.endControlFlow();

        return method.build();
    }

    /**
     * Creates get(Class, String) method. Dispatches by (name + assignable type)
     * so lookup by concrete class or implemented interface both work.
     */
    private MethodSpec createGetWithNameMethod() {
        TypeVariableName typeVar = TypeVariableName.get("T");
        ParameterizedTypeName classType = ParameterizedTypeName.get(ClassName.get(Class.class), typeVar);

        MethodSpec.Builder method = MethodSpec.methodBuilder("get")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addAnnotation(SUPPRESS_UNCHECKED)
                .addTypeVariable(typeVar)
                .addParameter(classType, "type")
                .addParameter(String.class, "name")
                .returns(typeVar);

        emitLookupEntry(method);

        // Runtime override consulted first on the lookup (type, name) pair (#128) — the
        // fast-path for the literal key the caller passed. Placed after the post-shutdown
        // gate and in-flight counter so overrides are a wiring mechanism, not a lifecycle
        // bypass — a closed container still refuses. The per-arm override checks below
        // remain in place: they catch cases where the caller looks up Impl.class with
        // name X but the override was registered under Interface.class with name X.
        // Single lookup + class-token cast (#309): getOverride returns null when absent.
        method.addStatement("$T __override = options.getOverride(type, name)", SUPPLIER_WILDCARD);
        method.beginControlFlow("if (__override != null)");
        method.addStatement("return type.cast(__override.get())");
        method.endControlFlow();

        List<ComponentModel> named =
                activeComponents().stream().filter(c -> c.getName().isPresent()).toList();

        boolean first = true;
        for (ComponentModel component : named) {
            String getterName = "get" + component.getClassName();
            String componentName = component.getName().orElseThrow();
            List<TypeName> keys = effectiveRoutableTypes(component);
            if (keys.isEmpty()) continue;

            // Emit one arm per routable type so the override key the generator bakes in
            // (e.g. DataSource.class vs PrimaryDs.class) matches the literal class the
            // user passed at the call site. The override Supplier wins when registered
            // for that exact (type, name) pair; otherwise the canonical named getter
            // runs and the component's own scope logic (which itself consults the
            // unqualified override via T6/T7) takes over.
            for (TypeName key : keys) {
                if (first) {
                    method.beginControlFlow("if ($S.equals(name) && type == $T.class)", componentName, key);
                    first = false;
                } else {
                    method.nextControlFlow("else if ($S.equals(name) && type == $T.class)", componentName, key);
                }
                method.addStatement(
                        "$T __namedOverride = options.getOverride($T.class, $S)",
                        SUPPLIER_WILDCARD,
                        key,
                        componentName);
                method.beginControlFlow("if (__namedOverride != null)");
                method.addStatement("return type.cast(__namedOverride.get())");
                method.endControlFlow();
                method.addStatement("return (T) $L()", getterName);
            }
        }

        // Named factory-produced components
        for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
            if (factory.getName() == null || factory.getName().isEmpty()) continue;
            TypeName producedType = TypeName.get(factory.getReturnType());
            String factoryName = factory.getName();

            // Exact-match the produced type, consistent with get(Class) and getAll (#304). The
            // name gate already disambiguates producers; widening the type with isAssignableFrom
            // additionally let a broad requested type (e.g. get(Object.class, "primary")) collide
            // first-match-wins across distinct named producers. Interface-keyed lookup still works
            // when the @Produces method declares the interface return type — same as get(Class).
            if (first) {
                method.beginControlFlow("if ($S.equals(name) && type == $T.class)", factoryName, producedType);
                first = false;
            } else {
                method.nextControlFlow("else if ($S.equals(name) && type == $T.class)", factoryName, producedType);
            }
            method.addStatement("return (T) $L()", factoryGetterName(factory));
        }

        if (!first) {
            method.endControlFlow();
        }

        method.addStatement("throw new $T(type, name)", NO_SUCH_COMPONENT);

        method.nextControlFlow("finally");
        method.addStatement("inFlightGets.decrementAndGet()");
        method.endControlFlow();

        return method.build();
    }

    /**
     * Creates {@code getAll(Class<T>)} — returns every registered impl assignable to
     * {@code type}, including both named and unnamed components and {@code @Produces}
     * outputs. Backbone of {@code Picker.list()}.
     *
     * <p>Scope semantics are preserved by delegating to each component's existing
     * getter — singletons return the cached instance, prototypes get freshly created
     * each time, scoped beans resolve in the current scope.
     */
    private MethodSpec createGetAllMethod() {
        TypeVariableName typeVar = TypeVariableName.get("T");
        ParameterizedTypeName classType = ParameterizedTypeName.get(ClassName.get(Class.class), typeVar);
        ParameterizedTypeName listType = ParameterizedTypeName.get(ClassName.get(List.class), typeVar);

        MethodSpec.Builder method = MethodSpec.methodBuilder("getAll")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addTypeVariable(typeVar)
                .addParameter(classType, "type")
                .returns(listType);

        emitLookupEntry(method);
        method.addStatement("$T<T> __result = new $T<>()", List.class, ArrayList.class);

        // One effective-routable-type check per component. Honours @Component(expose = {…})
        // — a component listed under expose=[Foo] is collected by getAll(Foo) but NOT by
        // getAll(OtherInterfaceItImplements). For permissive default beans this matches
        // every directly-implemented interface plus the class itself.
        for (ComponentModel component : activeComponents()) {
            String getterName = "get" + component.getClassName();
            List<TypeName> keys = effectiveRoutableTypes(component);
            if (keys.isEmpty()) continue;
            String predicate = renderTypeOrPredicate("type", keys);
            Object[] args = keys.toArray();
            method.beginControlFlow("if (" + predicate + ")", args);
            method.addStatement("__result.add(type.cast($L()))", getterName);
            method.endControlFlow();
        }

        // Same for factory-produced beans — both named and unnamed are visible to getAll.
        // Exact-match the produced type (not isAssignableFrom): a super-type lookup such as
        // getAll(Object.class) must not fire every producer and eagerly invoke throwing or
        // EVENT-scoped factories (#303). This mirrors the component arms above and the unnamed
        // get(Class) factory routing, both of which key on `type == ProducedType.class`.
        for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
            TypeName producedType = TypeName.get(factory.getReturnType());
            method.beginControlFlow("if (type == $T.class)", producedType);
            method.addStatement("__result.add(type.cast($L()))", factoryGetterName(factory));
            method.endControlFlow();
        }

        method.addStatement("return $T.unmodifiableList(__result)", java.util.Collections.class);

        method.nextControlFlow("finally");
        method.addStatement("inFlightGets.decrementAndGet()");
        method.endControlFlow();

        return method.build();
    }

    /**
     * Creates getProvider(Class) method — a lazy handle that delegates to get(type)
     * on each invocation, so scope semantics (singleton cache, event resolution,
     * prototype re-creation) are preserved.
     */
    private MethodSpec createGetProviderMethod() {
        TypeVariableName typeVar = TypeVariableName.get("T");
        ParameterizedTypeName classType = ParameterizedTypeName.get(ClassName.get(Class.class), typeVar);
        ParameterizedTypeName providerType = ParameterizedTypeName.get(ClassName.get("io.tiko", "Provider"), typeVar);

        return MethodSpec.methodBuilder("getProvider")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addTypeVariable(typeVar)
                .addParameter(classType, "type")
                .returns(providerType)
                .addStatement("return () -> get(type)")
                .build();
    }

    /**
     * Creates getProvider(Class, String) method.
     */
    private MethodSpec createGetProviderWithNameMethod() {
        TypeVariableName typeVar = TypeVariableName.get("T");
        ParameterizedTypeName classType = ParameterizedTypeName.get(ClassName.get(Class.class), typeVar);
        ParameterizedTypeName providerType = ParameterizedTypeName.get(ClassName.get("io.tiko", "Provider"), typeVar);

        return MethodSpec.methodBuilder("getProvider")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addTypeVariable(typeVar)
                .addParameter(classType, "type")
                .addParameter(String.class, "name")
                .returns(providerType)
                .addStatement("return () -> get(type, name)")
                .build();
    }

    /**
     * Creates getEventBus method.
     */
    private MethodSpec createGetEventBusMethod() {
        return MethodSpec.methodBuilder("getEventBus")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(EventBus.class)
                .addStatement("return eventBus")
                .build();
    }

    /**
     * Creates the package-private {@code options()} accessor that returns the stored
     * {@link io.tiko.runtime.TikoOptions}. Used by generated factories in the same
     * {@code io.tiko.generated} package to consult per-call-site overrides at injection
     * sites (#128). Not exposed on the {@link io.tiko.Container} interface — overrides
     * are an internal wiring concern.
     */
    private MethodSpec createOptionsAccessor() {
        return MethodSpec.methodBuilder("options")
                .returns(ClassName.get("io.tiko.runtime", "TikoOptions"))
                .addStatement("return this.options")
                .build();
    }

    /**
     * Creates getErrorHandler method — overrides {@link io.tiko.Container#getErrorHandler()}
     * to return the stored field, giving transports typed access to the configured handler
     * without reflection.
     */
    private MethodSpec createGetErrorHandlerMethod() {
        return MethodSpec.methodBuilder("getErrorHandler")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(ClassName.get("io.tiko", "ErrorHandler"))
                .addStatement("return this.errorHandler")
                .build();
    }

    /**
     * Creates getEventExecutor() — public accessor delegating to Container.getEventExecutor().
     */
    private MethodSpec createGetEventExecutorMethod() {
        return MethodSpec.methodBuilder("getEventExecutor")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(ClassName.get("java.util.concurrent", "ExecutorService"))
                .addStatement("return this.eventExecutor")
                .build();
    }

    /**
     * Returns the types under which a component is routable via {@code get(Class)} /
     * {@code get(Class, String)} / {@code getAll(Class)}. Honours both the explicit
     * {@code @Component(expose = {…})} list and the {@code exposeSelf} flag:
     *
     * <ul>
     *   <li>When {@code expose} is empty (permissive default), the list is every
     *       directly-implemented interface of the component class.</li>
     *   <li>When {@code expose} is non-empty (opt-in restriction), the list is exactly
     *       those declared types.</li>
     *   <li>The concrete component class itself is appended when {@code exposeSelf} is
     *       true (the default), and deduped if it was already listed.</li>
     * </ul>
     *
     * Returns an empty list only when a user explicitly sets {@code expose = {}} together
     * with {@code exposeSelf = false} on a class with no interfaces — that's a
     * "fully hidden" bean, callable only via {@code Provider} chains held by other beans.
     */
    private List<TypeName> effectiveRoutableTypes(ComponentModel component) {
        TypeName componentType = ClassName.get(component.getTypeElement());
        List<TypeName> keys = new ArrayList<>();
        // Implicit interfaces are filtered for JDK lifecycle markers (AutoCloseable/Closeable):
        // they are cleanup conventions, not service contracts, so they must not become dispatch
        // keys (#301). An explicit expose = {...} list is honored verbatim — if the user names a
        // marker there, that is a deliberate opt-in.
        boolean filterMarkers = !component.isExposeRestricted();
        List<? extends javax.lang.model.type.TypeMirror> declared = component.isExposeRestricted()
                ? component.getExposeTypes()
                : component.getTypeElement().getInterfaces();
        for (javax.lang.model.type.TypeMirror iface : declared) {
            TypeName n = TypeName.get(iface);
            // Class literals are erased: a parameterized interface (Repo<User>) must route
            // as its raw type — emitting type arguments into `$T.class` is a parse error
            // in the generated dispatch arms (#330; @Produces counterpart was #327).
            if (n instanceof ParameterizedTypeName p) {
                n = p.rawType();
            }
            if (filterMarkers && isLifecycleMarker(n)) continue;
            if (!keys.contains(n)) keys.add(n);
        }
        if (component.isExposeSelf() && !keys.contains(componentType)) {
            keys.add(componentType);
        }
        return keys;
    }

    /** True if {@code n} is a JDK lifecycle marker interface; delegates to the shared TypeUtil predicate. */
    private static boolean isLifecycleMarker(TypeName n) {
        return n instanceof ClassName cn && TypeUtil.isLifecycleMarkerInterface(cn.canonicalName());
    }

    /**
     * Renders an OR-of-equalities JavaPoet predicate over a list of types,
     * e.g. {@code type == Foo.class || type == Bar.class}. Caller passes a placeholder
     * for the variable being compared (typically {@code "type"}); the result is a
     * single-line predicate suitable for use inside a {@code beginControlFlow} format
     * string. Returned object's second element is the variadic arg array.
     */
    private static String renderTypeOrPredicate(String varName, List<TypeName> types) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) sb.append(" || ");
            sb.append(varName).append(" == $T.class");
        }
        return sb.toString();
    }

    /**
     * Converts class name to factory field name.
     * e.g., "MessageService" -> "messageServiceFactory"
     */
    private String getFactoryFieldName(String className) {
        return Character.toLowerCase(className.charAt(0)) + className.substring(1) + "Factory";
    }

    /**
     * Converts class name to proxy field name.
     * e.g., "RequestContextImpl" -> "requestContextImplProxy"
     */
    private String getProxyFieldName(String className) {
        return Character.toLowerCase(className.charAt(0)) + className.substring(1) + "Proxy";
    }

    /**
     * Writes a container descriptor file pointing at the just-generated container class.
     * Used for both the main {@code container.properties} and the test
     * {@code test-container.properties} — Maven routes {@code CLASS_OUTPUT} into
     * {@code target/test-classes/} during the test-compile phase, so the test descriptor
     * naturally lands on the test classpath only.
     */
    private void writeContainerDescriptor(String resourcePath, String containerClassName) throws IOException {
        String fullClassName = GENERATED_PACKAGE + "." + containerClassName;

        try (var writer = context.getFiler()
                .createResource(javax.tools.StandardLocation.CLASS_OUTPUT, "", resourcePath)
                .openWriter()) {

            writer.write("# Tiko DI Container Metadata\n");
            writer.write("impl=" + fullClassName + "\n");
        }
    }

    /**
     * Generates META-INF/tiko/components.txt file.
     * Contains newline-separated list of all component class names. Used in both main and
     * standalone-test-compile rounds: the {@code AggregatingContainer} reads this sibling
     * of each {@code container.properties} / {@code test-container.properties} resource
     * to map types to per-module containers. The two files live in {@code target/classes}
     * and {@code target/test-classes} respectively, so they never collide.
     */
    /**
     * Heterogeneous shutdown node: wraps either a {@code @Component} or a {@code @Produces}
     * factory so the topo-sort and emission loop can treat both kinds uniformly. Required
     * because the two destroy paths (component {@code @PreDestroy} vs. factory {@code close()})
     * must interleave in a single LIFO order to honour cross-kind dep chains (#189).
     */
    private sealed interface ShutdownTarget {
        String key();

        List<DependencyModel> dependencies();

        record Component(ComponentModel model) implements ShutdownTarget {
            @Override
            public String key() {
                return model.getComponentKey();
            }

            @Override
            public List<DependencyModel> dependencies() {
                return model.getDependencies();
            }
        }

        record Factory(FactoryMethodModel model) implements ShutdownTarget {
            @Override
            public String key() {
                return model.getComponentKey();
            }

            @Override
            public List<DependencyModel> dependencies() {
                return model.getDependencies();
            }
        }
    }

    /**
     * Topologically sorts all SINGLETON shutdown targets — {@code @Component} beans and
     * {@code @Produces} factory beans — by their constructor / method-parameter dep edges so
     * deps appear BEFORE the targets that depend on them. Reverse iteration in
     * {@link #createShutdownMethod} then destroys dependents first, matching the documented
     * LIFO destruction contract (#151, #189).
     *
     * <p>Edges followed: SINGLETON→SINGLETON, across both Component and Factory providers.
     * EVENT deps inject via proxies and impose no construction-order constraint.
     * Cycles cannot occur (caught at compile-time by CircularDependencyDetector); the
     * {@code visited} guard is defensive.
     */
    private List<ShutdownTarget> topoSortShutdownTargets() {
        var seeds = new ArrayList<ShutdownTarget>();
        for (ComponentModel c : activeComponents()) {
            if (c.getScope() == Scope.SINGLETON && !c.requiresProxy()) {
                seeds.add(new ShutdownTarget.Component(c));
            }
        }
        for (FactoryMethodModel f : context.getActiveFactoryMethods()) {
            if (f.getScope() == Scope.SINGLETON) {
                seeds.add(new ShutdownTarget.Factory(f));
            }
        }

        var sorted = new ArrayList<ShutdownTarget>();
        var visited = new HashSet<String>();
        for (var seed : seeds) {
            visitShutdownTarget(seed, visited, sorted);
        }
        return sorted;
    }

    private void visitShutdownTarget(ShutdownTarget target, Set<String> visited, List<ShutdownTarget> sorted) {
        if (!visited.add(target.key())) return;
        for (DependencyModel dep : target.dependencies()) {
            String depKey = dep.getDependencyKey();
            var resolved = context.findComponentOrFactory(depKey).orElse(null);
            ShutdownTarget child = null;
            if (resolved instanceof ComponentModel c && c.getScope() == Scope.SINGLETON && !c.requiresProxy()) {
                child = new ShutdownTarget.Component(c);
            } else if (resolved instanceof FactoryMethodModel f && f.getScope() == Scope.SINGLETON) {
                child = new ShutdownTarget.Factory(f);
            }
            if (child != null) visitShutdownTarget(child, visited, sorted);
        }
        sorted.add(target);
    }

    /**
     * Emits the {@code @PreDestroy} (or implicit {@code AutoCloseable.close()}) invocation for
     * one SINGLETON {@code @Component}, including the null guard, try/catch, log + ErrorHandler
     * routing. Called from {@link #createShutdownMethod}'s unified LIFO loop.
     */
    private void emitComponentDestroy(MethodSpec.Builder method, ComponentModel component) {
        String componentKey = component.getComponentKey();
        TypeName componentType = ClassName.get(component.getTypeElement());
        String variableName = Character.toLowerCase(component.getClassName().charAt(0))
                + component.getClassName().substring(1);

        method.addStatement(
                "$T $L = ($T) singletons.get($S)", componentType, variableName, componentType, componentKey);

        boolean isAutoCloseOnly =
                component.isAutoCloseable() && component.getPreDestroyMethods().isEmpty();
        ClassName failureType = isAutoCloseOnly
                ? ClassName.get("io.tiko", "AutoCloseFailure")
                : ClassName.get("io.tiko", "PreDestroyFailure");

        method.beginControlFlow("if ($L != null)", variableName);
        // Teardown runs under options.teardownTimeout() (#106): unset → inline on the shutdown
        // thread (today's behavior, zero overhead), set → bounded with TimeoutException-caused
        // routing. Either way failures route solely through ErrorHandler (#116) — no catch-site log.
        method.addStatement(
                "$T.run($L, this.options.teardownTimeout(), errorHandler, __t -> new $T($T.class, __t))",
                BOUNDED_EXECUTION,
                componentDestroyTask(component, variableName, isAutoCloseOnly),
                failureType,
                componentType);
        method.endControlFlow(); // if non-null
    }

    /**
     * Builds the teardown task lambda for one SINGLETON component: {@code () -> bean.close()} for an
     * AutoCloseable-only component, {@code () -> bean.preDestroy()} for a single {@code @PreDestroy},
     * or a block lambda invoking each {@code @PreDestroy} in turn.
     */
    private CodeBlock componentDestroyTask(ComponentModel component, String variableName, boolean isAutoCloseOnly) {
        if (isAutoCloseOnly) {
            return CodeBlock.of("() -> $L.close()", variableName);
        }
        var preDestroys = component.getPreDestroyMethods();
        if (preDestroys.size() == 1) {
            return CodeBlock.of(
                    "() -> $L.$L()", variableName, preDestroys.get(0).getSimpleName());
        }
        CodeBlock.Builder body = CodeBlock.builder().add("() -> {\n").indent();
        for (var preDestroy : preDestroys) {
            body.addStatement("$L.$L()", variableName, preDestroy.getSimpleName());
        }
        return body.unindent().add("}").build();
    }

    /**
     * Emits {@code close()} for one factory-produced SINGLETON {@code AutoCloseable}, including
     * the null guard, try/catch, and ErrorHandler routing. Covers third-party closeables
     * (data sources, HTTP clients, Kafka producers) returned by {@code @Produces} methods.
     */
    private void emitFactoryDestroy(MethodSpec.Builder method, FactoryMethodModel factory) {
        String factoryKey = factory.getComponentKey();
        String variableName = "__factory_" + factory.getFactoryIdentifier();

        method.addStatement(
                "$T $L = ($T) singletons.get($S)",
                ClassName.get(AutoCloseable.class),
                variableName,
                ClassName.get(AutoCloseable.class),
                factoryKey);
        method.beginControlFlow("if ($L != null)", variableName);
        // Bounded by options.teardownTimeout() (#106), same as component @PreDestroy; failures route
        // solely through ErrorHandler (#116).
        method.addStatement(
                "$T.run(() -> $L.close(), this.options.teardownTimeout(), errorHandler,"
                        + " __t -> new $T($L.getClass(), __t))",
                BOUNDED_EXECUTION,
                variableName,
                ClassName.get("io.tiko", "AutoCloseFailure"),
                variableName);
        method.endControlFlow(); // if non-null
    }

    private void generateComponentsListFile(List<ComponentModel> components) throws IOException {
        try (var writer = context.getFiler()
                .createResource(javax.tools.StandardLocation.CLASS_OUTPUT, "", "META-INF/tiko/components.txt")
                .openWriter()) {

            for (ComponentModel component : components) {
                // Use binary name (with '$' for nested classes) so Class.forName() works at runtime
                writer.write(context.getElementUtils()
                        .getBinaryName(component.getTypeElement())
                        .toString());
                writer.write("\n");
            }
        }
    }
}
