package io.tiko.processor.validation;

import io.tiko.Scope;
import io.tiko.processor.config.ConfigurationModel;
import io.tiko.processor.core.model.Nullability;
import io.tiko.processor.core.model.SourceLanguage;
import io.tiko.processor.core.model.TypeRef;
import io.tiko.processor.model.ComponentModel;
import io.tiko.processor.model.DependencyModel;
import io.tiko.processor.model.FactoryMethodModel;
import io.tiko.processor.util.ErrorReporter;
import io.tiko.processor.util.ProcessorContext;
import io.tiko.processor.util.TypeRefs;
import java.util.ArrayList;
import java.util.Optional;

/**
 * Checks that every injection point and its provider agree on nullability, exactly (the
 * nullability rule of the Kotlin support). Null is legitimate only when the provider itself may
 * return it, a {@code @Produces} whose return type is {@code T?}; such a provider may be injected
 * only into {@code T?} points, and every other provider only into {@code T} points. A {@code ?}
 * on a point therefore always means "this provider can return null", never "optional": a point
 * without a provider is the usual missing dependency, reported by
 * {@link DependencyGraphValidator}.
 *
 * <p>It reads {@link TypeRef#nullability()} only. Java declarations are always
 * {@link Nullability#NON_NULL}, and components and configurations are never null, so a Java
 * build has nothing to report. Each mismatch is a {@link ErrorReporter#NULLABILITY_MISMATCH} on
 * the parameter that names the provider and says which side to change.
 *
 * <p>It also rejects a nullable EVENT-scoped producer injected into a longer-lived component:
 * that component receives a proxy resolving the current unit's value on each call, and the proxy
 * itself is never null.
 *
 * <p>{@code Picker<T>} points are skipped: they resolve among implementations at runtime, not to
 * one provider.
 */
public final class NullabilityValidator {

    private final ProcessorContext context;
    private final ErrorReporter errors;

    public NullabilityValidator(ProcessorContext context) {
        this.context = context;
        this.errors = context.getErrorReporter();
    }

    /** {@code false} when a mismatch was reported. */
    public boolean validate() {
        boolean valid = true;
        for (var component : context.getActiveComponents()) {
            var consumer = new Consumer(
                    component.getClassName(),
                    component.getScope(),
                    component.getDecl().language() == SourceLanguage.KOTLIN,
                    true);
            for (var dependency : component.getDependencies()) {
                valid &= validate(dependency, consumer);
            }
        }
        for (var factory : context.getActiveFactoryMethods()) {
            var consumer = new Consumer(
                    producerName(factory),
                    factory.getScope(),
                    factory.getDeclaringClass().language() == SourceLanguage.KOTLIN,
                    false);
            for (var dependency : factory.getDependencies()) {
                valid &= validate(dependency, consumer);
            }
        }
        return valid;
    }

    private boolean validate(DependencyModel dependency, Consumer consumer) {
        var provider = provider(dependency);
        if (provider.isEmpty()) {
            return true; // a Picker, or a missing dependency DependencyGraphValidator reports
        }
        var pointType = dependency.isProvider() ? dependency.getUnwrappedType().orElseThrow() : dependency.getType();
        var pointNullable = pointType.nullability() == Nullability.NULLABLE;
        var providerNullable = provider.get().nullable();
        if (pointNullable != providerNullable) {
            reportMismatch(dependency, consumer, provider.get(), pointNullable);
            return false;
        }
        if (providerNullable && receivesProxy(consumer, provider.get())) {
            reportProxied(dependency, consumer, provider.get());
            return false;
        }
        return true;
    }

    /** The provider the dependency resolves to, as {@link DependencyGraphValidator} resolves it. */
    private Optional<Provider> provider(DependencyModel dependency) {
        if (dependency.isPicker()) {
            return Optional.empty();
        }
        if (dependency.isEventBus()) {
            return Optional.of(new Provider("the container's EventBus", false, null));
        }
        var key = dependency.getDependencyKey();
        var found = dependency.isPicked() && dependency.getQualifier().isEmpty()
                ? context.findByImplClass(key)
                : context.findComponentOrFactory(key);
        return found.map(NullabilityValidator::describe);
    }

    private static Provider describe(Object provider) {
        if (provider instanceof FactoryMethodModel factory) {
            return new Provider(
                    "@Produces " + producerName(factory),
                    factory.getReturnType().nullability() == Nullability.NULLABLE,
                    factory);
        }
        if (provider instanceof ComponentModel component) {
            return new Provider("@Component " + component.getClassName(), false, null);
        }
        var name = provider instanceof ConfigurationModel configuration
                ? configuration.simpleName()
                : String.valueOf(provider);
        return new Provider("@Configuration " + name, false, null);
    }

