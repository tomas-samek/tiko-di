package io.tiko;

import java.util.List;
import java.util.Optional;

/**
 * Typed runtime lookup primitive for polymorphic queries — the runtime complement
 * to compile-time qualifiers like {@code @Pick} and {@code @Named}.
 *
 * <p>Inject {@code Picker<T>} like {@link Provider}: the framework supplies a
 * generic implementation that delegates to the {@link Container}. The picker is
 * typed at the boundary — {@code Picker<DataSource>} can only return
 * {@code DataSource} (or subtypes), enforced by Java generics.
 *
 * <h2>When to use what</h2>
 * <ul>
 *   <li><b>{@code @Pick(SomeImpl.class) Interface i}</b> — compile-time-known,
 *       class-based qualifier. Declarative; the choice is visible in the
 *       constructor signature.</li>
 *   <li><b>{@code @Named("primary") Interface i}</b> — compile-time-known,
 *       string-based qualifier. Used to disambiguate multiple {@link annotations.Produces}
 *       factory methods returning the same type.</li>
 *   <li><b>{@code Picker<Interface>}</b> — runtime queries: the impl class or
 *       name is computed at runtime (e.g., from configuration), or you need to
 *       iterate all available impls.</li>
 * </ul>
 *
 * <h2>Example — config-driven dispatch</h2>
 * <pre>{@code
 * @Component
 * public class StrategyService {
 *     private final Strategy strategy;
 *
 *     @Inject
 *     public StrategyService(Picker<Strategy> strategies, StrategyConfig config) {
 *         // config.implName() comes from YAML — only known at runtime
 *         this.strategy = strategies.byName(config.implName())
 *                 .orElseThrow(() -> new IllegalStateException(
 *                         "no Strategy named '" + config.implName() + "'"));
 *     }
 * }
 * }</pre>
 *
 * <h2>Example — iterate all impls</h2>
 * <pre>{@code
 * @Component
 * public class HealthCheck {
 *     @Inject
 *     public HealthCheck(Picker<Probe> probes) {
 *         this.probes = probes.list();   // every registered Probe
 *     }
 * }
 * }</pre>
 *
 * <p>{@code Picker<T>} is the recommended escape hatch for any polymorphic
 * lookup the compile-time qualifiers cannot express. Cross-module pickers work
 * for free — the underlying {@code Container.get(Class)} routes through the
 * aggregator when multiple modules are linked.
 *
 * @param <T> the base type (interface or class) of the impls being queried
 */
public interface Picker<T> {

    /**
     * Returns every registered implementation assignable to {@code T}, in declaration
     * order. Includes both named and unnamed components and {@link annotations.Produces}
     * factory outputs. Returns an empty list if no impls exist.
     */
    List<T> list();

    /**
     * Resolves the bean qualified by {@code name} — the value of
     * {@code @Component(name = ...)} or {@code @Produces(name = ...)}.
     * Returns {@link Optional#empty()} when no bean is registered under that name.
     */
    Optional<T> byName(String name);

    /**
     * Resolves the bean by its concrete implementation class. The {@code S} type
     * parameter is constrained to subtypes of {@code T} so the picker stays type-safe
     * at the boundary. Returns {@link Optional#empty()} when no bean of that exact
     * implementation class is registered.
     */
    <S extends T> Optional<S> byImplClass(Class<S> implClass);
}
