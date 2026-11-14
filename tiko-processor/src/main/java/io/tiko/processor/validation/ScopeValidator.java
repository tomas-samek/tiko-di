package io.tiko.processor.validation;

import io.tiko.Scope;
import io.tiko.processor.core.model.DeclKind;
import io.tiko.processor.core.model.Location;
import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.ProcessorContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Validates scope hierarchy rules and cross-scope injection requirements.
 *
 * Rules:
 * - Longer-lived scopes can inject shorter-lived scopes via proxy
 * - Shorter-lived scoped beans injected into longer scopes must implement interfaces
 * - PROTOTYPE can be injected anywhere (creates new instance each time), but its consumer keeps
 *   that instance, so what the PROTOTYPE injects directly must be safe for the consumer too (#723)
 * - An instance @Produces method must not outlive its EVENT-scoped declaring component (#723)
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
            if (!validateDependencyOf(
                    component.getDecl().location(), component.getScope(), component.getClassName(), dependency)) {
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

        String consumerName = displayName(factory);
        for (DependencyModel dependency : factory.getDependencies()) {
            if (!validateDependencyOf(factory.getMethod().location(), factory.getScope(), consumerName, dependency)) {
                valid = false;
            }
        }

        return validateDeclaringComponentOf(factory) && valid;
    }

    /**
     * An instance {@code @Produces} method is called on an instance of its declaring component. On an
     * EVENT component that instance exists only inside a unit of work, so a SINGLETON value produced
     * from it cannot be built at start and would outlive the unit that built it (#723).
     */
    private boolean validateDeclaringComponentOf(FactoryMethodModel factory) {
        if (factory.isStatic() || factory.getScope() != Scope.SINGLETON) {
            return true;
        }
        String declaringClass = factory.getDeclaringClass().qualifiedName();
        for (ComponentModel component : context.getActiveComponents()) {
            if (component.getScope() == Scope.EVENT
                    && component.getQualifiedName().equals(declaringClass)) {
                context.getErrorReporter()
                        .producesOutlivesDeclaringComponent(
                                factory.getMethod().location(),
                                factory.getScope().name(),
                                displayName(factory),
                                factory.getMethodName(),
                                component.getScope().name(),
                                component.getClassName());
                return false;
            }
        }
        return true;
    }

    /**
     * Validates a single injection point — shared by component and factory consumers,
     * which differ only in how the consumer element, scope, and display name are derived.
     */
    private boolean validateDependencyOf(
            Location consumerLocation, Scope consumerScope, String consumerName, DependencyModel dependency) {
        // Checked before the Provider escape: Provider<ConcreteProxiedBean> is just as
        // unreceivable as a direct concrete injection (#331).
        if (!validateProxiedConcreteInjection(consumerLocation, dependency)) {
            return false;
        }

        // Provider<T> is always valid (lazy resolution)
        if (dependency.isProvider()) {
            return true;
        }

        String depKey = dependency.getDependencyKey();
        Optional<Object> provider = context.findComponentOrFactory(depKey);

        if (provider.isEmpty()) {
            // Missing dependency will be caught by DependencyGraphValidator
            return true;
        }

        Scope providerScope = getScope(provider.get());

        // PROTOTYPE can be injected anywhere, but a longer-lived consumer keeps the instance: check
        // what the PROTOTYPE injects directly against that consumer.
        if (providerScope == Scope.PROTOTYPE) {
            return consumerScope == Scope.PROTOTYPE
                    || validateHeldPrototype(
                            new Consumer(consumerLocation, consumerScope, consumerName),
                            provider.get(),
                            new ArrayList<>(),
                            Collections.newSetFromMap(new IdentityHashMap<>()));
        }

        return validateScopeHierarchy(
                consumerLocation, consumerScope, consumerName, providerScope, dependency.getTypeName(), provider.get());
    }

    /** The injection point whose lifetime every directly injected instance below it shares. */
    private record Consumer(Location location, Scope scope, String name) {}

    /**
     * Walks what {@code prototype} injects directly. The consumer holds that PROTOTYPE instance for its
     * own lifetime, so a shorter-lived bean the PROTOTYPE receives as the instance itself is captured by
     * the consumer exactly as a direct injection would capture it. {@code Provider<T>} edges resolve on
     * each call and end the walk; {@code visited} ends it on a PROTOTYPE seen before.
     */
    private boolean validateHeldPrototype(
            Consumer consumer, Object prototype, List<String> chain, Set<Object> visited) {
        if (!visited.add(prototype)) {
            return true;
        }
        chain.add("PROTOTYPE '" + displayName(prototype) + "'");
        boolean valid = true;
        for (DependencyModel dependency : dependenciesOf(prototype)) {
            Optional<Object> provider = dependency.isProvider()
                    ? Optional.empty()
                    : context.findComponentOrFactory(dependency.getDependencyKey());
            if (provider.isEmpty()) {
                continue;
            }
            Scope providerScope = getScope(provider.get());
            boolean dependencyValid = providerScope == Scope.PROTOTYPE
                    ? validateHeldPrototype(consumer, provider.get(), chain, visited)
                    : validateHeldDependency(consumer, providerScope, dependency.getTypeName(), provider.get(), chain);
            valid = valid && dependencyValid;
        }
        chain.removeLast();
        return valid;
    }

    /**
     * A SINGLETON or EVENT bean reached through held PROTOTYPEs. One the consumer outlives must reach the
     * PROTOTYPE as a proxy: an EVENT component with an interface always does, a concrete one cannot, and
     * an EVENT {@code @Produces} value reaches a PROTOTYPE as the value itself.
     */
    private boolean validateHeldDependency(
            Consumer consumer, Scope providerScope, String providerTypeName, Object provider, List<String> chain) {
        if (getScopeLevel(consumer.scope()) >= getScopeLevel(providerScope)) {
            return true;
        }
        String through = String.join(" -> ", chain);
        if (provider instanceof FactoryMethodModel) {
            context.getErrorReporter()
                    .producedValueCapturedThroughPrototype(
                            consumer.location(),
                            consumer.scope().name(),
                            consumer.name(),
                            providerScope.name(),
                            providerTypeName,
                            through,
                            chain.getLast());
            return false;
        }
        if (provider instanceof ComponentModel component
                && component.getImplementedInterface().isEmpty()) {
            context.getErrorReporter()
                    .missingInterfaceThroughPrototype(
                            consumer.location(),
                            providerTypeName,
                            consumer.scope() + "-scoped '" + consumer.name() + "' reaches this " + providerScope
                                    + "-scoped bean through " + through + " and keeps what it injects for its own"
                                    + " lifetime, so the bean must be proxied; the proxy needs an interface to"
                                    + " implement.",
                            chain.getFirst(),
                            consumer.name());
            return false;
        }
        return true;
    }

    /** Only components and factory methods are PROTOTYPE-scoped; a configuration record never is. */
    private static List<DependencyModel> dependenciesOf(Object prototype) {
        return prototype instanceof ComponentModel component
                ? component.getDependencies()
                : ((FactoryMethodModel) prototype).getDependencies();
    }

    private static String displayName(Object prototype) {
        return prototype instanceof ComponentModel component
                ? component.getClassName()
                : displayName((FactoryMethodModel) prototype);
    }

    private static String displayName(FactoryMethodModel factory) {
        return factory.getDeclaringClass().className().simpleName() + "." + factory.getMethodName();
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
            Location consumerLocation,
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
                context.getErrorReporter().missingInterface(consumerLocation, providerTypeName, reason);
                return false;
            }
        } else if (providerObj instanceof FactoryMethodModel factory) {
            // A @Produces output is proxied against its return type, so the return type must
            // itself be an interface (the proxy does `implements <returnType>`).
            var returnTypeDecl = context.env().types().findClass(factory.getReturnTypeName());
            if (returnTypeDecl.isPresent() && returnTypeDecl.get().kind() != DeclKind.INTERFACE) {
                context.getErrorReporter().missingInterface(consumerLocation, factory.getReturnTypeName(), reason);
                return false;
            }
        }

        return true;
    }

    /**
     * A proxied EVENT bean can only be received through its interface — the generated
     * proxy implements the interface, not the concrete class — so an injection point
     * typed by the concrete class (directly or as {@code Provider<Concrete>}) can never
     * be satisfied; without this check the mismatch surfaced as a raw javac inference
     * error inside generated factories (#331).
     */
    private boolean validateProxiedConcreteInjection(Location consumerLocation, DependencyModel dependency) {
        String depKey = dependency.getDependencyKey();
        String typePart = depKey.contains("#") ? depKey.substring(0, depKey.indexOf('#')) : depKey;
        if (context.findComponentOrFactory(depKey).orElse(null) instanceof ComponentModel component
                && component.requiresProxy()
                && component.getQualifiedName().equals(typePart)) {
            context.getErrorReporter()
                    .proxiedConcreteInjection(
                            consumerLocation,
                            component.getClassName(),
                            component.getImplementedInterface().orElseThrow().displayName());
            return false;
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
