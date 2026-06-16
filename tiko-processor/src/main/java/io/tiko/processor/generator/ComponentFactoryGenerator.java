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

    /**
     * Container class name the factory currently being emitted is bound to. Set per
     * {@link #generate(ComponentModel, String, String)} call so the main container and any
     * standalone test container can each have their own factory set with a typed
     * container parameter. Defaults to {@code context.getContainerClassName()} for the
     * one-arg legacy overload.
     */
    private String currentContainerClassName;

    public ComponentFactoryGenerator(ProcessorContext context) {
        this.context = context;
    }

    /**
     * Generates a factory class for the given component bound to the main container.
     * Equivalent to {@link #generate(ComponentModel, String, String)} with
     * {@code containerClassName = context.getContainerClassName()} and no factory prefix.
     */
    public void generate(ComponentModel component) throws IOException {
        generate(component, context.getContainerClassName(), "");
    }

    /**
     * Generates a factory class for the given component bound to {@code containerClassName}.
     * When emitting a standalone test container, a non-empty {@code factoryClassPrefix}
     * (e.g. {@code "Test_"}) avoids name collision with the main container's factory of
     * the same component.
     */
    public void generate(ComponentModel component, String containerClassName, String factoryClassPrefix)
            throws IOException {
        this.currentContainerClassName = containerClassName;
        String factoryClassName = factoryClassPrefix + component.getClassName() + "Factory";

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
                        ClassName.get(GENERATED_PACKAGE, currentContainerClassName),
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
                .addParameter(ClassName.get(GENERATED_PACKAGE, currentContainerClassName), "container")
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
                // Provider<T>'s lambda consults the override at call time so overrides
                // registered after Provider construction still take effect, and so the
                // resolution key is the inner type T (not Provider<T>). resolveOverride
                // does one map lookup and routes the cast through the class token (#309).
                TypeName providerType = TypeName.get(dependency.getType());
                TypeName innerType = TypeName.get(dependency.getUnwrappedType().orElseThrow());
                String existing = generateContainerGetCall(dependency, component);
                if (dependency.getQualifier().isPresent() && !dependency.isPicked()) {
                    String qualifier = dependency.getQualifier().orElseThrow();
                    methodBuilder.addStatement(
                            "$1T $2L = () -> container.options().resolveOverride($3T.class, $4S, () -> $5L)",
                            providerType,
                            paramName,
                            innerType,
                            qualifier,
                            existing);
                } else {
                    methodBuilder.addStatement(
                            "$1T $2L = () -> container.options().resolveOverride($3T.class, () -> $4L)",
                            providerType,
                            paramName,
                            innerType,
                            existing);
                }
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
                // Direct dependency resolution. Wrap with an override-aware lookup so a
                // TikoOptions.override(DeclaredType.class, ...) registered by tests applies
                // at the injection site, regardless of which concrete @Component provides
                // the type. The override key is the parameter's declared type — interface
                // when the consumer injects by interface, concrete class otherwise (#128).
                // resolveOverride does one map lookup and routes the cast through the
                // class token (#309); the production getter only runs on the fallback path.
                TypeName declaredType = TypeName.get(dependency.getType());
                String existing = generateContainerGetCall(dependency, component);
                if (dependency.getQualifier().isPresent() && !dependency.isPicked()) {
                    String qualifier = dependency.getQualifier().orElseThrow();
                    methodBuilder.addStatement(
                            "$1T $2L = container.options().resolveOverride($1T.class, $3S, () -> $4L)",
                            declaredType,
                            paramName,
                            qualifier,
                            existing);
                } else {
                    methodBuilder.addStatement(
                            "$1T $2L = container.options().resolveOverride($1T.class, () -> $3L)",
                            declaredType,
                            paramName,
                            existing);
                }
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
        // EventBus is a built-in, container-provided dependency (#314): resolve it from the
        // container rather than looking up a user bean. The Provider<EventBus> case is wrapped
        // into a lambda by the caller, exactly like any other direct provider.
        if (dependency.isEventBus()) {
            return "container.getEventBus()";
        }

        String typeName = dependency.isProvider()
                ? dependency.getUnwrappedType().orElseThrow().toString()
                : dependency.getTypeName();

        // @Pick: identity is the picked impl class. When @Named is also present, the
        // qualifier narrows further — the lookup goes through the composite key
        // (impl#name) so the right named provider is selected. PickValidator guarantees
        // there is exactly one match. Factories are addressed by their unique
        // produce_<id>() getter; components have one getter per class regardless of
        // any @Component(name=...) metadata.
        if (dependency.isPicked()) {
            String pickedFqn = dependency.getPickedTypeName().orElseThrow();
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
            if (dependency.getQualifier().isPresent()) {
                // Named lookup routes through the typed get(Class, name) dispatcher — the per-class
                // getter is no-arg and cannot take a qualifier (#242). The declared type keys the
                // lookup, exactly like the container.get(Type.class, name) runtime API.
                String qualifier = io.tiko.processor.util.CodeLiterals.javaString(
                        dependency.getQualifier().orElseThrow());
                return String.format("container.get(%s.class, \"%s\")", typeName, qualifier);
            }
            return String.format("container.get%s()", component.getClassName());
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
            // Fallback to the requested type name. Same #242 rule: a qualified lookup goes through
            // the typed dispatcher, an unqualified one through the no-arg per-class getter.
            if (dependency.getQualifier().isPresent()) {
                String qualifier = io.tiko.processor.util.CodeLiterals.javaString(
                        dependency.getQualifier().orElseThrow());
                return String.format("container.get(%s.class, \"%s\")", typeName, qualifier);
            }
            return String.format("container.get%s()", getSimpleClassName(typeName));
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
            case EVENT -> 1;
            case PROTOTYPE -> 2;
        };
    }
}
