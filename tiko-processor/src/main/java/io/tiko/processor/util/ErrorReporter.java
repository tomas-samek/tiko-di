package io.tiko.processor.util;

import io.tiko.processor.model.WiringError;
import java.util.Objects;
import java.util.function.Consumer;
import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

/**
 * Utility for reporting errors during annotation processing.
 * Formats error messages with location, problem description, and suggested fixes.
 *
 * <p>Every reported error is also forwarded to a {@link Consumer} of
 * {@link WiringError} so {@code META-INF/tiko/wiring-errors.json} captures the same
 * structured diagnostics downstream tooling (e.g. {@code tiko-mcp}) consumes via the
 * {@code list_wiring_errors} tool. Source positions are best-effort — we only attempt
 * to derive an owning component FQN from the reported {@link Element}; per-file /
 * per-line resolution requires Trees / JavacTask access that the processor
 * deliberately avoids, so {@code sourceFile} is null and {@code line} is 0.
 */
public final class ErrorReporter {

    /** Valid {@code kind} values for {@link WiringError#kind()} — kept in sync with the schema. */
    public static final String KIND_OTHER = "OTHER";

    public static final String KIND_MISSING_DEPENDENCY = "MISSING_DEPENDENCY";
    public static final String KIND_CIRCULAR_DEPENDENCY = "CIRCULAR_DEPENDENCY";
    public static final String KIND_SCOPE_VIOLATION = "SCOPE_VIOLATION";
    public static final String KIND_AMBIGUOUS_QUALIFIER = "AMBIGUOUS_QUALIFIER";
    public static final String KIND_BAD_PRODUCES = "BAD_PRODUCES";

    private final Messager messager;
    private final Consumer<WiringError> wiringErrorSink;
    private boolean hasErrors = false;

    public ErrorReporter(Messager messager, Consumer<WiringError> wiringErrorSink) {
        this.messager = messager;
        this.wiringErrorSink = Objects.requireNonNull(wiringErrorSink, "wiringErrorSink");
    }

    /**
     * Reports an error at the given element location.
     */
    public void error(Element element, String message) {
        reportError(KIND_OTHER, element, message);
    }

    /**
     * Reports an error with suggested fixes.
     */
    public void error(Element element, String problem, String... suggestedFixes) {
        reportError(KIND_OTHER, element, problem, suggestedFixes);
    }

    /**
     * Reports an error and tags the structured {@link WiringError} with a specific
     * {@code kind} other than {@code OTHER}. Public so a handful of call sites that
     * already classify their failure (e.g. {@code BAD_PRODUCES} duplicate/conflict
     * detection in {@link ProcessorContext}) can pass that classification through
     * without a separate convenience method.
     */
    public void error(String kind, Element element, String problem, String... suggestedFixes) {
        reportError(kind, element, problem, suggestedFixes);
    }

