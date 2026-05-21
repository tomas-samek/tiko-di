package io.tiko.processor.validation;

import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.ErrorReporter;
import io.tiko.processor.util.ProcessorContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;

/**
 * Detects two or more unnamed providers (components or factory methods) that
 * resolve to the same assignable type — concrete class or implemented interface.
 *
 * <p>Prevents silent first-match-wins dispatch in {@code container.get(Class)} when
 * multiple unnamed beans satisfy the same request. Named providers are not checked
 * here; they are disambiguated via {@code container.get(Class, String)}.
 */
public final class AmbiguityValidator {

    private final ProcessorContext context;

    public AmbiguityValidator(ProcessorContext context) {
        this.context = context;
    }

    public boolean validate() {
        Map<String, List<ProviderInfo>> providersByType = new LinkedHashMap<>();

        for (ComponentModel component : context.getActiveComponents()) {
            if (component.getName().isPresent()) continue;

            String kindLabel = component.isTestComponent() ? "@TestComponent" : "@Component";
            ProviderInfo info = new ProviderInfo(
                    component.getTypeElement(),
                    component.getClassName(),
                    kindLabel,
                    component.isTestComponent(),
                    component.getComponentKey());

            // Register the component under every type it is actually routable as — so two
            // unnamed beans listing the same interface in @Component(expose = {…}), or
            // implementing the same interface under the permissive default, collide here.
            if (component.isExposeSelf()) {
                register(providersByType, component.getQualifiedName(), info);
            }
            var declared = component.isExposeRestricted()
                    ? component.getExposeTypes()
                    : component.getTypeElement().getInterfaces();
            for (var iface : declared) {
                register(providersByType, iface.toString(), info);
            }
        }

        for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
            String name = factory.getName();
            if (name != null && !name.isEmpty()) continue;

            ProviderInfo info = new ProviderInfo(
                    factory.getMethodElement(), factory.getFactoryIdentifier(), "@Produces", false, null);
            register(providersByType, factory.getReturnTypeName(), info);
        }

        boolean valid = true;
        for (Map.Entry<String, List<ProviderInfo>> entry : providersByType.entrySet()) {
            List<ProviderInfo> providers = entry.getValue();
            if (providers.size() <= 1) continue;
            if (!hasUnqualifiedConsumer(entry.getKey())) continue;

            // Carve-out: a single @TestComponent shadowing one or more main providers is
            // an intentional test-side override, not an ambiguity. The main providers stay
            // wired for the production container; the test container substitutes the test
            // factory wherever a shadowed main provider would have been resolved.
            List<ProviderInfo> testProviders =
                    providers.stream().filter(p -> p.isTestComponent).toList();
            List<ProviderInfo> mainProviders =
                    providers.stream().filter(p -> !p.isTestComponent).toList();

            if (testProviders.size() == 1 && !mainProviders.isEmpty()) {
                for (ProviderInfo main : mainProviders) {
                    if (main.componentKey != null) {
                        context.markShadowedByTestOverride(main.componentKey);
                    }
                }
                continue;
            }

            reportAmbiguity(entry.getKey(), providers);
            valid = false;
        }
        return valid;
    }

    /**
     * True when at least one consumer injects the given type without disambiguating
     * via {@code @Pick} or {@code @Named}. When every consumer qualifies its lookup,
     * the multiple unnamed providers are not actually ambiguous at any call site —
     * silent first-match-wins dispatch can never happen — and the validator should
     * not fire.
     */
    private boolean hasUnqualifiedConsumer(String typeKey) {
        for (ComponentModel component : context.getActiveComponents()) {
            for (DependencyModel dep : component.getDependencies()) {
                if (matchesType(dep, typeKey) && isUnqualified(dep)) {
                    return true;
                }
            }
        }
        for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
            for (DependencyModel dep : factory.getDependencies()) {
                if (matchesType(dep, typeKey) && isUnqualified(dep)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesType(DependencyModel dep, String typeKey) {
        // Compare against the parameter type (or unwrapped Provider<T>), not the picked
        // class — we are asking "is this dependency for the ambiguous type?".
        String depType = dep.getUnwrappedType().map(Object::toString).orElse(dep.getTypeName());
        return depType.equals(typeKey);
    }

    private boolean isUnqualified(DependencyModel dep) {
        return !dep.isPicked() && dep.getQualifier().isEmpty();
    }

    private void register(Map<String, List<ProviderInfo>> map, String key, ProviderInfo provider) {
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(provider);
    }

    private void reportAmbiguity(String typeKey, List<ProviderInfo> providers) {
        String providerList =
                providers.stream().map(p -> p.label + " (" + p.kind + ")").collect(Collectors.joining(", "));
        String simpleName = simpleName(typeKey);
        ErrorReporter errorReporter = context.getErrorReporter();

        for (ProviderInfo provider : providers) {
            errorReporter.ambiguousProviders(provider.element, typeKey, providerList, simpleName);
        }
    }

    private static String simpleName(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot >= 0 ? qualifiedName.substring(lastDot + 1) : qualifiedName;
    }

    /**
     * One discovered provider for a given routable type. {@code isTestComponent}
     * distinguishes {@code @TestComponent} (shadow-allowed) from {@code @Component} /
     * {@code @Produces}. {@code componentKey} is the main-component identity used by
     * the test-container generator when applying a shadow override; null for factory
     * methods (factories cannot be shadowed in T11's scope).
     */
    private record ProviderInfo(
            Element element, String label, String kind, boolean isTestComponent, String componentKey) {}
}
