package io.tiko.processor.validation;

import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.ErrorReporter;
import io.tiko.processor.util.ProcessorContext;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;

/**
 * Validates that {@code @Produces} method signatures are ones the code generator can
 * actually realize.
 *
 * <p>Without these checks an unsupported signature slips past validation and the generated
 * factory fails to compile, surfacing a cascade of javac errors against generated source
 * the user never wrote (#165). Each rule here turns one of those into a single, located
 * Tiko error that names the method and suggests a fix:
 *
 * <ul>
 *   <li>{@code void} return — a factory must produce an instance.
 *   <li>primitive return — dependency injection resolves reference types.
 *   <li>non-{@code public} — the generated container invokes it from {@code io.tiko.generated}.
 *   <li>{@code abstract} — there is no body to produce an instance from.
 * </ul>
 */
public final class ProducesSignatureValidator {

    private final ProcessorContext context;

    public ProducesSignatureValidator(ProcessorContext context) {
        this.context = context;
    }

    /** Returns true if every active {@code @Produces} method has a supported signature. */
    public boolean validate() {
        boolean valid = true;
        for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
            if (!validateFactory(factory)) {
                valid = false;
            }
        }
        return valid;
    }

    private boolean validateFactory(FactoryMethodModel factory) {
        ExecutableElement method = factory.getMethodElement();
        String fqn = factory.getDeclaringClass().getQualifiedName() + "." + factory.getMethodName();
        TypeKind returnKind = factory.getReturnType().getKind();

        if (returnKind == TypeKind.VOID) {
            report(
                    method,
                    "@Produces method '" + fqn + "' must return a non-void type; factories produce instances.",
                    "Return the instance the factory creates instead of void",
                    "If the method only has side effects it is not a factory - remove @Produces");
            return false;
        }
        if (returnKind.isPrimitive()) {
            report(
                    method,
                    "@Produces method '" + fqn + "' must return a reference type, not the primitive '"
                            + factory.getReturnTypeName() + "'; dependency injection resolves object types.",
                    "Return the boxed type (e.g. Integer instead of int) or a domain type",
                    "Wrap the value in a small holder type and produce that");
            return false;
        }
        if (method.getModifiers().contains(Modifier.ABSTRACT)) {
            report(
                    method,
                    "@Produces method '" + fqn + "' must have a body; an abstract method cannot produce an instance.",
                    "Give the method a concrete body that returns the instance",
                    "Remove @Produces from the abstract method");
            return false;
        }
        if (!method.getModifiers().contains(Modifier.PUBLIC)) {
            report(
                    method,
                    "@Produces method '" + fqn
                            + "' must be public; the generated container invokes it from the io.tiko.generated package.",
                    "Add the public modifier to the method");
            return false;
        }
        return true;
    }

    private void report(ExecutableElement method, String problem, String... suggestedFixes) {
        context.getErrorReporter().error(ErrorReporter.KIND_BAD_PRODUCES, method, problem, suggestedFixes);
    }
}
