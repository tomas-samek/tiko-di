package io.tiko.processor.generator;

import com.palantir.javapoet.*;
import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.util.GeneratorAnnotations;
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
                .addAnnotation(GeneratorAnnotations.generatedBy(ComponentFactoryGenerator.class))
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
                        generateContainerGetCall(dependency, component));
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
                        generateContainerGetCall(dependency, component));
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

        // Call @PostConstruct methods. Failures route through ErrorHandler before re-throwing
        // so observability sees the lifecycle hook failure; container start / get() still halts
        // as before (PostConstructFailure is emitted, then the original throwable propagates).
        for (ExecutableElement postConstruct : component.getPostConstructMethods()) {
            methodBuilder.beginControlFlow("try");
            methodBuilder.addStatement("instance.$L()", postConstruct.getSimpleName());
            methodBuilder.nextControlFlow("catch ($T __t)", Exception.class);
            methodBuilder.addStatement(
                    "container.getErrorHandler().onError(new $T($T.class, __t))",
                    ClassName.get("io.tiko", "PostConstructFailure"),
                    componentClass);
            methodBuilder.addStatement(
                    "throw $T.<$T>sneakyThrow(__t)",
                    ClassName.get("io.tiko.runtime", "Unchecked"),
                    RuntimeException.class);
            methodBuilder.endControlFlow();
        }

        methodBuilder.addStatement("return instance");

        return methodBuilder.build();
    }

    /**
     * Generates a call to container.getXxx() for the given dependency.
     *
     * @param dependency the dependency to resolve
     * @param consumer the consuming component — needed for cross-scope proxy decisions
     *     when the provider is a {@code @Produces} factory output (a longer-lived consumer
     *     of a REQUEST/EVENT-scoped factory output gets a generated proxy instead of a
     *     direct {@code container.produce_*()} call so the value resolves per-call to the
     *     current scope).
     */
    private String generateContainerGetCall(DependencyModel dependency, ComponentModel consumer) {
        String typeName =
                dependency.isProvider() ? dependency.getUnwrappedType().get().toString() : dependency.getTypeName();

        // @Pick: identity is the picked impl class. When @Named is also present, the
        // qualifier narrows further — the lookup goes through the composite key
        // (impl#name) so the right named provider is selected. PickValidator guarantees
        // there is exactly one match. Factories are addressed by their unique
        // produce_<id>() getter; components have one getter per class regardless of
        // any @Component(name=...) metadata.
        if (dependency.isPicked()) {
            String pickedFqn = dependency.getPickedTypeName().get();
            Object pickedProvider = dependency
                    .getQualifier()
                    .map(q ->
                            context.findComponentOrFactory(pickedFqn + "#" + q).orElse(null))
                    .orElseGet(() -> context.findByImplClass(pickedFqn).orElse(null));
            if (pickedProvider instanceof io.tiko.processor.model.FactoryMethodModel pickedFactory) {
                return String.format("container.produce_%s()", pickedFactory.getFactoryIdentifier());
            }
            String methodName = (pickedProvider instanceof ComponentModel pickedComponent)
                    ? "get" + pickedComponent.getClassName()
                    : "get" + getSimpleClassName(pickedFqn);
            return String.format("container.%s()", methodName);
        }

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
            // Factories are addressed by their unique produce_<id>() getter regardless
            // of qualifier — the qualifier already disambiguated which factory the
            // dependency resolves to during processor lookup. Mirrors the @Pick branch
            // above and the corresponding logic in ContainerGenerator's dependency wiring.
            //
            // Cross-scope: when the consumer outlives the factory's scope AND the factory
            // produces an interface, instantiate the generated proxy so each method call
            // resolves to the current scope's value. Without the proxy, a SINGLETON consumer
            // would capture the first scope's instance and reuse a stale (often closed)
            // reference on subsequent scope frames.
            if (factory.requiresProxy() && scopeLevel(consumer.getScope()) < scopeLevel(factory.getScope())) {
                return String.format("new %sProxy(container)", factory.getFactoryIdentifier());
            }
            return String.format("container.produce_%s()", factory.getFactoryIdentifier());
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

    /**
     * Scope ordinal where lower = longer-lived. Used to decide whether a factory output
     * needs a per-call-resolving proxy when injected into a longer-lived consumer.
     */
    private static int scopeLevel(io.tiko.Scope scope) {
        return switch (scope) {
            case SINGLETON -> 0;
            case REQUEST -> 1;
            case EVENT -> 2;
            case PROTOTYPE -> 3;
        };
    }
}