    /** A component outliving an EVENT producer of an interface gets a proxy, never the value itself. */
    private static boolean receivesProxy(Consumer consumer, Provider provider) {
        return consumer.component()
                && provider.factory() != null
                && provider.factory().requiresProxy()
                // Scope constants are declared longest-lived first, as ComponentFactoryGenerator ranks them.
                && consumer.scope().ordinal() < provider.factory().getScope().ordinal();
    }

    private void reportMismatch(
            DependencyModel dependency, Consumer consumer, Provider provider, boolean pointNullable) {
        var type = simpleName(dependency);
        var point = "parameter '" + dependency.getParameterName() + "' of " + consumer.name();
        if (pointNullable) {
            var fixes = new ArrayList<String>();
            fixes.add("Drop the ?: declare the parameter as " + code(pointText(dependency, false)) + ", not "
                    + code(pointText(dependency, true)));
            if (provider.factory() != null
                    && provider.factory().getDeclaringClass().language() == SourceLanguage.KOTLIN) {
                fixes.add("If the producer can return null, declare " + producerName(provider.factory())
                        + "'s return type as " + code(type + "?"));
            }
            errors.error(
                    ErrorReporter.NULLABILITY_MISMATCH,
                    dependency.getParameter().location(),
                    "Nullability mismatch: " + point + " is declared " + code(pointText(dependency, true))
                            + ", but its provider " + provider.label() + " is never null. A ? on an injection"
                            + " point means its provider may return null; it never makes a dependency optional.",
                    fixes.toArray(String[]::new));
            return;
        }
        var makeNonNull =
                "make the producer non-null: return " + code(type) + " from " + producerName(provider.factory());
        if (!consumer.kotlin()) {
            errors.error(
                    ErrorReporter.NULLABILITY_MISMATCH,
                    dependency.getParameter().location(),
                    "Nullability mismatch: " + point + " is a Java parameter, which Tiko treats as non-null, but"
                            + " its provider " + provider.label() + " returns " + code(type + "?")
                            + ", which may be null.",
                    "Java parameters can't be declared nullable, so " + makeNonNull);
            return;
        }
        errors.error(
                ErrorReporter.NULLABILITY_MISMATCH,
                dependency.getParameter().location(),
                "Nullability mismatch: " + point + " is declared " + code(pointText(dependency, false))
                        + " (non-null), but its provider " + provider.label() + " returns " + code(type + "?")
                        + ", which may be null.",
                "If null is a valid value here, declare the parameter as " + code(pointText(dependency, true)),
                "Otherwise " + makeNonNull);
    }

    private void reportProxied(DependencyModel dependency, Consumer consumer, Provider provider) {
        var type = simpleName(dependency);
        var factory = provider.factory();
        errors.error(
                ErrorReporter.NULLABILITY_MISMATCH,
                dependency.getParameter().location(),
                "Nullability mismatch: parameter '" + dependency.getParameterName() + "' of " + consumer.name()
                        + " (" + consumer.scope() + ") receives the " + factory.getScope() + "-scoped "
                        + provider.label() + " through a proxy that resolves the current unit's value on every"
                        + " call. The proxy itself is never null, so it can't stand for a " + code(type + "?")
                        + " that may be null.",
                "Make " + consumer.name() + " " + factory.getScope() + "-scoped so it receives the unit's value itself",
                "Or make the producer non-null: return " + code(type) + " from " + producerName(factory));
    }

    /** The simple name of the type the point injects ({@code Foo} for {@code Foo?} and {@code Provider<Foo?>}). */
    private static String simpleName(DependencyModel dependency) {
        var type = dependency.isProvider() ? dependency.getUnwrappedType().orElseThrow() : dependency.getType();
        return TypeRefs.declared(type)
                .map(decl -> decl.className().simpleName())
                .orElseGet(type::displayName);
    }

    /** The point's type as Kotlin writes it, {@code Foo?} or {@code Provider<Foo?>} when nullable. */
    private static String pointText(DependencyModel dependency, boolean nullable) {
        var name = simpleName(dependency) + (nullable ? "?" : "");
        return dependency.isProvider() ? "Provider<" + name + ">" : name;
    }

    private static String producerName(FactoryMethodModel factory) {
        return factory.getDeclaringClass().className().simpleName() + "." + factory.getMethodName();
    }

    private static String code(String text) {
        return "`" + text + "`";
    }

    /**
     * The side of an injection point that declares it: a component's constructor (or static
     * factory) or a {@code @Produces} method, in its scope and source language.
     */
    private record Consumer(String name, Scope scope, boolean kotlin, boolean component) {}

    /** What a dependency resolves to: its label in messages, and the factory when it is one. */
    private record Provider(String label, boolean nullable, FactoryMethodModel factory) {}
}
