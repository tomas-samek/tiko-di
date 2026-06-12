package io.tiko.processor.validation;

import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.model.EventHandlerModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.ProcessorContext;
import io.tiko.processor.util.TypeUtil;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

/**
 * Structural checks that must pass before any other validator or generator touches the
 * collected models (#333). Each case here used to surface as a raw javac error inside
 * generated sources or as a processor stack trace:
 *
 * <ul>
 *   <li>raw {@code Provider} / {@code Picker} injection points (no type argument) — the
 *       dependency key derefs the missing argument and NPEs the processor;</li>
 *   <li>abstract or non-static nested {@code @Component} classes — the generated factory
 *       cannot instantiate them;</li>
 *   <li>two components sharing a simple name — per-simple-name factories and getters
 *       collide ({@code FilerException});</li>
 *   <li>{@code @EventHandler} methods on classes that are not components — the generated
 *       registry references a getter that does not exist.</li>
 * </ul>
 *
 * <p>The caller short-circuits on failure: downstream validators assume well-shaped
 * models, so running them against a malformed one reintroduces the crashes this
 * validator exists to prevent.
 */
public final class ComponentShapeValidator {

    private final ProcessorContext context;
    private final TypeUtil typeUtil;

    public ComponentShapeValidator(ProcessorContext context, TypeUtil typeUtil) {
        this.context = context;
        this.typeUtil = typeUtil;
    }

    public boolean validate() {
        boolean valid = true;

        Map<String, List<ComponentModel>> bySimpleName = new LinkedHashMap<>();
        for (ComponentModel component : context.getComponents().values()) {
            valid &= validateInstantiable(component);
            valid &= validateDependencies(component.getTypeElement(), component.getDependencies());
            bySimpleName
                    .computeIfAbsent(component.getClassName(), k -> new java.util.ArrayList<>())
                    .add(component);
        }
        for (FactoryMethodModel factory : context.getFactoryMethods()) {
            valid &= validateDependencies(factory.getMethodElement(), factory.getDependencies());
        }

        for (Map.Entry<String, List<ComponentModel>> entry : bySimpleName.entrySet()) {
            List<ComponentModel> sharing = entry.getValue();
            if (sharing.size() <= 1) continue;
            String fqns = sharing.stream().map(ComponentModel::getQualifiedName).collect(Collectors.joining(", "));
            context.getErrorReporter().duplicateSimpleNames(sharing.get(0).getTypeElement(), entry.getKey(), fqns);
            valid = false;
        }

        for (EventHandlerModel handler : context.getEventHandlers()) {
            String declaringFqn = handler.getDeclaringClass().getQualifiedName().toString();
            boolean onComponent = context.getComponents().values().stream()
                    .anyMatch(c -> c.getQualifiedName().equals(declaringFqn));
            if (!onComponent) {
                context.getErrorReporter()
                        .eventHandlerOutsideComponent(
                                handler.getMethodElement(),
                                handler.getDeclaringClass().getSimpleName().toString());
                valid = false;
            }
        }

        return valid;
    }

    private boolean validateInstantiable(ComponentModel component) {
        TypeElement el = component.getTypeElement();
        if (!typeUtil.isConcreteClass(el)) {
            context.getErrorReporter()
                    .nonInstantiableComponent(
                            el,
                            component.getClassName(),
                            "it is not a concrete class",
                            "Make " + component.getClassName() + " a concrete (non-abstract) class",
                            "Remove the @Component annotation");
            return false;
        }
        if (el.getNestingKind().isNested() && !el.getModifiers().contains(Modifier.STATIC)) {
            context.getErrorReporter()
                    .nonInstantiableComponent(
                            el,
                            component.getClassName(),
                            "non-static nested classes need an enclosing instance the container cannot provide",
                            "Declare " + component.getClassName() + " as a static nested class",
                            "Move " + component.getClassName() + " to the top level");
            return false;
        }
        return true;
    }

    private boolean validateDependencies(Element owner, List<DependencyModel> dependencies) {
        boolean valid = true;
        for (DependencyModel dep : dependencies) {
            boolean rawWrapper = (dep.isProvider() || dep.isPicker())
                    && dep.getUnwrappedType().isEmpty();
            if (rawWrapper) {
                String wrapper = dep.isProvider() ? "Provider" : "Picker";
                context.getErrorReporter().rawWrapperInjection(dep.getParameter(), owner, wrapper);
                valid = false;
            }
        }
        return valid;
    }
}
