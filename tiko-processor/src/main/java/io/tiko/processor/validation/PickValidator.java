package io.tiko.processor.validation;

import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.ProcessorContext;
import java.util.List;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Validates {@code @Pick} usage on injection-point parameters.
 *
 * <p>Rules enforced (intra-module — cross-module manifest validation is layered on top
 * separately):
 * <ol>
 *   <li>The picked class must be assignable to the parameter's declared type
 *       (or to the {@code T} of {@code Provider<T>}).</li>
 *   <li>{@code @Pick} cannot reference the parameter's declared type itself —
 *       that's plain {@code @Inject}, no disambiguation needed.</li>
 *   <li>{@code @Pick} and {@code @Named} cannot coexist on one parameter; they're
 *       alternative qualifier mechanisms.</li>
 *   <li>{@code @Pick} cannot be applied to collection parameters
 *       ({@code Set<T>}, {@code List<T>}, {@code Iterable<T>}) — picking selects
 *       a single bean.</li>
 *   <li>The picked class must not be ambiguously produced by multiple
 *       {@code @Produces} factory methods returning the same type. With multiple
 *       producers the class literal cannot disambiguate; the user must reach for
 *       {@code @Named}.</li>
 * </ol>
 *
 * <p>Existence of the picked class as a registered component or factory return type
 * is not enforced here in commit 1 — {@link DependencyGraphValidator} already reports
 * "Cannot resolve dependency" when no provider is found, with the picked-class FQN as
 * the dependency key. Cross-module visibility comes with the {@code components.txt}
 * manifest in a follow-up commit.
 */
public final class PickValidator {

    private final ProcessorContext context;

    public PickValidator(ProcessorContext context) {
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
        if (!dep.isPicked()) {
            return true;
        }
        VariableElement param = dep.getParameter();
        TypeMirror pickedType = dep.getPickedType().orElseThrow();
        String pickedFqn = dep.getPickedTypeName().orElseThrow();

        // Rule 4: @Pick + @Named is forbidden — pick one mechanism.
        if (dep.getQualifier().isPresent()) {
            context.getErrorReporter()
                    .error(
                            param,
                            "@Pick and @Named cannot be combined on the same parameter",
                            "Use @Pick(" + simpleName(pickedFqn)
                                    + ".class) when disambiguating between @Component impls",
                            "Use @Named(\"" + dep.getQualifier().orElse("...")
                                    + "\") when disambiguating between @Produces factory methods");
            return false;
        }

        // Rule 5: collection/iterable injection isn't a pick site.
        if (isCollectionType(dep.getType())) {
            context.getErrorReporter()
                    .error(
                            param,
                            "@Pick cannot be applied to collection or iterable parameters",
                            "Use @Pick on a single-bean parameter (T or Provider<T>)",
                            "For multi-impl iteration, inject Set<T> or List<T> without @Pick");
            return false;
        }

        // The type the picked class must be assignable to: T for plain T, T for Provider<T>.
        TypeMirror targetType = dep.getUnwrappedType().orElse(dep.getType());

        // Rule 3: redundant @Pick — picked class equals the parameter type.
        if (context.getTypeUtils().isSameType(pickedType, targetType)) {
            context.getErrorReporter()
                    .error(
                            param,
                            "@Pick references the parameter type itself — no disambiguation possible",
                            "Remove @Pick when there is no specific implementation to choose",
                            "Reference a concrete @Component class implementing " + simpleName(toFqn(targetType)));
            return false;
        }

        // Rule 1: assignability.
        if (!context.getTypeUtils().isAssignable(pickedType, targetType)) {
            context.getErrorReporter()
                    .error(
                            param,
                            "@Pick(" + simpleName(pickedFqn) + ".class) is not assignable to parameter type "
                                    + simpleName(toFqn(targetType)),
                            "Reference a class implementing or extending " + simpleName(toFqn(targetType)),
                            "Change the parameter type to match " + simpleName(pickedFqn));
            return false;
        }

        // Rule 6: ambiguous @Produces target.
        List<Object> matches = context.findAllByImplClass(pickedFqn);
        if (matches.size() > 1) {
            context.getErrorReporter()
                    .error(
                            param,
                            "@Pick(" + simpleName(pickedFqn) + ".class) is ambiguous: " + matches.size()
                                    + " providers return this type",
                            "Use @Named(\"...\") to disambiguate between multiple @Produces methods",
                            "@Pick can only resolve when exactly one provider produces the picked type");
            return false;
        }

        return true;
    }

    private boolean isCollectionType(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        String fqn = toFqn(type);
        return "java.util.Set".equals(fqn)
                || "java.util.List".equals(fqn)
                || "java.util.Collection".equals(fqn)
                || "java.lang.Iterable".equals(fqn)
                || "java.util.Map".equals(fqn);
    }

    private String toFqn(TypeMirror type) {
        if (type instanceof DeclaredType declared
                && declared.asElement() instanceof javax.lang.model.element.TypeElement te) {
            return te.getQualifiedName().toString();
        }
        return type.toString();
    }

    private static String simpleName(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
    }
}
