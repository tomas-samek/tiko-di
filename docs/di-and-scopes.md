# Dependency injection and scopes

This page is the full reference for Tiko's DI model: scopes, lifecycle hooks, qualifiers, factory methods, and the `pick()` lookup API. For the 10-line "hello world", see the [README quick example](../README.md#quick-example). For runnable code, see [`tiko-examples/01_basic_di`](../tiko-examples/01_basic_di).

All annotations live in `io.tiko.annotations.*`.

## Scopes

Three scopes — longest to shortest lifetime:

| Scope                | Lifetime                                                                  | Typical use                              |
|----------------------|---------------------------------------------------------------------------|------------------------------------------|
| `Scope.SINGLETON`    | Application                                                               | Stateless services, repositories         |
| `Scope.EVENT`        | One unit of work (HTTP request, consumed message, scheduled job, async)   | Per-unit context, txn, connection        |
| `Scope.PROTOTYPE`    | One injection (default)                                                   | New instance every time                  |

A **unit of work** is the synchronous reach of an inbound stimulus, bounded at every async / transport hop. EVENT is single-frame in `0.x.0` — calling `runInEventScope` while a unit is already open throws `IllegalStateException`. Nestability is a deferred-but-additive future change.

EVENT-scoped beans only resolve while a unit of work is open. Resolving one outside a frame — via `container.get(...)`, `getAll(...)`, or a method call on a proxy held by a SINGLETON — throws `NoActiveEventScopeException` instead of silently materializing an instance that no teardown would ever drain.

### When singletons are constructed

A **single-module container** is the usual application: one module runs the processor. When it starts (`Tiko.create(...)`, `Tiko.daemon(...)`), it constructs every SINGLETON `@Component`, and with it everything those constructors take. Everything else is constructed on first use:

- **A SINGLETON value from a `@Produces` method** is built the first time something resolves it: a constructor that takes it, a `container.get(...)`, or `Provider.get()`. Constructing the `@Component` that declares the method does not call the method.
- **A dependency taken as `Provider<T>`** is not resolved until `get()` is called.
- **EVENT and PROTOTYPE beans** are built inside a unit of work and per injection.

A failure while the container starts fails `Tiko.create(...)` or `Tiko.daemon(...)`, and the container shuts down what it already started. A produced value that is first resolved later fails at that later call instead.

**Two kinds of container construct no SINGLETON when they start.** There a SINGLETON `@Component` is built on first use, like a produced value:

- **A multi-module container**: more than one module on the classpath ran the processor, as in [`tiko-examples/05_multi_module`](../tiko-examples/05_multi_module).
- **A test-wiring container**: a `@TikoTest` container (or one created with `testWiring(true)`) whose test tree declares components of its own, `@Component` or `@TestComponent`, on top of the main module's.

In both, a failing constructor or `@PostConstruct` surfaces at the first use and does not fail `Tiko.create(...)`.

So a produced resource that starts its own work, such as a relay, a scheduler or a pool you want to fail fast, does nothing until something asks for it. To start it at boot in a single-module container, take it as a constructor parameter of a SINGLETON `@Component`:

```java
@Component(scope = Scope.SINGLETON)
public class OutboxStartup {
    @Inject
    public OutboxStartup(OutboxRelay relay) {
        // Constructed at container start, so the produced relay is built and running too.
    }
}
```

If the class is yours, making it a SINGLETON `@Component` instead of producing it does the same.

In a multi-module or test-wiring container `OutboxStartup` itself waits for its first use. Two ways start it at boot in every kind of container:

- **Resolve it yourself** right after the container is created: `container.get(OutboxRelay.class)`. A failure then fails your `main`. [`tiko-examples/17_transactional_outbox`](../tiko-examples/17_transactional_outbox) does this.
- **Handle `ApplicationStartedEvent` in the SINGLETON.** The container delivers that event when it starts, and delivering it constructs the component:

  ```java
  @Component(scope = Scope.SINGLETON)
  public class OutboxStartup {
      @Inject
      public OutboxStartup(OutboxRelay relay) {}

      @EventHandler
      public void onStarted(ApplicationStartedEvent started) {
          // Handling the event is what gets this component, and the relay, built at start.
      }
  }
  ```

  In a multi-module or test-wiring container, a failure while the event constructs it is logged at `WARNING` and does not fail `Tiko.create(...)`.

### Cross-scope injection

An EVENT-scoped bean injected into a SINGLETON is wired through an **auto-generated proxy** that resolves the current unit's instance on every call. The proxied bean must implement an interface (no reflection — the processor emits a typed proxy class).

