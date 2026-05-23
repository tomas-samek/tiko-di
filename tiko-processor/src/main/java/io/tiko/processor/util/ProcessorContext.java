package io.tiko.processor.util;

import io.tiko.processor.config.ConfigurationModel;
import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.EventHandlerModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.model.WiringError;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Shared context for annotation processing.
 * Holds discovered components, factories, event handlers, and utilities.
 */
public final class ProcessorContext {

    private final ProcessingEnvironment processingEnv;
    private final Elements elementUtils;
    private final Types typeUtils;
    private final Filer filer;
    private final Messager messager;
    private final ErrorReporter errorReporter;

    // Discovered components and factories
    private final Map<String, ComponentModel> components = new HashMap<>();
    private final Map<String, FactoryMethodModel> factoryMethods = new HashMap<>();
    private final List<EventHandlerModel> eventHandlers = new ArrayList<>();
    private final List<ConfigurationModel> configurations = new ArrayList<>();

    // Main-component keys shadowed by a same-typed @TestComponent, mapped to the
    // shadowing test ComponentModel. Populated by AmbiguityValidator; consumed by
    // test-container code generation, which wires the test factory in place of the
    // main factory for shadowed entries. LinkedHashMap preserves registration order.
    private final Map<String, ComponentModel> shadowedByTestOverride = new LinkedHashMap<>();

    // Structured wiring diagnostics captured for META-INF/tiko/wiring-errors.json.
    // Populated by ErrorReporter on every error() call (and a few direct addWiringError
    // call sites for non-ErrorReporter diagnostics). Reads/writes are unsynchronised:
    // annotation processing runs single-threaded, so the simple ArrayList is fine.
    private final List<WiringError> wiringErrors = new ArrayList<>();

    // Active profiles (for filtering components during generation)
    private final List<String> activeProfiles;

    // Generated container class name (set during code generation)
    private String containerClassName;

    public ProcessorContext(ProcessingEnvironment processingEnv, List<String> activeProfiles) {
        this.processingEnv = processingEnv;
        this.elementUtils = processingEnv.getElementUtils();
        this.typeUtils = processingEnv.getTypeUtils();
        this.filer = processingEnv.getFiler();
        this.messager = processingEnv.getMessager();
        // ErrorReporter forwards every recorded error to this context's wiring-errors
        // list so the JSON sibling artifact captures the same diagnostics the Messager
        // prints. Lambda is a `this::addWiringError` style sink — keeps ErrorReporter
        // dependency-free of ProcessorContext.
        this.errorReporter = new ErrorReporter(messager, this::addWiringError);
        this.activeProfiles = new ArrayList<>(activeProfiles);
    }

    // Getters for processing utilities
    public ProcessingEnvironment getProcessingEnv() {
        return processingEnv;
    }

    public Elements getElementUtils() {
        return elementUtils;
    }

    public Types getTypeUtils() {
        return typeUtils;
    }

    public Filer getFiler() {
        return filer;
    }

    public Messager getMessager() {
        return messager;
    }

    public ErrorReporter getErrorReporter() {
        return errorReporter;
    }

    /**
     * Records a structured wiring diagnostic for {@code META-INF/tiko/wiring-errors.json}.
     * Called by {@link ErrorReporter} for every reported error, and directly by a few
     * call sites that bypass {@code ErrorReporter} (raw {@code messager.printMessage}
     * users in the generator and leak validator).
     */
    public void addWiringError(WiringError error) {
        wiringErrors.add(error);
    }

    /** Snapshot of every wiring diagnostic recorded so far. */
    public List<WiringError> getWiringErrors() {
        return List.copyOf(wiringErrors);
    }

    public List<String> getActiveProfiles() {
        return List.copyOf(activeProfiles);
    }

    // Component registration and lookup
    public void registerComponent(ComponentModel component) {
        String key = component.getComponentKey();
        if (components.containsKey(key)) {
            errorReporter.error(component.getTypeElement(), "Duplicate component: " + key + " is already registered");
        }
        components.put(key, component);
    }

    public void registerFactoryMethod(FactoryMethodModel factory) {
        String key = factory.getComponentKey();
        if (factoryMethods.containsKey(key)) {
            errorReporter.error(
                    ErrorReporter.KIND_BAD_PRODUCES,
                    factory.getMethodElement(),
                    "Duplicate factory method: " + key + " is already registered");
        }
        if (components.containsKey(key)) {
            errorReporter.error(
                    ErrorReporter.KIND_BAD_PRODUCES,
                    factory.getMethodElement(),
                    "Factory method produces type " + key + " which is already provided by a @Component");
        }
        factoryMethods.put(key, factory);
    }

