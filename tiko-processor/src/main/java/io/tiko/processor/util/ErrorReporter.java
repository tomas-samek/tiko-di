package io.tiko.processor.util;

import io.tiko.processor.core.Diagnostics;
import io.tiko.processor.core.model.Location;
import io.tiko.processor.model.WiringError;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Utility for reporting errors during annotation processing.
 * Formats error messages with location, problem description, and suggested fixes.
 *
 * <p>Every reported error is also forwarded to a {@link Consumer} of
 * {@link WiringError} so {@code META-INF/tiko/wiring-errors.json} captures the same
 * structured diagnostics downstream tooling (e.g. {@code tiko-mcp}) consumes via the
 * {@code list_wiring_errors} tool. Source positions are best-effort — we only attempt
 * to derive an owning component FQN from the reported {@link Location}; per-file /
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
    /** A Kotlin construct Tiko rejects because it would add DI semantics Java has no counterpart for. */
    public static final String KOTLIN_UNSUPPORTED = "KOTLIN_UNSUPPORTED";
    /** A provider's nullability differs from its injection point's (they must match exactly). */
    public static final String NULLABILITY_MISMATCH = "NULLABILITY_MISMATCH";

    private final Diagnostics diagnostics;
    private final Consumer<WiringError> wiringErrorSink;
    private boolean hasErrors = false;

    public ErrorReporter(Diagnostics diagnostics, Consumer<WiringError> wiringErrorSink) {
        this.diagnostics = diagnostics;
        this.wiringErrorSink = Objects.requireNonNull(wiringErrorSink, "wiringErrorSink");
    }

    /**
     * Reports an error at the given location.
     */
    public void error(Location at, String message) {
        reportError(KIND_OTHER, at, message);
    }

    /**
     * Reports an error with suggested fixes.
     */
    public void error(Location at, String problem, String... suggestedFixes) {
        reportError(KIND_OTHER, at, problem, suggestedFixes);
    }

    /**
     * Reports an error and tags the structured {@link WiringError} with a specific
     * {@code kind} other than {@code OTHER}. Public so a handful of call sites that
     * already classify their failure (e.g. {@code BAD_PRODUCES} duplicate/conflict
     * detection in {@link ProcessorContext}) can pass that classification through
     * without a separate convenience method.
     */
    public void error(String kind, Location at, String problem, String... suggestedFixes) {
        reportError(kind, at, problem, suggestedFixes);
    }

    /**
     * Reports a warning at the given location.
     */
    public void warning(Location at, String message) {
        diagnostics.warning(at, message);
    }

    /**
     * Reports a note (informational message) at the given location.
     */
    public void note(Location at, String message) {
        diagnostics.note(at, message);
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

    public void missingDependency(Location at, String dependencyKey) {
        reportError(
                KIND_MISSING_DEPENDENCY,
                at,
                "Cannot resolve dependency: " + dependencyKey,
                "Add a @Component class of type " + dependencyKey,
                "Create a @Produces factory method that returns " + dependencyKey,
                "Check if the dependency has the correct qualifier (@Named)");
    }

    public void missingDependencyHiddenByExpose(
            Location at, String dependencyKey, String injectingClass, String hidingComponent) {
        reportError(
                KIND_MISSING_DEPENDENCY,
                at,
                hidingComponent + " implements " + dependencyKey
                        + " but does not expose it for injection (used by "
                        + injectingClass + ")",
                "Add " + dependencyKey + ".class to " + hidingComponent + "'s @Component(expose = {...}) list",
                "Drop the expose attribute on " + hidingComponent
                        + " to fall back to the permissive default (expose every implemented interface)",
                "Inject a different type that " + hidingComponent + " does expose");
    }

    public void circularDependency(Location at, String cycle) {
        reportError(
                KIND_CIRCULAR_DEPENDENCY,
                at,
                "Circular dependency detected: " + cycle,
                "Break the cycle by using Provider<T> for one of the dependencies",
                "Refactor to remove the circular dependency",
                "Consider using event-based communication instead of direct dependencies");
    }

    public void scopeViolation(Location at, String consumerScope, String dependencyScope, String dependencyType) {
        reportError(
                KIND_SCOPE_VIOLATION,
                at,
                "Scope violation: " + consumerScope + "-scoped bean cannot inject " + dependencyScope + "-scoped bean '"
                        + dependencyType + "'",
                "Make " + dependencyType + " implement an interface for proxy generation",
                "Change the scope of " + dependencyType + " to " + consumerScope + " or longer",
                "Use Provider<" + dependencyType + "> for lazy resolution");
    }

    public void missingInterface(Location at, String className, String reason) {
        // missingInterface is emitted exclusively from ScopeValidator's cross-scope path
        // (proxy generation requires an interface) — tag as SCOPE_VIOLATION so MCP
        // consumers can group the two cross-scope diagnostics together.
        reportError(
                KIND_SCOPE_VIOLATION,
                at,
                className + " must implement an interface. " + reason,
                "Extract an interface from " + className,
                "Make " + className + " implement an existing interface");
    }

    public void rawWrapperInjection(Location parameter, Location owner, String wrapperName) {
        // Prefer the parameter for location; fall back to the owner when the
        // parameter location is unavailable (defensive — collection always sets it today).
        reportError(
                KIND_OTHER,
                parameter != null ? parameter : owner,
                "Raw " + wrapperName + " injection point — without a type argument the container has no type to"
                        + " resolve.",
                "Declare the dependency as " + wrapperName + "<YourType>");
    }

    public void nonInstantiableComponent(Location at, String className, String reason, String... fixes) {
        reportError(KIND_OTHER, at, className + " cannot be a @Component: " + reason + ".", fixes);
    }

    public void duplicateSimpleNames(Location at, String simpleName, String qualifiedNames) {
        reportError(
                KIND_OTHER,
                at,
                "Two or more components share the simple name '" + simpleName + "': " + qualifiedNames
                        + ". Tiko generates per-simple-name factories and getters, so component simple names"
                        + " must be unique across packages.",
                "Rename one of the classes so every component has a unique simple name");
    }

    public void eventHandlerOutsideComponent(Location at, String className) {
        reportError(
                KIND_OTHER,
                at,
                "@EventHandler methods are only wired on @Component classes; '" + className
                        + "' is not registered as a component.",
                "Annotate " + className + " with @Component",
                "Move the handler method to a @Component class");
    }

    public void proxiedConcreteInjection(Location at, String concreteName, String interfaceName) {
        // Same SCOPE_VIOLATION family as missingInterface: the cross-scope proxy implements
        // the interface, so a concrete-typed injection point can never receive it (#331).
        reportError(
                KIND_SCOPE_VIOLATION,
                at,
                concreteName + " is EVENT-scoped and proxied; the generated proxy implements " + interfaceName
                        + ", so this injection point cannot be typed by the concrete class.",
                "Inject " + interfaceName + " instead of " + concreteName,
                "Use Provider<" + interfaceName + "> for lazy per-call resolution");
    }

    public void invalidAnnotationUsage(Location at, String annotationName, String reason) {
        reportError(
                KIND_OTHER,
                at,
                "Invalid use of " + annotationName + ": " + reason,
                "Review the " + annotationName + " usage against the Tiko documentation");
    }

    public void duplicateQualifier(Location at, String qualifier, String existingComponent) {
        reportError(
                KIND_AMBIGUOUS_QUALIFIER,
                at,
                "Duplicate qualifier '" + qualifier + "' - already used by " + existingComponent,
                "Use a different qualifier name",
                "Remove the qualifier if not needed",
                "Ensure each qualifier is unique within a type");
    }

    public void ambiguousProviders(Location at, String type, String providers, String simpleTypeName) {
        reportError(
                KIND_AMBIGUOUS_QUALIFIER,
                at,
                "Multiple unnamed providers for type " + type + ": " + providers,
                "Add @Named(\"...\") to each and use container.get(" + simpleTypeName + ".class, \"name\")",
                "Keep one provider unnamed as the default and give the others @Component(name = \"...\") or @Produces(name = \"...\")");
    }

    public void testComponentValueNotAssignable(Location at, String testClassFqn, String valueTypeFqn) {
        reportError(
                KIND_OTHER,
                at,
                "@TestComponent class " + testClassFqn + " is not assignable to declared value type " + valueTypeFqn
                        + ". The annotated class must extend or implement the value type to shadow it.",
                "Make " + testClassFqn + " extend or implement " + valueTypeFqn,
                "Remove the value attribute and let the implicit superclass walk pick the shadowed component",
                "Point value() at a type that " + testClassFqn + " actually implements");
    }

    public void testComponentScopeMismatch(
            Location at, String testFqn, io.tiko.Scope testScope, String mainFqn, io.tiko.Scope mainScope) {
        reportError(
                KIND_SCOPE_VIOLATION,
                at,
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
     * Single error-reporting funnel: reports the compiler diagnostic and forwards a
     * structured {@link WiringError} to the sink. All other public methods route
     * here so the two outputs stay in lock-step.
     */
    private void reportError(String kind, Location at, String problem, String... suggestedFixes) {
        hasErrors = true;
        StringBuilder message = new StringBuilder(problem);
        if (suggestedFixes != null && suggestedFixes.length > 0) {
            message.append("\n\nSuggested fixes:");
            for (int i = 0; i < suggestedFixes.length; i++) {
                message.append("\n").append(i + 1).append(". ").append(suggestedFixes[i]);
            }
        }
        diagnostics.error(at, message.toString());
        String firstFix = (suggestedFixes != null && suggestedFixes.length > 0) ? suggestedFixes[0] : null;
        wiringErrorSink.accept(new WiringError(kind, null, 0, deriveComponentFqn(at), problem, firstFix));
    }

    /**
     * Returns the FQN of the nearest class enclosing {@code at}, so a parameter, a
     * method/constructor, or a class all resolve to their owning component class. Returns
     * {@code null} when there is no location or no enclosing class.
     */
    private static String deriveComponentFqn(Location at) {
        return at == null ? null : at.enclosingClassName().orElse(null);
    }
}
