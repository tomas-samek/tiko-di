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
import io.tiko.processor.util.TypeUtil;
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
 * the parameter that names the provider and says which side to change. Besides the exact match:
 *
 * <ul>
 *   <li>A {@code Provider<T>} or {@code Picker<T>} wrapper is never null (the container always
 *       passes one), so a nullable wrapper ({@code Provider<Foo>?}) is a mismatch.
 *   <li>A {@code Picker<T>} hands out every provider of {@code T}: {@code list()} and
 *       {@code byName} the ones producing exactly {@code T}, {@code byImplClass} those producing a
 *       subtype. Any nullable producer it can reach is a mismatch for its non-null {@code T}.
 *   <li>A {@code Picker<T?>} is rejected: it can't carry a null ({@code byName} answers with an
 *       {@code Optional}, which reads a null as "absent", the optional meaning {@code ?} must never
 *       have; {@code byImplClass} would fail on it).
 *   <li>A nullable EVENT-scoped producer injected into a longer-lived component is rejected: that
 *       component receives a proxy resolving the current unit's value on each call, and the proxy
 *       itself is never null.
 * </ul>
 */
public final class NullabilityValidator {

    private final ProcessorContext context;
    private final ErrorReporter errors;
    private final TypeUtil typeUtil;

    public NullabilityValidator(ProcessorContext context) {
        this.context = context;
        this.errors = context.getErrorReporter();
        this.typeUtil = new TypeUtil(context.env().types());
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
        if (isWrapped(dependency) && dependency.getType().nullability() == Nullability.NULLABLE) {
            reportNullableWrapper(dependency, consumer);
            return false;
        }
        if (dependency.isPicker()) {
            return validatePicker(dependency, consumer);
        }
        var provider = provider(dependency);
        if (provider.isEmpty()) {
            return true; // a missing dependency, which DependencyGraphValidator reports
        }
        var pointNullable = injectedType(dependency).nullability() == Nullability.NULLABLE;
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

    /** A {@code Picker<T?>} is rejected; a {@code Picker<T>} must reach no nullable producer. */
    private boolean validatePicker(DependencyModel dependency, Consumer consumer) {
        var base = dependency.getUnwrappedType();
        if (base.isEmpty()) {
            return true; // a raw Picker, which ComponentShapeValidator reports
        }
        if (base.get().nullability() == Nullability.NULLABLE) {
            reportNullablePicker(dependency, consumer);
            return false;
        }
        boolean valid = true;
        for (var factory : context.getActiveFactoryMethods()) {
            if (isNullable(factory) && reachableThrough(factory, base.get())) {
                reportPickedNullable(dependency, consumer, factory);
                valid = false;
            }
        }
        return valid;
    }

    /**
     * Whether a {@code Picker} of {@code base} can hand out the factory's output: it produces
     * {@code base} itself ({@code list()}, {@code byName}) or a subtype of it ({@code byImplClass}).
     * Compared on the declared classes, since generic {@code @Produces} returns are rejected.
     */
    private boolean reachableThrough(FactoryMethodModel factory, TypeRef base) {
        var produced = TypeRefs.declared(factory.getReturnType());
        var picked = TypeRefs.declared(base);
        return produced.isPresent()
                && picked.isPresent()
                && typeUtil.isAssignable(produced.get().asType(), picked.get().asType());
    }

    /** The provider the dependency resolves to, as {@link DependencyGraphValidator} resolves it. */
    private Optional<Provider> provider(DependencyModel dependency) {
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
            return new Provider("@Produces " + producerName(factory), isNullable(factory), factory);
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
                && provider.factory().isProxiedFor(consumer.scope());
    }

    private void reportMismatch(
            DependencyModel dependency, Consumer consumer, Provider provider, boolean pointNullable) {
        var type = simpleName(dependency);
        var point = point(dependency, consumer);
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
        var makeNonNull = makeNonNull(provider.factory());
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
                "Nullability mismatch: " + point(dependency, consumer) + " (" + consumer.scope() + ") receives the "
                        + factory.getScope() + "-scoped " + provider.label() + " through a proxy that resolves the"
                        + " current unit's value on every call. The proxy itself is never null, so it can't stand"
                        + " for a " + code(type + "?") + " that may be null.",
                "Make " + consumer.name() + " " + factory.getScope() + "-scoped so it receives the unit's value itself",
                "Or " + makeNonNull(factory));
    }

