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
            List<String> cycle = findCycle(component.getComponentKey(), new HashSet<>(), new ArrayList<>());
            if (!cycle.isEmpty()) {
                reportCycle(component, cycle);
                valid = false;
            }
        }

        // Check each factory method
        for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
            List<String> cycle = findCycle(factory.getComponentKey(), new HashSet<>(), new ArrayList<>());
            if (!cycle.isEmpty()) {
                reportCycleForFactory(factory, cycle);
                valid = false;
            }
        }

        return valid;
    }

    /**
     * Depth-first search returning the full cycle path (e.g. {@code [A, B, A]}) when a cycle is
     * reachable from {@code key}, or an empty list otherwise. Uses a single {@code visiting} set
     * and {@code path} list with backtracking, so the detected path propagates back to the caller
     * intact — the earlier copy-on-recurse logic discarded it and surfaced only the root node.
     */
    private List<String> findCycle(String key, Set<String> visiting, List<String> path) {
        // Back-edge to a node already on the current path: close the cycle from its first
        // occurrence to the end, then repeat the node so the rendered path reads A -> B -> A.
        if (visiting.contains(key)) {
            List<String> cycle = new ArrayList<>(path.subList(path.indexOf(key), path.size()));
            cycle.add(key);
            return cycle;
        }

        // Missing dependency is reported by DependencyGraphValidator, not here.
        if (context.findComponentOrFactory(key).isEmpty()) {
            return List.of();
        }

        visiting.add(key);
        path.add(key);

        for (DependencyModel dependency : getDependencies(key)) {
            // Provider<T> breaks cycles - skip
            if (dependency.isProvider()) {
                continue;
            }
            List<String> cycle = findCycle(dependency.getDependencyKey(), visiting, path);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }

        visiting.remove(key);
        path.remove(path.size() - 1);
        return List.of();
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