    /**
     * Reports a warning at the given element location.
     */
    public void warning(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.WARNING, message, element);
    }

    /**
     * Reports a note (informational message) at the given element location.
     */
    public void note(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.NOTE, message, element);
    }

    /**
     * Returns true if any errors have been reported.
     */
    public boolean hasErrors() {
        return hasErrors;
    }

    /**
     * Resets the error state (useful for testing).
     */
    public void reset() {
        hasErrors = false;
    }

    // Convenience methods for common error patterns

    public void missingDependency(Element element, String dependencyKey, String injectingClass) {
        reportError(
                KIND_MISSING_DEPENDENCY,
                element,
                "Cannot resolve dependency: " + dependencyKey,
                "Add a @Component class of type " + dependencyKey,
                "Create a @Produces factory method that returns " + dependencyKey,
                "Check if the dependency has the correct qualifier (@Named)");
    }

    public void missingDependencyHiddenByExpose(
            Element element, String dependencyKey, String injectingClass, String hidingComponent) {
        reportError(
                KIND_MISSING_DEPENDENCY,
                element,
                hidingComponent + " implements " + dependencyKey
                        + " but does not expose it for injection (used by "
                        + injectingClass + ")",
                "Add " + dependencyKey + ".class to " + hidingComponent + "'s @Component(expose = {...}) list",
                "Drop the expose attribute on " + hidingComponent
                        + " to fall back to the permissive default (expose every implemented interface)",
                "Inject a different type that " + hidingComponent + " does expose");
    }

    public void circularDependency(Element element, String cycle) {
        reportError(
                KIND_CIRCULAR_DEPENDENCY,
                element,
                "Circular dependency detected: " + cycle,
                "Break the cycle by using Provider<T> for one of the dependencies",
                "Refactor to remove the circular dependency",
                "Consider using event-based communication instead of direct dependencies");
    }

    public void scopeViolation(Element element, String consumerScope, String dependencyScope, String dependencyType) {
        reportError(
                KIND_SCOPE_VIOLATION,
                element,
                "Scope violation: " + consumerScope + "-scoped bean cannot inject " + dependencyScope + "-scoped bean '"
                        + dependencyType + "'",
                "Make " + dependencyType + " implement an interface for proxy generation",
                "Change the scope of " + dependencyType + " to " + consumerScope + " or longer",
                "Use Provider<" + dependencyType + "> for lazy resolution");
    }

    public void missingInterface(Element element, String className, String reason) {
        // missingInterface is emitted exclusively from ScopeValidator's cross-scope path
        // (proxy generation requires an interface) — tag as SCOPE_VIOLATION so MCP
        // consumers can group the two cross-scope diagnostics together.
        reportError(
                KIND_SCOPE_VIOLATION,
                element,
                className + " must implement an interface. " + reason,
                "Extract an interface from " + className,
                "Make " + className + " implement an existing interface");
    }

    public void invalidAnnotationUsage(Element element, String annotationName, String reason) {
        reportError(
                KIND_OTHER,
                element,
                "Invalid use of " + annotationName + ": " + reason,
                "Review the " + annotationName + " usage against the Tiko documentation");
    }

    public void duplicateQualifier(Element element, String qualifier, String existingComponent) {
        reportError(
                KIND_AMBIGUOUS_QUALIFIER,
                element,
                "Duplicate qualifier '" + qualifier + "' - already used by " + existingComponent,
                "Use a different qualifier name",
                "Remove the qualifier if not needed",
                "Ensure each qualifier is unique within a type");
    }

    public void ambiguousProviders(Element element, String type, String providers, String simpleTypeName) {
        reportError(
                KIND_AMBIGUOUS_QUALIFIER,
                element,
                "Multiple unnamed providers for type " + type + ": " + providers,
                "Add @Named(\"...\") to each and use container.get(" + simpleTypeName + ".class, \"name\")",
                "Keep one provider unnamed as the default and give the others @Component(name = \"...\") or @Produces(name = \"...\")");
    }

    public void testComponentValueNotAssignable(Element element, String testClassFqn, String valueTypeFqn) {
        reportError(
                KIND_OTHER,
                element,
                "@TestComponent class " + testClassFqn + " is not assignable to declared value type " + valueTypeFqn
                        + ". The annotated class must extend or implement the value type to shadow it.",
                "Make " + testClassFqn + " extend or implement " + valueTypeFqn,
                "Remove the value attribute and let the implicit superclass walk pick the shadowed component",
                "Point value() at a type that " + testClassFqn + " actually implements");
    }

    public void testComponentScopeMismatch(
            javax.lang.model.element.Element element,
            String testFqn,
            io.tiko.Scope testScope,
            String mainFqn,
            io.tiko.Scope mainScope) {
        reportError(
                KIND_SCOPE_VIOLATION,
                element,
                String.format(
                        "Scope mismatch: @TestComponent %s declares scope %s but shadows @Component %s "
                                + "declared with scope %s. Either match the scope or use TikoOptions.override(...) "
                                + "for different lifecycle.",
                        testFqn, testScope, mainFqn, mainScope),
                "Declare @TestComponent " + testFqn + " with scope " + mainScope,
                "Use TikoOptions.override(...) when the test lifecycle must differ");
    }

    // ----- internal -----

    /**
     * Single error-reporting funnel: prints the Messager diagnostic and forwards a
     * structured {@link WiringError} to the sink. All other public methods route
     * here so the two outputs stay in lock-step.
     */
    private void reportError(String kind, Element element, String problem, String... suggestedFixes) {
        hasErrors = true;
        StringBuilder message = new StringBuilder(problem);
        if (suggestedFixes != null && suggestedFixes.length > 0) {
            message.append("\n\nSuggested fixes:");
            for (int i = 0; i < suggestedFixes.length; i++) {
                message.append("\n").append(i + 1).append(". ").append(suggestedFixes[i]);
            }
        }
        messager.printMessage(Diagnostic.Kind.ERROR, message.toString(), element);
        String firstFix = (suggestedFixes != null && suggestedFixes.length > 0) ? suggestedFixes[0] : null;
        wiringErrorSink.accept(new WiringError(kind, null, 0, deriveComponentFqn(element), problem, firstFix));
    }

    /**
     * Returns the FQN of the nearest enclosing {@link TypeElement}, walking up the
     * element chain so a {@code VariableElement} (parameter), {@code ExecutableElement}
     * (method/constructor), or {@code TypeElement} (class) all resolve to their owning
     * component class. Returns {@code null} when no enclosing type can be found.
     */
    private static String deriveComponentFqn(Element element) {
        if (element == null) return null;
        Element cursor = element;
        while (cursor != null && !(cursor instanceof TypeElement)) {
            cursor = cursor.getEnclosingElement();
        }
        if (cursor instanceof TypeElement type) {
            return type.getQualifiedName().toString();
        }
        return null;
    }
}
