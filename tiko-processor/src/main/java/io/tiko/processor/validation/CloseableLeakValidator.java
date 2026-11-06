package io.tiko.processor.validation;

import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.FieldDecl;
import io.tiko.processor.core.model.TypeRef;
import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.util.ProcessorContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compile-time leak check: warns when a {@code @Component} holds an instance field
 * of an {@link AutoCloseable} type but has no destroy hook to release it.
 *
 * <p>A destroy hook is either an explicit {@code @PreDestroy} method on the
 * component or the component itself implementing {@code AutoCloseable} (which
 * the codegen turns into an implicit {@code close()} hook).</p>
 *
 * <p>This is a heuristic: not every closeable field is owned by the holder —
 * users sometimes hold a shared client passed in via {@code @Inject}. The
 * validator suppresses the warning for fields whose declared type matches a
 * constructor dependency (likely injected, not owned). For other false positives,
 * the user can suppress with {@code @SuppressWarnings("resource")} on the field
 * or the class.</p>
 *
 * <p>Unlike most validators in this package, this one only emits warnings — it
 * never fails the build.</p>
 */
public final class CloseableLeakValidator {

    private static final String AUTO_CLOSEABLE = "java.lang.AutoCloseable";
    private static final String SUPPRESS_WARNINGS = SuppressWarnings.class.getCanonicalName();
    private static final String SUPPRESS_TOKEN = "resource";

    private final ProcessorContext context;

    public CloseableLeakValidator(ProcessorContext context) {
        this.context = context;
    }

    /**
     * Always returns {@code true} — leak warnings never fail the build.
     */
    public boolean validate() {
        for (ComponentModel component : context.getActiveComponents()) {
            // Beans that already have a destroy hook are off the hook.
            if (component.hasDestroyHook()) continue;
            // Class-level suppression covers all fields.
            if (hasResourceSuppression(component.getDecl().annotations())) continue;
            scanFields(component);
        }
        return true;
    }

    private void scanFields(ComponentModel component) {
        Set<String> dependencyTypeNames = collectDependencyTypeNames(component);

        // Enum constants are static, so the static check leaves only instance fields.
        for (FieldDecl field : component.getDecl().fields()) {
            if (field.isStatic()) continue;
            if (hasResourceSuppression(field.annotations())) continue;

            TypeRef fieldType = field.type();
            if (!implementsAutoCloseable(fieldType)) continue;

            // Probably injected, not owned: skip.
            String fieldTypeName =
                    context.env().types().findClass(erasureName(fieldType)).isPresent()
                            ? erasureName(fieldType)
                            : fieldType.displayName();
            if (dependencyTypeNames.contains(fieldTypeName)) continue;

            context.env().diagnostics().warning(field.location(), buildMessage(component, field));
        }
    }

    private Set<String> collectDependencyTypeNames(ComponentModel component) {
        Set<String> names = new HashSet<>();
        for (DependencyModel dep : component.getDependencies()) {
            names.add(dep.getTypeName());
            dep.getUnwrappedType().ifPresent(t -> names.add(erasureName(t)));
        }
        return names;
    }

    private String erasureName(TypeRef type) {
        var erased = context.env().types().erasure(type);
        return erased.declaration().map(ClassDecl::qualifiedName).orElseGet(erased::displayName);
    }

    private boolean implementsAutoCloseable(TypeRef type) {
        var closeable = context.env().types().findClass(AUTO_CLOSEABLE);
        if (closeable.isEmpty()) return false;
        return context.env().types().isAssignable(type, closeable.get().asType());
    }

    private static boolean hasResourceSuppression(List<AnnotationRef> annotations) {
        return annotations.stream()
                .filter(annotation -> annotation.annotationName().equals(SUPPRESS_WARNINGS))
                .findFirst()
                .map(sw -> sw.strings("value").contains(SUPPRESS_TOKEN))
                .orElse(false);
    }

    private String buildMessage(ComponentModel component, FieldDecl field) {
        return "Tiko DI: @Component '" + component.getClassName()
                + "' holds AutoCloseable field '" + field.name()
                + "' (type: " + field.type().displayName()
                + ") but declares no @PreDestroy and does not implement AutoCloseable. "
                + "The held resource will leak when the bean is destroyed.\n\n"
                + "Suggested fixes:\n"
                + "1. Add a @PreDestroy method on " + component.getClassName()
                + " that closes " + field.name()
                + "\n2. Make " + component.getClassName()
                + " implement AutoCloseable (the container will call close() automatically)"
                + "\n3. Suppress with @SuppressWarnings(\"resource\") if the resource is owned elsewhere";
    }
}
