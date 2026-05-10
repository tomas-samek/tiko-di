package io.tiko.processor.generator;

import com.palantir.javapoet.*;
import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.util.ProcessorContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

/**
 * Generates factory classes for @Component annotated classes.
 *
 * Example generated code:
 * <pre>
 * public final class MessageServiceFactory {
 *     private final TikoContainerImpl container;
 *
 *     public MessageServiceFactory(TikoContainerImpl container) {
 *         this.container = container;
 *     }
 *
 *     public MessageService create() {
 *         MessageRepository repository = container.getMessageRepository();
 *         MessageService instance = new MessageService(repository);
 *         instance.initialize(); // @PostConstruct
 *         return instance;
 *     }
 * }
 * </pre>
 */
public final class ComponentFactoryGenerator {

    private static final String GENERATED_PACKAGE = "io.tiko.generated";

    private final ProcessorContext context;

    public ComponentFactoryGenerator(ProcessorContext context) {
        this.context = context;
    }

    /**
     * Generates a factory class for the given component.
     */
    public void generate(ComponentModel component) throws IOException {
        String factoryClassName = component.getClassName() + "Factory";

        TypeSpec factoryClass = TypeSpec.classBuilder(factoryClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addField(createContainerField())
                .addMethod(createConstructor())
                .addMethod(createFactoryMethod(component))
                .build();

        JavaFile javaFile = JavaFile.builder(GENERATED_PACKAGE, factoryClass).build();

        javaFile.writeTo(context.getFiler());
    }

    /**
     * Creates the container field: private final TikoContainerImpl container;
     */
    private FieldSpec createContainerField() {
        return FieldSpec.builder(
                        ClassName.get(GENERATED_PACKAGE, context.getContainerClassName()),
                        "container",
                        Modifier.PRIVATE,
                        Modifier.FINAL)
                .build();
    }

    /**
     * Creates the constructor that takes the container.
     */
    private MethodSpec createConstructor() {
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ClassName.get(GENERATED_PACKAGE, context.getContainerClassName()), "container")
                .addStatement("this.container = container")
                .build();
    }

    /**
     * Creates the factory method that instantiates the component.
     */
    private MethodSpec createFactoryMethod(ComponentModel component) {
        TypeElement typeElement = component.getTypeElement();
        ClassName componentClass = ClassName.get(typeElement);

        MethodSpec.Builder methodBuilder =
                MethodSpec.methodBuilder("create").addModifiers(Modifier.PUBLIC).returns(componentClass);

        // Resolve dependencies
        List<String> parameterNames = new ArrayList<>();
        for (DependencyModel dependency : component.getDependencies()) {
            String paramName = dependency.getParameterName();
            parameterNames.add(paramName);

            if (dependency.isProvider()) {
                // For Provider<T>, create a lambda that calls container
                methodBuilder.addStatement(
                        "$T $L = () -> $L",
                        TypeName.get(dependency.getType()),
                        paramName,
                        generateContainerGetCall(dependency));
            } else if (dependency.isPicker()) {
                // Picker<T> is constructed inline via the generic ContainerPicker<T> impl —
                // one runtime class services every Picker injection point regardless of T.
                TypeName baseType = TypeName.get(dependency.getUnwrappedType().orElseThrow());
                methodBuilder.addStatement(
                        "$T $L = new $T<>(container, $T.class)",
                        TypeName.get(dependency.getType()),
                        paramName,
                        ClassName.get("io.tiko.runtime", "ContainerPicker"),
                        baseType);
            } else {
                // Direct dependency resolution
                methodBuilder.addStatement(
                        "$T $L = $L",
                        TypeName.get(dependency.getType()),
                        paramName,
                        generateContainerGetCall(dependency));
            }
        }

        // Create instance — via static @Produces factory method if present, else constructor.
        Optional<ExecutableElement> staticFactory = component.getStaticFactoryMethod();
        String params = String.join(", ", parameterNames);
        if (staticFactory.isPresent()) {
            String factoryMethodName = staticFactory.get().getSimpleName().toString();
            methodBuilder.addStatement(
                    "$T instance = $T.$L($L)", componentClass, componentClass, factoryMethodName, params);
        } else if (parameterNames.isEmpty()) {
            methodBuilder.addStatement("$T instance = new $T()", componentClass, componentClass);
        } else {
            methodBuilder.addStatement("$T instance = new $T($L)", componentClass, componentClass, params);
        }

        // Call @PostConstruct methods
        for (ExecutableElement postConstruct : component.getPostConstructMethods()) {
            methodBuilder.addStatement("instance.$L()", postConstruct.getSimpleName());
        }

        methodBuilder.addStatement("return instance");

        return methodBuilder.build();
    }

    /**
     * Generates a call to container.getXxx() for the given dependency.
     */
    private String generateContainerGetCall(DependencyModel dependency) {
        String typeName =
                dependency.isProvider() ? dependency.getUnwrappedType().get().toString() : dependency.getTypeName();

        // Find the actual component or factory that provides this dependency
        String dependencyKey = dependency.getDependencyKey();
        Object provider = context.findComponentOrFactory(dependencyKey).orElse(null);

        if (provider instanceof ComponentModel component) {
            // Use the actual component class name, not the interface
            String className = component.getClassName();
            String methodName = "get" + className;
            if (dependency.getQualifier().isPresent()) {
                String qualifier = dependency.getQualifier().get();
                return String.format("container.%s(\"%s\")", methodName, qualifier);
            } else {
                return String.format("container.%s()", methodName);
            }
        } else if (provider instanceof io.tiko.processor.model.FactoryMethodModel factory) {
            // Use the factory's return type
            String className = getSimpleClassName(factory.getReturnTypeName());
            String methodName = "get" + className;
            if (dependency.getQualifier().isPresent()) {
                String qualifier = dependency.getQualifier().get();
                return String.format("container.%s(\"%s\")", methodName, qualifier);
            } else {
                return String.format("container.%s()", methodName);
            }
        } else if (provider instanceof io.tiko.processor.config.ConfigurationModel) {
            // @Configuration records are stored in configSingletons and retrieved via container.get(Class)
            return String.format("container.get(%s.class)", typeName);
        } else {
            // Fallback to the requested type name
            String className = getSimpleClassName(typeName);
            String methodName = "get" + className;
            if (dependency.getQualifier().isPresent()) {
                String qualifier = dependency.getQualifier().get();
                return String.format("container.%s(\"%s\")", methodName, qualifier);
            } else {
                return String.format("container.%s()", methodName);
            }
        }
    }

    /**
     * Extracts simple class name from fully qualified name.
     * e.g., "io.tiko.examples.MessageService" -> "MessageService"
     */
    private String getSimpleClassName(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot >= 0 ? qualifiedName.substring(lastDot + 1) : qualifiedName;
    }
}
