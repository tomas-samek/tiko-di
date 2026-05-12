# Dependency injection and scopes

This page is the full reference for Tiko's DI model: scopes, lifecycle hooks, qualifiers, factory methods, and the `pick()` lookup API. For the 10-line "hello world", see the [README quick example](../README.md#quick-example). For runnable code, see [`tiko-examples/01_basic_di`](../tiko-examples/01_basic_di).

All annotations live in `io.tiko.annotations.*`.

## Scopes

Four scopes — longest to shortest lifetime:

| Scope                | Lifetime                                              | Typical use                              |
|----------------------|-------------------------------------------------------|------------------------------------------|
| `Scope.SINGLETON`    | Application                                           | Stateless services, repositories         |
| `Scope.REQUEST`      | One transaction / HTTP request / batch                | Coarse-grained unit of work              |
| `Scope.EVENT`        | One event handler execution                           | Fine-grained per-event context           |
| `Scope.PROTOTYPE`    | One injection (default)                               | New instance every time                  |

One `REQUEST` can wrap many `EVENT`s — e.g. a batch transaction that processes a stream of events.

### Cross-scope injection

Shorter-lived beans injected into longer-lived scopes are wired through an **auto-generated proxy** that resolves the active scope's instance on every call. The proxied bean must implement an interface (no reflection — the processor emits a typed proxy class).

| Injecting into | Can inject  | Notes                                  |
|----------------|-------------|----------------------------------------|
| `SINGLETON`    | `SINGLETON` | Direct injection                       |
| `SINGLETON`    | `REQUEST`   | Automatic proxy (requires interface)   |
| `SINGLETON`    | `EVENT`     | Automatic proxy (requires interface)   |
| `SINGLETON`    | `PROTOTYPE` | New instance each time                 |
| `REQUEST`      | `SINGLETON` | Direct injection                       |
| `REQUEST`      | `REQUEST`   | Direct injection                       |
| `REQUEST`      | `EVENT`     | Automatic proxy (requires interface)   |
| `REQUEST`      | `PROTOTYPE` | New instance each time                 |
| `EVENT`        | `SINGLETON` | Direct injection                       |
| `EVENT`        | `REQUEST`   | Direct injection                       |
| `EVENT`        | `EVENT`     | Direct injection                       |
| `EVENT`        | `PROTOTYPE` | New instance each time                 |

### REQUEST and EVENT scopes together

```java
public interface TransactionContext { String getTransactionId(); }

@Component(scope = Scope.REQUEST)
public class TransactionContextImpl implements TransactionContext {
    private final String txId = UUID.randomUUID().toString();
    public String getTransactionId() { return txId; }
}

public interface EventContext { String getEventId(); Instant getTimestamp(); }

@Component(scope = Scope.EVENT)
public class EventContextImpl implements EventContext {
    private final String eventId = UUID.randomUUID().toString();
    private final Instant timestamp = Instant.now();
    public String getEventId()   { return eventId; }
    public Instant getTimestamp(){ return timestamp; }
}

// Batch processing — one REQUEST, many EVENTs
container.runInRequestScope(() -> {
    for (Order order : orders) {
        container.runInEventScope(() -> {
            orderService.process(order);
            // Same TransactionContext, different EventContext per iteration
        });
    }
});
```

## Constructor injection

The preferred and recommended form. All dependencies are wired before the constructor body runs, so initialization belongs there.

```java
@Component(scope = Scope.SINGLETON)
public class OrderService {
    private final OrderRepository repository;
    private final PaymentService paymentService;

    @Inject
    public OrderService(OrderRepository repository, PaymentService paymentService) {
        this.repository = repository;
        this.paymentService = paymentService;
    }
}
```

Tiko deliberately does **not** support field or setter injection.

## Lifecycle hooks

The recommended cleanup pattern is `AutoCloseable`. A component (or `@Produces`-returned type) that implements `AutoCloseable` and declares no explicit `@PreDestroy` gets `close()` called automatically at scope teardown — no annotation required. This lets `@Produces` factories return third-party closeables (data sources, HTTP clients, Kafka producers) without writing a wrapper.

```java
@Component(scope = Scope.SINGLETON)
public class DatabaseConnection implements AutoCloseable {
    private final Connection connection;

    @Inject
    public DatabaseConnection(ConfigProvider config) throws SQLException {
        this.connection = DriverManager.getConnection(config.dbUrl());
    }

    public Connection get() { return connection; }

    @Override
    public void close() throws SQLException {
        connection.close();   // called automatically at container.shutdown()
    }
}
```

