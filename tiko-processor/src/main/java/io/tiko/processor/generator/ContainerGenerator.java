package io.tiko.processor.generator;

import com.palantir.javapoet.*;
import io.tiko.Container;
import io.tiko.EventBus;
import io.tiko.Scope;
import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.ProcessorContext;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.UUID;
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

    private final ProcessorContext context;

    public ContainerGenerator(ProcessorContext context) {
        this.context = context;
    }

    /**
     * Generates the TikoContainerImpl class.
     */
    public void generate() throws IOException {
        String containerClassName = context.getContainerClassName();

        TypeSpec.Builder containerBuilder = TypeSpec.classBuilder(containerClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(Container.class);

        // Add fields
        containerBuilder.addField(createSingletonStorageField());
        containerBuilder.addField(createRequestScopeField());
        containerBuilder.addField(createEventScopeField());
        containerBuilder.addField(createEventBusField());
        containerBuilder.addField(createErrorHandlerField());
        containerBuilder.addField(createEventExecutorField());
        containerBuilder.addField(createOwnsEventExecutorField());
        containerBuilder.addField(createStartedAtField());
        containerBuilder.addField(createConfigSingletonsField());
        containerBuilder.addField(createShutdownInvokedField());
        containerBuilder.addField(createStoppedField());
        containerBuilder.addField(createInFlightGetsField());
        containerBuilder.addField(createInShutdownThreadField());
        containerBuilder.addField(createStartInvokedField());
        containerBuilder.addField(createPublishLifecycleEventsField());
        containerBuilder.addFields(createFactoryFields());

        // Add constructor
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

        // Generate metadata files for multi-module support
        generateContainerPropertiesFile();
        generateComponentsListFile();
    }

    /**
     * Creates the singleton storage field: Map<String, Object>
     */
    private FieldSpec createSingletonStorageField() {
        ParameterizedTypeName mapType = ParameterizedTypeName.get(
                ClassName.get(Map.class), ClassName.get(String.class), ClassName.get(Object.class));

        return FieldSpec.builder(mapType, "singletons", Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T<>()", ConcurrentHashMap.class)
                .build();
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

        return FieldSpec.builder(threadLocalType, "requestScoped", Modifier.PRIVATE, Modifier.FINAL)
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

        return FieldSpec.builder(threadLocalType, "eventScoped", Modifier.PRIVATE, Modifier.FINAL)
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

        for (ComponentModel component : context.getActiveComponents()) {
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
     * Creates the constructor that initializes factories, event bus, and error handler.
     * <p>The {@code publishLifecycleEvents} flag (#45) controls whether this container
     * publishes its own {@code ApplicationStartedEvent} / {@code ApplicationEndingEvent}.
     * Single-module setups pass {@code true}; per-module containers run under an
     * {@code AggregatingContainer} pass {@code false} so the aggregator can publish
     * exactly once on the shared bus.
     */
    private MethodSpec createConstructor() {
        MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(EventBus.class, "eventBus")
                .addParameter(ClassName.get("io.tiko", "ErrorHandler"), "errorHandler")
                .addParameter(ClassName.get("java.util.concurrent", "ExecutorService"), "userEventExecutor")
                .addParameter(TypeName.BOOLEAN, "publishLifecycleEvents")
                .addStatement("this.eventBus = eventBus")
                .addStatement("this.errorHandler = errorHandler")
                .addStatement("this.eventExecutor = userEventExecutor != null ? userEventExecutor : "
                        + "io.tiko.runtime.DefaultEventExecutorFactory.create()")
                .addStatement("this.ownsEventExecutor = (userEventExecutor == null)")
                .addStatement("this.publishLifecycleEvents = publishLifecycleEvents");

        // Initialize factory fields
        for (ComponentModel component : context.getActiveComponents()) {
            String factoryClassName = component.getClassName() + "Factory";
            String fieldName = getFactoryFieldName(component.getClassName());

            constructor.addStatement("this.$L = new $L(this)", fieldName, factoryClassName);
        }

        // Initialize proxy fields
        for (ComponentModel component : context.getActiveComponents()) {
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

        for (ComponentModel component : context.getActiveComponents()) {
            methods.add(createComponentGetter(component));

            // If REQUEST or EVENT scoped, also create getCurrentXxx method for proxies
            if (component.getScope() == Scope.REQUEST || component.getScope() == Scope.EVENT) {
                methods.add(createCurrentScopedGetter(component));
            }
        }

        for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
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
            case REQUEST -> emitScopedGetOrCreate(method, returnType, "requestScoped.get()", storageKey, callExpr);
            case EVENT -> emitScopedGetOrCreate(method, returnType, "eventScoped.get()", storageKey, callExpr);
            case PROTOTYPE -> method.addStatement("return $L", callExpr);
        }

        return method.build();
    }

    /**
     * Builds the expression that invokes the factory method, including dependency resolution.
     * Instance methods are called on the declaring @Component singleton; static methods are called directly.
     */
    private String buildFactoryCallExpression(FactoryMethodModel factory) {
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
                // Return from singleton cache, create if not exists
                if (component.requiresProxy()) {
                    // Proxies are created eagerly in constructor, just return the field
                    String proxyFieldName = getProxyFieldName(component.getClassName());
                    method.addStatement("return $L", proxyFieldName);
                } else {
                    method.addStatement(
                            "return ($T) singletons.computeIfAbsent($S, k -> $L.create())",
                            returnType,
                            storageKey,
                            factoryFieldName);
                }
            }
            case REQUEST -> {
                // Return from REQUEST scope storage
                if (component.requiresProxy()) {
                    // Proxies are created eagerly in constructor, just return the field
                    String proxyFieldName = getProxyFieldName(component.getClassName());
                    method.addStatement("return $L", proxyFieldName);
                } else {
                    emitScopedGetOrCreate(
                            method, returnType, "requestScoped.get()", storageKey, factoryFieldName + ".create()");
                }
            }
            case EVENT -> {
                // Return from EVENT scope storage
                if (component.requiresProxy()) {
                    // Proxies are created eagerly in constructor, just return the field
                    String proxyFieldName = getProxyFieldName(component.getClassName());
                    method.addStatement("return $L", proxyFieldName);
                } else {
                    emitScopedGetOrCreate(
                            method, returnType, "eventScoped.get()", storageKey, factoryFieldName + ".create()");
                }
            }
            case PROTOTYPE -> {
                // Always create new instance
                method.addStatement("return $L.create()", factoryFieldName);
            }
        }

        return method.build();
    }

    /**
     * Creates getCurrentXxx method for REQUEST/EVENT scoped components (used by proxies).
     */
    private MethodSpec createCurrentScopedGetter(ComponentModel component) {
        String methodName = "getCurrent" + component.getClassName();
        TypeName returnType = ClassName.get(component.getTypeElement());
        String storageKey = component.getComponentKey();
        String factoryFieldName = getFactoryFieldName(component.getClassName());

        MethodSpec.Builder method = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC)
                .returns(returnType);

        if (component.getScope() == Scope.REQUEST) {
            emitScopedGetOrCreate(
                    method, returnType, "requestScoped.get()", storageKey, factoryFieldName + ".create()");
        } else { // EVENT
            emitScopedGetOrCreate(method, returnType, "eventScoped.get()", storageKey, factoryFieldName + ".create()");
        }

        return method.build();
    }

    /**
     * Emits a reentrant-safe get-or-create for a scope map. Plain
     * {@code computeIfAbsent} on {@link LinkedHashMap} throws
     * {@link java.util.ConcurrentModificationException} when the create lambda
     * recursively pulls another bean from the same map (e.g., dependency chains).
     * The map is single-threaded (per-thread {@code ThreadLocal}) so this
     * non-atomic get/put pair is safe — and produces the desired insertion order:
     * dependencies are put first, dependents last, which is exactly what scope
     * teardown's reverse iteration relies on for LIFO destruction.
     */
    private void emitScopedGetOrCreate(
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
        for (ComponentModel c : context.getActiveComponents()) {
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

        ClassName logger = ClassName.get("java.util.logging", "Logger");
        ClassName level = ClassName.get("java.util.logging", "Level");

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
            if (c.isAutoCloseable() && c.getPreDestroyMethods().isEmpty()) {
                method.addStatement("$L.close()", varName);
            } else {
                for (var preDestroy : c.getPreDestroyMethods()) {
                    method.addStatement("$L.$L()", varName, preDestroy.getSimpleName());
                }
            }
            method.nextControlFlow("catch ($T __t)", Throwable.class);
            method.addStatement(
                    "$T.getLogger($S).log($T.WARNING, $S, __t)",
                    logger,
                    "io.tiko.events",
                    level,
                    "@PreDestroy threw on " + c.getClassName());
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
                    logger,
                    "io.tiko.events",
                    level,
                    "AutoCloseable.close() threw on factory-produced bean");
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

        for (ComponentModel component : context.getActiveComponents()) {
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

        ClassName logger = ClassName.get("java.util.logging", "Logger");
        ClassName level = ClassName.get("java.util.logging", "Level");
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
                logger,
                "io.tiko.events",
                level,
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
                logger,
                "io.tiko.events",
                level,
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
        for (ComponentModel component : context.getActiveComponents()) {
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
            if (component.isAutoCloseable() && component.getPreDestroyMethods().isEmpty()) {
                method.addStatement("$L.close()", variableName);
            } else {
                for (var preDestroy : component.getPreDestroyMethods()) {
                    method.addStatement("$L.$L()", variableName, preDestroy.getSimpleName());
                }
            }
            method.nextControlFlow("catch ($T __t)", Throwable.class);
            method.addStatement(
                    "$T.getLogger($S).log($T.WARNING, $S, __t)",
                    logger,
                    "io.tiko.events",
                    level,
                    "@PreDestroy threw on " + component.getClassName());
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
                    logger,
                    "io.tiko.events",
                    level,
                    "AutoCloseable.close() threw on " + factory.getFactoryIdentifier());
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
        method.beginControlFlow("if (!this.eventExecutor.awaitTermination(10, $T.SECONDS))", timeUnit);
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

        // Generate if-else chain for each component.
        // Named components match only their concrete class; unnamed components also match
        // their implemented interface (the "default" for that interface).
        boolean first = true;
        for (ComponentModel component : context.getActiveComponents()) {
            TypeName componentType = ClassName.get(component.getTypeElement());
            String getterName = "get" + component.getClassName();
            boolean includeInterface = component.getName().isEmpty()
                    && component.getImplementedInterface().isPresent();

            if (includeInterface) {
                TypeName ifaceType =
                        TypeName.get(component.getImplementedInterface().get());
                if (first) {
                    method.beginControlFlow("if (type == $T.class || type == $T.class)", componentType, ifaceType);
                } else {
                    method.nextControlFlow("else if (type == $T.class || type == $T.class)", componentType, ifaceType);
                }
            } else {
                if (first) {
                    method.beginControlFlow("if (type == $T.class)", componentType);
                } else {
                    method.nextControlFlow("else if (type == $T.class)", componentType);
                }
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

        List<ComponentModel> named = context.getActiveComponents().stream()
                .filter(c -> c.getName().isPresent())
                .toList();

        boolean first = true;
        for (ComponentModel component : named) {
            TypeName componentType = ClassName.get(component.getTypeElement());
            String getterName = "get" + component.getClassName();
            String componentName = component.getName().get();

            if (first) {
                method.beginControlFlow(
                        "if ($S.equals(name) && type.isAssignableFrom($T.class))", componentName, componentType);
                first = false;
            } else {
                method.nextControlFlow(
                        "else if ($S.equals(name) && type.isAssignableFrom($T.class))", componentName, componentType);
            }
            method.addStatement("return (T) $L()", getterName);
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

        // One assignability check per component. type.isAssignableFrom(ComponentClass.class)
        // catches both interface-typed and concrete-typed picker base types.
        for (ComponentModel component : context.getActiveComponents()) {
            TypeName componentType = ClassName.get(component.getTypeElement());
            String getterName = "get" + component.getClassName();
            method.beginControlFlow("if (type.isAssignableFrom($T.class))", componentType);
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
     * Generates META-INF/tiko/container.properties file.
     * Contains the fully qualified class name of the generated container.
     */
    private void generateContainerPropertiesFile() throws IOException {
        String fullClassName = GENERATED_PACKAGE + "." + context.getContainerClassName();

        try (var writer = context.getFiler()
                .createResource(javax.tools.StandardLocation.CLASS_OUTPUT, "", "META-INF/tiko/container.properties")
                .openWriter()) {

            writer.write("# Tiko DI Container Metadata\n");
            writer.write("impl=" + fullClassName + "\n");
        }
    }

    /**
     * Generates META-INF/tiko/components.txt file.
     * Contains newline-separated list of all component class names.
     */
    private void generateComponentsListFile() throws IOException {
        try (var writer = context.getFiler()
                .createResource(javax.tools.StandardLocation.CLASS_OUTPUT, "", "META-INF/tiko/components.txt")
                .openWriter()) {

            for (ComponentModel component : context.getActiveComponents()) {
                // Use binary name (with '$' for nested classes) so Class.forName() works at runtime
                writer.write(context.getElementUtils()
                        .getBinaryName(component.getTypeElement())
                        .toString());
                writer.write("\n");
            }
        }
    }
}
