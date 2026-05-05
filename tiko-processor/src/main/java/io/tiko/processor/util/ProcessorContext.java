package io.tiko.processor.util;

import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.model.EventHandlerModel;
import io.tiko.processor.config.ConfigurationModel;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        this.errorReporter = new ErrorReporter(messager);
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

    public List<String> getActiveProfiles() {
        return List.copyOf(activeProfiles);
    }

    // Component registration and lookup
    public void registerComponent(ComponentModel component) {
        String key = component.getComponentKey();
        if (components.containsKey(key)) {
            errorReporter.error(
                component.getTypeElement(),
                "Duplicate component: " + key + " is already registered"
            );
        }
        components.put(key, component);
    }

    public void registerFactoryMethod(FactoryMethodModel factory) {
        String key = factory.getComponentKey();
        if (factoryMethods.containsKey(key)) {
            errorReporter.error(
                factory.getMethodElement(),
                "Duplicate factory method: " + key + " is already registered"
            );
        }
        if (components.containsKey(key)) {
            errorReporter.error(
                factory.getMethodElement(),
                "Factory method produces type " + key + " which is already provided by a @Component"
            );
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

        // Look for a component that implements this interface
        for (ComponentModel component : components.values()) {
            // Check if component implements the requested interface
            if (component.getImplementedInterface().isPresent()) {
                String interfaceName = component.getImplementedInterface().get().toString();
                if (interfaceName.equals(typeName)) {
                    // Check qualifier matches if present
                    if (qualifier.isEmpty() || qualifier.equals(component.getName().orElse(""))) {
                        return Optional.of(component);
                    }
                }
            }
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
     * Returns all components that should be active based on current profiles.
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
}