    private void reportNullableWrapper(DependencyModel dependency, Consumer consumer) {
        var wrapper = wrapperName(dependency);
        var declared = pointText(dependency, injectedType(dependency).nullability() == Nullability.NULLABLE);
        errors.error(
                ErrorReporter.NULLABILITY_MISMATCH,
                dependency.getParameter().location(),
                "Nullability mismatch: " + point(dependency, consumer) + " is declared " + code(declared + "?")
                        + ", but the container always passes a " + wrapper + ", never null.",
                "Drop the ?: declare the parameter as " + code(declared) + ", not " + code(declared + "?"));
    }

    private void reportNullablePicker(DependencyModel dependency, Consumer consumer) {
        var type = simpleName(dependency);
        errors.error(
                ErrorReporter.NULLABILITY_MISMATCH,
                dependency.getParameter().location(),
                "Nullability mismatch: " + point(dependency, consumer) + " is declared "
                        + code(pointText(dependency, true)) + ", but a Picker can't hand out null: byName() answers"
                        + " with an Optional, which reads a null as absent, and byImplClass() would fail on it. A ?"
                        + " never makes a dependency optional.",
                "Declare the parameter as " + code(pointText(dependency, false)) + ", not "
                        + code(pointText(dependency, true)),
                "To inject a nullable producer's value, declare the parameter as " + code(type + "?")
                        + " and let that producer provide it");
    }

    private void reportPickedNullable(DependencyModel dependency, Consumer consumer, FactoryMethodModel factory) {
        var produced = TypeRefs.declared(factory.getReturnType())
                .map(decl -> decl.className().simpleName())
                .orElseGet(() -> factory.getReturnType().displayName());
        var fixes = new ArrayList<String>();
        fixes.add("A Picker can't hand out null, so " + makeNonNull(factory));
        if (consumer.kotlin()) {
            fixes.add("If the null is meaningful, inject " + code(produced + "?") + " from the producer directly"
                    + " instead of picking it");
        }
        errors.error(
                ErrorReporter.NULLABILITY_MISMATCH,
                dependency.getParameter().location(),
                "Nullability mismatch: " + point(dependency, consumer) + " is a " + code(pointText(dependency, false))
                        + ", which can hand out the value of @Produces " + producerName(factory) + ", and that"
                        + " producer returns " + code(produced + "?") + ", which may be null.",
                fixes.toArray(String[]::new));
    }

    private static boolean isWrapped(DependencyModel dependency) {
        return dependency.isProvider() || dependency.isPicker();
    }

    /** The type the point injects: {@code T} of a {@code Provider<T>} / {@code Picker<T>}, else the declared type. */
    private static TypeRef injectedType(DependencyModel dependency) {
        return isWrapped(dependency) ? dependency.getUnwrappedType().orElseThrow() : dependency.getType();
    }

    private static boolean isNullable(FactoryMethodModel factory) {
        return factory.getReturnType().nullability() == Nullability.NULLABLE;
    }

    private static String point(DependencyModel dependency, Consumer consumer) {
        return "parameter '" + dependency.getParameterName() + "' of " + consumer.name();
    }

    private static String makeNonNull(FactoryMethodModel factory) {
        var type = TypeRefs.declared(factory.getReturnType())
                .map(decl -> decl.className().simpleName())
                .orElseGet(() -> factory.getReturnType().displayName());
        return "make the producer non-null: return " + code(type) + " from " + producerName(factory);
    }

    /** The simple name of the type the point injects ({@code Foo} for {@code Foo?} and {@code Provider<Foo?>}). */
    private static String simpleName(DependencyModel dependency) {
        var type = injectedType(dependency);
        return TypeRefs.declared(type)
                .map(decl -> decl.className().simpleName())
                .orElseGet(type::displayName);
    }

    /** The point's type as Kotlin writes it, {@code Foo?} or {@code Provider<Foo?>} when nullable. */
    private static String pointText(DependencyModel dependency, boolean nullable) {
        var name = simpleName(dependency) + (nullable ? "?" : "");
        return isWrapped(dependency) ? wrapperName(dependency) + "<" + name + ">" : name;
    }

    private static String wrapperName(DependencyModel dependency) {
        return dependency.isPicker() ? "Picker" : "Provider";
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
