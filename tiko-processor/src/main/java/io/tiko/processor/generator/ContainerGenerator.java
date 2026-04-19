package io.tiko.processor.generator;

import com.palantir.javapoet.*;
import io.tiko.Container;
import io.tiko.EventBus;
import io.tiko.Scope;
import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.ProcessorContext;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.*;
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

        // TODO: Add getters for factory methods

        return methods;
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

    /**
     * Creates runInRequestScope method.
     */
    private MethodSpec createRunInRequestScopeMethod() {
        return MethodSpec.methodBuilder("runInRequestScope")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addParameter(Runnable.class, "task")
                .beginControlFlow("try")
                .addStatement("task.run()")
                .nextControlFlow("finally")
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
                .beginControlFlow("try")
                .addStatement("return supplier.get()")
                .nextControlFlow("finally")
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
                .beginControlFlow("try")
                .addStatement("task.run()")
                .nextControlFlow("finally")
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
                .beginControlFlow("try")
                .addStatement("return supplier.get()")
                .nextControlFlow("finally")
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

        return method.build();
    }

    /**
     * Creates shutdown method that calls @PreDestroy on all singletons.
     */
    private MethodSpec createShutdownMethod() {
        MethodSpec.Builder method = MethodSpec.methodBuilder("shutdown")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class);

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

        // Generate if-else chain for each component
        boolean first = true;
        for (ComponentModel component : context.getActiveComponents()) {
            TypeName componentType = ClassName.get(component.getTypeElement());
            String getterName = "get" + component.getClassName();

            if (first) {
                method.beginControlFlow("if (type == $T.class)", componentType);
                first = false;
            } else {
                method.nextControlFlow("else if (type == $T.class)", componentType);
            }
            method.addStatement("return (T) $L()", getterName);
        }

        if (!context.getActiveComponents().isEmpty()) {
            method.endControlFlow();
        }

        // If no match found, throw exception
        method.addStatement("throw new $T($S + type.getName())",
            IllegalArgumentException.class,
            "No component found for type: ");

        return method.build();
    }

    /**
     * Creates get(Class, String) method.
     */
    private MethodSpec createGetWithNameMethod() {
        TypeVariableName typeVar = TypeVariableName.get("T");
        ParameterizedTypeName classType = ParameterizedTypeName.get(
                ClassName.get(Class.class),
                typeVar
        );

        return MethodSpec.methodBuilder("get")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class)
                .addTypeVariable(typeVar)
                .addParameter(classType, "type")
                .addParameter(String.class, "name")
                .returns(typeVar)
                .addStatement("throw new $T($S)", UnsupportedOperationException.class,
                    "get(Class, String) not yet implemented")
                .build();
    }

    /**
     * Creates getProvider(Class) method.
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
                .addStatement("throw new $T($S)", UnsupportedOperationException.class,
                    "getProvider(Class) not yet implemented")
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
                .addStatement("throw new $T($S)", UnsupportedOperationException.class,
                    "getProvider(Class, String) not yet implemented")
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
