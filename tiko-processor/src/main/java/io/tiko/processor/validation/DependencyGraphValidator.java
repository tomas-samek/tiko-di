package io.tiko.processor.validation;

import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.ProcessorContext;
import io.tiko.processor.util.TypeUtil;
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;

/**
 * Validates the complete dependency graph.
 *
 * Checks:
 * - All dependencies can be resolved
 * - No missing components
 * - Factory method declaring classes are available (for instance methods)
 *
 * When a dependency cannot be resolved, also detects the specific case where a
 * {@code @Component} class implements the requested type but does not expose it
 * via {@code @Component(expose = {...})}. That distinct error helps users with
 * Spring-style mental models notice the opt-in restriction.
 */
public final class DependencyGraphValidator {

    private final ProcessorContext context;
    private final TypeUtil typeUtil;

    public DependencyGraphValidator(ProcessorContext context, TypeUtil typeUtil) {
        this.context = context;
        this.typeUtil = typeUtil;
    }

    /**
     * Validates the entire dependency graph.
     * Returns true if validation passes, false if errors were reported.
     */
    public boolean validate() {
        boolean valid = true;

        // Validate all component dependencies
        for (ComponentModel component : context.getActiveComponents()) {
            if (!validateComponent(component)) {
                valid = false;
            }
        }

        // Validate all factory method dependencies
        for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
            if (!validateFactory(factory)) {
                valid = false;
            }
        }

        return valid;
    }

    /**
     * Validates all dependencies of a component.
     */
    private boolean validateComponent(ComponentModel component) {
        boolean valid = true;

        for (DependencyModel dependency : component.getDependencies()) {
            // Picker<T> doesn't resolve to a single provider — its existence check
            // ("at least one impl of T") lives in PickerValidator.
            if (dependency.isPicker()) {
                continue;
            }
            // EventBus is a built-in, container-provided dependency (#314) — not a user bean.
            if (dependency.isEventBus()) {
                continue;
            }
            String depKey = dependency.getDependencyKey();
            boolean resolved = dependency.isPicked()
                    ? (dependency.getQualifier().isPresent()
                            ? context.findComponentOrFactory(depKey).isPresent()
                            : context.findByImplClass(depKey).isPresent())
                    : context.findComponentOrFactory(depKey).isPresent();
            if (!resolved) {
                reportUnresolved(component.getTypeElement(), depKey, component.getClassName());
                valid = false;
            }
        }

        return valid;
    }

    /**
     * Validates all dependencies of a factory method.
     */
    private boolean validateFactory(FactoryMethodModel factory) {
        boolean valid = true;

        // For instance methods, validate that the declaring class is available as a component
        if (!factory.isStatic()) {
            String declaringClassKey =
                    factory.getDeclaringClass().getQualifiedName().toString();

            if (!context.getComponents().containsKey(declaringClassKey)) {
                context.getErrorReporter()
                        .error(
                                factory.getMethodElement(),
                                "Factory method " + factory.getMethodName() + " is an instance method, "
                                        + "but declaring class "
                                        + factory.getDeclaringClass().getSimpleName()
                                        + " is not registered as a @Component",
                                "Add @Component annotation to "
                                        + factory.getDeclaringClass().getSimpleName(),
                                "Make the factory method static if it doesn't need component dependencies");
                valid = false;
            }
        }

        // Validate factory method's own dependencies
        for (DependencyModel dependency : factory.getDependencies()) {
            if (dependency.isPicker()) {
                continue;
            }
            // EventBus is a built-in, container-provided dependency (#314) — not a user bean.
            if (dependency.isEventBus()) {
                continue;
            }
            String depKey = dependency.getDependencyKey();
            boolean resolved = dependency.isPicked()
                    ? (dependency.getQualifier().isPresent()
                            ? context.findComponentOrFactory(depKey).isPresent()
                            : context.findByImplClass(depKey).isPresent())
                    : context.findComponentOrFactory(depKey).isPresent();
            if (!resolved) {
                reportUnresolved(factory.getMethodElement(), depKey, factory.getFactoryIdentifier());
                valid = false;
            }
        }

        return valid;
    }

    /**
     * Reports a missing dependency. If a {@code @Component} class implements the requested
     * type but doesn't expose it via {@code @Component(expose = {...})}, emit the targeted
     * diagnostic instead of the generic "cannot resolve" message.
     */
    private void reportUnresolved(Element location, String depKey, String injectingIdentifier) {
        ComponentModel hider = findComponentHidingType(depKey);
        if (hider != null) {
            context.getErrorReporter()
                    .missingDependencyHiddenByExpose(location, depKey, injectingIdentifier, hider.getQualifiedName());
            return;
        }
        context.getErrorReporter().missingDependency(location, depKey, injectingIdentifier);
    }

    /**
     * Returns the first {@code @Component} whose class is assignable to {@code depKey} but
     * that does not expose {@code depKey} — i.e. an opted-in restriction is hiding the
     * type from injection routing. Returns {@code null} if no such component exists.
     */
    private ComponentModel findComponentHidingType(String depKey) {
        var depTypeElement = typeUtil.getTypeElement(depKey).orElse(null);
        if (depTypeElement == null) return null;
        TypeMirror depType = depTypeElement.asType();

        for (ComponentModel component : context.getActiveComponents()) {
            if (!component.isExposeRestricted() && component.isExposeSelf()) continue;
            TypeMirror componentType = component.getTypeElement().asType();
            if (!typeUtil.isAssignable(componentType, depType)) continue;
            if (exposes(component, depType, componentType)) continue;
            return component;
        }
        return null;
    }

    /**
     * Returns true when {@code component} routes injection requests for {@code requested}.
     * Considers both the explicit {@code expose} list (if restricted) or the default set
     * of directly-implemented interfaces, plus the impl class itself when
     * {@code exposeSelf} is true.
     */
    private boolean exposes(ComponentModel component, TypeMirror requested, TypeMirror componentType) {
        if (component.isExposeSelf() && typeUtil.isSameType(requested, componentType)) {
            return true;
        }
        var declared = component.isExposeRestricted()
                ? component.getExposeTypes()
                : component.getTypeElement().getInterfaces();
        for (TypeMirror exposed : declared) {
            if (typeUtil.isSameType(requested, exposed)) {
                return true;
            }
        }
        return false;
    }
}
