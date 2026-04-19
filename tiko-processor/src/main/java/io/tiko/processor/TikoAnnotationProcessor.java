package io.tiko.processor;

import com.google.auto.service.AutoService;
import io.tiko.Scope;
import io.tiko.annotations.*;
import io.tiko.processor.generator.*;
import io.tiko.processor.model.*;
import io.tiko.processor.util.ProcessorContext;
import io.tiko.processor.util.TypeUtil;
import io.tiko.processor.validation.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.*;

/**
 * Main annotation processor for Tiko DI.
 *
 * Processing stages:
 * 1. Scan and collect @Component, @Produces, @EventHandler annotations
 * 2. Build internal models (ComponentModel, FactoryMethodModel, EventHandlerModel)
 * 3. Validate dependency graph, circular dependencies, scope rules
 * 4. Generate code: factories, proxies, event registry, container
 */
@AutoService(Processor.class)
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public final class TikoAnnotationProcessor extends AbstractProcessor {

    private ProcessorContext context;
    private TypeUtil typeUtil;
    private boolean processed = false;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);

        // Read active profiles from compiler options (e.g., -Atiko.profiles=dev,test)
        List<String> activeProfiles = parseActiveProfiles(processingEnv);

        this.context = new ProcessorContext(processingEnv, activeProfiles);
        this.typeUtil = new TypeUtil(
                processingEnv.getElementUtils(),
                processingEnv.getTypeUtils()
        );
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(
                Component.class.getCanonicalName(),
                Produces.class.getCanonicalName(),
                EventHandler.class.getCanonicalName()
        );
    }

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of("tiko.profiles");
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // Skip if already processed
        if (processed) {
            return false;
        }

        // Collect annotations in all rounds
        if (!roundEnv.processingOver()) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.NOTE,
                    "Tiko DI: Processing round, collecting annotations..."
            );
            collectComponents(roundEnv);
            collectFactoryMethods(roundEnv);
            collectEventHandlers(roundEnv);

            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.NOTE,
                    "Tiko DI: Collected " + context.getComponents().size() + " components, " +
                            context.getFactoryMethods().size() + " factories, " +
                            context.getEventHandlers().size() + " event handlers"
            );
            return true; // Claim the annotations
        }

        // In final round, validate and generate code
        processed = true;

        processingEnv.getMessager().printMessage(
                Diagnostic.Kind.NOTE,
                "Tiko DI: Final round - starting code generation..."
        );

        try {
            // Check if we have anything to process
            if (context.getComponents().isEmpty() && context.getFactoryMethods().isEmpty()) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.WARNING,
                        "Tiko DI: No components or factories found to process!"
                );
                return false;
            }

            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.NOTE,
                    "Tiko DI: Found " + context.getComponents().size() + " components to process"
            );

            // Stage 2: Validate
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.NOTE,
                    "Tiko DI: Starting validation..."
            );
            if (!validate()) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "Tiko DI: Validation failed!"
                );
                return false;
            }
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.NOTE,
                    "Tiko DI: Validation passed"
            );

            // Stage 3: Generate code
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.NOTE,
                    "Tiko DI: Starting code generation..."
            );
            generate();

            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.NOTE,
                    "Tiko DI: Successfully generated container with " +
                            context.getComponents().size() + " components, " +
                            context.getFactoryMethods().size() + " factories, " +
                            context.getEventHandlers().size() + " event handlers"
            );

        } catch (Exception e) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Tiko DI processing failed: " + e.getMessage() + "\n" +
                            Arrays.toString(e.getStackTrace())
            );
            e.printStackTrace();
            return false;
        }

        return true;
    }

    /**
     * Collects all @Component annotated classes.
     */
    private void collectComponents(RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(Component.class)) {
            if (!(element instanceof TypeElement typeElement)) {
                context.getErrorReporter().error(element, "@Component can only be applied to classes");
                continue;
            }

            ComponentModel component = buildComponentModel(typeElement);
            if (component != null) {
                context.registerComponent(component);
            }
        }
    }

    /**
     * Builds a ComponentModel from a @Component annotated class.
     */
    private ComponentModel buildComponentModel(TypeElement typeElement) {
        Component annotation = typeElement.getAnnotation(Component.class);

        // Find @Inject constructor
        ExecutableElement constructor = findInjectConstructor(typeElement);
        if (constructor == null) {
            context.getErrorReporter().error(
                    typeElement,
                    "@Component must have exactly one constructor annotated with @Inject or a single constructor",
                    "Add @Inject annotation to a constructor",
                    "Ensure only one constructor exists if not using @Inject"
            );
            return null;
        }

        // Build dependencies from constructor parameters
        List<DependencyModel> dependencies = buildDependencies(constructor);

        // Find @PostConstruct and @PreDestroy methods
        List<ExecutableElement> postConstructMethods = findAnnotatedMethods(typeElement, PostConstruct.class);
        List<ExecutableElement> preDestroyMethods = findAnnotatedMethods(typeElement, PreDestroy.class);

        // Check if component implements an interface (for proxy generation)
        Optional<TypeMirror> implementedInterface = typeUtil.getFirstInterface(typeElement);

        ComponentModel.Builder builder = ComponentModel.builder()
                .typeElement(typeElement)
                .packageName(typeUtil.getPackageName(typeElement))
                .className(typeElement.getSimpleName().toString())
                .qualifiedName(typeElement.getQualifiedName().toString())
                .scope(annotation.scope())
                .name(annotation.name())
                .profiles(Arrays.asList(annotation.profiles()))
                .dependencies(dependencies)
                .constructor(constructor)
                .postConstructMethods(postConstructMethods)
                .preDestroyMethods(preDestroyMethods);

        implementedInterface.ifPresent(builder::implementedInterface);

        // Determine if proxy is needed (REQUEST/EVENT scope with interface)
        boolean needsProxy = (annotation.scope() == Scope.REQUEST || annotation.scope() == Scope.EVENT) &&
                             implementedInterface.isPresent();
        builder.requiresProxy(needsProxy);

        return builder.build();
    }

    /**
     * Finds the constructor to use for injection.
     */
    private ExecutableElement findInjectConstructor(TypeElement typeElement) {
        List<ExecutableElement> constructors = new ArrayList<>();
        ExecutableElement injectConstructor = null;

        for (Element element : typeElement.getEnclosedElements()) {
            if (element.getKind() == ElementKind.CONSTRUCTOR) {
                ExecutableElement constructor = (ExecutableElement) element;
                constructors.add(constructor);

                if (constructor.getAnnotation(Inject.class) != null) {
                    if (injectConstructor != null) {
                        context.getErrorReporter().error(
                                typeElement,
                                "Multiple constructors annotated with @Inject"
                        );
                        return null;
                    }
                    injectConstructor = constructor;
                }
            }
        }

        // If @Inject found, use it
        if (injectConstructor != null) {
            return injectConstructor;
        }

        // Otherwise, require exactly one constructor
        if (constructors.size() == 1) {
            return constructors.get(0);
        }

        return null;
    }

    /**
     * Builds dependency models from method/constructor parameters.
     */
    private List<DependencyModel> buildDependencies(ExecutableElement executable) {
        List<DependencyModel> dependencies = new ArrayList<>();

        for (VariableElement parameter : executable.getParameters()) {
            TypeMirror paramType = parameter.asType();
            String typeName = typeUtil.getQualifiedName(paramType);

            // Check if it's Provider<T>
            boolean isProvider = typeUtil.isProvider(paramType);
            TypeMirror unwrappedType = null;

            if (isProvider) {
                unwrappedType = typeUtil.unwrapProvider(paramType).orElse(null);
                if (unwrappedType != null) {
                    typeName = typeUtil.getQualifiedName(unwrappedType);
                }
            }

            // Check for @Named qualifier
            String qualifier = "";
            Named namedAnnotation = parameter.getAnnotation(Named.class);
            if (namedAnnotation != null) {
                qualifier = namedAnnotation.value();
            }

            DependencyModel dependency = DependencyModel.builder()
                    .parameter(parameter)
                    .type(paramType)
                    .typeName(typeName)
                    .qualifier(qualifier)
                    .isProvider(isProvider)
                    .unwrappedType(unwrappedType)
                    .build();

            dependencies.add(dependency);
        }

        return dependencies;
    }

    /**
     * Finds all methods annotated with the given annotation.
     */
    private List<ExecutableElement> findAnnotatedMethods(TypeElement typeElement, Class<?> annotationClass) {
        List<ExecutableElement> methods = new ArrayList<>();

        for (Element element : typeElement.getEnclosedElements()) {
            if (element.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) element;
                if (method.getAnnotation(annotationClass.asSubclass(java.lang.annotation.Annotation.class)) != null) {
                    methods.add(method);
                }
            }
        }

        return methods;
    }

    /**
     * Collects all @Produces factory methods.
     */
    private void collectFactoryMethods(RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(Produces.class)) {
            if (!(element instanceof ExecutableElement methodElement)) {
                context.getErrorReporter().error(element, "@Produces can only be applied to methods");
                continue;
            }

            FactoryMethodModel factory = buildFactoryMethodModel(methodElement);
            if (factory != null) {
                context.registerFactoryMethod(factory);
            }
        }
    }

    /**
     * Builds a FactoryMethodModel from a @Produces method.
     */
    private FactoryMethodModel buildFactoryMethodModel(ExecutableElement methodElement) {
        Produces annotation = methodElement.getAnnotation(Produces.class);
        TypeElement declaringClass = (TypeElement) methodElement.getEnclosingElement();

        TypeMirror returnType = methodElement.getReturnType();
        String returnTypeName = typeUtil.getQualifiedName(returnType);

        // Build dependencies from method parameters
        List<DependencyModel> dependencies = buildDependencies(methodElement);

        return FactoryMethodModel.builder()
                .methodElement(methodElement)
                .declaringClass(declaringClass)
                .methodName(methodElement.getSimpleName().toString())
                .returnType(returnType)
                .returnTypeName(returnTypeName)
                .scope(annotation.scope())
                .name(annotation.name())
                .profiles(Arrays.asList(annotation.profiles()))
                .dependencies(dependencies)
                .build();
    }

    /**
     * Collects all @EventHandler methods.
     */
    private void collectEventHandlers(RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(EventHandler.class)) {
            if (!(element instanceof ExecutableElement methodElement)) {
                context.getErrorReporter().error(element, "@EventHandler can only be applied to methods");
                continue;
            }

            EventHandlerModel handler = buildEventHandlerModel(methodElement);
            if (handler != null) {
                context.registerEventHandler(handler);
            }
        }
    }

    /**
     * Builds an EventHandlerModel from an @EventHandler method.
     */
    private EventHandlerModel buildEventHandlerModel(ExecutableElement methodElement) {
        EventHandler annotation = methodElement.getAnnotation(EventHandler.class);
        TypeElement declaringClass = (TypeElement) methodElement.getEnclosingElement();

        List<? extends VariableElement> parameters = methodElement.getParameters();
        if (parameters.isEmpty()) {
            context.getErrorReporter().error(
                    methodElement,
                    "@EventHandler method must have at least one parameter (the event)"
            );
            return null;
        }

        // First parameter is the event type
        TypeMirror eventType = parameters.get(0).asType();
        String eventTypeName = typeUtil.getQualifiedName(eventType);

        // Check if second parameter is Event<?> wrapper
        boolean hasEventWrapper = parameters.size() > 1 &&
                typeUtil.getQualifiedName(parameters.get(1).asType()).equals("io.tiko.Event");

        return EventHandlerModel.builder()
                .methodElement(methodElement)
                .declaringClass(declaringClass)
                .methodName(methodElement.getSimpleName().toString())
                .eventType(eventType)
                .eventTypeName(eventTypeName)
                .async(annotation.async())
                .hasEventWrapper(hasEventWrapper)
                .build();
    }

    /**
     * Validates the dependency graph.
     */
    private boolean validate() {
        boolean valid = true;

        // Validate scope rules
        ScopeValidator scopeValidator = new ScopeValidator(context);
        for (ComponentModel component : context.getActiveComponents()) {
            if (!scopeValidator.validate(component)) {
                valid = false;
            }
        }
        for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
            if (!scopeValidator.validate(factory)) {
                valid = false;
            }
        }

        // Validate dependencies exist
        DependencyGraphValidator graphValidator = new DependencyGraphValidator(context);
        if (!graphValidator.validate()) {
            valid = false;
        }

        // Detect circular dependencies
        CircularDependencyDetector circularDetector = new CircularDependencyDetector(context);
        if (!circularDetector.validate()) {
            valid = false;
        }

        return valid;
    }

    /**
     * Generates all code.
     */
    private void generate() throws Exception {
        // Compute container class name first (needed by all generators)
        String containerClassName = computeContainerClassName();
        context.setContainerClassName(containerClassName);

        // Generate factory for each component
        ComponentFactoryGenerator factoryGenerator = new ComponentFactoryGenerator(context);
        for (ComponentModel component : context.getActiveComponents()) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.NOTE,
                    "Tiko DI: Generating factory for " + component.getClassName()
            );
            factoryGenerator.generate(component);
        }

        // Generate proxies for cross-scope injection
        ProxyGenerator proxyGenerator = new ProxyGenerator(context);
        for (ComponentModel component : context.getActiveComponents()) {
            if (component.requiresProxy()) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.NOTE,
                        "Tiko DI: Generating proxy for " + component.getClassName()
                );
            }
            proxyGenerator.generate(component);
        }

        // Generate event registry
        processingEnv.getMessager().printMessage(
                Diagnostic.Kind.NOTE,
                "Tiko DI: Generating event registry..."
        );
        EventRegistryGenerator eventRegistryGenerator = new EventRegistryGenerator(context);
        eventRegistryGenerator.generate();

        // Generate container (must be last)
        processingEnv.getMessager().printMessage(
                Diagnostic.Kind.NOTE,
                "Tiko DI: Generating container..."
        );
        ContainerGenerator containerGenerator = new ContainerGenerator(context);
        containerGenerator.generate();
    }

    /**
     * Parses active profiles from compiler options.
     */
    private List<String> parseActiveProfiles(ProcessingEnvironment processingEnv) {
        String profilesOption = processingEnv.getOptions().get("tiko.profiles");
        if (profilesOption == null || profilesOption.isBlank()) {
            return List.of();
        }
        return Arrays.asList(profilesOption.split(","));
    }

    /**
     * Computes a unique container class name based on component keys.
     * This ensures different modules generate different container class names.
     */
    private String computeContainerClassName() {
        // Create deterministic ID based on component names
        List<String> componentKeys = context.getActiveComponents().stream()
                .map(c -> c.getComponentKey())
                .sorted()
                .toList();

        int hash = Objects.hash(componentKeys.toArray());
        // Convert to positive hex string
        String suffix = Integer.toHexString(hash & 0x7FFFFFFF);

        return "TikoContainerImpl_" + suffix;
    }
}