    public void registerEventHandler(EventHandlerModel eventHandler) {
        eventHandlers.add(eventHandler);
    }

    public Map<String, ComponentModel> getComponents() {
        return Map.copyOf(components);
    }

    public Map<String, FactoryMethodModel> getFactoryMethods() {
        return Map.copyOf(factoryMethods);
    }

    public List<EventHandlerModel> getEventHandlers() {
        return List.copyOf(eventHandlers);
    }

    public void registerConfiguration(ConfigurationModel cfg) {
        configurations.add(cfg);
    }

    public List<ConfigurationModel> getConfigurations() {
        return List.copyOf(configurations);
    }

    /**
     * Finds a single provider whose implementation class (for {@link ComponentModel})
     * or return type (for {@link FactoryMethodModel}) equals the given fully qualified
     * name. This is the {@code @Pick(X.class)} lookup: identity is the impl class
     * itself, ignoring any {@code @Component(name = ...)} or {@code @Produces(name = ...)}
     * qualifier — those are name-keyed, this is class-keyed.
     */
    public Optional<Object> findByImplClass(String implFqn) {
        for (ComponentModel c : components.values()) {
            if (c.getQualifiedName().equals(implFqn)) {
                return Optional.of(c);
            }
        }
        for (FactoryMethodModel f : factoryMethods.values()) {
            if (f.getReturnTypeName().equals(implFqn)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns every provider (component or factory) matching the given impl class FQN.
     * Used by the {@code @Pick} validator to detect ambiguous {@code @Produces} targets:
     * if two factory methods return the same type, {@code @Pick(That.class)} cannot
     * disambiguate them and the user must use {@code @Named} instead.
     */
    public List<Object> findAllByImplClass(String implFqn) {
        List<Object> matches = new ArrayList<>();
        for (ComponentModel c : components.values()) {
            if (c.getQualifiedName().equals(implFqn)) {
                matches.add(c);
            }
        }
        for (FactoryMethodModel f : factoryMethods.values()) {
            if (f.getReturnTypeName().equals(implFqn)) {
                matches.add(f);
            }
        }
        return matches;
    }

    /**
     * Looks up a component or factory by its key (typeName or typeName#qualifier).
     * Also matches {@code @Configuration} records, which are injected as SINGLETON beans
     * by the runtime after config binding.
     */
    public Optional<Object> findComponentOrFactory(String key) {
        // First try exact match
        if (components.containsKey(key)) {
            return Optional.of(components.get(key));
        }
        if (factoryMethods.containsKey(key)) {
            return Optional.of(factoryMethods.get(key));
        }

        // @Configuration records are bound by the runtime and registered as SINGLETON beans;
        // treat them as resolvable so that DependencyGraphValidator doesn't reject them.
        String typeName = key.contains("#") ? key.substring(0, key.indexOf("#")) : key;
        for (io.tiko.processor.config.ConfigurationModel cfg : configurations) {
            if (cfg.qualifiedName().equals(typeName)) {
                return Optional.of(cfg);
            }
        }

        // Try to find by interface implementation
        // Extract qualifier
        String qualifier = key.contains("#") ? key.substring(key.indexOf("#") + 1) : "";

        // Look for a component that implements this interface. Prefer non-test components
        // so that main-container wiring never resolves a dependency to a @TestComponent;
        // the test container performs its own shadow swap downstream via the carve-out in
        // AmbiguityValidator + getActiveTestContainerComponents().
        ComponentModel testFallback = null;
        for (ComponentModel component : components.values()) {
            // Check if component implements the requested interface
            if (component.getImplementedInterface().isPresent()) {
                String interfaceName = component.getImplementedInterface().get().toString();
                if (interfaceName.equals(typeName)) {
                    // Check qualifier matches if present
                    if (qualifier.isEmpty()
                            || qualifier.equals(component.getName().orElse(""))) {
                        if (!component.isTestComponent()) {
                            return Optional.of(component);
                        }
                        if (testFallback == null) testFallback = component;
                    }
                }
            }
        }
        if (testFallback != null) {
            return Optional.of(testFallback);
        }

        return Optional.empty();
    }

    /**
     * Returns true if the given component/factory should be included based on active profiles.
     */
    public boolean isProfileActive(List<String> profiles) {
        // If no profiles specified on the component, it's always active
        if (profiles.isEmpty()) {
            return true;
        }
        // If active profiles is empty, include all components
        if (activeProfiles.isEmpty()) {
            return true;
        }
        // Check if any of the component's profiles match active profiles
        return profiles.stream().anyMatch(activeProfiles::contains);
    }

    /**
     * Returns all components that should be active based on current profiles. Includes
     * both {@code @Component} and {@code @TestComponent} entries — callers that need to
     * separate the two should use {@link #getActiveMainComponents()} or
     * {@link #getActiveTestContainerComponents()} instead.
     */
    public List<ComponentModel> getActiveComponents() {
        return components.values().stream()
                .filter(c -> isProfileActive(c.getProfiles()))
                .toList();
    }

    /**
     * Returns all factory methods that should be active based on current profiles.
     */
    public List<FactoryMethodModel> getActiveFactoryMethods() {
        return factoryMethods.values().stream()
                .filter(f -> isProfileActive(f.getProfiles()))
                .toList();
    }

    /**
     * Returns ALL active components in this round, regardless of whether they are
     * production {@code @Component}s or test-classpath {@code @TestComponent}s.
     * Used by the standalone test container generation to discover every component
     * visible in the test-compile round (where production sources are not presented).
     */
    public List<ComponentModel> getAllActiveComponents() {
        return components.values().stream()
                .filter(c -> isProfileActive(c.getProfiles()))
                .toList();
    }

    /**
     * Returns the active components for the production-classpath {@code TikoContainerImpl}.
     * Test components are filtered out so the main container's wiring is uncontaminated
     * by test-only beans even when a test-compile round happens to see them.
     */
    public List<ComponentModel> getActiveMainComponents() {
        return components.values().stream()
                .filter(c -> isProfileActive(c.getProfiles()))
                .filter(c -> !c.isTestComponent())
                .toList();
    }

    /**
     * Returns the active components for the test-classpath {@code TestTikoContainerImpl}:
     * main components with any shadowed entries swapped for their shadowing
     * {@code @TestComponent}, plus all test components that are not shadowing anything
     * (test-only fixtures with no main counterpart).
     *
     * <p>The swap preserves the iteration order of the original main-component list — a
     * shadowed main slot is replaced in-place by the test model — so downstream
     * generation (factory init order, eager SINGLETON walk order, etc.) stays stable.
     */
    public List<ComponentModel> getActiveTestContainerComponents() {
        var mains = getActiveMainComponents();
        var result = new ArrayList<ComponentModel>(mains.size() + shadowedByTestOverride.size());
        var consumedTestKeys = new java.util.HashSet<String>();
        for (ComponentModel main : mains) {
            ComponentModel shadow = shadowedByTestOverride.get(main.getComponentKey());
            if (shadow != null) {
                result.add(shadow);
                consumedTestKeys.add(shadow.getComponentKey());
            } else {
                result.add(main);
            }
        }
        // Append any @TestComponents not consumed above (test-only fixtures with no main).
        for (ComponentModel c : components.values()) {
            if (!c.isTestComponent()) continue;
            if (!isProfileActive(c.getProfiles())) continue;
            if (consumedTestKeys.contains(c.getComponentKey())) continue;
            result.add(c);
        }
        return List.copyOf(result);
    }

    /** True when at least one {@code @TestComponent} was discovered in this round. */
    public boolean hasTestComponents() {
        return components.values().stream().anyMatch(ComponentModel::isTestComponent);
    }

    /**
     * Returns the generated container class name.
     */
    public String getContainerClassName() {
        return containerClassName;
    }

    /**
     * Sets the generated container class name.
     */
    public void setContainerClassName(String containerClassName) {
        this.containerClassName = containerClassName;
    }

    /**
     * Marks a main {@code @Component} as shadowed by a same-typed {@code @TestComponent}.
     * The validator calls this when it detects a one-to-many shadow pair so the main
     * container's wiring stays unchanged, while the test container can substitute the
     * test factory wherever the marked component would have been resolved.
     *
     * @param mainComponentKey the main component key being shadowed
     * @param testComponent the {@code @TestComponent} model providing the override
     */
    public void markShadowedByTestOverride(String mainComponentKey, ComponentModel testComponent) {
        Objects.requireNonNull(mainComponentKey, "mainComponentKey");
        Objects.requireNonNull(testComponent, "testComponent");
        shadowedByTestOverride.put(mainComponentKey, testComponent);
    }

    /** True when the given component key has been marked as shadowed by a {@code @TestComponent}. */
    public boolean isShadowedByTestOverride(String mainComponentKey) {
        return shadowedByTestOverride.containsKey(mainComponentKey);
    }

    /**
     * Returns the {@code @TestComponent} model shadowing the given main component key,
     * or {@code null} if the key is not shadowed.
     */
    public ComponentModel getTestComponentShadowing(String mainComponentKey) {
        return shadowedByTestOverride.get(mainComponentKey);
    }

    /** Snapshot of the shadowed main-component keys. Iteration order is registration order. */
    public Set<String> getShadowedMainKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(shadowedByTestOverride.keySet()));
    }
}
