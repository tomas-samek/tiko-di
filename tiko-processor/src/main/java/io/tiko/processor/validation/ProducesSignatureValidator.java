package io.tiko.processor.validation;

import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.ErrorReporter;
import io.tiko.processor.util.ProcessorContext;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

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
 *   <li>generic return — Tiko wires by exact type and a generic type erases to its raw
 *       class ({@code List<String>} and {@code List<Integer>} collapse to one key), so it
 *       cannot be resolved unambiguously; emitting {@code List<String>.class} in the
 *       dispatcher is also not legal Java. The fix is a named interface that fixes the type
 *       arguments — {@code interface MyParamList extends List<MyEntity> {}} (#327).
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
        if (isGenericType(factory.getReturnType())) {
            report(
                    method,
                    "@Produces method '" + fqn + "' must return a non-generic type, not '" + factory.getReturnType()
                            + "'. Tiko wires by exact type and a generic type erases to its raw class"
                            + " (List<String> and List<Integer> collapse to the same key), so it cannot be"
                            + " resolved unambiguously.",
                    "Declare a named interface that fixes the type arguments and return that, e.g."
                            + " 'interface MyParamList extends List<MyEntity> {}' then '@Produces MyParamList'",
                    "If you only need a single value, return its element type directly instead of a collection");
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

    /**
     * True when {@code type} is a generic type — its declaring element has type parameters.
     * This catches both the parameterized form ({@code List<String>}) and the raw form
     * ({@code List}): both key on the same erased FQN and are unresolvable by Tiko's
     * exact-type wiring. A named interface that <em>extends</em> a parameterized type
     * (e.g. {@code interface MyParamList extends List<MyEntity> {}}) declares no type
     * parameters of its own, so it is allowed — that is the intended workaround (#327).
     * Arrays and other non-declared types are never generic here.
     */
    private static boolean isGenericType(TypeMirror type) {
        return type instanceof DeclaredType declared
                && declared.asElement() instanceof TypeElement element
                && !element.getTypeParameters().isEmpty();
    }

    private void report(ExecutableElement method, String problem, String... suggestedFixes) {
        context.getErrorReporter().error(ErrorReporter.KIND_BAD_PRODUCES, method, problem, suggestedFixes);
    }
}