```java
@Component
public class DataSources {
    @Produces(scope = Scope.SINGLETON, name = "primary")
    public DataSource primary(ConfigProvider cfg) {
        return new HikariDataSource(toHikari(cfg));   // close() auto-called at shutdown
    }
}
```

A compile-time leak check warns when a `@Component` holds an `AutoCloseable`-typed field but has neither a `@PreDestroy` nor implements `AutoCloseable` itself. Suppress with `@SuppressWarnings("resource")` on the field or class when the resource is owned elsewhere.

### `@PostConstruct` and `@PreDestroy` (legacy form)

Supported for compatibility and migration ease. **Prefer the constructor + `AutoCloseable` form** — single source of truth, no risk of multiple `@PreDestroy` methods on one class.

- `@PostConstruct` runs after dependency injection completes. With constructor injection there's rarely a need: deps are already wired by the time the constructor body executes, so init logic belongs in the constructor.
- `@PreDestroy` runs at scope teardown (`SINGLETON`: on `container.shutdown()`; `REQUEST`/`EVENT`: on scope exit). Hooks fire in reverse-creation (LIFO) order — a bean's dependencies are still available during its cleanup. The corresponding `Ending` lifecycle event is published before any `@PreDestroy` runs.
- If a class declares an explicit `@PreDestroy`, it wins over `AutoCloseable.close()` (no double-cleanup).

```java
@Component(scope = Scope.SINGLETON)
public class DatabaseConnection {
    private Connection connection;

    @PostConstruct
    public void connect() {
        this.connection = DriverManager.getConnection("jdbc:...");
    }

    @PreDestroy
    public void disconnect() throws SQLException {
        connection.close();
    }
}
```

## Qualifiers — `@Named`, `@Pick`, `Picker<T>`, `pick()`

When multiple components implement the same interface (or multiple `@Produces` methods return the same type), the injection site needs to disambiguate. Tiko has three mechanisms, each for a different situation.

### `@Named("...")` — string-keyed disambiguation

Used to disambiguate `@Produces` factory methods returning the same type, or when the bean name is genuinely string-keyed metadata.

```java
@Component(scope = Scope.SINGLETON, name = "mysql")
public class MySqlDatabase implements Database { }

@Component(scope = Scope.SINGLETON, name = "postgres")
public class PostgresDatabase implements Database { }

@Component(scope = Scope.SINGLETON)
public class DataService {
    @Inject
    public DataService(@Named("mysql") Database database) {
        // injects MySqlDatabase
    }
}
```

### `@Pick(Class)` — class-literal, refactor-safe

When the disambiguator is a *class* — not a string — `@Pick(SomeImpl.class)` is the compile-time alternative to `@Named`. Typos become compile errors, IDE rename refactors update every usage, and the choice of implementation is visible in the constructor signature.

```java
@Component class MySqlDatabase    implements Database { ... }
@Component class PostgresDatabase implements Database { ... }

@Component(scope = Scope.SINGLETON)
public class DataService {
    @Inject
    public DataService(
            @Pick(MySqlDatabase.class)    Database primary,
            @Pick(PostgresDatabase.class) Database analytics) {
        // ...
    }
}
```

### `@Pick(Class) @Named("...")` together — narrow then select

When several `@Produces` methods return the same concrete subtype with different names: `@Pick` pins the impl, `@Named` selects which producer.

```java
@Component class DataSourceFactories {
    @Produces @Named("primary") HikariDataSource primary() { ... }
    @Produces @Named("backup")  HikariDataSource backup()  { ... }
}

@Component
public class OrderService {
    @Inject
    public OrderService(@Pick(HikariDataSource.class) @Named("primary") DataSource ds) {
        // ...
    }
}
```

Compile-time errors enforce: the picked class must be assignable to the parameter type, must not be the parameter type itself, and — when `@Named` is absent — must be uniquely produced.

### `Picker<T>` — runtime polymorphic queries

When the choice of impl is not known at compile time (driven by configuration, runtime data, or "I want to iterate them all"), inject a `Picker<T>` and query at runtime. Typed at the boundary (`Picker<DataSource>` only returns `DataSource` subtypes); one runtime impl handles every injection point.

```java
@Component
public class StrategyService {
    private final Strategy strategy;

    @Inject
    public StrategyService(Picker<Strategy> strategies, StrategyConfig config) {
        // config.implName() comes from YAML — only known at runtime
        this.strategy = strategies.byName(config.implName())
                .orElseThrow(() -> new IllegalStateException(
                        "no Strategy named '" + config.implName() + "'"));
    }
}
```

