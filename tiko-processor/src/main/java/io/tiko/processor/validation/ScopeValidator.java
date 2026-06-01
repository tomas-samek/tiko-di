package io.tiko.processor.validation;

import io.tiko.Scope;
import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.ProcessorContext;
import java.util.Optional;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

/**
 * Validates scope hierarchy rules and cross-scope injection requirements.
 *
 * Rules:
 * - Longer-lived scopes can inject shorter-lived scopes via proxy
 * - Shorter-lived scoped beans injected into longer scopes must implement interfaces
 * - PROTOTYPE can be injected anywhere (creates new instance each time)
 */
public final class ScopeValidator {

    private final ProcessorContext context;

    public ScopeValidator(ProcessorContext context) {
        this.context = context;
    }

    /**
     * Validates all scope rules for the given component.
     * Returns true if validation passes, false if errors were reported.
     */
    public boolean validate(ComponentModel component) {
        boolean valid = true;

        for (DependencyModel dependency : component.getDependencies()) {
            if (!validateDependency(component, dependency)) {
                valid = false;
            }
        }

        return valid;
    }

    /**
     * Validates all scope rules for the given factory method.
     */
    public boolean validate(FactoryMethodModel factory) {
        boolean valid = true;

        for (DependencyModel dependency : factory.getDependencies()) {
            if (!validateFactoryDependency(factory, dependency)) {
                valid = false;
            }
        }

        return valid;
    }

    /**
     * Validates a single dependency of a component.
     */
    private boolean validateDependency(ComponentModel consumer, DependencyModel dependency) {
        // Provider<T> is always valid (lazy resolution)
        if (dependency.isProvider()) {
            return true;
        }

        // Find the component or factory that provides this dependency
        String depKey = dependency.getDependencyKey();
        Optional<Object> provider = context.findComponentOrFactory(depKey);

        if (provider.isEmpty()) {
            // Missing dependency will be caught by DependencyGraphValidator
            return true;
        }

        Scope providerScope = getScope(provider.get());

        // PROTOTYPE can be injected anywhere
        if (providerScope == Scope.PROTOTYPE) {
            return true;
        }

        return validateScopeHierarchy(
                consumer.getTypeElement(),
                consumer.getScope(),
                consumer.getClassName(),
                providerScope,
                dependency.getTypeName(),
                provider.get());
    }

    /**
     * Validates a single dependency of a factory method.
     */
    private boolean validateFactoryDependency(FactoryMethodModel factory, DependencyModel dependency) {
        // Provider<T> is always valid
        if (dependency.isProvider()) {
            return true;
        }

        String depKey = dependency.getDependencyKey();
        Optional<Object> provider = context.findComponentOrFactory(depKey);

        if (provider.isEmpty()) {
            return true;
        }

        Scope providerScope = getScope(provider.get());

        // PROTOTYPE can be injected anywhere
        if (providerScope == Scope.PROTOTYPE) {
            return true;
        }

        return validateScopeHierarchy(
                factory.getMethodElement(),
                factory.getScope(),
                factory.getDeclaringClass().getSimpleName() + "." + factory.getMethodName(),
                providerScope,
                dependency.getTypeName(),
                provider.get());
    }

    /**
     * Validates scope hierarchy: can consumerScope inject providerScope?
     *
     * Valid injections:
     * - Same scope or longer -> shorter (SINGLETON -> EVENT)
     * - Any -> PROTOTYPE
     *
     * Invalid:
     * - Shorter -> longer (EVENT -> SINGLETON without a proxy)
     */
    private boolean validateScopeHierarchy(
            javax.lang.model.element.Element consumerElement,
            Scope consumerScope,
            String consumerName,
            Scope providerScope,
            String providerTypeName,
            Object providerObj) {
        int consumerLevel = getScopeLevel(consumerScope);
        int providerLevel = getScopeLevel(providerScope);

        // Consumer is same-lived or shorter-lived than the provider (e.g. EVENT consuming
        // SINGLETON): a direct reference is safe, no proxy needed.
        if (consumerLevel >= providerLevel) {
            return true;
        }

        // Consumer outlives the provider: the container injects a generated proxy so the
        // long-lived consumer resolves the current scope's instance on every call. The proxy
        // implements an interface, so the shorter-lived bean must expose one — a concrete
        // class cannot be proxied without runtime bytecode tricks Tiko deliberately avoids.
        String reason = "A proxy is generated when " + consumerScope + "-scoped '" + consumerName + "' injects this "
                + providerScope + "-scoped bean; the proxy needs an interface to implement.";

        if (providerObj instanceof ComponentModel component) {
            if (component.getImplementedInterface().isEmpty()) {
                context.getErrorReporter().missingInterface(consumerElement, providerTypeName, reason);
                return false;
            }
        } else if (providerObj instanceof FactoryMethodModel factory) {
            // A @Produces output is proxied against its return type, so the return type must
            // itself be an interface (the proxy does `implements <returnType>`).
            TypeElement returnTypeElement = context.getElementUtils().getTypeElement(factory.getReturnTypeName());
            if (returnTypeElement != null && returnTypeElement.getKind() != ElementKind.INTERFACE) {
                context.getErrorReporter().missingInterface(consumerElement, factory.getReturnTypeName(), reason);
                return false;
            }
        }

        return true;
    }

    /**
     * Returns the scope level (lower number = longer lifetime).
     * SINGLETON = 0, EVENT = 1, PROTOTYPE = 2
     */
    private int getScopeLevel(Scope scope) {
        return switch (scope) {
            case SINGLETON -> 0;
            case EVENT -> 1;
            case PROTOTYPE -> 2;
        };
    }

    /**
     * Gets the scope from a component or factory method model.
     * {@code @Configuration} records are always SINGLETON-scoped.
     */
    private Scope getScope(Object componentOrFactory) {
        if (componentOrFactory instanceof ComponentModel) {
            return ((ComponentModel) componentOrFactory).getScope();
        } else if (componentOrFactory instanceof FactoryMethodModel) {
            return ((FactoryMethodModel) componentOrFactory).getScope();
        } else if (componentOrFactory instanceof io.tiko.processor.config.ConfigurationModel) {
            return Scope.SINGLETON;
        }
        throw new IllegalArgumentException("Unknown type: " + componentOrFactory.getClass());
    }
}
