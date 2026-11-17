package io.tiko.processor.validation;

import io.tiko.processor.core.model.Location;
import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.ProcessorContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Detects circular dependencies in the component graph.
 *
 * A circular dependency occurs when:
 * - A -> B -> A
 * - A -> B -> C -> A
 *
 * Provider<T> breaks cycles (lazy resolution).
 *
 * <p>The walk runs over the providers the build's profiles select — the {@link ComponentModel} or
 * {@link FactoryMethodModel} each dependency resolves to — so two {@code @Produces} variants of one
 * key under different profiles are told apart (#722). An instance {@code @Produces} method also
 * depends on its declaring component, whose instance it is called on (#722).
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

        for (ComponentModel component : context.getActiveComponents()) {
            valid = reportCycleFrom(component, component.getDecl().location()) && valid;
        }
        for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
            valid = reportCycleFrom(factory, factory.getMethod().location()) && valid;
        }

        return valid;
    }

    /** Reports the cycle reachable from {@code provider}, if any. Returns true when there is none. */
    private boolean reportCycleFrom(Object provider, Location at) {
        List<Object> cycle = findCycle(provider, Collections.newSetFromMap(new IdentityHashMap<>()), new ArrayList<>());
        if (cycle.isEmpty()) {
            return true;
        }
        context.getErrorReporter()
                .circularDependency(
                        at,
                        String.join(
                                " -> ",
                                cycle.stream()
                                        .map(CircularDependencyDetector::keyOf)
                                        .toList()));
        return false;
    }

    /**
     * Depth-first search returning the full cycle path (e.g. {@code [A, B, A]}) when a cycle is
     * reachable from {@code provider}, or an empty list otherwise. Uses a single {@code visiting} set
     * and {@code path} list with backtracking, so the detected path propagates back to the caller
     * intact.
     */
    private List<Object> findCycle(Object provider, Set<Object> visiting, List<Object> path) {
        // Back-edge to a node already on the current path: close the cycle from its first
        // occurrence to the end, then repeat the node so the rendered path reads A -> B -> A.
        if (visiting.contains(provider)) {
            List<Object> cycle = new ArrayList<>(path.subList(path.indexOf(provider), path.size()));
            cycle.add(provider);
            return cycle;
        }

        visiting.add(provider);
        path.add(provider);

        for (Object next : providersNeededBy(provider)) {
            List<Object> cycle = findCycle(next, visiting, path);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }

        visiting.remove(provider);
        path.removeLast();
        return List.of();
    }

    /**
     * The providers {@code provider} needs before it can be built: what its injection points resolve
     * to, and for an instance {@code @Produces} method the component it is declared on.
     * {@code Provider<T>} injection points resolve lazily and are left out.
     */
    private List<Object> providersNeededBy(Object provider) {
        var needed = new ArrayList<Object>();
        for (DependencyModel dependency : dependenciesOf(provider)) {
            if (!dependency.isProvider()) {
                resolve(dependency).ifPresent(needed::add);
            }
        }
        if (provider instanceof FactoryMethodModel factory && !factory.isStatic()) {
            context.activeComponentOf(factory.getDeclaringClass()).ifPresent(needed::add);
        }
        return needed;
    }

    /**
     * Resolves a dependency to the active component or factory method that provides it, so
     * interface- and qualifier-typed edges land on the same node as direct ones (#329), and a
     * {@code @Pick} lands on the class it names however that class is exposed (#823). Empty for
     * a missing dependency (reported by DependencyGraphValidator, not here) and for runtime-bound
     * {@code @Configuration} records — leaves for cycle purposes.
     */
    private Optional<Object> resolve(DependencyModel dependency) {
        return context.findProvider(dependency)
                .filter(provider -> provider instanceof ComponentModel || provider instanceof FactoryMethodModel);
    }

    private static List<DependencyModel> dependenciesOf(Object provider) {
        return provider instanceof ComponentModel component
                ? component.getDependencies()
                : ((FactoryMethodModel) provider).getDependencies();
    }

    private static String keyOf(Object provider) {
        return provider instanceof ComponentModel component
                ? component.getComponentKey()
                : ((FactoryMethodModel) provider).getComponentKey();
    }
}
