package io.tiko.processor.generator;

import com.palantir.javapoet.*;
import io.tiko.Container;
import io.tiko.EventBus;
import io.tiko.Scope;
import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.GeneratorAnnotations;
import io.tiko.processor.util.ProcessorContext;
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
 * - REQUEST/EVENT scope storage (ThreadLocal)
 * - Factory instances for each component
 * - Getter methods for each component (respecting scope)
 * - Scope management methods (runInRequestScope, runInEventScope)
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
     * True while the main container is being emitted in a round that also produces a
     * test subclass. The flag drops {@code Modifier.FINAL} from the main container class
     * and {@code Modifier.PRIVATE} from a handful of fields ({@code singletons},
     * {@code requestScoped}, {@code eventScoped}, {@code options}) so the
     * {@code TestTikoContainerImpl_<hash>} subclass — emitted in the same
     * {@code io.tiko.generated} package — can extend it and access those fields when
     * overriding shadowed-component getters. When no {@code @TestComponent} is present
     * in the round, the main container is emitted with its original {@code final}
     * modifier and {@code private} fields, so production builds are byte-identical.
     */
    private boolean extensibleMainContainer = false;

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
        boolean dualEmission = context.hasTestComponents();
        this.extensibleMainContainer = dualEmission;

        String mainContainerClassName = context.getContainerClassName();
        var mainComponents = context.getActiveMainComponents();
        generateOne(mainContainerClassName, mainComponents, MAIN_DESCRIPTOR);
        generateComponentsListFile();

        if (dualEmission) {
            // Test container is a subclass of the main container — so factories generated
            // against the main container type (e.g. {@code FakeClockFactory(TikoContainerImpl_<hash> c)})
            // still accept a {@code TestTikoContainerImpl_<hash>} instance, and dispatch
            // through inherited getters works naturally. The test container only emits the
            // diffs: new fields/getters for test-only components, plus overrides for any
            // main component shadowed by a same-typed {@code @TestComponent}.
            //
            // Test container's hash is recomputed from {main components + test components}
            // (via the test-active component list) so it is independent of the main hash.
            // The distinct "TestTikoContainerImpl_" prefix means even a hash collision
            // could not put the two on a classpath collision course.
            var testComponents = context.getActiveTestContainerComponents();
            String testContainerClassName = "TestTikoContainerImpl_" + computeHash(testComponents);
            generateTestSubclass(mainContainerClassName, testContainerClassName, mainComponents, testComponents);
            writeContainerDescriptor(TEST_DESCRIPTOR, testContainerClassName);
        }
    }

    /**
     * Emits one container class + its descriptor file from the given active-component view.
     * Stashes the view on {@link #currentComponents} so the sub-generator helpers — which
     * are private to this class and walk components implicitly — see exactly the slice
     * passed in.
     */
    private void generateOne(String containerClassName, List<ComponentModel> activeComponents, String descriptorPath)
            throws IOException {
        this.currentComponents = activeComponents;

        TypeSpec.Builder containerBuilder = TypeSpec.classBuilder(containerClassName)
                .addAnnotation(GeneratorAnnotations.generatedBy(ContainerGenerator.class));
        if (extensibleMainContainer) {
            // Non-final so the test subclass can extend; stays in io.tiko.generated so
            // production user code still cannot reach it (and there is no public
            // io.tiko-side hook to instantiate it directly).
            containerBuilder.addModifiers(Modifier.PUBLIC);
        } else {
            containerBuilder.addModifiers(Modifier.PUBLIC, Modifier.FINAL);
        }
        containerBuilder.addSuperinterface(Container.class);

        // Add fields
        containerBuilder.addField(createSingletonStorageField());
        containerBuilder.addField(createRequestScopeField());
        containerBuilder.addField(createEventScopeField());
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
        containerBuilder.addMethod(createRunInRequestScopeMethod());
        containerBuilder.addMethod(createSupplyInRequestScopeMethod());
        containerBuilder.addMethod(createRunInEventScopeMethod());
        containerBuilder.addMethod(createSupplyInEventScopeMethod());

        // Add lifecycle methods
        containerBuilder.addMethod(createStartMethod());
        containerBuilder.addMethod(createShutdownMethod());

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

        TypeSpec containerClass = containerBuilder.build();

        JavaFile javaFile = JavaFile.builder(GENERATED_PACKAGE, containerClass).build();

        javaFile.writeTo(context.getFiler());

        // Descriptor pointing at this container — main and test paths use the same shape
        // (just a different filename) so the runtime preference logic in Tiko.createInternal
        // is a one-line resource-name swap.
        writeContainerDescriptor(descriptorPath, containerClassName);
    }

    /**
     * Emits the {@code TestTikoContainerImpl_<hash>} subclass of the main container.
     * Carries only the diffs: factory fields and getters for test-only components,
     * overrides for shadowed main-component getters, plus minimal {@code start()} /
     * {@code get(Class)} / {@code getAll(Class)} overrides so the test-only additions
     * are reachable from the {@link Container} interface.
     *
     * <p>The factory and proxy classes themselves are not re-emitted — they were
     * generated once against the main container's class name and the test container
     * is-a main container, so {@code new XxxFactory(this)} from this subclass's
     * constructor passes type-check naturally.
     */
    private void generateTestSubclass(
            String mainContainerClassName,
            String testContainerClassName,
            List<ComponentModel> mainComponents,
            List<ComponentModel> testComponents)
            throws IOException {
        ClassName mainContainerType = ClassName.get(GENERATED_PACKAGE, mainContainerClassName);

        // Stash the test-component view so any helper that walks "active components" while
        // emitting the subclass sees the test set. Currently only {@code emitScopedTeardown}
        // would consult it transitively, but the subclass does not emit scope teardown, so
        // this is a defensive set rather than a hot path.
        this.currentComponents = testComponents;

        // Index main components by key so we can quickly classify each test entry as
        // (additions) vs (shadow override) vs (passthrough). Shadow keys come from T11's
        // shadowedByTestOverride map — every entry there is exactly the test-override of
        // a same-keyed main component. Additions are test-active components whose key is
        // NEITHER a main key NOR the key of any shadowing test model (test-only fixtures).
        Map<String, ComponentModel> mainByKey = new LinkedHashMap<>();
        for (ComponentModel m : mainComponents) {
            mainByKey.put(m.getComponentKey(), m);
        }
        var shadowedKeys = context.getShadowedMainKeys();
        List<ShadowOverride> shadowOverrides = new ArrayList<>();
        Set<String> shadowingTestKeys = new java.util.HashSet<>();
        for (String mainKey : shadowedKeys) {
            ComponentModel mainModel = mainByKey.get(mainKey);
            ComponentModel testModel = context.getTestComponentShadowing(mainKey);
            if (mainModel != null && testModel != null) {
                shadowOverrides.add(new ShadowOverride(mainModel, testModel));
                shadowingTestKeys.add(testModel.getComponentKey());
            }
        }

        List<ComponentModel> additions = new ArrayList<>();
        for (ComponentModel t : testComponents) {
            String key = t.getComponentKey();
            if (mainByKey.containsKey(key)) {
                // Passthrough — main model already lives on the superclass.
                continue;
            }
            if (shadowingTestKeys.contains(key)) {
                // Already handled as a shadow override below; field + getter come from
                // that path so we must not double-emit them here.
                continue;
            }
            additions.add(t);
        }

        TypeSpec.Builder subclass = TypeSpec.classBuilder(testContainerClassName)
                .addAnnotation(GeneratorAnnotations.generatedBy(ContainerGenerator.class))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .superclass(mainContainerType);

        // Factory fields for additions and for shadowing test components.
        for (ComponentModel c : additions) {
            subclass.addField(createSubclassFactoryField(c));
        }
        for (ShadowOverride so : shadowOverrides) {
            subclass.addField(createSubclassFactoryField(so.test));
        }

        // Constructor: forward to super(...) and init only the extra factory fields.
        subclass.addMethod(createTestSubclassConstructor(mainContainerType, additions, shadowOverrides));

        // Getter for each addition. Mirrors the main container's getter shape — same
        // singleton/request/event/prototype dispatch and override consultation.
        for (ComponentModel c : additions) {
            subclass.addMethod(createSubclassAdditionGetter(c));
        }

        // Override the shadowed main getter so dependents that call container.getXxx()
        // — directly or via {@code get(Class)} / dispatch from this subclass — receive
        // the test instance. Storage key stays the main key so the singleton cache
        // semantics match super's view of the world.
        for (ShadowOverride so : shadowOverrides) {
            subclass.addMethod(createSubclassShadowGetter(so));
        }

        // Override start() to eagerly init test-only SINGLETONs. super.start() handles
        // the CAS guard, main-component eager init, and lifecycle event publishing.
        if (!additions.isEmpty() || !shadowOverrides.isEmpty()) {
            subclass.addMethod(createSubclassStartMethod(additions));
        }

        // Override get(Class) and getAll(Class) so additions are dispatchable through the
        // {@link Container} interface. get(Class, String) is overridden only when at
        // least one addition or shadow override has a name qualifier.
        if (!additions.isEmpty()) {
            subclass.addMethod(createSubclassGetMethod(additions));
            subclass.addMethod(createSubclassGetAllMethod(additions));
        }

        JavaFile javaFile =
                JavaFile.builder(GENERATED_PACKAGE, subclass.build()).build();
        javaFile.writeTo(context.getFiler());
    }

    /** Field for a test-only or shadowing factory, stored on the subclass. */
    private FieldSpec createSubclassFactoryField(ComponentModel component) {
        String factoryClassName = component.getClassName() + "Factory";
        String fieldName = getFactoryFieldName(component.getClassName());
        return FieldSpec.builder(
                        ClassName.get(GENERATED_PACKAGE, factoryClassName), fieldName, Modifier.PRIVATE, Modifier.FINAL)
                .build();
    }

    /** Subclass constructor: forwards to super and initialises test-only factory fields. */
    private MethodSpec createTestSubclassConstructor(
            ClassName mainContainerType, List<ComponentModel> additions, List<ShadowOverride> shadowOverrides) {
        MethodSpec.Builder ctor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(EventBus.class, "eventBus")
                .addParameter(ClassName.get("io.tiko", "ErrorHandler"), "errorHandler")
                .addParameter(ClassName.get("java.util.concurrent", "ExecutorService"), "userEventExecutor")
                .addParameter(TypeName.BOOLEAN, "publishLifecycleEvents")
                .addParameter(Duration.class, "shutdownTimeout")
                .addParameter(ClassName.get("io.tiko.runtime", "TikoOptions"), "options")
                .addStatement(
                        "super(eventBus, errorHandler, userEventExecutor, publishLifecycleEvents, shutdownTimeout, options)");
        for (ComponentModel c : additions) {
            ctor.addStatement(
                    "this.$L = new $L(this)", getFactoryFieldName(c.getClassName()), c.getClassName() + "Factory");
        }
        for (ShadowOverride so : shadowOverrides) {
            ctor.addStatement(
                    "this.$L = new $L(this)",
                    getFactoryFieldName(so.test.getClassName()),
                    so.test.getClassName() + "Factory");
        }
        return ctor.build();
    }

    /** Mirrors {@link #createComponentGetter} for a test-only addition. */
    private MethodSpec createSubclassAdditionGetter(ComponentModel component) {
        // Reuse the main getter emission directly: the only differences for a subclass-
        // hosted addition are (a) the storage key still includes the test bean's own
        // componentKey (which is what the main path does anyway) and (b) the factory
        // field lives on this subclass (named identically by class name). The fields
        // are accessible because singletons/options were emitted package-private in
        // extensible mode.
        return createComponentGetter(component);
    }

    /**
     * Override of the main getter for a shadowed component. The signature matches the
     * inherited method (same name, same return type — the test model must be assignable
     * to the main type because shadowing is detected via shared routable types), but
     * the body uses the test factory instead.
     */
    private MethodSpec createSubclassShadowGetter(ShadowOverride so) {
        ComponentModel main = so.main;
        ComponentModel test = so.test;
        String methodName = "get" + main.getClassName();
        TypeName returnType = ClassName.get(main.getTypeElement());
        TypeName testType = ClassName.get(test.getTypeElement());
        String storageKey = main.getComponentKey();
        String factoryFieldName = getFactoryFieldName(test.getClassName());

        MethodSpec.Builder method = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .returns(returnType);

        switch (main.getScope()) {
            case SINGLETON ->
                method.addStatement(
                        "return ($1T) singletons.computeIfAbsent($2S, k -> options.hasOverride($3T.class) ? options.getOverride($3T.class).get() : $4L.create())",
                        returnType,
                        storageKey,
                        returnType,
                        factoryFieldName);
            case REQUEST ->
                emitScopedGetOrCreate(
                        method,
                        returnType,
                        returnType,
                        "requestScoped.get()",
                        storageKey,
                        factoryFieldName + ".create()");
            case EVENT ->
                emitScopedGetOrCreate(
                        method,
                        returnType,
                        returnType,
                        "eventScoped.get()",
                        storageKey,
                        factoryFieldName + ".create()");
            case PROTOTYPE -> method.addStatement("return $L.create()", factoryFieldName);
        }
        return method.build();
    }

    /**
     * Override of start() that delegates to super then eagerly inits subclass SINGLETONs.
     * Shadowed main components are already covered by {@code super.start()}'s eager-init
     * loop — its {@code getXxx()} call is dispatched dynamically and lands on the
     * subclass override emitted by {@link #createSubclassShadowGetter}.
     */
    private MethodSpec createSubclassStartMethod(List<ComponentModel> additions) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("start")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addStatement("super.start()");
        // Note: super.start() returns no-op on a second invocation (CAS guard). Re-calling
        // the getters below is harmless — each is itself singleton-cached.
        for (ComponentModel c : additions) {
            if (c.getScope() == Scope.SINGLETON && !c.requiresProxy()) {
                method.addStatement("get$L()", c.getClassName());
            }
        }
        return method.build();
    }

    /**
     * Override of get(Class) that catches the not-found arm from super and dispatches
     * to the test-only additions. The shutdown gate and in-flight counter are handled
     * by super.get(); on the not-found path the counter is already decremented by its
     * finally block, so the additional dispatch runs outside the gate — acceptable for
     * a test-only container.
     */
    private MethodSpec createSubclassGetMethod(List<ComponentModel> additions) {
        TypeVariableName typeVar = TypeVariableName.get("T");
        ParameterizedTypeName classType = ParameterizedTypeName.get(ClassName.get(Class.class), typeVar);

        MethodSpec.Builder method = MethodSpec.methodBuilder("get")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                        .addMember("value", "$S", "unchecked")
                        .build())
                .addTypeVariable(typeVar)
                .addParameter(classType, "type")
                .returns(typeVar);

        // Try the test-only arms first so additions take precedence over any same-typed
        // main hits (defensive — additions by definition are NOT in main, but a future
        // expose-list overlap should still resolve to the test bean under this container).
        boolean first = true;
        for (ComponentModel c : additions) {
            List<TypeName> keys = effectiveRoutableTypes(c);
            if (c.getName().isPresent()) {
                keys = c.isExposeSelf() ? List.of(ClassName.get(c.getTypeElement())) : List.of();
            }
            if (keys.isEmpty()) continue;
            String predicate = renderTypeOrPredicate("type", keys);
            Object[] args = keys.toArray();
            if (first) {
                method.beginControlFlow("if (" + predicate + ")", args);
                first = false;
            } else {
                method.nextControlFlow("else if (" + predicate + ")", args);
            }
            method.addStatement("return (T) get$L()", c.getClassName());
        }
        if (!first) {
            method.endControlFlow();
        }

        // Delegate to the inherited dispatch for everything else.
        method.addStatement("return super.get(type)");
        return method.build();
    }

    /**
     * Override of getAll(Class) that appends test-only additions to the inherited list.
     */
    private MethodSpec createSubclassGetAllMethod(List<ComponentModel> additions) {
        TypeVariableName typeVar = TypeVariableName.get("T");
        ParameterizedTypeName classType = ParameterizedTypeName.get(ClassName.get(Class.class), typeVar);
        ParameterizedTypeName listType = ParameterizedTypeName.get(ClassName.get(List.class), typeVar);

        MethodSpec.Builder method = MethodSpec.methodBuilder("getAll")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addTypeVariable(typeVar)
                .addParameter(classType, "type")
                .returns(listType);

        method.addStatement("$T<T> __result = new $T<>(super.getAll(type))", List.class, ArrayList.class);
        for (ComponentModel c : additions) {
            List<TypeName> keys = effectiveRoutableTypes(c);
            if (keys.isEmpty()) continue;
            String predicate = renderTypeOrPredicate("type", keys);
            Object[] args = keys.toArray();
            method.beginControlFlow("if (" + predicate + ")", args);
            method.addStatement("__result.add(type.cast(get$L()))", c.getClassName());
            method.endControlFlow();
        }
        method.addStatement("return $T.unmodifiableList(__result)", java.util.Collections.class);
        return method.build();
    }

    /** Pair of (main, test) {@link ComponentModel}s for a shadowed-key entry. */
    private record ShadowOverride(ComponentModel main, ComponentModel test) {}

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
     * Drops {@code Modifier.PRIVATE} when the container is being emitted extensibly
     * (test subclass present in this round) so the subclass can call
     * {@code singletons.computeIfAbsent(...)} from its overridden / additional getters.
     */
    private FieldSpec createSingletonStorageField() {
        ParameterizedTypeName mapType = ParameterizedTypeName.get(
                ClassName.get(Map.class), ClassName.get(String.class), ClassName.get(Object.class));

        return FieldSpec.builder(mapType, "singletons", scopeStorageModifiers())
                .initializer("new $T<>()", ConcurrentHashMap.class)
                .build();
    }

    /**
     * Field-modifier set for the per-container scope-storage and override-source fields
     * that the test subclass needs to access. Package-private (no PRIVATE) when emitting
     * extensibly so the subclass — in the same {@code io.tiko.generated} package — can
     * read/mutate them; fully private otherwise.
     */
    private Modifier[] scopeStorageModifiers() {
        return extensibleMainContainer
                ? new Modifier[] {Modifier.FINAL}
                : new Modifier[] {Modifier.PRIVATE, Modifier.FINAL};
    }

    /**
     * Creates the REQUEST scope storage field: ThreadLocal<Map<String, Object>>.
     * <p>Uses {@link LinkedHashMap} so scope teardown can iterate beans in
     * insertion order (= creation order for lazy scoped beans) and invoke
     * {@code @PreDestroy} hooks in reverse-creation (LIFO) order. Per-thread
     * via {@link ThreadLocal}, so the non-thread-safe map is fine.
     */
    private FieldSpec createRequestScopeField() {
        ParameterizedTypeName mapType = ParameterizedTypeName.get(
                ClassName.get(Map.class), ClassName.get(String.class), ClassName.get(Object.class));

        ParameterizedTypeName threadLocalType = ParameterizedTypeName.get(ClassName.get(ThreadLocal.class), mapType);

        return FieldSpec.builder(threadLocalType, "requestScoped", scopeStorageModifiers())
                .initializer("$T.withInitial($T::new)", ThreadLocal.class, LinkedHashMap.class)
                .build();
    }

    /**
     * Creates the EVENT scope storage field: ThreadLocal<Map<String, Object>>.
     * Same rationale as the REQUEST field — {@link LinkedHashMap} for ordered teardown.
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
     * Field: TikoOptions options — held so getter methods can consult
     * {@link io.tiko.runtime.TikoOptions#getOverride} before constructing the canonical
     * production bean. The SINGLETON getter consults this inside its
     * {@code computeIfAbsent} lambda so the override {@code Supplier} is called at most
     * once per container; REQUEST and EVENT getters consult it inside
     * {@link #emitScopedGetOrCreate} so the override {@code Supplier} is called at most
     * once per scope instance. Named-qualified getters wire the same consultation
     * through their respective name-dispatch paths.
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
            String factoryClassName = component.getClassName() + "Factory";
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
            String factoryClassName = component.getClassName() + "Factory";
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

            // If REQUEST or EVENT scoped, also create getCurrentXxx method for proxies
            if (component.getScope() == Scope.REQUEST || component.getScope() == Scope.EVENT) {
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
     * method's scope (singleton cache / request / event / prototype) around a call to
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

        switch (factory.getScope()) {
            case SINGLETON ->
                method.addStatement(
                        "return ($T) singletons.computeIfAbsent($S, k -> $L)", returnType, storageKey, callExpr);
            case REQUEST ->
                emitScopedGetOrCreateNoOverride(method, returnType, "requestScoped.get()", storageKey, callExpr);
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
            String baseType = dependency.getUnwrappedType().get().toString();
            return "new io.tiko.runtime.ContainerPicker<>(this, " + baseType + ".class)";
        }

        String typeName =
                dependency.isProvider() ? dependency.getUnwrappedType().get().toString() : dependency.getTypeName();

        // @Pick: route to the picked impl's getter. When @Named is also present, the
        // qualifier narrows the lookup to a specific provider via the (impl#name) key —
        // typically one of several @Produces methods that all return the picked class.
        // Mirrors ComponentFactoryGenerator without the "container." prefix.
        if (dependency.isPicked()) {
            String pickedFqn = dependency.getPickedTypeName().get();
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
            String methodName = "get" + component.getClassName();
            call = dependency
                    .getQualifier()
                    .map(q -> methodName + "(\"" + q + "\")")
                    .orElse(methodName + "()");
        } else if (provider instanceof io.tiko.processor.config.ConfigurationModel) {
            // @Configuration records are stored in configSingletons and retrieved via get(Class)
            call = "get(" + typeName + ".class)";
        } else {
            String methodName = "get" + simpleClassName(typeName);
            call = dependency
                    .getQualifier()
                    .map(q -> methodName + "(\"" + q + "\")")
                    .orElse(methodName + "()");
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
                // Return from singleton cache, create if not exists. The override consultation
                // lives inside computeIfAbsent's lambda so the Supplier is invoked at most once
                // per container — the result is cached in `singletons` like any production bean,
                // matching SINGLETON's "instance per container" semantics. Falls back to the
                // canonical factory when no override is registered for the component's user type.
                if (component.requiresProxy()) {
                    // Proxies are created eagerly in constructor, just return the field
                    String proxyFieldName = getProxyFieldName(component.getClassName());
                    method.addStatement("return $L", proxyFieldName);
                } else {
                    TypeName componentType = ClassName.get(component.getTypeElement());
                    method.addStatement(
                            "return ($1T) singletons.computeIfAbsent($2S, k -> options.hasOverride($3T.class) ? options.getOverride($3T.class).get() : $4L.create())",
                            returnType,
                            storageKey,
                            componentType,
                            factoryFieldName);
                }
            }
            case REQUEST -> {
                // Return from REQUEST scope storage. The override consultation lives inside
                // emitScopedGetOrCreate's get-then-put block so the override Supplier is
                // invoked at most once per REQUEST scope instance — the result is then
                // cached in the per-thread requestScoped map like any production bean.
                if (component.requiresProxy()) {
                    // Proxies are created eagerly in constructor, just return the field
                    String proxyFieldName = getProxyFieldName(component.getClassName());
                    method.addStatement("return $L", proxyFieldName);
                } else {
                    TypeName componentType = ClassName.get(component.getTypeElement());
                    emitScopedGetOrCreate(
                            method,
                            returnType,
                            componentType,
                            "requestScoped.get()",
                            storageKey,
                            factoryFieldName + ".create()");
                }
            }
            case EVENT -> {
                // Return from EVENT scope storage. The override consultation lives inside
                // emitScopedGetOrCreate's get-then-put block so the override Supplier is
                // invoked at most once per EVENT scope instance — the result is then
                // cached in the per-thread eventScoped map like any production bean.
                if (component.requiresProxy()) {
                    // Proxies are created eagerly in constructor, just return the field
                    String proxyFieldName = getProxyFieldName(component.getClassName());
                    method.addStatement("return $L", proxyFieldName);
                } else {
                    TypeName componentType = ClassName.get(component.getTypeElement());
                    emitScopedGetOrCreate(
                            method,
                            returnType,
                            componentType,
                            "eventScoped.get()",
                            storageKey,
                            factoryFieldName + ".create()");
                }
            }
            case PROTOTYPE -> // Always create new instance
                method.addStatement("return $L.create()", factoryFieldName);
        }

        return method.build();
    }

    /**
     * Creates getCurrentXxx method for REQUEST/EVENT scoped components (used by proxies).
     */
    private MethodSpec createCurrentScopedGetter(ComponentModel component) {
        String methodName = "getCurrent" + component.getClassName();
        TypeName returnType = ClassName.get(component.getTypeElement());
        TypeName componentType = ClassName.get(component.getTypeElement());
        String storageKey = component.getComponentKey();
        String factoryFieldName = getFactoryFieldName(component.getClassName());

        MethodSpec.Builder method = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC)
                .returns(returnType);

        if (component.getScope() == Scope.REQUEST) {
            emitScopedGetOrCreate(
                    method,
                    returnType,
                    componentType,
                    "requestScoped.get()",
                    storageKey,
                    factoryFieldName + ".create()");
        } else { // EVENT
            emitScopedGetOrCreate(
                    method, returnType, componentType, "eventScoped.get()", storageKey, factoryFieldName + ".create()");
        }

        return method.build();
    }

    /**
     * Emits a reentrant-safe get-or-create for a scope map. Plain
     * {@code computeIfAbsent} on {@link LinkedHashMap} throws
     * {@link java.util.ConcurrentModificationException} when the create lambda
     * recursively pulls another bean from the same map (e.g., dependency chains
     * like REQUEST {@code TransactionContext} depending on REQUEST {@code Connection}
     * in {@code tiko-examples/10_persistence_jdbc}). The map is single-threaded
     * (per-thread {@code ThreadLocal}) so this non-atomic get/put pair is safe —
     * and produces the desired insertion order: dependencies are put first,
     * dependents last, which is exactly what scope teardown's reverse iteration
     * relies on for LIFO destruction.
     *
     * <p>This is intentionally different from the SINGLETON case above, which
     * uses {@code singletons.computeIfAbsent(...)}: {@code singletons} is a
     * {@link java.util.concurrent.ConcurrentHashMap} (chosen for thread safety,
     * since SINGLETON beans are reachable from any thread) and tolerates
     * recursive update on different keys in practice. SWAPPING THIS HELPER TO
     * {@code computeIfAbsent} TO MATCH SINGLETON WILL BREAK any REQUEST/EVENT
     * dependency chain — see closed issue #100 for the analysis.
     *
     * <p>Before invoking the canonical factory, this consults
     * {@link io.tiko.runtime.TikoOptions#hasOverride(Class)} on the user-facing
     * component type; if present, the override {@code Supplier} produces the
     * bean instead. The result is then stored in the scope map like any
     * production bean, so subsequent lookups within the same scope reuse it —
     * matching SINGLETON's T6 contract at the scope-instance granularity.
     */
    private void emitScopedGetOrCreate(
            MethodSpec.Builder method,
            TypeName returnType,
            TypeName componentType,
            String mapExpr,
            String storageKey,
            String createExpr) {
        method.addStatement("$T __existing = ($T) $L.get($S)", returnType, returnType, mapExpr, storageKey);
        method.beginControlFlow("if (__existing == null)");
        method.addStatement(
                "__existing = options.hasOverride($1T.class) ? ($2T) options.getOverride($1T.class).get() : $3L",
                componentType,
                returnType,
                createExpr);
        method.addStatement("$L.put($S, __existing)", mapExpr, storageKey);
        method.endControlFlow();
        method.addStatement("return __existing");
    }

    /**
     * Variant of {@link #emitScopedGetOrCreate} used by {@code @Produces} factory-method
     * getters, which deliberately do <em>not</em> consult {@link io.tiko.runtime.TikoOptions}
     * overrides. Overrides are keyed on the user-facing {@code @Component} type, not on
     * arbitrary {@code @Produces} return types — mirroring the SINGLETON factory-method
     * arm above. If/when {@code @Produces} overrides are introduced, this helper folds
     * back into {@link #emitScopedGetOrCreate}.
     */
    private void emitScopedGetOrCreateNoOverride(
            MethodSpec.Builder method, TypeName returnType, String mapExpr, String storageKey, String createExpr) {
        method.addStatement("$T __existing = ($T) $L.get($S)", returnType, returnType, mapExpr, storageKey);
        method.beginControlFlow("if (__existing == null)");
        method.addStatement("__existing = $L", createExpr);
        method.addStatement("$L.put($S, __existing)", mapExpr, storageKey);
        method.endControlFlow();
        method.addStatement("return __existing");
    }

    private static final ClassName REQUEST_STARTED = ClassName.get("io.tiko.events", "RequestStartedEvent");
    private static final ClassName REQUEST_ENDING = ClassName.get("io.tiko.events", "RequestEndingEvent");
    private static final ClassName EVENT_STARTED = ClassName.get("io.tiko.events", "EventStartedEvent");
    private static final ClassName EVENT_ENDING = ClassName.get("io.tiko.events", "EventEndingEvent");
    private static final ClassName APP_STARTED = ClassName.get("io.tiko.events", "ApplicationStartedEvent");
    private static final ClassName APP_ENDING = ClassName.get("io.tiko.events", "ApplicationEndingEvent");

    /**
     * Creates runInRequestScope method.
     */
    private MethodSpec createRunInRequestScopeMethod() {
        MethodSpec.Builder method = MethodSpec.methodBuilder("runInRequestScope")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addParameter(Runnable.class, "task")
                .addStatement("$T __requestId = $T.randomUUID().toString()", String.class, UUID.class)
                .addStatement("$T __requestStart = $T.now()", Instant.class, Instant.class)
                .addStatement("eventBus.publish(new $T(__requestId, __requestStart))", REQUEST_STARTED)
                .beginControlFlow("try")
                .addStatement("task.run()")
                .nextControlFlow("finally")
                .addStatement("$T __requestEnd = $T.now()", Instant.class, Instant.class)
                .addStatement(
                        "eventBus.publish(new $T(__requestId, __requestEnd, $T.between(__requestStart, __requestEnd)))",
                        REQUEST_ENDING,
                        Duration.class);
        emitScopedTeardown(method, Scope.REQUEST, "requestScoped.get()");
        method.addStatement("requestScoped.get().clear()").endControlFlow();
        return method.build();
    }

    /**
     * Creates supplyInRequestScope method.
     */
    private MethodSpec createSupplyInRequestScopeMethod() {
        TypeVariableName typeVar = TypeVariableName.get("T");
        ParameterizedTypeName supplierType =
                ParameterizedTypeName.get(ClassName.get(java.util.function.Supplier.class), typeVar);

        MethodSpec.Builder method = MethodSpec.methodBuilder("supplyInRequestScope")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addTypeVariable(typeVar)
                .addParameter(supplierType, "supplier")
                .returns(typeVar)
                .addStatement("$T __requestId = $T.randomUUID().toString()", String.class, UUID.class)
                .addStatement("$T __requestStart = $T.now()", Instant.class, Instant.class)
                .addStatement("eventBus.publish(new $T(__requestId, __requestStart))", REQUEST_STARTED)
                .beginControlFlow("try")
                .addStatement("return supplier.get()")
                .nextControlFlow("finally")
                .addStatement("$T __requestEnd = $T.now()", Instant.class, Instant.class)
                .addStatement(
                        "eventBus.publish(new $T(__requestId, __requestEnd, $T.between(__requestStart, __requestEnd)))",
                        REQUEST_ENDING,
                        Duration.class);
        emitScopedTeardown(method, Scope.REQUEST, "requestScoped.get()");
        method.addStatement("requestScoped.get().clear()").endControlFlow();
        return method.build();
    }

    /**
     * Creates runInEventScope method.
     */
    private MethodSpec createRunInEventScopeMethod() {
        MethodSpec.Builder method = MethodSpec.methodBuilder("runInEventScope")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addParameter(Runnable.class, "task")
                .addStatement("$T __eventId = $T.randomUUID().toString()", String.class, UUID.class)
                .addStatement("$T __eventStart = $T.now()", Instant.class, Instant.class)
                .addStatement("eventBus.publish(new $T(__eventId, __eventStart))", EVENT_STARTED)
                .beginControlFlow("try")
                .addStatement("task.run()")
                .nextControlFlow("finally")
                .addStatement("$T __eventEnd = $T.now()", Instant.class, Instant.class)
                .addStatement(
                        "eventBus.publish(new $T(__eventId, __eventEnd, $T.between(__eventStart, __eventEnd)))",
                        EVENT_ENDING,
                        Duration.class);
        emitScopedTeardown(method, Scope.EVENT, "eventScoped.get()");
        method.addStatement("eventScoped.get().clear()").endControlFlow();
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
                .returns(typeVar)
                .addStatement("$T __eventId = $T.randomUUID().toString()", String.class, UUID.class)
                .addStatement("$T __eventStart = $T.now()", Instant.class, Instant.class)
                .addStatement("eventBus.publish(new $T(__eventId, __eventStart))", EVENT_STARTED)
                .beginControlFlow("try")
                .addStatement("return supplier.get()")
                .nextControlFlow("finally")
                .addStatement("$T __eventEnd = $T.now()", Instant.class, Instant.class)
                .addStatement(
                        "eventBus.publish(new $T(__eventId, __eventEnd, $T.between(__eventStart, __eventEnd)))",
                        EVENT_ENDING,
                        Duration.class);
        emitScopedTeardown(method, Scope.EVENT, "eventScoped.get()");
        method.addStatement("eventScoped.get().clear()").endControlFlow();
        return method.build();
    }

    /**
     * Emits the destroy-hook walk for a REQUEST/EVENT scope teardown. Iterates the
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

        ClassName loggerLevel = ClassName.get("java.lang", "System", "Logger", "Level");

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
            String hookLabel = isAutoCloseOnly ? "AutoCloseable.close()" : "@PreDestroy";
            method.addStatement(
                    "$T.getLogger($S).log($T.WARNING, $S, __t)",
                    ClassName.get("java.lang", "System"),
                    "io.tiko.events",
                    loggerLevel,
                    hookLabel + " threw on " + c.getClassName());
            // Route the failure through ErrorHandler — AutoCloseable.close() and @PreDestroy
            // emit distinct permits so observability code can discriminate without parsing.
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
                    "$T.getLogger($S).log($T.WARNING, $S, __t)",
                    ClassName.get("java.lang", "System"),
                    "io.tiko.events",
                    loggerLevel,
                    "AutoCloseable.close() threw on factory-produced bean");
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
        method.beginControlFlow("if (publishLifecycleEvents)");
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
     *   <li>Set stopped, drain in-flight gets (10s timeout, spin-wait).</li>
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
        method.beginControlFlow("if (publishLifecycleEvents)");
        method.beginControlFlow("try");
        method.addStatement("eventBus.publish(new $T(__endTimestamp, __uptime))", APP_ENDING);
        method.nextControlFlow("catch ($T __t)", Throwable.class);
        method.addComment("Bus-impl defect; @PreDestroy must still run (handler exceptions are isolated by #44)");
        method.addStatement(
                "$T.getLogger($S).log($T.WARNING, $S, __t)",
                ClassName.get("java.lang", "System"),
                "io.tiko.events",
                loggerLevel,
                "ApplicationEndingEvent publish threw");
        method.endControlFlow();
        method.endControlFlow();

        method.addComment("Phase 3: gate new get() calls and drain in-flight ones");
        method.addStatement("stopped.set(true)");
        method.addStatement("long __deadlineNanos = $T.nanoTime() + $T.SECONDS.toNanos(10)", System.class, timeUnit);
        method.beginControlFlow("while (inFlightGets.get() > 0 && $T.nanoTime() < __deadlineNanos)", System.class);
        method.addStatement("$T.onSpinWait()", Thread.class);
        method.endControlFlow();
        method.beginControlFlow("if (inFlightGets.get() > 0)");
        method.addStatement(
                "$T.getLogger($S).log($T.WARNING, $S + inFlightGets.get())",
                ClassName.get("java.lang", "System"),
                "io.tiko.events",
                loggerLevel,
                "Container shutdown drain timed out with in-flight get() calls: ");
        method.endControlFlow();

        method.addComment("Phase 4: @PreDestroy on SINGLETON components, reverse-creation (LIFO) order. "
                + "Thread-local bypass so they can call get(). Each hook is isolated so one failure "
                + "does not skip the rest.");
        method.addStatement("inShutdownThread.set($T.TRUE)", Boolean.class);
        method.beginControlFlow("try");

        // Snapshot SINGLETON components with destroy hooks in registration order, then walk in reverse.
        // start() initialises eagerly in registration order, so reverse iteration approximates LIFO.
        List<ComponentModel> singletonHooks = new ArrayList<>();
        for (ComponentModel component : activeComponents()) {
            if (component.getScope() == Scope.SINGLETON && component.hasDestroyHook() && !component.requiresProxy()) {
                singletonHooks.add(component);
            }
        }

        for (int i = singletonHooks.size() - 1; i >= 0; i--) {
            ComponentModel component = singletonHooks.get(i);
            String componentKey = component.getComponentKey();
            TypeName componentType = ClassName.get(component.getTypeElement());
            String variableName = Character.toLowerCase(component.getClassName().charAt(0))
                    + component.getClassName().substring(1);

            method.addStatement(
                    "$T $L = ($T) singletons.get($S)", componentType, variableName, componentType, componentKey);

            method.beginControlFlow("if ($L != null)", variableName);
            method.beginControlFlow("try");
            boolean isAutoCloseOnly = component.isAutoCloseable()
                    && component.getPreDestroyMethods().isEmpty();
            if (isAutoCloseOnly) {
                method.addStatement("$L.close()", variableName);
            } else {
                for (var preDestroy : component.getPreDestroyMethods()) {
                    method.addStatement("$L.$L()", variableName, preDestroy.getSimpleName());
                }
            }
            method.nextControlFlow("catch ($T __t)", Throwable.class);
            String hookLabel = isAutoCloseOnly ? "AutoCloseable.close()" : "@PreDestroy";
            method.addStatement(
                    "$T.getLogger($S).log($T.WARNING, $S, __t)",
                    ClassName.get("java.lang", "System"),
                    "io.tiko.events",
                    loggerLevel,
                    hookLabel + " threw on " + component.getClassName());
            ClassName failureType = isAutoCloseOnly
                    ? ClassName.get("io.tiko", "AutoCloseFailure")
                    : ClassName.get("io.tiko", "PreDestroyFailure");
            method.addStatement("errorHandler.onError(new $T($T.class, __t))", failureType, componentType);
            method.endControlFlow(); // try/catch
            method.endControlFlow(); // if non-null
        }

        // Factory-produced SINGLETON beans whose return type implements AutoCloseable.
        // Covers @Produces returning third-party closeables (data sources, HTTP clients,
        // Kafka producers) without forcing the user to write a wrapper @Component.
        List<FactoryMethodModel> singletonFactoryHooks = new ArrayList<>();
        for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
            if (factory.getScope() == Scope.SINGLETON && factory.isAutoCloseable()) {
                singletonFactoryHooks.add(factory);
            }
        }
        for (int i = singletonFactoryHooks.size() - 1; i >= 0; i--) {
            FactoryMethodModel factory = singletonFactoryHooks.get(i);
            String factoryKey = factory.getComponentKey();
            String variableName = "__factory_" + factory.getFactoryIdentifier();

            method.addStatement(
                    "$T $L = ($T) singletons.get($S)",
                    ClassName.get(AutoCloseable.class),
                    variableName,
                    ClassName.get(AutoCloseable.class),
                    factoryKey);
            method.beginControlFlow("if ($L != null)", variableName);
            method.beginControlFlow("try");
            method.addStatement("$L.close()", variableName);
            method.nextControlFlow("catch ($T __t)", Throwable.class);
            method.addStatement(
                    "$T.getLogger($S).log($T.WARNING, $S, __t)",
                    ClassName.get("java.lang", "System"),
                    "io.tiko.events",
                    loggerLevel,
                    "AutoCloseable.close() threw on " + factory.getFactoryIdentifier());
            method.addStatement(
                    "errorHandler.onError(new $T($L.getClass(), __t))",
                    ClassName.get("io.tiko", "AutoCloseFailure"),
                    variableName);
            method.endControlFlow(); // try/catch
            method.endControlFlow(); // if non-null
        }

        method.nextControlFlow("finally");
        method.addStatement("inShutdownThread.remove()");
        method.endControlFlow();

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
     * Creates get(Class) method.
     */
    private MethodSpec createGetMethod() {
        TypeVariableName typeVar = TypeVariableName.get("T");
        ParameterizedTypeName classType = ParameterizedTypeName.get(ClassName.get(Class.class), typeVar);

        MethodSpec.Builder method = MethodSpec.methodBuilder("get")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                        .addMember("value", "$S", "unchecked")
                        .build())
                .addTypeVariable(typeVar)
                .addParameter(classType, "type")
                .returns(typeVar);

        // Runtime override consulted first on the lookup type (#128) — applies to any
        // routable key (interface or concrete class), so override(Interface.class, mock)
        // wins regardless of whether the caller asks for the interface or the impl.
        method.beginControlFlow("if (options.hasOverride(type))");
        method.addStatement("return (T) options.getOverride(type).get()");
        method.endControlFlow();

        // Post-shutdown gate (#47). PreDestroy methods on the shutdown thread bypass via the thread-local.
        method.beginControlFlow("if (stopped.get() && !inShutdownThread.get())");
        method.addStatement("throw new $T($S)", IllegalStateException.class, "Container has been shut down");
        method.endControlFlow();

        // Drain barrier (#47): mark this get() as in-flight so shutdown() can wait for it.
        method.addStatement("inFlightGets.incrementAndGet()");
        method.beginControlFlow("try");

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
        method.addStatement(
                "throw new $T($S + type.getName())", IllegalArgumentException.class, "No component found for type: ");

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
                .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                        .addMember("value", "$S", "unchecked")
                        .build())
                .addTypeVariable(typeVar)
                .addParameter(classType, "type")
                .addParameter(String.class, "name")
                .returns(typeVar);

        // Post-shutdown gate (#47).
        method.beginControlFlow("if (stopped.get() && !inShutdownThread.get())");
        method.addStatement("throw new $T($S)", IllegalStateException.class, "Container has been shut down");
        method.endControlFlow();
        method.addStatement("inFlightGets.incrementAndGet()");
        method.beginControlFlow("try");

        List<ComponentModel> named =
                activeComponents().stream().filter(c -> c.getName().isPresent()).toList();

        boolean first = true;
        for (ComponentModel component : named) {
            String getterName = "get" + component.getClassName();
            String componentName = component.getName().get();
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
                method.beginControlFlow("if (options.hasOverride($T.class, $S))", key, componentName);
                method.addStatement("return (T) options.getOverride($T.class, $S).get()", key, componentName);
                method.endControlFlow();
                method.addStatement("return (T) $L()", getterName);
            }
        }

        // Named factory-produced components
        for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
            if (factory.getName() == null || factory.getName().isEmpty()) continue;
            TypeName producedType = TypeName.get(factory.getReturnType());
            String factoryName = factory.getName();

            if (first) {
                method.beginControlFlow(
                        "if ($S.equals(name) && type.isAssignableFrom($T.class))", factoryName, producedType);
                first = false;
            } else {
                method.nextControlFlow(
                        "else if ($S.equals(name) && type.isAssignableFrom($T.class))", factoryName, producedType);
            }
            method.addStatement("return (T) $L()", factoryGetterName(factory));
        }

        if (!first) {
            method.endControlFlow();
        }

        method.addStatement(
                "throw new $T($S + name + $S + type.getName())",
                IllegalArgumentException.class,
                "No component found for name '",
                "' and type: ");

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

        // Post-shutdown gate, mirrors get(Class).
        method.beginControlFlow("if (stopped.get() && !inShutdownThread.get())");
        method.addStatement("throw new $T($S)", IllegalStateException.class, "Container has been shut down");
        method.endControlFlow();

        method.addStatement("inFlightGets.incrementAndGet()");
        method.beginControlFlow("try");
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
        for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
            TypeName producedType = TypeName.get(factory.getReturnType());
            method.beginControlFlow("if (type.isAssignableFrom($T.class))", producedType);
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
     * on each invocation, so scope semantics (singleton cache, request/event resolution,
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
        List<? extends javax.lang.model.type.TypeMirror> declared = component.isExposeRestricted()
                ? component.getExposeTypes()
                : component.getTypeElement().getInterfaces();
        for (javax.lang.model.type.TypeMirror iface : declared) {
            TypeName n = TypeName.get(iface);
            if (!keys.contains(n)) keys.add(n);
        }
        if (component.isExposeSelf() && !keys.contains(componentType)) {
            keys.add(componentType);
        }
        return keys;
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
     * Contains newline-separated list of all component class names — main-only, mirroring
     * the main container's component list. The {@code AggregatingContainer} loads this
     * file to map types to per-module containers; tests do not need a parallel listing
     * because the test container is loaded directly via {@code test-container.properties},
     * not aggregated.
     */
    private void generateComponentsListFile() throws IOException {
        try (var writer = context.getFiler()
                .createResource(javax.tools.StandardLocation.CLASS_OUTPUT, "", "META-INF/tiko/components.txt")
                .openWriter()) {

            for (ComponentModel component : context.getActiveMainComponents()) {
                // Use binary name (with '$' for nested classes) so Class.forName() works at runtime
                writer.write(context.getElementUtils()
                        .getBinaryName(component.getTypeElement())
                        .toString());
                writer.write("\n");
            }
        }
    }
}
