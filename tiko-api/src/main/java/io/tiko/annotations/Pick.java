package io.tiko.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-literal qualifier for constructor parameters: selects a specific implementation
 * by its concrete class instead of by a string name.
 *
 * <p>{@code @Pick(SomeImpl.class)} is the compile-time, refactor-safe alternative to
 * {@code @Named("some-impl")} when disambiguating between multiple {@code @Component}
 * implementations of the same interface. Where {@link Named} relies on a string key
 * the IDE can't follow, {@code @Pick} uses a class literal — typos become compile
 * errors and rename refactors update every usage.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @Component class MySqlDataSource    implements DataSource { ... }
 * @Component class PostgresDataSource implements DataSource { ... }
 *
 * @Component
 * public class OrderService {
 *     @Inject
 *     public OrderService(
 *             @Pick(MySqlDataSource.class)    DataSource primary,
 *             @Pick(PostgresDataSource.class) DataSource analytics) {
 *         ...
 *     }
 * }
 * }</pre>
 *
 * <h2>When to use {@code @Pick} vs. {@link Named}</h2>
 * <ul>
 *   <li><b>{@code @Pick}</b> — disambiguating between multiple {@code @Component}
 *       classes that implement the same interface. Always prefer when there is a
 *       distinct class to point at.</li>
 *   <li><b>{@link Named}</b> — disambiguating between multiple {@link Produces}
 *       factory methods that return the same type (no distinct class to pick), or
 *       when the bean name is genuinely string-keyed metadata.</li>
 *   <li><b>{@code @Pick} + {@link Named} together</b> — narrows by impl class first,
 *       then by name. The composition is the right choice when several
 *       {@code @Produces} methods return the same picked type with different names:
 *       {@code @Pick} pins the impl, {@link Named} selects which producer.</li>
 * </ul>
 *
 * <h2>Composition example</h2>
 * <pre>{@code
 * @Component class DataSourceFactories {
 *     @Produces @Named("primary") HikariDataSource primary() { ... }
 *     @Produces @Named("backup")  HikariDataSource backup()  { ... }
 * }
 *
 * @Component
 * public class OrderService {
 *     @Inject
 *     public OrderService(@Pick(HikariDataSource.class) @Named("primary") DataSource ds) {
 *         ...
 *     }
 * }
 * }</pre>
 *
 * <h2>Validator rules</h2>
 * <ul>
 *   <li>The picked class must be assignable to the parameter type.</li>
 *   <li>The picked class must resolve to a known {@code @Component} or {@link Produces}
 *       return type — verified at compile time. With {@link Named}, the lookup uses
 *       the composite key {@code impl#name}; without {@link Named}, the picked class
 *       must be unambiguous (exactly one provider).</li>
 *   <li>{@code @Pick} cannot reference the parameter type itself (use plain
 *       {@code @Inject} when no disambiguation is needed).</li>
 *   <li>{@code @Pick} cannot be applied to collection or {@code Iterable} parameters —
 *       picking selects a single bean.</li>
 * </ul>
 *
 * <p>Retention is {@link RetentionPolicy#SOURCE} — the annotation processor consumes
 * it at compile time and emits explicit lookups in the generated factory code. Nothing
 * reads it at runtime.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.SOURCE)
public @interface Pick {

    /**
     * The concrete implementation class to inject. Must be assignable to the
     * annotated parameter's declared type.
     *
     * @return the implementation class
     */
    Class<?> value();
}
