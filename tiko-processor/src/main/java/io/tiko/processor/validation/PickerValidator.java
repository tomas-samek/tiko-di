package io.tiko.processor.validation;

import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.ProcessorContext;
import javax.lang.model.type.TypeMirror;

/**
 * Validates {@code Picker<T>} injection points.
 *
 * <p>Rule: at least one impl assignable to {@code T} must be reachable — either a
 * registered {@code @Component} or a {@code @Produces} factory output, in this module.
 * A picker with zero possible impls is almost always a typo (wrong type parameter,
 * forgot to add the impl, etc.) and silently injecting an empty picker would lead to
 * a confusing runtime "no impls" error far from the actual mistake.
 *
 * <p>Cross-module pickers (where the impls live in another module) are accepted by
 * this validator without checking the manifest — the runtime aggregator handles the
 * lookup, and the {@code Picker<T>} construction is harmless even if no impls
 * exist locally.
 */
public final class PickerValidator {

    private final ProcessorContext context;

    public PickerValidator(ProcessorContext context) {
        this.context = context;
    }

    public boolean validate() {
        boolean valid = true;
        for (ComponentModel component : context.getActiveComponents()) {
            for (DependencyModel dep : component.getDependencies()) {
                if (!validateDep(dep)) {
                    valid = false;
                }
            }
        }
        for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
            for (DependencyModel dep : factory.getDependencies()) {
                if (!validateDep(dep)) {
                    valid = false;
                }
            }
        }
        return valid;
    }

    private boolean validateDep(DependencyModel dep) {
        if (!dep.isPicker()) {
            return true;
        }
        TypeMirror baseType = dep.getUnwrappedType().orElseThrow();
        String baseFqn = dep.getDependencyKey();

        // Local impls — anything assignable to the picker's base type. Counts both
        // @Component classes (matched against their declared type) and @Produces
        // outputs (matched against return type).
        boolean hasLocalImpl = anyComponentAssignableTo(baseType) || anyFactoryAssignableTo(baseType);
        if (hasLocalImpl) {
            return true;
        }

        // No local impl. We do NOT enforce cross-module visibility here in v1 — the
        // runtime aggregator will return an empty list rather than throwing, and the
        // caller's `picker.list()` simply yields nothing. If/when cross-module manifest
        // reading lands, this is the place to consult it for a better diagnostic.
        // For now: warn so the user notices, don't fail.
        context.getErrorReporter()
                .warning(
                        dep.getParameter(),
                        "Picker<" + simpleName(baseFqn)
                                + "> has no impls registered in this module. If the impls live in another module this is fine; otherwise the picker will always be empty.");
        return true;
    }

    private boolean anyComponentAssignableTo(TypeMirror baseType) {
        for (ComponentModel component : context.getActiveComponents()) {
            if (context.getTypeUtils().isAssignable(component.getTypeElement().asType(), baseType)) {
                return true;
            }
        }
        return false;
    }

    private boolean anyFactoryAssignableTo(TypeMirror baseType) {
        for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
            if (context.getTypeUtils().isAssignable(factory.getReturnType(), baseType)) {
                return true;
            }
        }
        return false;
    }

    private static String simpleName(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
    }
}
