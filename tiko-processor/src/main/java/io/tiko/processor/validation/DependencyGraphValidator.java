package io.tiko.processor.validation;

import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.ProcessorContext;

/**
 * Validates the complete dependency graph.
 *
 * Checks:
 * - All dependencies can be resolved
 * - No missing components
 * - Factory method declaring classes are available (for instance methods)
 */
public final class DependencyGraphValidator {

    private final ProcessorContext context;

    public DependencyGraphValidator(ProcessorContext context) {
        this.context = context;
    }

    /**
     * Validates the entire dependency graph.
     * Returns true if validation passes, false if errors were reported.
     */
    public boolean validate() {
        boolean valid = true;

        // Validate all component dependencies
        for (ComponentModel component : context.getActiveComponents()) {
            if (!validateComponent(component)) {
                valid = false;
            }
        }

        // Validate all factory method dependencies
        for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
            if (!validateFactory(factory)) {
                valid = false;
            }
        }

        return valid;
    }

    /**
     * Validates all dependencies of a component.
     */
    private boolean validateComponent(ComponentModel component) {
        boolean valid = true;

        for (DependencyModel dependency : component.getDependencies()) {
            // Get the actual type to resolve (unwrap Provider<T>)
            String depKey = dependency.getDependencyKey();

            // Check if dependency exists
            if (!context.findComponentOrFactory(depKey).isPresent()) {
                context.getErrorReporter().missingDependency(
                    component.getTypeElement(),
                    depKey,
                    component.getClassName()
                );
                valid = false;
            }
        }

        return valid;
    }

    /**
     * Validates all dependencies of a factory method.
     */
    private boolean validateFactory(FactoryMethodModel factory) {
        boolean valid = true;

        // For instance methods, validate that the declaring class is available as a component
        if (!factory.isStatic()) {
            String declaringClassKey = factory.getDeclaringClass().getQualifiedName().toString();

            if (!context.getComponents().containsKey(declaringClassKey)) {
                context.getErrorReporter().error(
                    factory.getMethodElement(),
                    "Factory method " + factory.getMethodName() + " is an instance method, " +
                    "but declaring class " + factory.getDeclaringClass().getSimpleName() +
                    " is not registered as a @Component",
                    "Add @Component annotation to " + factory.getDeclaringClass().getSimpleName(),
                    "Make the factory method static if it doesn't need component dependencies"
                );
                valid = false;
            }
        }

        // Validate factory method's own dependencies
        for (DependencyModel dependency : factory.getDependencies()) {
            String depKey = dependency.getDependencyKey();

            if (!context.findComponentOrFactory(depKey).isPresent()) {
                context.getErrorReporter().missingDependency(
                    factory.getMethodElement(),
                    depKey,
                    factory.getFactoryIdentifier()
                );
                valid = false;
            }
        }

        return valid;
    }
}
