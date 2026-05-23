# Project context for Claude Code (and other coding agents)

This project uses **[Tiko DI](https://github.com/tomas-samek/tiko-di)** —
a compile-time dependency injection framework for Java 21+. All wiring
is validated and generated at build time; the runtime container does
zero reflection and zero classpath scanning.

The first half of this file is a Tiko DI reference. The second half
(["About this project"](#about-this-project)) is a template for the
project's own documentation — fill it in as the project grows.

---

# Tiko DI reference

## Scopes

Four scopes, longest to shortest lifetime:

| Scope         | Lifetime                           | Typical use                                |
| ------------- | ---------------------------------- | ------------------------------------------ |
| `SINGLETON`   | application lifetime               | stateless services, repositories           |
| `REQUEST`     | one transaction / HTTP request / batch | per-request context, JDBC connection   |
| `EVENT`       | one event handler execution        | per-event correlation ID, audit context    |
| `PROTOTYPE`   | new instance per injection (default) | short-lived value objects                |

**Default scope is `PROTOTYPE`** — declare `Scope.SINGLETON` (or another)
explicitly when you want a different lifetime.

**Cross-scope injection** (e.g. `SINGLETON` depending on `REQUEST`) is
allowed — the framework generates an auto-proxy at compile time, but
**the shorter-scoped bean must implement an interface** for the proxy
to bind to. Cross-scope from longer-lived to shorter-lived is fine;
the reverse (short into long) is the case that needs the interface.

## Annotations cheat-sheet

### Core (from `io.tiko.annotations`)

| Annotation                              | Purpose                                          |
| --------------------------------------- | ------------------------------------------------ |
| `@Component(scope, name, profiles)`     | Marks a class for DI. `SOURCE` retention.       |
| `@Inject`                               | Marks the constructor to wire. Constructor-only — no field injection. |
| `@Named("qualifier")`                   | Disambiguates when multiple impls exist.        |
| `@Pick(SomeImpl.class)`                 | Picks a specific impl by class.                  |
| `@Produces(scope, name, profiles)`      | Factory method on a `@Component` class.          |
| `@PostConstruct` / `@PreDestroy`        | Lifecycle hooks.                                 |
| `@EventHandler(async, eventType)`       | Subscribe to events.                             |
| `@EventTrigger(eventName, ...)`         | Declarative event chains (return-as-payload).    |

### Config (optional, from `tiko-config`)

| Annotation                  | Purpose                                          |
| --------------------------- | ------------------------------------------------ |
| `@Configuration(prefix)`    | Marks a record as a YAML-backed config root.    |
| `@Key("yaml.path")`         | Override the YAML key name.                      |
| `@Default("value")`         | Default for optional config fields.              |

### Test (optional, from `tiko-test`)

| Annotation                              | Purpose                                          |
| --------------------------------------- | ------------------------------------------------ |
| `@TikoTest`                             | JUnit 5 extension; class-level.                 |
| `@TestComponent(value, scope, name)`    | Shadow a production `@Component` in tests.      |
| `@RequestScopeTest` / `@EventScopeTest` | Wrap a `@Test` in a scope.                       |

## Rules

- **Constructor injection only.** `@Inject` on the constructor, never on fields or setters.
- **Every `@Component` declares a scope** (or accepts the `PROTOTYPE` default).
- **`AutoCloseable.close()` is implicitly a `@PreDestroy`** when the component implements `AutoCloseable` and has no explicit `@PreDestroy` method.
- **Lifecycle hooks run LIFO at scope teardown** — last constructed, first destroyed.
- **Annotation processing runs in `mvn compile`.** Code generation lives in `target/generated-sources/annotations/io/tiko/generated/` — readable, debuggable, no magic.
- **`Container` is `AutoCloseable`** — use try-with-resources, or call `container.shutdown()` explicitly.

## Common patterns

### Constructor injection

```java
@Component(scope = Scope.SINGLETON)
public class OrderService {

    private final OrderRepository repository;
    private final Clock clock;

    @Inject
    public OrderService(OrderRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Order create(String customerId, long amountCents) {
        var order = new Order(customerId, amountCents, clock.now());
        repository.save(order);
        return order;
    }
}
```

### Disambiguating with `@Named`

```java
@Component(scope = Scope.SINGLETON, name = "primary")
public class PrimaryDataSource implements DataSource { /* ... */ }

@Component(scope = Scope.SINGLETON, name = "audit")
public class AuditDataSource implements DataSource { /* ... */ }

@Component(scope = Scope.SINGLETON)
public class UserRepository {
    @Inject
    public UserRepository(@Named("primary") DataSource ds) { /* ... */ }
}
```

### Factory methods with `@Produces`

```java
@Component(scope = Scope.SINGLETON)
public class CacheConfig {

    @Produces(scope = Scope.SINGLETON, name = "userCache")
    public Cache<String, User> userCache() {
        return Caffeine.newBuilder().maximumSize(10_000).build();
    }
}
```

### Lifecycle hooks

```java
@Component(scope = Scope.SINGLETON)
public class HttpServer implements AutoCloseable {

    private Server server;

    @PostConstruct
    void start() { server = new Server(8080); server.start(); }

    @Override
    public void close() { if (server != null) server.stop(); }
    // No explicit @PreDestroy needed — AutoCloseable.close() runs at shutdown.
}
```

### Cross-scope proxy

```java
public interface RequestContext { String requestId(); }

@Component(scope = Scope.REQUEST)
public class RequestContextImpl implements RequestContext {
    private final String id = UUID.randomUUID().toString();
    public String requestId() { return id; }
}

@Component(scope = Scope.SINGLETON)
public class AuditLogger {
    @Inject
    public AuditLogger(RequestContext ctx) {
        // ctx is auto-proxied — each call resolves the current REQUEST scope's instance.
    }
}
```

### YAML configuration (requires `tiko-config`)

```java
@Configuration(prefix = "database")
public record DbConfig(
        String url,
        String username,
        @Default("10") int poolSize,
        @Default("PT30S") Duration connectTimeout) {}

@Component(scope = Scope.SINGLETON)
public class DataSourceFactory {
    @Inject
    public DataSourceFactory(DbConfig config) { /* ... */ }
}
```

`config.yaml` at the classpath root:

```yaml
database:
  url: jdbc:postgresql://localhost/app
  username: app
  poolSize: 20
```

Bootstrap with:

```java
try (Container container = Tiko.create(
        TikoOptions.builder()
            .configSource(ConfigSources.classpath("config.yaml"))
            .build())) {
    // ...
}
```

### Events

```java
public record OrderPlaced(String orderId, long amountCents) {}

@Component(scope = Scope.SINGLETON)
public class OrderListener {
    @EventHandler
    public void onOrderPlaced(OrderPlaced event) {
        // Synchronous by default.
    }

    @EventHandler(async = true)
    public void notifyAsync(OrderPlaced event) {
        // Off the publisher thread, bounded executor.
    }
}

@Component(scope = Scope.SINGLETON)
public class OrderService {
    private final EventBus bus;

    @Inject
    public OrderService(EventBus bus) { this.bus = bus; }

    public void create(String orderId, long amountCents) {
        bus.publish(new OrderPlaced(orderId, amountCents));
    }
}
```

### Declarative chains with `@EventTrigger`

```java
@EventHandler
@EventTrigger(eventName = "OrderValidated")
public ValidationResult onOrderCreated(OrderCreated event) {
    return validate(event);  // return value becomes the next event's payload
}
```

### Testing with `@TikoTest` (requires `tiko-test`)

```java
@TikoTest
class OrderServiceTest {

    @Test
    void createsAndPublishes(OrderService orders, RecordingEventBus bus) {
        orders.create("ord-1", 4200L);
        bus.assertPublished(OrderPlaced.class)
           .withPayload((OrderPlaced e) -> e.orderId().equals("ord-1"));
    }

    @Test
    void mocksThePaymentGateway() {
        var mock = mock(PaymentGateway.class);
        when(mock.charge(any(), anyLong())).thenReturn("MOCK-TXN");
        try (Container c = Tiko.create(TikoOptions.builder()
                .override(PaymentGateway.class, () -> mock)
                .build())) {
            // ...
        }
    }
}

// Shadow a production @Component with a test fixture:
@TestComponent
public class FixedClock extends Clock {
    public Instant now() { return Instant.parse("2026-01-01T00:00:00Z"); }
}
```

## Common pitfalls

- **Field injection doesn't work** — Tiko rejects it at compile time. Use the constructor.
- **`@Component` with no scope is `PROTOTYPE`** — a new instance per injection. Usually you want `SINGLETON`. Be explicit.
- **`SINGLETON` injecting `REQUEST`/`EVENT` requires an interface on the shorter-scoped bean** — the framework generates the proxy via that interface.
- **`Container.get(...)` after `shutdown()` throws** — the container is one-shot. Use try-with-resources or careful manual lifecycle.
- **Annotation processing is silently skipped on JDK 23+ without `<proc>full</proc>`** — the archetype's `pom.xml` already sets this.
- **Override the *consumer's* declared type, not the impl's concrete class** — `TikoOptions.override(PaymentGateway.class, mock)` matches injection sites typed `PaymentGateway`. Overriding `HttpPaymentGateway.class` only matches sites typed at that concrete class.

## Build and run

```bash
mvn compile                       # runs annotation processing → generated container
mvn test                          # runs tests
mvn exec:java                     # runs Main (pom.xml sets the mainClass)
mvn clean install                 # full clean build
```

To inspect what the processor generated:

```bash
ls target/generated-sources/annotations/io/tiko/generated/
```

You'll see `TikoContainerImpl_<hash>.java` (the wiring), `<Component>Factory.java` per component, `EventRegistry.java` if you use events, and optional config binders.

## Optional Tiko modules

The starter `pom.xml` already wires the core (`tiko-api`, `tiko-runtime`) and the annotation processor. To opt into more:

| Module                     | Purpose                                                                  | Scope |
| -------------------------- | ------------------------------------------------------------------------ | ----- |
| `tiko-config`              | Typed YAML configuration injection via `@Configuration` records.        | compile |
| `tiko-test`                | JUnit 5 extension, `@TestComponent` shadow overrides, `RecordingEventBus`. | test  |
| `tiko-kafka` + `tiko-kafka-processor` | Kafka transport — `@KafkaSource` / `@KafkaSink`, JSON serializer. | compile |

Each is opt-in. Uncomment the corresponding block in `pom.xml` to enable.

For logging, Tiko routes through `java.lang.System.Logger` — works with JUL out of the box, or add `slf4j-jdk-platform-logging` + your slf4j backend to route through slf4j.

## Where to dig deeper

- Framework README: <https://github.com/tomas-samek/tiko-di/blob/main/README.md>
- Worked examples (12 modules): <https://github.com/tomas-samek/tiko-di/tree/main/tiko-examples>
- Documentation index: <https://github.com/tomas-samek/tiko-di/tree/main/docs>

---

# About this project

*The sections below are for the project's own documentation. Fill them
in as the project grows; delete this notice when done.*

## Project overview

*Describe what this project does, in one or two paragraphs.*

## Architecture

*High-level architecture: modules, layers, key boundaries. Link to ADRs
or design docs if any.*

## Conventions

*Project-specific coding conventions. Naming, layering rules, anything
that isn't obvious from looking at one file.*

## Patterns to follow

*Project-specific patterns. (For framework-level patterns see the
"Common patterns" section above.)*

## Patterns to avoid

*Anti-patterns or things tried and rejected.*

## External dependencies

*Integrations, third-party services, environment setup.*
