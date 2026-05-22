package io.tiko.processor;

import com.google.auto.service.AutoService;
import io.tiko.EventTriggerGuard;
import io.tiko.Scope;
import io.tiko.annotations.*;
import io.tiko.processor.generator.*;
import io.tiko.processor.model.*;
import io.tiko.processor.util.ProcessorContext;
import io.tiko.processor.util.TypeUtil;
import io.tiko.processor.validation.*;
import java.util.*;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.MirroredTypesException;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

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
@SupportedSourceVersion(SourceVersion.RELEASE_21)
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
        this.typeUtil = new TypeUtil(processingEnv.getElementUtils(), processingEnv.getTypeUtils());
    }

    /**
     * FQN of the test-classpath shadow annotation. Referenced as a string to keep
     * tiko-processor free of any compile-time dependency on the optional test
     * support module that defines it; the annotation is detected reflectively at
     * processing time and silently ignored when absent.
     */
    private static final String TEST_COMPONENT_FQN = "io.tiko.test.TestComponent";

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(
                Component.class.getCanonicalName(),
                Produces.class.getCanonicalName(),
                EventHandler.class.getCanonicalName(),
                Configuration.class.getCanonicalName(),
                TEST_COMPONENT_FQN);
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
            processingEnv
                    .getMessager()
                    .printMessage(Diagnostic.Kind.NOTE, "Tiko DI: Processing round, collecting annotations...");
            collectComponents(roundEnv);
            collectFactoryMethods(roundEnv);
            collectEventHandlers(roundEnv);
            collectConfigurations(roundEnv);

            processingEnv
                    .getMessager()
                    .printMessage(
                            Diagnostic.Kind.NOTE,
                            "Tiko DI: Collected " + context.getComponents().size() + " components, "
                                    + context.getFactoryMethods().size()
                                    + " factories, "
                                    + context.getEventHandlers().size()
                                    + " event handlers");
            return true; // Claim the annotations
        }

        // In final round, validate and generate code
        processed = true;

        processingEnv
                .getMessager()
                .printMessage(Diagnostic.Kind.NOTE, "Tiko DI: Final round - starting code generation...");

        try {
            // Check if we have anything to process
            if (context.getComponents().isEmpty()
                    && context.getFactoryMethods().isEmpty()
                    && context.getConfigurations().isEmpty()) {
                processingEnv
                        .getMessager()
                        .printMessage(
                                Diagnostic.Kind.WARNING,
                                "Tiko DI: No components, factories, or configurations found to process!");
                return false;
            }

            processingEnv
                    .getMessager()
                    .printMessage(
                            Diagnostic.Kind.NOTE,
                            "Tiko DI: Found " + context.getComponents().size() + " components to process");

            // Stage 2: Validate
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Tiko DI: Starting validation...");
            if (!validate()) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Tiko DI: Validation failed!");
                return false;
            }
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Tiko DI: Validation passed");

            // Stage 3: Generate code
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Tiko DI: Starting code generation...");
            generate();

            processingEnv
                    .getMessager()
                    .printMessage(
                            Diagnostic.Kind.NOTE,
                            "Tiko DI: Successfully generated container with "
                                    + context.getComponents().size()
                                    + " components, "
                                    + context.getFactoryMethods().size()
                                    + " factories, "
                                    + context.getEventHandlers().size()
                                    + " event handlers");

        } catch (Exception e) {
            processingEnv
                    .getMessager()
                    .printMessage(Diagnostic.Kind.ERROR, "Tiko DI processing failed:\n" + formatStackTrace(e));
            return false;
        }

        return true;
    }

    /**
     * Collects all @Component annotated classes, plus any @TestComponent classes when
     * the optional test-classpath annotation is present on the processor's classpath.
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

        // Test-classpath shadow annotation. Resolved by FQN so the processor stays
        // dependency-free of the optional support module and tolerates its absence in
        // production builds (lookup returns null → loop is skipped silently).
        TypeElement testComponentType = processingEnv.getElementUtils().getTypeElement(TEST_COMPONENT_FQN);
        if (testComponentType != null) {
            for (Element element : roundEnv.getElementsAnnotatedWith(testComponentType)) {
                if (!(element instanceof TypeElement typeElement)) {
                    context.getErrorReporter().error(element, "@TestComponent can only be applied to classes");
                    continue;
                }
                ComponentModel component = buildTestComponentModel(typeElement);
                if (component != null) {
                    context.registerComponent(component);
                }
            }
        }
    }

    /**
     * Builds a ComponentModel from a @Component annotated class.
     */
    private ComponentModel buildComponentModel(TypeElement typeElement) {
        Component annotation = typeElement.getAnnotation(Component.class);

        // Read @Component(expose = {...}) — uses the MirroredTypesException dance because
        // Class<?>[] annotation values are not directly accessible at processor time.
        List<TypeMirror> exposeTypes;
        try {
            annotation.expose();
            exposeTypes = List.of();
        } catch (MirroredTypesException mte) {
            exposeTypes = List.copyOf(mte.getTypeMirrors());
        }

        return buildComponentModel(
                typeElement,
                annotation.scope(),
                annotation.name(),
                Arrays.asList(annotation.profiles()),
                exposeTypes,
                annotation.exposeSelf(),
                false,
                "@Component");
    }

    /**
     * Builds a ComponentModel from a class carrying the test-classpath shadow annotation.
     * Reads {@code scope} and {@code name} via {@link AnnotationMirror} traversal (string
     * FQN match) so the processor avoids a compile-time dependency on the support module
     * that declares the annotation. The remaining ComponentModel fields use the shadow
     * annotation's documented defaults (no profile gating, permissive exposure).
     */
    private ComponentModel buildTestComponentModel(TypeElement typeElement) {
        AnnotationMirror mirror = findAnnotationMirror(typeElement, TEST_COMPONENT_FQN);
        if (mirror == null) {
            // Element claimed by roundEnv but mirror missing — defensive guard, should not happen.
            return null;
        }

        Scope scope = readEnumAttribute(mirror, "scope", Scope.class).orElse(Scope.SINGLETON);
        String name = readStringAttribute(mirror, "name").orElse("");

        java.util.Set<String> extraKeys = computeTestExtraKeys(typeElement, mirror);

        ComponentModel model =
                buildComponentModel(typeElement, scope, name, List.of(), List.of(), true, true, "@TestComponent");
        if (model == null) {
            return null;
        }
        // Defensive rebuild to carry testExtraKeys. Task 9 cleans this up by threading
        // extraKeys through the shared buildComponentModel helper.
        return ComponentModel.builder()
                .typeElement(model.getTypeElement())
                .packageName(model.getPackageName())
                .className(model.getClassName())
                .qualifiedName(model.getQualifiedName())
                .scope(model.getScope())
                .name(model.getName().orElse(""))
                .profiles(model.getProfiles())
                .dependencies(model.getDependencies())
                .constructor(model.getConstructor())
                .postConstructMethods(model.getPostConstructMethods())
                .preDestroyMethods(model.getPreDestroyMethods())
                .implementedInterface(model.getImplementedInterface().orElse(null))
                .requiresProxy(model.requiresProxy())
                .staticFactoryMethod(model.getStaticFactoryMethod().orElse(null))
                .autoCloseable(model.isAutoCloseable())
                .exposeTypes(model.getExposeTypes())
                .exposeSelf(model.isExposeSelf())
                .testComponent(true)
                .testExtraKeys(extraKeys)
                .build();
    }

    private java.util.Set<String> computeTestExtraKeys(TypeElement testClass, AnnotationMirror mirror) {
        java.util.Optional<TypeMirror> valueAttr = readClassAttribute(mirror, "value");
        if (valueAttr.isPresent() && !isVoid(valueAttr.get())) {
            return java.util.Set.of(valueAttr.get().toString());
        }
        // Implicit walk lands in Task 6.
        return java.util.Set.of();
    }

    private static boolean isVoid(TypeMirror tm) {
        return tm.toString().equals("java.lang.Void");
    }

    /**
     * Shared model assembly for both {@code @Component} and the test-classpath shadow
     * annotation. Caller supplies annotation-derived attributes; this method handles
     * constructor / static-factory selection, lifecycle method discovery, proxy
     * decision, and builder population.
     */
    private ComponentModel buildComponentModel(
            TypeElement typeElement,
            Scope scope,
            String name,
            List<String> profiles,
            List<TypeMirror> exposeTypes,
            boolean exposeSelf,
            boolean isTestComponent,
            String annotationLabel) {
        // Detect a self-@Produces method: a static @Produces method on this class
        // returning this class with the same qualifier name. When present, it acts
        // as the bean's instantiation strategy (replaces the constructor call).
        ExecutableElement staticFactoryMethod = findSelfStaticFactory(typeElement, name);

        ExecutableElement constructor;
        List<DependencyModel> dependencies;
        if (staticFactoryMethod != null) {
            // Static factory governs instantiation; constructor is optional.
            constructor = findInjectConstructor(typeElement); // may be null when private/multiple
            dependencies = buildDependencies(staticFactoryMethod);
        } else {
            // Standard path: require a usable constructor.
            constructor = findInjectConstructor(typeElement);
            if (constructor == null) {
                context.getErrorReporter()
                        .error(
                                typeElement,
                                annotationLabel
                                        + " must have an @Inject-annotated constructor or a single public constructor",
                                "Add @Inject annotation to a constructor",
                                "Ensure only one public constructor exists if not using @Inject",
                                "If instantiation goes through a static @Produces factory, no usable constructor is required");
                return null;
            }
            dependencies = buildDependencies(constructor);
        }

        // Find @PostConstruct and @PreDestroy methods
        List<ExecutableElement> postConstructMethods = findAnnotatedMethods(typeElement, PostConstruct.class);
        List<ExecutableElement> preDestroyMethods = findAnnotatedMethods(typeElement, PreDestroy.class);

        // Implicit AutoCloseable cleanup: when the component implements AutoCloseable and
        // the user did not declare an explicit @PreDestroy, the container codegen calls
        // close() at scope teardown. Explicit @PreDestroy always wins to avoid double-cleanup.
        boolean autoCloseable =
                preDestroyMethods.isEmpty() && typeUtil.implementsInterface(typeElement, "java.lang.AutoCloseable");

        // Check if component implements an interface (for proxy generation)
        Optional<TypeMirror> implementedInterface = typeUtil.getFirstInterface(typeElement);

        ComponentModel.Builder builder = ComponentModel.builder()
                .typeElement(typeElement)
                .packageName(typeUtil.getPackageName(typeElement))
                .className(typeElement.getSimpleName().toString())
                .qualifiedName(typeElement.getQualifiedName().toString())
                .scope(scope)
                .name(name)
                .profiles(profiles)
                .dependencies(dependencies)
                .postConstructMethods(postConstructMethods)
                .preDestroyMethods(preDestroyMethods)
                .autoCloseable(autoCloseable)
                .exposeTypes(exposeTypes)
                .exposeSelf(exposeSelf)
                .testComponent(isTestComponent);

        if (constructor != null) {
            builder.constructor(constructor);
        }
        if (staticFactoryMethod != null) {
            builder.staticFactoryMethod(staticFactoryMethod);
        }

        implementedInterface.ifPresent(builder::implementedInterface);

        // Determine if proxy is needed (REQUEST/EVENT scope with interface)
        boolean needsProxy = (scope == Scope.REQUEST || scope == Scope.EVENT) && implementedInterface.isPresent();
        builder.requiresProxy(needsProxy);

        return builder.build();
    }

    /**
     * Returns the {@link AnnotationMirror} on {@code element} whose annotation type has
     * the given canonical name, or {@code null} when none. Used for annotations the
     * processor must read without a compile-time dependency on the declaring module.
     */
    private AnnotationMirror findAnnotationMirror(Element element, String annotationFqn) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            Element declaring = mirror.getAnnotationType().asElement();
            if (declaring instanceof TypeElement typeElement
                    && typeElement.getQualifiedName().contentEquals(annotationFqn)) {
                return mirror;
            }
        }
        return null;
    }

    /**
     * Reads a string-valued annotation attribute by name. Returns empty when the
     * attribute is not present (defaults are not surfaced by
     * {@link Elements#getElementValuesWithDefaults(AnnotationMirror)} call sites
     * here — callers supply their own default).
     */
    private Optional<String> readStringAttribute(AnnotationMirror mirror, String attributeName) {
        Map<? extends ExecutableElement, ? extends AnnotationValue> values =
                processingEnv.getElementUtils().getElementValuesWithDefaults(mirror);
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : values.entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(attributeName)) {
                Object v = entry.getValue().getValue();
                return v instanceof String s ? Optional.of(s) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Reads an enum-valued annotation attribute by name and resolves it to the matching
     * enum constant on {@code enumType}. Returns empty when the attribute is absent or
     * does not name a known constant.
     */
    private <E extends Enum<E>> Optional<E> readEnumAttribute(
            AnnotationMirror mirror, String attributeName, Class<E> enumType) {
        Map<? extends ExecutableElement, ? extends AnnotationValue> values =
                processingEnv.getElementUtils().getElementValuesWithDefaults(mirror);
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : values.entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(attributeName)) {
                Object v = entry.getValue().getValue();
                if (v instanceof VariableElement constant) {
                    try {
                        return Optional.of(
                                Enum.valueOf(enumType, constant.getSimpleName().toString()));
                    } catch (IllegalArgumentException ignored) {
                        return Optional.empty();
                    }
                }
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Reads a {@code Class<?>} annotation attribute as a {@link TypeMirror}. Annotation
     * {@code Class<?>} values are mirrored at processor time — direct {@code Class.getName()}
     * access throws {@link MirroredTypeException}, so the value must be extracted via the
     * AnnotationValue API.
     *
     * @return the attribute's TypeMirror, or empty when the attribute is absent.
     */
    private Optional<TypeMirror> readClassAttribute(AnnotationMirror mirror, String attributeName) {
        Map<? extends ExecutableElement, ? extends AnnotationValue> values =
                processingEnv.getElementUtils().getElementValuesWithDefaults(mirror);
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : values.entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(attributeName)) {
                Object v = entry.getValue().getValue();
                if (v instanceof TypeMirror tm) {
                    return Optional.of(tm);
                }
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Finds the constructor to use for injection. Selection rule:
     *   1. an @Inject-annotated constructor (any visibility) wins; or
     *   2. the sole public constructor when no @Inject is present.
     * Constructors that are not public and not @Inject-annotated are ignored — their
     * parameters are not injection points (issue #7).
     */
    private ExecutableElement findInjectConstructor(TypeElement typeElement) {
        List<ExecutableElement> publicConstructors = new ArrayList<>();
        ExecutableElement injectConstructor = null;

        for (Element element : typeElement.getEnclosedElements()) {
            if (element.getKind() != ElementKind.CONSTRUCTOR) continue;
            ExecutableElement constructor = (ExecutableElement) element;

            if (constructor.getAnnotation(Inject.class) != null) {
                if (injectConstructor != null) {
                    context.getErrorReporter().error(typeElement, "Multiple constructors annotated with @Inject");
                    return null;
                }
                injectConstructor = constructor;
            }

            if (constructor.getModifiers().contains(Modifier.PUBLIC)) {
                publicConstructors.add(constructor);
            }
        }

        if (injectConstructor != null) {
            return injectConstructor;
        }

        if (publicConstructors.size() == 1) {
            return publicConstructors.get(0);
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

            // Provider<T> and Picker<T> are wrappers around a base type T. They are
            // mutually exclusive (a parameter can't be both), so detect each and unwrap
            // to the inner T for downstream lookup / codegen.
            boolean isProvider = typeUtil.isProvider(paramType);
            boolean isPicker = !isProvider && typeUtil.isPicker(paramType);
            TypeMirror unwrappedType = null;

            if (isProvider) {
                unwrappedType = typeUtil.unwrapProvider(paramType).orElse(null);
                if (unwrappedType != null) {
                    typeName = typeUtil.getQualifiedName(unwrappedType);
                }
            } else if (isPicker) {
                unwrappedType = typeUtil.unwrapPicker(paramType).orElse(null);
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

            // Check for @Pick class-literal qualifier. Reading the Class value goes
            // through MirroredTypeException because the picked class is a TypeMirror
            // at processor time, not yet a loadable Class.
            TypeMirror pickedType = null;
            String pickedTypeName = null;
            Pick pickAnnotation = parameter.getAnnotation(Pick.class);
            if (pickAnnotation != null) {
                try {
                    pickAnnotation.value();
                } catch (MirroredTypeException e) {
                    pickedType = e.getTypeMirror();
                }
                if (pickedType != null) {
                    pickedTypeName = typeUtil.getQualifiedName(pickedType);
                }
            }

            DependencyModel dependency = DependencyModel.builder()
                    .parameter(parameter)
                    .type(paramType)
                    .typeName(typeName)
                    .qualifier(qualifier)
                    .isProvider(isProvider)
                    .isPicker(isPicker)
                    .unwrappedType(unwrappedType)
                    .pickedType(pickedType)
                    .pickedTypeName(pickedTypeName)
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

            // Skip a self-@Produces method already absorbed by its @Component model
            // (static method on the component class returning that class with matching qualifier).
            if (isSelfStaticFactory(methodElement)) {
                continue;
            }

            FactoryMethodModel factory = buildFactoryMethodModel(methodElement);
            if (factory != null) {
                context.registerFactoryMethod(factory);
            }
        }
    }

    /**
     * Returns the static @Produces method on {@code typeElement} that returns the same
     * type with a qualifier matching {@code componentName}, if present. Used so the
     * factory method becomes the component's instantiation strategy instead of a
     * competing provider.
     */
    private ExecutableElement findSelfStaticFactory(TypeElement typeElement, String componentName) {
        for (Element element : typeElement.getEnclosedElements()) {
            if (element.getKind() != ElementKind.METHOD) continue;
            ExecutableElement method = (ExecutableElement) element;
            Produces produces = method.getAnnotation(Produces.class);
            if (produces == null) continue;
            if (!method.getModifiers().contains(Modifier.STATIC)) continue;
            if (!processingEnv.getTypeUtils().isSameType(method.getReturnType(), typeElement.asType())) continue;
            if (!Objects.equals(produces.name(), componentName)) continue;
            return method;
        }
        return null;
    }

    /**
     * Predicate form of {@link #findSelfStaticFactory} for use during factory collection.
     */
    private boolean isSelfStaticFactory(ExecutableElement methodElement) {
        if (!methodElement.getModifiers().contains(Modifier.STATIC)) return false;
        Element enclosing = methodElement.getEnclosingElement();
        if (!(enclosing instanceof TypeElement declaringType)) return false;
        Component componentAnnotation = declaringType.getAnnotation(Component.class);
        if (componentAnnotation == null) return false;
        if (!processingEnv.getTypeUtils().isSameType(methodElement.getReturnType(), declaringType.asType()))
            return false;
        Produces produces = methodElement.getAnnotation(Produces.class);
        return produces != null && Objects.equals(produces.name(), componentAnnotation.name());
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

        // Factory-produced beans implementing AutoCloseable get implicit close() at teardown,
        // covering the common case of @Produces returning third-party types (HikariDataSource,
        // HttpClient, KafkaProducer, etc.).
        boolean factoryAutoCloseable = typeUtil.isAssignableTo(returnType, "java.lang.AutoCloseable");

        // Auto-proxy: when a REQUEST/EVENT-scoped factory produces an interface, a longer-lived
        // consumer (SINGLETON injecting a REQUEST-scoped Connection, for example) needs a proxy
        // that resolves to the current scope's value on every method call — same teaching as
        // @Component-based cross-scope proxies. Proxying a concrete class would require bytecode
        // tricks Tiko deliberately avoids, so we only emit a proxy for interface returns.
        boolean requiresProxy = (annotation.scope() == Scope.REQUEST || annotation.scope() == Scope.EVENT)
                && returnType.getKind() == javax.lang.model.type.TypeKind.DECLARED
                && ((javax.lang.model.type.DeclaredType) returnType).asElement().getKind()
                        == javax.lang.model.element.ElementKind.INTERFACE;

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
                .autoCloseable(factoryAutoCloseable)
                .requiresProxy(requiresProxy)
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
     * Collects all @Configuration records.
     */
    private void collectConfigurations(RoundEnvironment roundEnv) {
        new io.tiko.processor.config.ConfigurationCollector(context).collect(roundEnv);
    }

    /**
     * Builds an EventHandlerModel from an @EventHandler method.
     */
    private EventHandlerModel buildEventHandlerModel(ExecutableElement methodElement) {
        EventHandler annotation = methodElement.getAnnotation(EventHandler.class);
        TypeElement declaringClass = (TypeElement) methodElement.getEnclosingElement();

        List<? extends VariableElement> parameters = methodElement.getParameters();
        if (parameters.isEmpty()) {
            context.getErrorReporter()
                    .error(methodElement, "@EventHandler method must have at least one parameter (the event)");
            return null;
        }

        // First parameter is the event type
        TypeMirror eventType = parameters.get(0).asType();
        String eventTypeName = typeUtil.getQualifiedName(eventType);

        // Check if second parameter is Event<?> wrapper
        boolean hasEventWrapper = parameters.size() > 1
                && typeUtil.getQualifiedName(parameters.get(1).asType()).equals("io.tiko.Event");

        List<EventTriggerModel> eventTriggers = collectEventTriggers(methodElement);

        return EventHandlerModel.builder()
                .methodElement(methodElement)
                .declaringClass(declaringClass)
                .methodName(methodElement.getSimpleName().toString())
                .eventType(eventType)
                .eventTypeName(eventTypeName)
                .async(annotation.async())
                .hasEventWrapper(hasEventWrapper)
                .eventTriggers(eventTriggers)
                .build();
    }

    /**
     * Pulls every {@code @EventTrigger} annotation off a method (handles the implicit
     * {@code @EventTriggers} container that the compiler synthesises when the user
     * declares multiple). Default {@code AlwaysAllow} guards are dropped — codegen
     * treats an empty guard list as "always allow".
     */
    private List<EventTriggerModel> collectEventTriggers(ExecutableElement methodElement) {
        List<EventTrigger> raw = new ArrayList<>();
        EventTriggers container = methodElement.getAnnotation(EventTriggers.class);
        if (container != null) {
            raw.addAll(Arrays.asList(container.value()));
        } else {
            EventTrigger single = methodElement.getAnnotation(EventTrigger.class);
            if (single != null) raw.add(single);
        }

        String alwaysAllowName = EventTriggerGuard.AlwaysAllow.class.getCanonicalName();
        List<EventTriggerModel> models = new ArrayList<>(raw.size());
        for (EventTrigger trigger : raw) {
            List<TypeMirror> guardMirrors;
            try {
                trigger.guard();
                guardMirrors = List.of();
            } catch (MirroredTypesException e) {
                guardMirrors = new ArrayList<>(e.getTypeMirrors());
            }
            // Strip the default AlwaysAllow so codegen can skip the guard call entirely.
            guardMirrors.removeIf(m -> typeUtil.getQualifiedName(m).equals(alwaysAllowName));

            models.add(EventTriggerModel.builder()
                    .eventName(trigger.eventName())
                    .async(trigger.async())
                    .spread(trigger.spread())
                    .guardClasses(guardMirrors)
                    .build());
        }
        return models;
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

        // Validate @Component(expose = {...}) entries are actually implemented.
        ExposureValidator exposureValidator = new ExposureValidator(context, typeUtil);
        if (!exposureValidator.validate()) {
            valid = false;
        }

        // Validate dependencies exist
        DependencyGraphValidator graphValidator = new DependencyGraphValidator(context, typeUtil);
        if (!graphValidator.validate()) {
            valid = false;
        }

        // Detect circular dependencies
        CircularDependencyDetector circularDetector = new CircularDependencyDetector(context);
        if (!circularDetector.validate()) {
            valid = false;
        }

        // Detect ambiguous unnamed providers (two+ @Components/@Produces for same type)
        AmbiguityValidator ambiguityValidator = new AmbiguityValidator(context);
        if (!ambiguityValidator.validate()) {
            valid = false;
        }

        // Validate Picker<T> injection points (warn if no local impls)
        PickerValidator pickerValidator = new PickerValidator(context);
        if (!pickerValidator.validate()) {
            valid = false;
        }

        // Validate @Pick usage on injection-point parameters
        PickValidator pickValidator = new PickValidator(context);
        if (!pickValidator.validate()) {
            valid = false;
        }

        // Validate @Configuration records
        io.tiko.processor.config.ConfigurationValidator configValidator =
                new io.tiko.processor.config.ConfigurationValidator(context, processingEnv.getTypeUtils());
        if (!configValidator.validate()) {
            valid = false;
        }

        // Closeable-field leak warning (warning only, never fails the build)
        new CloseableLeakValidator(context).validate();

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
            processingEnv
                    .getMessager()
                    .printMessage(Diagnostic.Kind.NOTE, "Tiko DI: Generating factory for " + component.getClassName());
            factoryGenerator.generate(component);
        }

        // Generate proxies for cross-scope injection
        ProxyGenerator proxyGenerator = new ProxyGenerator(context);
        for (ComponentModel component : context.getActiveComponents()) {
            if (component.requiresProxy()) {
                processingEnv
                        .getMessager()
                        .printMessage(
                                Diagnostic.Kind.NOTE, "Tiko DI: Generating proxy for " + component.getClassName());
            }
            proxyGenerator.generate(component);
        }
        // Factory outputs that are interfaces in shorter-lived scopes also need proxies so
        // longer-lived consumers (e.g., SINGLETON repo injecting a REQUEST-scoped Connection)
        // resolve per-call to the current scope's value instead of capturing the first one.
        for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
            if (factory.requiresProxy()) {
                processingEnv
                        .getMessager()
                        .printMessage(
                                Diagnostic.Kind.NOTE,
                                "Tiko DI: Generating proxy for " + factory.getFactoryIdentifier());
                proxyGenerator.generate(factory);
            }
        }

        // Generate event registry
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Tiko DI: Generating event registry...");
        EventRegistryGenerator eventRegistryGenerator = new EventRegistryGenerator(context);
        eventRegistryGenerator.generate();

        // Generate per-record ConfigBinder classes
        io.tiko.processor.config.ConfigBinderGenerator configBinderGen =
                new io.tiko.processor.config.ConfigBinderGenerator(
                        processingEnv.getFiler(), processingEnv.getMessager());
        for (io.tiko.processor.config.ConfigurationModel cfg : context.getConfigurations()) {
            if (configBinderGen.canGenerate(cfg)) {
                configBinderGen.generate(cfg);
            }
        }

        // Generate ConfigBinderRegistry and configs.txt manifest
        List<io.tiko.processor.config.ConfigurationModel> configs = context.getConfigurations();
        // containerClassName already computed above; extract the hash suffix from it
        String hashSuffix = containerClassName.substring(containerClassName.lastIndexOf('_') + 1);
        io.tiko.processor.config.ConfigBinderRegistryGenerator regGen =
                new io.tiko.processor.config.ConfigBinderRegistryGenerator(processingEnv.getFiler(), hashSuffix);
        regGen.generate(configs);
        new io.tiko.processor.config.ConfigManifestWriter(processingEnv.getFiler(), regGen.registryClassFqn())
                .write(configs);

        // Generate container (must be last)
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "Tiko DI: Generating container...");
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
                .map(ComponentModel::getComponentKey)
                .sorted()
                .toList();

        int hash = Objects.hash(componentKeys.toArray());
        // Convert to positive hex string
        String suffix = Integer.toHexString(hash & 0x7FFFFFFF);

        return "TikoContainerImpl_" + suffix;
    }

    private static String formatStackTrace(Throwable t) {
        var sb = new StringBuilder();
        sb.append(t.getClass().getName());
        if (t.getMessage() != null) {
            sb.append(": ").append(t.getMessage());
        }
        for (var frame : t.getStackTrace()) {
            sb.append("\n  at ").append(frame);
        }
        var cause = t.getCause();
        if (cause != null && cause != t) {
            sb.append("\nCaused by: ").append(formatStackTrace(cause));
        }
        return sb.toString();
    }
}
