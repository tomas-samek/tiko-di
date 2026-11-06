package io.tiko.processor.validation;

import io.tiko.processor.core.model.TypeRef;
import io.tiko.processor.util.ProcessorContext;
import io.tiko.processor.util.TypeUtil;

/**
 * Verifies that every type listed in {@code @Component(expose = {...})} is actually
 * implemented by the annotated class (or is the class itself). The annotation processor
 * rejects mismatches with a clear, fix-suggesting error.
 *
 * <p>Empty {@code expose} lists are skipped — that is the permissive default and validation
 * does not apply.
 */
public final class ExposureValidator {

    private final ProcessorContext context;
    private final TypeUtil typeUtil;

    public ExposureValidator(ProcessorContext context, TypeUtil typeUtil) {
        this.context = context;
        this.typeUtil = typeUtil;
    }

    public boolean validate() {
        boolean ok = true;
        for (var component : context.getActiveComponents()) {
            if (!component.isExposeRestricted()) continue;
            var componentType = component.getDecl().asType();
            for (TypeRef exposed : component.getExposeTypes()) {
                if (!typeUtil.isAssignable(componentType, exposed)) {
                    context.getErrorReporter()
                            .error(
                                    component.getDecl().location(),
                                    "@Component(expose = {...}) lists " + exposed.displayName() + ", but "
                                            + component.getQualifiedName()
                                            + " does not implement or extend it",
                                    "Make " + component.getClassName() + " implement " + exposed.displayName(),
                                    "Remove " + exposed.displayName() + " from the expose list",
                                    "Drop the expose attribute entirely to fall back to the permissive default "
                                            + "(expose every implemented interface)");
                    ok = false;
                }
            }
        }
        return ok;
    }
}
