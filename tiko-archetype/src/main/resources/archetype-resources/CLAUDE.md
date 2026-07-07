# Project context for Claude Code (and other coding agents)

This project uses **[Tiko DI](https://github.com/tomas-samek/tiko-di)** —
a compile-time dependency injection framework for Java 21+. Wiring is
validated and generated at build time — zero runtime reflection, zero
classpath scanning.

> **Building a service here?** Read
> [`.ai-skills/tiko-build/SKILL.md`](./.ai-skills/tiko-build/SKILL.md)
> first — decision tree, `@Produces` cookbook, anti-pattern redirects.
>
> **Library the cookbook doesn't cover?** Read
> [`.ai-skills/tiko-cookbook-extension/SKILL.md`](./.ai-skills/tiko-cookbook-extension/SKILL.md) —
> ask, don't fabricate.

First half: Tiko DI reference. Second half: template for this project's own docs.

---

# Tiko DI reference

## Scopes

Three scopes, longest to shortest lifetime:

| Scope | Lifetime | Typical use |
|---|---|---|
| `SINGLETON` | application lifetime | stateless services, repositories |
| `EVENT` | one unit of work — request, message, job, async dispatch | per-unit context (txn, connection, correlation ID) |
| `PROTOTYPE` | new instance per injection (default) | short-lived value objects |

**Default is `PROTOTYPE`** — declare `Scope.SINGLETON` explicitly for a longer lifetime.

**Cross-scope injection** (e.g. `SINGLETON` depending on `EVENT`) is allowed via a
compile-time auto-proxy, but **the shorter-scoped bean must implement an interface**
for the proxy to bind to. Longer-into-shorter is direct; shorter-into-longer needs
the interface.

## Annotations cheat-sheet

### Core (from `io.tiko.annotations`)

| Annotation | Purpose |
|---|---|
| `@Component(scope, name, profiles)` | Marks a class for DI (`SOURCE` retention). |
| `@Inject` | Marks the constructor to wire (constructor-only). |
| `@Named("qualifier")` | Disambiguates impls (worked example: `docs/di-and-scopes.md`). |
| `@Pick(SomeImpl.class)` | Picks a specific impl by class. |
| `@Produces(scope, name, profiles)` | Factory method on a `@Component` class. |
| `@PostConstruct` / `@PreDestroy` | Lifecycle hooks. |
| `@EventHandler(async, eventType)` | Subscribe to events. |
| `@EventTrigger(eventName, ...)` | Declarative event chains (return-as-payload). |

### Optional modules (`tiko-config` / `tiko-test`)

| Annotation | Purpose |
|---|---|
| `@Configuration(prefix)` | YAML-backed config root record. |
| `@Key("yaml.path")` | Override the YAML key name. |
| `@Default("value")` | Default for optional config fields. |
| `@TikoTest` | JUnit 5 extension; class-level. |
| `@TestComponent(value, scope, name)` | Shadow a `@Component` in tests. |
| `@RequestScopeTest` / `@EventScopeTest` | Wrap a `@Test` in a scope. |

### Exact packages (import from here, not from memory)

| Type | Package |
|---|---|
| `@Component` `@Inject` `@Named` `@Pick` `@Produces` `@PostConstruct` `@PreDestroy` `@EventHandler` `@EventTrigger` `@Configuration` `@Default` `@Key` | `io.tiko.annotations` |
| `@KafkaSource` `@KafkaSink` | `io.tiko.kafka.annotations` — **NOT** `io.tiko.annotations` |
| `Container` `EventBus` `Scope` `Provider` | `io.tiko` |
| `Tiko` `TikoOptions` `TikoDaemon` | `io.tiko.runtime` |
| `ConfigSources` | `io.tiko.config` |
| `KafkaTransport` / `JsonKafkaSerializer` / `FakeKafkaBroker` `FakeKafkaTransport` | `io.tiko.kafka` / `io.tiko.kafka.serializer` / `io.tiko.kafka.test` |

A `cannot find symbol` on an import means a wrong package — check this table, then
`javap` the jar; never conclude an annotation or class does not exist because one
import guess failed. Kafka types need `tiko-kafka` + `tiko-kafka-processor` (both
ship commented out; enable first). Signatures:
[`.ai-skills/tiko-build/SKILL.md`](.ai-skills/tiko-build/SKILL.md).

### Where the depth lives (read on demand)

| file | read when |
|---|---|
| [`.ai-skills/tiko-build/SKILL.md`](./.ai-skills/tiko-build/SKILL.md) | starting any new service work — decision tree, cookbook, anti-patterns |
| [`reference/api-signatures.md`](./.ai-skills/tiko-build/reference/api-signatures.md) | writing any import, or unsure of a signature / attribute / config key |
| [`reference/kafka.md`](./.ai-skills/tiko-build/reference/kafka.md) | consuming or producing Kafka, or the Kafka integration test |
| [`reference/config.md`](./.ai-skills/tiko-build/reference/config.md) | `@Configuration` records or override YAML |
| [`reference/events.md`](./.ai-skills/tiko-build/reference/events.md) | imperative publish, lifecycle hooks, daemon keep-alive |

## Rules

- **Constructor injection only** — `@Inject` on the constructor, never fields or setters.
- **Every `@Component` declares a scope** (or accepts `PROTOTYPE` default).
- **`AutoCloseable.close()` is implicitly a `@PreDestroy`** for a component implementing it with no explicit `@PreDestroy`.
- **Lifecycle hooks run LIFO at teardown** — last constructed, first destroyed.
- **Annotation processing runs in `mvn compile`** — generated code lives under `target/generated-sources/annotations/io/tiko/generated/`.
- **`Container` is `AutoCloseable`** — try-with-resources, or call `container.shutdown()`.

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

### YAML configuration (requires `tiko-config`)

See [`reference/config.md`](./.ai-skills/tiko-build/reference/config.md) for the
record + YAML walkthrough, imports, and file-name conventions.

> Module-shipped keys may differ: a record component annotated `@Key("...")` binds that literal key instead — `tiko.kafka.*` keys are kebab-case for exactly this reason (`bootstrap-servers`, see the key table in the tiko-build skill).

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

- **Field injection doesn't work** — rejected at compile time; use the constructor.
- **`@Component` with no scope is `PROTOTYPE`** — usually you want `SINGLETON`; be explicit.
- **`SINGLETON` injecting `EVENT` requires an interface** on the shorter-scoped bean.
- **`Container.get(...)` after `shutdown()` throws** — one-shot container.
- **JDK 23+ silently skips annotation processing without `<proc>full</proc>`** — already set in this `pom.xml`.
- **Override the *consumer's* declared type, not the impl's** — `TikoOptions.override(PaymentGateway.class, mock)` matches sites typed `PaymentGateway`, not `HttpPaymentGateway`.

## Build and run

```bash
mvn compile          # runs annotation processing → generated container
mvn test             # runs tests
mvn exec:java        # runs Main (pom.xml sets the mainClass)
mvn clean install    # full clean build
```

Generated code lives under `target/generated-sources/annotations/io/tiko/generated/`
(`TikoContainerImpl_<hash>.java`, per-component factories, `EventRegistry.java`).

### Long-running services (Kafka consumers, schedulers)

`Tiko.create(...)` try-with-resources shuts the container down at block end; a
transport-driven app (e.g. `@KafkaSource`) needs `Tiko.daemon(...).awaitShutdown()`
instead — full idiom in
[`reference/events.md`](./.ai-skills/tiko-build/reference/events.md).

## Optional Tiko modules

The starter `pom.xml` wires the core (`tiko-api`, `tiko-runtime`) + the processor; opt into more by uncommenting its block:

| Module | Purpose | Scope |
|---|---|---|
| `tiko-config` | Typed YAML configuration via `@Configuration` records. | compile |
| `tiko-test` | JUnit 5 extension, `@TestComponent`, `RecordingEventBus`. | test |
| `tiko-kafka` + `tiko-kafka-processor` | `@KafkaSource` / `@KafkaSink` bridges — see [`reference/kafka.md`](./.ai-skills/tiko-build/reference/kafka.md). | compile |

Logging: `java.lang.System.Logger` (JUL default; add `slf4j-jdk-platform-logging` to bridge to slf4j).

## MCP topology server

Ships a `.mcp.json`; MCP-aware agents auto-connect to the `tiko-mcp` topology
server for read access to the component graph, scopes, events, and config
schema, via [jbang](https://www.jbang.dev/). Setup/cache:
<https://github.com/tomas-samek/tiko-di/tree/main/tiko-examples/13_mcp_introspection>.

**No MCP (or no jbang)?** After a build, the same data is on disk under
`target/classes/META-INF/tiko/`: `topology.json`, `config-schema.json`,
`wiring-errors.json`, plus `topology-kafka.json` with the Kafka transport. For a
dependency's API, `javap` its jar.

## Where to dig deeper

- README: <https://github.com/tomas-samek/tiko-di/blob/main/README.md>
- Examples (12 modules): <https://github.com/tomas-samek/tiko-di/tree/main/tiko-examples>
- Docs index: <https://github.com/tomas-samek/tiko-di/tree/main/docs>

---

# About this project

*Fill in as the project grows; delete this notice when done.*

## Project overview

*What this project does, in one or two paragraphs.*

## Architecture

*Modules, layers, key boundaries. Link ADRs/design docs if any.*

## Conventions

*Naming, layering, anything not obvious from one file.*

## Patterns to follow

*Project-specific patterns (see "Common patterns" above for framework-level).*

## Patterns to avoid

*Anti-patterns or rejected approaches.*

## External dependencies

*Integrations, third-party services, environment setup.*