| Consumer ↓ / Dependency → | SINGLETON | EVENT     | PROTOTYPE |
|---------------------------|-----------|-----------|-----------|
| `SINGLETON`               | direct    | **proxy** | direct    |
| `EVENT`                   | direct    | direct    | direct    |
| `PROTOTYPE`               | direct    | direct    | direct    |

A SINGLETON keeps a PROTOTYPE it injects for its own lifetime, so the rule reaches through it: whatever that PROTOTYPE injects directly must be safe for the SINGLETON too. Two shapes are compile errors:

- **A SINGLETON that injects a PROTOTYPE which injects an EVENT bean as the instance itself**: a concrete EVENT component, or an EVENT `@Produces` value (a PROTOTYPE receives the value, not its proxy). The SINGLETON would keep one unit's instance in every later unit. Take the PROTOTYPE, or the EVENT dependency inside it, as a `Provider<T>` and call `get()` on each use. An EVENT component that implements an interface is fine: every consumer receives its proxy.
- **An instance `@Produces(scope = Scope.SINGLETON)` method on an EVENT component**: calling it needs an instance that exists only inside a unit. Make the method `static`, or declare it on a SINGLETON or PROTOTYPE component.

Resources bind to the current unit only; sharing one across units is a non-goal.

### Unit of work in practice

A batch that needs one transaction across its items is one unit of work, not many:

```java
public interface TransactionContext { String getTransactionId(); }

@Component(scope = Scope.EVENT)
public class TransactionContextImpl implements TransactionContext {
    private final String txId = UUID.randomUUID().toString();
    public String getTransactionId() { return txId; }
}

container.runInEventScope(() -> {
    var tx = container.get(TransactionContext.class);
    tx.begin();
    try {
        for (Order order : orders) {
            process(order, tx);
        }
        tx.commit();
    } catch (Exception e) {
        tx.rollback();
        throw e;
    }
});
```

Genuinely independent events — those that need to be retryable, distributed, or re-ordered relative to the batch — each own their own unit of work and their own transaction; cross-event consistency is an outbox/saga concern above the DI layer.

Async `@EventHandler` dispatch is a scope boundary: the handler runs in its own fresh
EVENT unit (per attempt, when retries are configured), so EVENT-scoped dependencies of an
async handler resolve to that unit and are torn down when it ends. The publishing side's
unit — if any — is left behind at the async hop.

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
- `@PreDestroy` runs at scope teardown (`SINGLETON`: on `container.shutdown()`; `EVENT`: on scope exit). Hooks fire in reverse-creation (LIFO) order — a bean's dependencies are still available during its cleanup. The corresponding `Ending` lifecycle event is published before any `@PreDestroy` runs.
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

`@Named` resolves named `@Component`s (above) and named `@Produces` outputs alike. **Reach for `@Named` when the qualifier is genuinely a string** — externally configured, supplied at runtime, or familiar to teams coming from `jakarta.inject`. When the disambiguator is a *class* you can name at compile time, prefer `@Pick` (next) for refactor safety. A runnable injection-site example lives in `tiko-examples/01_basic_di` (`NamedGreeterConsumer`).

### `@Pick(Class)` — class-literal, refactor-safe

When the disambiguator is a *class* — not a string — `@Pick(SomeImpl.class)` is the compile-time alternative to `@Named`. Typos become compile errors, IDE rename refactors update every usage, and the choice of implementation is visible in the constructor signature.

```java
@Component public class MySqlDatabase    implements Database { ... }
@Component public class PostgresDatabase implements Database { ... }

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

When several `@Produces` methods return the same concrete subtype with different names: `@Pick` pins the impl, `@Named` selects which producer. The producer states its name with `@Produces(name = ...)`; `@Named` goes on the injection point only.

```java
@Component
public class DataSourceFactories {
    @Produces(name = "primary") public HikariDataSource primary() { ... }
    @Produces(name = "backup")  public HikariDataSource backup()  { ... }
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

In a multi-module container, unqualified `container.get(Greeter.class)` and
`container.getProvider(Greeter.class)` use the first matching module in discovery order when no
runtime override applies. Discovery order is unspecified and can vary with packaging or class
loading. For an explicit choice, request a registered concrete class such as
`container.get(EnglishGreeter.class)` or use a unique qualifier such as
`container.get(Greeter.class, "english")`. Use `container.getAll(Greeter.class)` to list all matches.
Constructor dependencies follow the compile-time qualifier rules above.

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

`Database` and its `create` factory are one component, so its scope and profiles may be stated on
either annotation: here `Database` is a SINGLETON. Two different scopes, or two different profile
lists, stated on the two annotations are a compile error.

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
