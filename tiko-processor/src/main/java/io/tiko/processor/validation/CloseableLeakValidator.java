package io.tiko.processor.validation;

import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.util.ProcessorContext;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.HashSet;
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
            if (hasResourceSuppression(component.getTypeElement())) continue;
            scanFields(component);
        }
        return true;
    }

    private void scanFields(ComponentModel component) {
        Set<String> dependencyTypeNames = collectDependencyTypeNames(component);

        for (Element enclosed : component.getTypeElement().getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) continue;
            VariableElement field = (VariableElement) enclosed;
            if (field.getModifiers().contains(Modifier.STATIC)) continue;
            if (hasResourceSuppression(field)) continue;

            TypeMirror fieldType = field.asType();
            if (!implementsAutoCloseable(fieldType)) continue;

            // Probably injected, not owned: skip.
            String fieldTypeName = context.getElementUtils()
                    .getTypeElement(erasureName(fieldType)) != null
                ? erasureName(fieldType) : fieldType.toString();
            if (dependencyTypeNames.contains(fieldTypeName)) continue;

            context.getProcessingEnv().getMessager().printMessage(
                Diagnostic.Kind.WARNING,
                buildMessage(component, field),
                field
            );
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

    private String erasureName(TypeMirror type) {
        TypeMirror erased = context.getProcessingEnv().getTypeUtils().erasure(type);
        Element el = context.getProcessingEnv().getTypeUtils().asElement(erased);
        if (el instanceof TypeElement te) {
            return te.getQualifiedName().toString();
        }
        return erased.toString();
    }

    private boolean implementsAutoCloseable(TypeMirror type) {
        TypeElement closeable = context.getElementUtils().getTypeElement(AUTO_CLOSEABLE);
        if (closeable == null) return false;
        return context.getProcessingEnv().getTypeUtils().isAssignable(type, closeable.asType());
    }

    private boolean hasResourceSuppression(Element element) {
        SuppressWarnings sw = element.getAnnotation(SuppressWarnings.class);
        if (sw == null) return false;
        for (String value : sw.value()) {
            if (SUPPRESS_TOKEN.equals(value)) return true;
        }
        return false;
    }

    private String buildMessage(ComponentModel component, VariableElement field) {
        return "Tiko DI: @Component '" + component.getClassName()
            + "' holds AutoCloseable field '" + field.getSimpleName()
            + "' (type: " + field.asType()
            + ") but declares no @PreDestroy and does not implement AutoCloseable. "
            + "The held resource will leak when the bean is destroyed.\n\n"
            + "Suggested fixes:\n"
            + "1. Add a @PreDestroy method on " + component.getClassName()
            + " that closes " + field.getSimpleName()
            + "\n2. Make " + component.getClassName()
            + " implement AutoCloseable (the container will call close() automatically)"
            + "\n3. Suppress with @SuppressWarnings(\"resource\") if the resource is owned elsewhere";
    }
}
