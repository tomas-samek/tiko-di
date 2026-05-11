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
 *   <li>{@code @Pick} cannot be applied to collection parameters
 *       ({@code Set<T>}, {@code List<T>}, {@code Iterable<T>}) — picking selects
 *       a single bean.</li>
 *   <li>Without {@code @Named}, the picked class must not be ambiguously produced
 *       by multiple {@code @Produces} factory methods returning the same type. With
 *       multiple producers the class literal alone cannot disambiguate — combine
 *       {@code @Pick(X.class)} with {@code @Named("...")} to select one.</li>
 * </ol>
 *
 * <p>{@code @Pick} composes with {@code @Named}: the pair narrows by impl class first,
 * then disambiguates among that impl's named providers. Existence of the picked class
 * as a registered component or factory return type is not enforced here —
 * {@link DependencyGraphValidator} already reports "Cannot resolve dependency" when no
 * provider matches the (impl, optional name) lookup.
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

        // Rule: collection/iterable injection isn't a pick site.
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

        // Rule: redundant @Pick — picked class equals the parameter type.
        if (context.getTypeUtils().isSameType(pickedType, targetType)) {
            context.getErrorReporter()
                    .error(
                            param,
                            "@Pick references the parameter type itself — no disambiguation possible",
                            "Remove @Pick when there is no specific implementation to choose",
                            "Reference a concrete @Component class implementing " + simpleName(toFqn(targetType)));
            return false;
        }

        // Rule: assignability.
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

        // Rule: ambiguous @Produces target. Skipped when @Named is present — the
        // qualifier picks one specific producer among the matches.
        if (dep.getQualifier().isEmpty()) {
            List<Object> matches = context.findAllByImplClass(pickedFqn);
            if (matches.size() > 1) {
                context.getErrorReporter()
                        .error(
                                param,
                                "@Pick(" + simpleName(pickedFqn) + ".class) is ambiguous: " + matches.size()
                                        + " providers return this type",
                                "Add @Named(\"...\") to select one of the @Produces methods",
                                "@Pick alone resolves only when exactly one provider produces the picked type");
                return false;
            }
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