API surface (three methods, all return-by-value):

```java
public interface Picker<T> {
    List<T> list();                                                // every registered impl of T
    Optional<T> byName(String name);                               // @Component(name=...) lookup
    <S extends T> Optional<S> byImplClass(Class<S> implClass);     // class-keyed lookup
}
```

Cross-module pickers work for free — the underlying `Container.get(Class)` / `getAll(Class)` route through the aggregator when multiple modules are linked, so `Picker<T>.list()` returns the union of impls from every module on the classpath.

### Decision tree

- **`@Pick(SomeImpl.class) T`** — compile-time-known, class-based qualifier. Declarative; the choice is visible in the constructor signature. **Default choice when there's a class to point at.**
- **`@Named("name") T`** — compile-time-known, string-based qualifier. Best for disambiguating multiple `@Produces` methods returning the same type.
- **`Picker<T>`** — runtime queries: the impl class or name is computed at runtime, you need to iterate all impls, or you want a single primitive that handles cross-module lookup transparently.

### Fluent `pick()` API on the container

`container.get(Class)` and `container.get(Class, String)` are the 80% shortcuts. For anything else — a fallback when the bean is missing, a lazy `Provider`, or future axes — go through `container.pick(Class)`:

```java
// Same as container.get(Greeter.class)
Greeter g = container.pick(Greeter.class).resolve();

// Named lookup — same as container.get(Greeter.class, "english")
Greeter english = container.pick(Greeter.class).withName("english").resolve();

// Lazy Provider that re-resolves each call (preserves scope semantics)
Provider<Greeter> p = container.pick(Greeter.class).withName("spanish").asProvider();

// Fallback when the bean is absent — no IllegalArgumentException
Greeter french = container.pick(Greeter.class)
        .withName("french")
        .orDefault(new NoopGreeter());
```

## `@Produces` factory methods

For cases where a component cannot be constructed by a plain `@Inject` constructor — validation logic, complex initialization, third-party classes, private constructors.

Two forms: instance methods (separate factory component, may itself have `@Inject` dependencies) and static methods (same class — typically used with a private constructor for forced factory construction).

```java
// Instance factory with injected dependencies
@Component(scope = Scope.SINGLETON)
public class DatabaseConfiguration {

    private final ConfigProvider config;

    @Inject
    public DatabaseConfiguration(ConfigProvider config) {
        this.config = config;
    }

    @Produces(scope = Scope.SINGLETON, name = "mysql")
    public DataSource mysqlDataSource() {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.getMysqlUrl());
        hikariConfig.setUsername(config.getMysqlUsername());
        hikariConfig.setPassword(config.getMysqlPassword());
        return new HikariDataSource(hikariConfig);
    }

    @Produces(scope = Scope.SINGLETON, name = "postgres")
    public DataSource postgresDataSource() {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.getPostgresUrl());
        return new HikariDataSource(hikariConfig);
    }
}
```

```java
// Factory methods can have their own dependencies injected
@Component
public class ServiceFactories {
    @Produces(scope = Scope.SINGLETON)
    public CacheService cacheService(RedisClient redis, MetricsCollector metrics) {
        CacheService service = new CacheServiceImpl(redis);
        service.enableMetrics(metrics);
        return service;
    }
}
```

```java
// Static factory method with validation (same class, private constructor)
@Component
public class Database {
    private final Connection connection;

    private Database(Connection connection) {
        this.connection = connection;
    }

    @Produces(scope = Scope.SINGLETON)
    public static Database create(ConfigProvider config) {
        if (config.getUrl() == null || config.getUrl().isEmpty()) {
            throw new IllegalArgumentException("Database URL is required");
        }

        Connection conn = DriverManager.getConnection(
                config.getUrl(),
                config.getUsername(),
                config.getPassword());
        conn.setAutoCommit(false);

        return new Database(conn);
    }
}
```

## `Provider<T>` — lazy resolution

When a bean shouldn't be eagerly constructed at injection time — circular references, optional dependencies, deferred-until-first-use components — inject a `Provider<T>` instead of the bean itself. `provider.get()` resolves the current-scope instance every call.

```java
@Component(scope = Scope.SINGLETON)
public class Workflow {
    private final Provider<ExpensiveComponent> lazy;

    @Inject
    public Workflow(Provider<ExpensiveComponent> lazy) {
        this.lazy = lazy;
    }

    public void run() {
        if (shouldUseExpensive()) {
            lazy.get().doWork();
        }
    }
}
```
