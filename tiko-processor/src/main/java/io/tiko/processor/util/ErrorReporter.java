package io.tiko.processor.util;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

/**
 * Utility for reporting errors during annotation processing.
 * Formats error messages with location, problem description, and suggested fixes.
 */
public final class ErrorReporter {

    private final Messager messager;
    private boolean hasErrors = false;

    public ErrorReporter(Messager messager) {
        this.messager = messager;
    }

    /**
     * Reports an error at the given element location.
     */
    public void error(Element element, String message) {
        hasErrors = true;
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    /**
     * Reports an error with suggested fixes.
     */
    public void error(Element element, String problem, String... suggestedFixes) {
        hasErrors = true;
        StringBuilder message = new StringBuilder(problem);

        if (suggestedFixes.length > 0) {
            message.append("\n\nSuggested fixes:");
            for (int i = 0; i < suggestedFixes.length; i++) {
                message.append("\n").append(i + 1).append(". ").append(suggestedFixes[i]);
            }
        }

        messager.printMessage(Diagnostic.Kind.ERROR, message.toString(), element);
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
        error(
                element,
                "Cannot resolve dependency: " + dependencyKey,
                "Add a @Component class of type " + dependencyKey,
                "Create a @Produces factory method that returns " + dependencyKey,
                "Check if the dependency has the correct qualifier (@Named)");
    }

    public void missingDependencyHiddenByExpose(
            Element element, String dependencyKey, String injectingClass, String hidingComponent) {
        error(
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
        error(
                element,
                "Circular dependency detected: " + cycle,
                "Break the cycle by using Provider<T> for one of the dependencies",
                "Refactor to remove the circular dependency",
                "Consider using event-based communication instead of direct dependencies");
    }

    public void scopeViolation(Element element, String consumerScope, String dependencyScope, String dependencyType) {
        error(
                element,
                "Scope violation: " + consumerScope + "-scoped bean cannot inject " + dependencyScope + "-scoped bean '"
                        + dependencyType + "'",
                "Make " + dependencyType + " implement an interface for proxy generation",
                "Change the scope of " + dependencyType + " to " + consumerScope + " or longer",
                "Use Provider<" + dependencyType + "> for lazy resolution");
    }

    public void missingInterface(Element element, String className, String reason) {
        error(
                element,
                className + " must implement an interface. " + reason,
                "Extract an interface from " + className,
                "Make " + className + " implement an existing interface");
    }

    public void invalidAnnotationUsage(Element element, String annotationName, String reason) {
        error(element, "Invalid use of " + annotationName + ": " + reason);
    }

    public void duplicateQualifier(Element element, String qualifier, String existingComponent) {
        error(
                element,
                "Duplicate qualifier '" + qualifier + "' - already used by " + existingComponent,
                "Use a different qualifier name",
                "Remove the qualifier if not needed",
                "Ensure each qualifier is unique within a type");
    }

    public void ambiguousProviders(Element element, String type, String providers, String simpleTypeName) {
        error(
                element,
                "Multiple unnamed providers for type " + type + ": " + providers,
                "Add @Named(\"...\") to each and use container.get(" + simpleTypeName + ".class, \"name\")",
                "Keep one provider unnamed as the default and give the others @Component(name = \"...\") or @Produces(name = \"...\")");
    }
}
