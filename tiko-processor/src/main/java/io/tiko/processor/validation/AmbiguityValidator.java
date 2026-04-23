package io.tiko.processor.validation;

import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.ErrorReporter;
import io.tiko.processor.util.ProcessorContext;

import javax.lang.model.element.Element;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

            ProviderInfo info = new ProviderInfo(
                    component.getTypeElement(),
                    component.getClassName(),
                    "@Component"
            );

            register(providersByType, component.getQualifiedName(), info);
            component.getImplementedInterface().ifPresent(iface ->
                    register(providersByType, iface.toString(), info)
            );
        }

        for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
            String name = factory.getName();
            if (name != null && !name.isEmpty()) continue;

            ProviderInfo info = new ProviderInfo(
                    factory.getMethodElement(),
                    factory.getFactoryIdentifier(),
                    "@Produces"
            );
            register(providersByType, factory.getReturnTypeName(), info);
        }

        boolean valid = true;
        for (Map.Entry<String, List<ProviderInfo>> entry : providersByType.entrySet()) {
            List<ProviderInfo> providers = entry.getValue();
            if (providers.size() > 1) {
                reportAmbiguity(entry.getKey(), providers);
                valid = false;
            }
        }
        return valid;
    }

    private void register(Map<String, List<ProviderInfo>> map, String key, ProviderInfo provider) {
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(provider);
    }

    private void reportAmbiguity(String typeKey, List<ProviderInfo> providers) {
        String providerList = providers.stream()
                .map(p -> p.label + " (" + p.kind + ")")
                .collect(Collectors.joining(", "));
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

    private record ProviderInfo(Element element, String label, String kind) {}
}
