package io.tiko.processor.generator;

import com.palantir.javapoet.*;
import io.tiko.Container;
import io.tiko.EventBus;
import io.tiko.Scope;
import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.ProcessorContext;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
        containerBuilder.addField(createStartedAtField());
        containerBuilder.addField(createConfigSingletonsField());
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

        // Add config injection method
        containerBuilder.addMethod(createInjectConfigsMethod());

        // Add EventBus getter
        containerBuilder.addMethod(createGetEventBusMethod());

        TypeSpec containerClass = containerBuilder.build();

        JavaFile javaFile = JavaFile.builder(GENERATED_PACKAGE, containerClass)
                .build();

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
                ClassName.get(Map.class),
                ClassName.get(String.class),
                ClassName.get(Object.class)
        );

        return FieldSpec.builder(mapType, "singletons", Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T<>()", ConcurrentHashMap.class)
                .build();
    }

    /**
     * Creates the REQUEST scope storage field: ThreadLocal<Map<String, Object>>
     */
    private FieldSpec createRequestScopeField() {
        ParameterizedTypeName mapType = ParameterizedTypeName.get(
                ClassName.get(Map.class),
                ClassName.get(String.class),
                ClassName.get(Object.class)
        );

        ParameterizedTypeName threadLocalType = ParameterizedTypeName.get(
                ClassName.get(ThreadLocal.class),
                mapType
        );

        return FieldSpec.builder(threadLocalType, "requestScoped", Modifier.PRIVATE, Modifier.FINAL)
                .initializer("$T.withInitial($T::new)", ThreadLocal.class, ConcurrentHashMap.class)
                .build();
    }

    /**
     * Creates the EVENT scope storage field: ThreadLocal<Map<String, Object>>
     */
    private FieldSpec createEventScopeField() {
        ParameterizedTypeName mapType = ParameterizedTypeName.get(
                ClassName.get(Map.class),
                ClassName.get(String.class),
                ClassName.get(Object.class)
        );

        ParameterizedTypeName threadLocalType = ParameterizedTypeName.get(
                ClassName.get(ThreadLocal.class),
                mapType
        );

        return FieldSpec.builder(threadLocalType, "eventScoped", Modifier.PRIVATE, Modifier.FINAL)
                .initializer("$T.withInitial($T::new)", ThreadLocal.class, ConcurrentHashMap.class)
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
            ClassName.get(Object.class)
        );
        return FieldSpec.builder(mapType, "configSingletons", Modifier.PRIVATE, Modifier.FINAL)
            .initializer("new $T<>()", ConcurrentHashMap.class)
            .build();
    }

    /**
     * public void injectConfigs(Map&lt;Class&lt;?&gt;, Object&gt; configs) — populates the configSingletons map.
     */
    private MethodSpec createInjectConfigsMethod() {
        ParameterizedTypeName mapType = ParameterizedTypeName.get(
            ClassName.get(Map.class),
            ParameterizedTypeName.get(ClassName.get(Class.class), WildcardTypeName.subtypeOf(Object.class)),
            ClassName.get(Object.class)
        );
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
                    Modifier.PRIVATE, Modifier.FINAL
            ).build());

            // Add proxy field for components that need proxies
            if (component.requiresProxy()) {
                String proxyFieldName = getProxyFieldName(component.getClassName());
                String proxyClassName = component.getClassName() + "Proxy";

                fields.add(FieldSpec.builder(
                        ClassName.get(GENERATED_PACKAGE, proxyClassName),
                        proxyFieldName,
                        Modifier.PRIVATE, Modifier.FINAL
                ).build());
            }
        }

        return fields;
    }

    /**
     * Creates the constructor that initializes factories and event bus.
     */
    private MethodSpec createConstructor() {
        MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(EventBus.class, "eventBus")
                .addStatement("this.eventBus = eventBus");

        // Initialize factory fields
        for (ComponentModel component : context.getActiveComponents()) {
            String factoryClassName = component.getClassName() + "Factory";
            String fieldName = getFactoryFieldName(component.getClassName());

            constructor.addStatement(
                    "this.$L = new $L(this)",
                    fieldName,
                    factoryClassName
            );
        }

        // Initialize proxy fields
        for (ComponentModel component : context.getActiveComponents()) {
            if (component.requiresProxy()) {
                String proxyFieldName = getProxyFieldName(component.getClassName());
                String proxyClassName = component.getClassName() + "Proxy";

                constructor.addStatement(
                        "this.$L = new $L(this)",
                        proxyFieldName,
                        proxyClassName
                );
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
            case SINGLETON -> method.addStatement(
                    "return ($T) singletons.computeIfAbsent($S, k -> $L)",
                    returnType, storageKey, callExpr);
            case REQUEST -> method.addStatement(
                    "return ($T) requestScoped.get().computeIfAbsent($S, k -> $L)",
                    returnType, storageKey, callExpr);
            case EVENT -> method.addStatement(
                    "return ($T) eventScoped.get().computeIfAbsent($S, k -> $L)",
                    returnType, storageKey, callExpr);
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
        String typeName = dependency.isProvider()
                ? dependency.getUnwrappedType().get().toString()
                : dependency.getTypeName();

        Object provider = context.findComponentOrFactory(dependency.getDependencyKey()).orElse(null);

        String call;
        if (provider instanceof FactoryMethodModel factoryDep) {
            call = factoryGetterName(factoryDep) + "()";
        } else if (provider instanceof ComponentModel component) {
            String methodName = "get" + component.getClassName();
            call = dependency.getQualifier()
                    .map(q -> methodName + "(\"" + q + "\")")
                    .orElse(methodName + "()");
        } else if (provider instanceof io.tiko.processor.config.ConfigurationModel) {
            // @Configuration records are stored in configSingletons and retrieved via get(Class)
            call = "get(" + typeName + ".class)";
        } else {
            String methodName = "get" + simpleClassName(typeName);
            call = dependency.getQualifier()
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
                            factoryFieldName
                    );
                }
            }
            case REQUEST -> {
                // Return from REQUEST scope storage
                if (component.requiresProxy()) {
                    // Proxies are created eagerly in constructor, just return the field
                    String proxyFieldName = getProxyFieldName(component.getClassName());
                    method.addStatement("return $L", proxyFieldName);
                } else {
                    method.addStatement(
                            "return ($T) requestScoped.get().computeIfAbsent($S, k -> $L.create())",
                            returnType,
                            storageKey,
                            factoryFieldName
                    );
                }
            }
            case EVENT -> {
                // Return from EVENT scope storage
                if (component.requiresProxy()) {
                    // Proxies are created eagerly in constructor, just return the field
                    String proxyFieldName = getProxyFieldName(component.getClassName());
                    method.addStatement("return $L", proxyFieldName);
                } else {
                    method.addStatement(
                            "return ($T) eventScoped.get().computeIfAbsent($S, k -> $L.create())",
                            returnType,
                            storageKey,
                            factoryFieldName
                    );
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
            method.addStatement(
                    "return ($T) requestScoped.get().computeIfAbsent($S, k -> $L.create())",
                    returnType,
                    storageKey,
                    factoryFieldName
            );
        } else { // EVENT
            method.addStatement(
                    "return ($T) eventScoped.get().computeIfAbsent($S, k -> $L.create())",
                    returnType,
                    storageKey,
                    factoryFieldName
            );
        }

        return method.build();
    }

    private static final ClassName REQUEST_STARTED = ClassName.get("io.tiko.events", "RequestStartedEvent");
    private static final ClassName REQUEST_ENDING  = ClassName.get("io.tiko.events", "RequestEndingEvent");
    private static final ClassName EVENT_STARTED   = ClassName.get("io.tiko.events", "EventStartedEvent");
    private static final ClassName EVENT_ENDING    = ClassName.get("io.tiko.events", "EventEndingEvent");
    private static final ClassName APP_STARTED     = ClassName.get("io.tiko.events", "ApplicationStartedEvent");
    private static final ClassName APP_ENDING      = ClassName.get("io.tiko.events", "ApplicationEndingEvent");

    /**
     * Creates runInRequestScope method.
     */
    private MethodSpec createRunInRequestScopeMethod() {
        return MethodSpec.methodBuilder("runInRequestScope")
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
                        REQUEST_ENDING, Duration.class)
                .addStatement("requestScoped.get().clear()")
                .endControlFlow()
                .build();
    }

    /**
     * Creates supplyInRequestScope method.
     */
    private MethodSpec createSupplyInRequestScopeMethod() {
        TypeVariableName typeVar = TypeVariableName.get("T");
        ParameterizedTypeName supplierType = ParameterizedTypeName.get(
                ClassName.get(java.util.function.Supplier.class),
                typeVar
        );

        return MethodSpec.methodBuilder("supplyInRequestScope")
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
                        REQUEST_ENDING, Duration.class)
                .addStatement("requestScoped.get().clear()")
                .endControlFlow()
                .build();
    }

    /**
     * Creates runInEventScope method.
     */
    private MethodSpec createRunInEventScopeMethod() {
        return MethodSpec.methodBuilder("runInEventScope")
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
                        EVENT_ENDING, Duration.class)
                .addStatement("eventScoped.get().clear()")
                .endControlFlow()
                .build();
    }

    /**
     * Creates supplyInEventScope method.
     */
    private MethodSpec createSupplyInEventScopeMethod() {
        TypeVariableName typeVar = TypeVariableName.get("T");
        ParameterizedTypeName supplierType = ParameterizedTypeName.get(
                ClassName.get(java.util.function.Supplier.class),
                typeVar
        );

        return MethodSpec.methodBuilder("supplyInEventScope")
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
                        EVENT_ENDING, Duration.class)
                .addStatement("eventScoped.get().clear()")
                .endControlFlow()
                .build();
    }

    /**
     * Creates start method that calls @PostConstruct on all singletons.
     */
    private MethodSpec createStartMethod() {
        MethodSpec.Builder method = MethodSpec.methodBuilder("start")
                .addModifiers(Modifier.PUBLIC);

        method.addComment("Initialize all SINGLETON components");

        for (ComponentModel component : context.getActiveComponents()) {
            if (component.getScope() == Scope.SINGLETON && !component.requiresProxy()) {
                String getterName = "get" + component.getClassName();
                method.addStatement("$L()", getterName);
            }
        }

        method.addStatement("this.startedAt = $T.now()", Instant.class);
        method.addStatement("eventBus.publish(new $T(this.startedAt))", APP_STARTED);

        return method.build();
    }

    /**
     * Creates shutdown method that calls @PreDestroy on all singletons.
     */
    private MethodSpec createShutdownMethod() {
        MethodSpec.Builder method = MethodSpec.methodBuilder("shutdown")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class);

        method.addComment("Publish ApplicationEndingEvent before tearing things down");
        method.addStatement("$T __endTimestamp = $T.now()", Instant.class, Instant.class);
        method.addStatement(
                "$T __uptime = (this.startedAt != null) ? $T.between(this.startedAt, __endTimestamp) : $T.ZERO",
                Duration.class, Duration.class, Duration.class);
        method.addStatement("eventBus.publish(new $T(__endTimestamp, __uptime))", APP_ENDING);

        method.addComment("Call @PreDestroy on all SINGLETON components");

        for (ComponentModel component : context.getActiveComponents()) {
            if (component.getScope() == Scope.SINGLETON &&
                !component.getPreDestroyMethods().isEmpty() &&
                !component.requiresProxy()) {

                String componentKey = component.getComponentKey();
                TypeName componentType = ClassName.get(component.getTypeElement());
                String variableName = Character.toLowerCase(component.getClassName().charAt(0)) +
                                     component.getClassName().substring(1);

                method.addStatement(
                        "$T $L = ($T) singletons.get($S)",
                        componentType,
                        variableName,
                        componentType,
                        componentKey
                );

                method.beginControlFlow("if ($L != null)", variableName);
                for (var preDestroy : component.getPreDestroyMethods()) {
                    method.addStatement("$L.$L()", variableName, preDestroy.getSimpleName());
                }
                method.endControlFlow();
            }
        }

        return method.build();
    }

    /**
     * Creates get(Class) method.
     */
    private MethodSpec createGetMethod() {
        TypeVariableName typeVar = TypeVariableName.get("T");
        ParameterizedTypeName classType = ParameterizedTypeName.get(
                ClassName.get(Class.class),
                typeVar
        );

        MethodSpec.Builder method = MethodSpec.methodBuilder("get")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                        .addMember("value", "$S", "unchecked")
                        .build())
                .addTypeVariable(typeVar)
                .addParameter(classType, "type")
                .returns(typeVar);

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
                TypeName ifaceType = TypeName.get(component.getImplementedInterface().get());
                if (first) {
                    method.beginControlFlow(
                            "if (type == $T.class || type == $T.class)", componentType, ifaceType);
                } else {
                    method.nextControlFlow(
                            "else if (type == $T.class || type == $T.class)", componentType, ifaceType);
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
        method.addStatement("throw new $T($S + type.getName())",
            IllegalArgumentException.class,
            "No component found for type: ");

        return method.build();
    }

    /**
     * Creates get(Class, String) method. Dispatches by (name + assignable type)
     * so lookup by concrete class or implemented interface both work.
     */
    private MethodSpec createGetWithNameMethod() {
        TypeVariableName typeVar = TypeVariableName.get("T");
        ParameterizedTypeName classType = ParameterizedTypeName.get(
                ClassName.get(Class.class),
                typeVar
        );

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
                        "if ($S.equals(name) && type.isAssignableFrom($T.class))",
                        componentName, componentType);
                first = false;
            } else {
                method.nextControlFlow(
                        "else if ($S.equals(name) && type.isAssignableFrom($T.class))",
                        componentName, componentType);
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
                        "if ($S.equals(name) && type.isAssignableFrom($T.class))",
                        factoryName, producedType);
                first = false;
            } else {
                method.nextControlFlow(
                        "else if ($S.equals(name) && type.isAssignableFrom($T.class))",
                        factoryName, producedType);
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

        return method.build();
    }

    /**
     * Creates getProvider(Class) method — a lazy handle that delegates to get(type)
     * on each invocation, so scope semantics (singleton cache, request/event resolution,
     * prototype re-creation) are preserved.
     */
    private MethodSpec createGetProviderMethod() {
        TypeVariableName typeVar = TypeVariableName.get("T");
        ParameterizedTypeName classType = ParameterizedTypeName.get(
                ClassName.get(Class.class),
                typeVar
        );
        ParameterizedTypeName providerType = ParameterizedTypeName.get(
                ClassName.get("io.tiko", "Provider"),
                typeVar
        );

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
        ParameterizedTypeName classType = ParameterizedTypeName.get(
                ClassName.get(Class.class),
                typeVar
        );
        ParameterizedTypeName providerType = ParameterizedTypeName.get(
                ClassName.get("io.tiko", "Provider"),
                typeVar
        );

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
                .createResource(
                        javax.tools.StandardLocation.CLASS_OUTPUT,
                        "",
                        "META-INF/tiko/container.properties"
                )
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
                .createResource(
                        javax.tools.StandardLocation.CLASS_OUTPUT,
                        "",
                        "META-INF/tiko/components.txt"
                )
                .openWriter()) {

            for (ComponentModel component : context.getActiveComponents()) {
                writer.write(component.getTypeElement().getQualifiedName().toString());
                writer.write("\n");
            }
        }
    }
}
