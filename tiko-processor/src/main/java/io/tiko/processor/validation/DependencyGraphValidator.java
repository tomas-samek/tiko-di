package io.tiko.processor.validation;

import io.tiko.processor.core.model.Location;
import io.tiko.processor.core.model.TypeRef;
import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.ProcessorContext;
import io.tiko.processor.util.TypeUtil;

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
 *
 * <p>An absence that only follows from a component rejected during collection (a dependency it
 * would have provided, an instance factory declared on it) fails validation without a report of
 * its own: the rejection's located error already names the cause (#589).
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
            // Skip dependencies that don't resolve to a single user-declared provider here:
            //  - Picker<T>: its "at least one impl of T" check lives in PickerValidator.
            //  - EventBus: a built-in, container-provided dependency (#314), not a user bean.
            if (dependency.isPicker() || dependency.isEventBus()) {
                continue;
            }
            if (!isResolved(dependency)) {
                reportUnresolved(component.getDecl().location(), dependency, component.getClassName());
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
            String declaringClassKey = factory.getDeclaringClass().qualifiedName();

            if (context.isRejected(factory.getDeclaringClass())) {
                valid = false; // the declaring component's rejection was reported
            } else if (!context.getComponents().containsKey(declaringClassKey)) {
                context.getErrorReporter()
                        .error(
                                factory.getMethod().location(),
                                "Factory method " + factory.getMethodName() + " is an instance method, "
                                        + "but declaring class "
                                        + factory.getDeclaringClass()
                                                .className()
                                                .simpleName()
                                        + " is not registered as a @Component",
                                "Add @Component annotation to "
                                        + factory.getDeclaringClass()
                                                .className()
                                                .simpleName(),
                                "Make the factory method static if it doesn't need component dependencies");
                valid = false;
            }
        }

        // Validate factory method's own dependencies
        for (DependencyModel dependency : factory.getDependencies()) {
            // Picker<T> (checked in PickerValidator) and the built-in EventBus (#314) don't
            // resolve to a user-declared provider here.
            if (dependency.isPicker() || dependency.isEventBus()) {
                continue;
            }
            if (!isResolved(dependency)) {
                reportUnresolved(factory.getMethod().location(), dependency, factory.getFactoryIdentifier());
                valid = false;
            }
        }

        return valid;
    }

    private boolean isResolved(DependencyModel dependency) {
        String depKey = dependency.getDependencyKey();
        if (dependency.isPicked() && dependency.getQualifier().isEmpty()) {
            return context.findByImplClass(depKey).isPresent();
        }
        return context.findComponentOrFactory(depKey).isPresent();
    }

    /**
     * Reports a missing dependency, unless a rejected component would have provided it. If a
     * {@code @Component} class implements the requested type but doesn't expose it via
     * {@code @Component(expose = {...})}, emit the targeted diagnostic instead of the generic
     * "cannot resolve" message.
     */
    private void reportUnresolved(Location location, DependencyModel dependency, String injectingIdentifier) {
        if (providedByRejectedComponent(dependency)) {
            return; // the rejection was reported; this absence only follows from it
        }
        String depKey = dependency.getDependencyKey();
        ComponentModel hider = findComponentHidingType(depKey);
        if (hider != null) {
            context.getErrorReporter()
                    .missingDependencyHiddenByExpose(location, depKey, injectingIdentifier, hider.getQualifiedName());
            return;
        }
        context.getErrorReporter().missingDependency(location, depKey);
    }

    /**
     * Whether a rejected component would have been a candidate for the dependency: its class is
     * the picked class, or is assignable to the requested type under a matching (or no) qualifier.
     */
    private boolean providedByRejectedComponent(DependencyModel dependency) {
        String qualifier = dependency.getQualifier().orElse("");
        TypeRef requested = dependency.getUnwrappedType().orElse(dependency.getType());
        for (var rejected : context.getRejectedComponents()) {
            if (!qualifier.isEmpty() && !qualifier.equals(rejected.name())) continue;
            TypeRef rejectedType = rejected.decl().asType();
            boolean candidate = dependency
                    .getPickedType()
                    .map(picked -> typeUtil.isSameType(rejectedType, picked))
                    .orElseGet(() -> typeUtil.isAssignable(rejectedType, requested));
            if (candidate) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the first {@code @Component} whose class is assignable to {@code depKey} but
     * that does not expose {@code depKey} — i.e. an opted-in restriction is hiding the
     * type from injection routing. Returns {@code null} if no such component exists.
     */
    private ComponentModel findComponentHidingType(String depKey) {
        var depTypeDecl = typeUtil.findClass(depKey).orElse(null);
        if (depTypeDecl == null) return null;
        TypeRef depType = depTypeDecl.asType();

        for (ComponentModel component : context.getActiveComponents()) {
            if (!component.isExposeRestricted() && component.isExposeSelf()) continue;
            TypeRef componentType = component.getDecl().asType();
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
    private boolean exposes(ComponentModel component, TypeRef requested, TypeRef componentType) {
        if (component.isExposeSelf() && typeUtil.isSameType(requested, componentType)) {
            return true;
        }
        var declared = component.isExposeRestricted()
                ? component.getExposeTypes()
                : component.getDecl().interfaces();
        for (TypeRef exposed : declared) {
            if (typeUtil.isSameType(requested, exposed)) {
                return true;
            }
        }
        return false;
    }
}
