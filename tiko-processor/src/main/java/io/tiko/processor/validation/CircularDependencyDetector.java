package io.tiko.processor.validation;

import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.ProcessorContext;
import java.util.*;

/**
 * Detects circular dependencies in the component graph.
 *
 * A circular dependency occurs when:
 * - A -> B -> A
 * - A -> B -> C -> A
 *
 * Provider<T> breaks cycles (lazy resolution).
 */
public final class CircularDependencyDetector {

    private final ProcessorContext context;

    public CircularDependencyDetector(ProcessorContext context) {
        this.context = context;
    }

    /**
     * Validates all components for circular dependencies.
     * Returns true if no cycles found, false if errors were reported.
     */
    public boolean validate() {
        boolean valid = true;

        // Check each component
        for (ComponentModel component : context.getActiveComponents()) {
            Set<String> visiting = new HashSet<>();
            List<String> path = new ArrayList<>();

            if (hasCycle(component.getComponentKey(), visiting, path)) {
                reportCycle(component, path);
                valid = false;
            }
        }

        // Check each factory method
        for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
            Set<String> visiting = new HashSet<>();
            List<String> path = new ArrayList<>();

            if (hasCycle(factory.getComponentKey(), visiting, path)) {
                reportCycleForFactory(factory, path);
                valid = false;
            }
        }

        return valid;
    }

    /**
     * Performs depth-first search to detect cycles.
     * Returns true if a cycle is detected.
     */
    private boolean hasCycle(String componentKey, Set<String> visiting, List<String> path) {
        // Already visiting this node - cycle detected
        if (visiting.contains(componentKey)) {
            // Add to path to show the cycle
            path.add(componentKey);
            return true;
        }

        // Mark as visiting
        visiting.add(componentKey);
        path.add(componentKey);

        // Get dependencies
        List<DependencyModel> dependencies = getDependencies(componentKey);

        for (DependencyModel dependency : dependencies) {
            // Provider<T> breaks cycles - skip
            if (dependency.isProvider()) {
                continue;
            }

            String depKey = dependency.getDependencyKey();

            // Check if dependency exists
            if (!context.findComponentOrFactory(depKey).isPresent()) {
                // Missing dependency will be caught by DependencyGraphValidator
                continue;
            }

            // Recursively check for cycles
            if (hasCycle(depKey, new HashSet<>(visiting), new ArrayList<>(path))) {
                return true;
            }
        }

        return false;
    }

    /**
     * Gets dependencies for a component or factory by key.
     */
    private List<DependencyModel> getDependencies(String componentKey) {
        // Check components first
        ComponentModel component = context.getComponents().get(componentKey);
        if (component != null) {
            return component.getDependencies();
        }

        // Check factory methods
        FactoryMethodModel factory = context.getFactoryMethods().get(componentKey);
        if (factory != null) {
            return factory.getDependencies();
        }

        return List.of();
    }

    /**
     * Reports a circular dependency for a component.
     */
    private void reportCycle(ComponentModel component, List<String> path) {
        String cycle = String.join(" -> ", path);
        context.getErrorReporter().circularDependency(component.getTypeElement(), cycle);
    }

    /**
     * Reports a circular dependency for a factory method.
     */
    private void reportCycleForFactory(FactoryMethodModel factory, List<String> path) {
        String cycle = String.join(" -> ", path);
        context.getErrorReporter().circularDependency(factory.getMethodElement(), cycle);
    }
}
