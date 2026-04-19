package io.tiko.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a constructor for dependency injection.
 *
 * <p>Tiko DI supports <b>constructor injection only</b> to encourage immutable design,
 * better testability, and compile-time safety. Field injection is not supported.
 *
 * <h2>Basic Usage</h2>
 * <pre>{@code
 * @Component(scope = Scope.SINGLETON)
 * public class UserService {
 *     private final UserRepository repository;
 *     private final EmailService emailService;
 *
 *     @Inject
 *     public UserService(UserRepository repository, EmailService emailService) {
 *         this.repository = repository;
 *         this.emailService = emailService;
 *     }
 * }
 * }</pre>
 *
 * <h2>Named Qualifiers</h2>
 * <p>Use {@link Named @Named} on constructor parameters to disambiguate when multiple
 * implementations of the same type exist:</p>
 * <pre>{@code
 * @Component
 * public class DataService {
 *     @Inject
 *     public DataService(
 *         @Named("mysql") DataSource primary,
 *         @Named("postgres") DataSource replica
 *     ) {
 *         // Uses specific implementations
 *     }
 * }
 * }</pre>
 *
 * <h2>Multiple Constructors</h2>
 * <p>A component class can have multiple constructors, but only one can be annotated
 * with {@code @Inject}. If no constructor is annotated, the no-arg constructor is used
 * (if present).</p>
 *
 * <h2>Benefits of Constructor-Only Injection</h2>
 * <ul>
 *     <li><b>Immutability</b> - Dependencies can be declared as {@code final} fields</li>
 *     <li><b>Testability</b> - Easy to construct instances in tests with mock dependencies</li>
 *     <li><b>Null safety</b> - No risk of using partially initialized objects</li>
 *     <li><b>Compile-time validation</b> - Annotation processor validates all dependencies exist</li>
 *     <li><b>Clear contracts</b> - Dependencies are explicit in constructor signature</li>
 * </ul>
 *
 * <h2>Factory Methods</h2>
 * <p>For {@link Produces @Produces} factory methods, the {@code @Inject} annotation is not
 * needed. Factory method parameters are automatically injected:</p>
 * <pre>{@code
 * @Component
 * public class DatabaseConfiguration {
 *     @Produces(scope = Scope.SINGLETON, name = "mysql")
 *     public DataSource mysqlDataSource(ConfigProvider config) {
 *         // 'config' is automatically injected - no @Inject needed
 *         return new HikariDataSource(config.getMysqlUrl());
 *     }
 * }
 * }</pre>
 *
 * @see Component
 * @see Named
 * @see Produces
 * @since 1.0
 */
@Target({ElementType.CONSTRUCTOR, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface Inject {
}
