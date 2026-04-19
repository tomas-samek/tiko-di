package io.tiko.annotations;

import io.tiko.Scope;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method within a {@link Component} class as a factory method that produces component instances.
 *
 * <p>Factory methods allow programmatic creation of components with custom initialization logic,
 * dynamic configuration, complex validation, or construction requirements that cannot be handled
 * by simple constructor injection.
 *
 * <p>Supports both <b>instance methods</b> (in separate factory components) and <b>static methods</b>
 * (in the component class itself).
 *
 * <h2>Instance Factory Methods (Separate Factory Component)</h2>
 * <p>Use instance methods when you need a dedicated factory component with its own dependencies:</p>
 * <pre>{@code
 * @Component
 * public class DatabaseConfiguration {
 *
 *     private final ConfigProvider config;
 *
 *     @Inject
 *     public DatabaseConfiguration(ConfigProvider config) {
 *         this.config = config;
 *     }
 *
 *     @Produces(scope = Scope.SINGLETON, name = "mysql")
 *     public DataSource mysqlDataSource() {
 *         HikariConfig hikariConfig = new HikariConfig();
 *         hikariConfig.setJdbcUrl(config.getMysqlUrl());
 *         hikariConfig.setUsername(config.getMysqlUsername());
 *         hikariConfig.setPassword(config.getMysqlPassword());
 *         return new HikariDataSource(hikariConfig);
 *     }
 *
 *     @Produces(scope = Scope.SINGLETON, name = "postgres")
 *     public DataSource postgresDataSource() {
 *         HikariConfig hikariConfig = new HikariConfig();
 *         hikariConfig.setJdbcUrl(config.getPostgresUrl());
 *         return new HikariDataSource(hikariConfig);
 *     }
 * }
 * }</pre>
 *
 * <h2>Static Factory Methods (Same Class)</h2>
 * <p>Use static methods when the factory logic belongs with the component itself, especially for
 * validation, private constructors, or complex initialization:</p>
 * <pre>{@code
 * @Component
 * public class Database {
 *
 *     private final Connection connection;
 *
 *     // Private constructor - force factory usage
 *     private Database(Connection connection) {
 *         this.connection = connection;
 *     }
 *
 *     @Produces(scope = Scope.SINGLETON)
 *     public static Database create(ConfigProvider config) {
 *         // Validation
 *         if (config.getUrl() == null || config.getUrl().isEmpty()) {
 *             throw new IllegalArgumentException("Database URL is required");
 *         }
 *         if (config.getMaxConnections() < 1) {
 *             throw new IllegalArgumentException("Max connections must be positive");
 *         }
 *
 *         // Complex initialization
 *         Connection conn = DriverManager.getConnection(
 *             config.getUrl(),
 *             config.getUsername(),
 *             config.getPassword()
 *         );
 *         conn.setAutoCommit(false);
 *
 *         return new Database(conn);
 *     }
 * }
 * }</pre>
 *
 * <h2>Factory Method Requirements</h2>
 * <ul>
 *     <li><b>Instance OR static methods</b> - Both are supported. Static methods are called directly;
 *         instance methods require the factory component to be instantiated first.</li>
 *     <li><b>Return type</b> - Determines what component type is being produced.</li>
 *     <li><b>Parameters</b> - Can have dependencies injected (just like constructor injection).</li>
 *     <li><b>Scope</b> - Specified via the {@code scope} parameter (defaults to PROTOTYPE).</li>
 *     <li><b>Lifecycle</b> - Products created by factory methods do NOT have {@code @PostConstruct}
 *         or {@code @PreDestroy} called automatically (initialize in the factory method itself).</li>
 * </ul>
 *
 * <h2>When to Use Each Pattern</h2>
 * <ul>
 *     <li><b>Instance methods</b> - Factory has its own dependencies, multiple factory methods sharing state,
 *         or you want lifecycle hooks (@PostConstruct/@PreDestroy) on the factory itself.</li>
 *     <li><b>Static methods</b> - Validation before construction, private constructors, named constructor pattern,
 *         or keeping creation logic with the class definition.</li>
 * </ul>
 *
 * <h2>Dependency Injection in Factory Methods</h2>
 * <pre>{@code
 * @Component
 * public class ServiceFactories {
 *
 *     @Produces(scope = Scope.SINGLETON)
 *     public CacheService cacheService(RedisClient redis, MetricsCollector metrics) {
 *         CacheService service = new CacheServiceImpl(redis);
 *         service.enableMetrics(metrics);
 *         return service;
 *     }
 * }
 * }</pre>
 *
 * <h2>Named Qualifiers</h2>
 * <p>Use the {@code name} parameter to create multiple implementations of the same type:</p>
 * <pre>{@code
 * @Component
 * public class CacheFactories {
 *
 *     @Produces(scope = Scope.SINGLETON, name = "local")
 *     public Cache localCache() {
 *         return new InMemoryCache();
 *     }
 *
 *     @Produces(scope = Scope.SINGLETON, name = "distributed")
 *     public Cache distributedCache(RedisClient redis) {
 *         return new RedisCache(redis);
 *     }
 * }
 *
 * // Injection
 * @Component
 * public class UserService {
 *     @Inject
 *     public UserService(@Named("local") Cache cache) {
 *         // Uses localCache
 *     }
 * }
 * }</pre>
 *
 * <h2>Profile Support</h2>
 * <p>Factory methods can be conditionally enabled based on active profiles:</p>
 * <pre>{@code
 * @Component
 * public class DatabaseFactories {
 *
 *     @Produces(scope = Scope.SINGLETON, profiles = {"dev", "test"})
 *     public DataSource h2DataSource() {
 *         return new H2DataSource();
 *     }
 *
 *     @Produces(scope = Scope.SINGLETON, profiles = {"prod"})
 *     public DataSource productionDataSource(ConfigProvider config) {
 *         return new PostgresDataSource(config.getDbUrl());
 *     }
 * }
 * }</pre>
 *
 * <h2>Factory Component Lifecycle</h2>
 * <p>The containing {@link Component} class is a regular component with full lifecycle support:</p>
 * <pre>{@code
 * @Component(scope = Scope.SINGLETON)
 * public class ResourceFactories {
 *
 *     private ConfigProvider config;
 *
 *     @Inject
 *     public ResourceFactories(ConfigProvider config) {
 *         this.config = config;
 *     }
 *
 *     @PostConstruct
 *     public void init() {
 *         System.out.println("Factory initialized");
 *     }
 *
 *     @Produces(scope = Scope.SINGLETON)
 *     public Database database() {
 *         return new DatabaseImpl(config.getDbUrl());
 *     }
 *
 *     @PreDestroy
 *     public void cleanup() {
 *         System.out.println("Factory shutting down");
 *     }
 * }
 * }</pre>
 *
 * <h2>When to Use Factory Methods</h2>
 * <ul>
 *     <li><b>Complex initialization</b> - Builder patterns, multi-step setup</li>
 *     <li><b>Third-party classes</b> - Classes you don't control and can't annotate with {@code @Component}</li>
 *     <li><b>Dynamic configuration</b> - Runtime decisions about which implementation to create</li>
 *     <li><b>Multiple configurations</b> - Same type with different settings (databases, caches, etc.)</li>
 *     <li><b>Resource management</b> - Connection pools, thread pools, custom cleanup logic</li>
 * </ul>
 *
 * <h2>Annotation Processor Behavior</h2>
 * <p>At compile-time, the annotation processor will:</p>
 * <ol>
 *     <li>Scan all {@link Component} classes for {@code @Produces} methods</li>
 *     <li>Validate return types and parameter dependencies</li>
 *     <li>Generate container code that calls the factory method when the product is requested</li>
 *     <li>Handle scope management (singleton caching, request/event scope storage, etc.)</li>
 * </ol>
 *
 * @see Component
 * @see Inject
 * @see Named
 * @see Scope
 * @since 1.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Produces {

    /**
     * The lifecycle scope of the produced component.
     *
     * <p>Determines how long instances live and when they're created:</p>
     * <ul>
     *     <li>{@link Scope#SINGLETON SINGLETON} - One instance for the entire application</li>
     *     <li>{@link Scope#REQUEST REQUEST} - One instance per request/transaction</li>
     *     <li>{@link Scope#EVENT EVENT} - One instance per event processing</li>
     *     <li>{@link Scope#PROTOTYPE PROTOTYPE} - New instance every time (default)</li>
     * </ul>
     *
     * @return the scope of the produced component
     */
    Scope scope() default Scope.PROTOTYPE;

    /**
     * Optional qualifier name for the produced component.
     *
     * <p>Used to disambiguate when multiple components of the same type exist.
     * The name must match the {@code @Named} annotation at the injection point.
     *
     * <p>Example:
     * <pre>{@code
     * @Produces(name = "primary")
     * public Database primaryDatabase() { }
     *
     * @Inject
     * public UserService(@Named("primary") Database db) { }
     * }</pre>
     *
     * @return the qualifier name, or empty string if unnamed
     */
    String name() default "";

    /**
     * Optional profiles that must be active for this factory method to be enabled.
     *
     * <p>If specified, the produced component will only be created when at least one
     * of the listed profiles is active. If no profiles are specified, the component
     * is always enabled.
     *
     * <p>Example:
     * <pre>{@code
     * @Produces(profiles = {"dev", "test"})
     * public DataSource h2DataSource() { }  // Only in dev/test
     *
     * @Produces(profiles = {"prod"})
     * public DataSource postgresDataSource() { }  // Only in production
     * }</pre>
     *
     * @return array of profile names, or empty array if always enabled
     */
    String[] profiles() default {};
}
