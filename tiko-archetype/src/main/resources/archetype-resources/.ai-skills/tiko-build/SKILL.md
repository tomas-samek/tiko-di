---
name: tiko-build
description: Use when scaffolding or extending a service built on Tiko DI. Decision tree + @Produces cookbook + anti-pattern redirects so agents reach for the tiko-native primitive instead of searching for a Spring equivalent.
---

# tiko-build

> Tiko orchestrates, it doesn't bundle — direct access, compile-time safe,
> nothing wrapped.

This file is the **operational distillation** of the orchestrator-model doc
([`docs/orchestrator-model.md`](https://github.com/tomas-samek/tiko-di/blob/main/docs/orchestrator-model.md)
in the framework repo). That long doc is the source of truth for prose; this
file is the shape an agent reads to act.

## The rule

When a user says *"I need X"*, classify X into one of three buckets:

| Bucket | What it means | What to do |
|---|---|---|
| **Core** | container, scopes, event bus, compile-time wiring, lifecycle | Use a tiko primitive directly. |
| **Plug in** | any integration with an external system (HTTP, DB, cache, templating, scheduling, retry, observability, security, SDK clients) | Bring the library; expose it as a `@Produces` value; consume as a constructor parameter. |
| **Open** | extending the event model itself (new async modes, scheduling-as-event, retry-as-loop) | Open an issue against tiko-di. Don't invent. |

**Default if uncertain: Plug in.** Never search for "tiko's equivalent of
Spring's X" — that frame is the failure mode this skill exists to prevent.

## When in doubt, ask

If the user's prompt doesn't make the bucket obvious, **ask before
inventing**. A clarifying question is always cheaper than a wrong recipe.
Ask about:

- The lifecycle of the resource (per-request, per-app, per-event).
- Whether the user has already chosen a library.
- Whether the concern lives inside the app (Plug in) or is about extending
  tiko itself (Open).

Never fabricate a recipe for a library the user didn't name. The cookbook
below covers the canonical libraries; outside that list, ask which one to
plug in.

## Scaffolding shape

```
service/
├── pom.xml                            # tiko-api + tiko-processor + tiko-runtime + tiko-config + your libs
├── src/main/java/com/example/svc/
│   ├── AppConfig.java                 # @Configuration root record
│   ├── <Library>Factory.java          # @Produces <ThirdPartyValue>
│   ├── <Thing>Repository.java         # raw library API
│   ├── <Thing>Created.java            # domain event record
│   ├── <Thing>Auditor.java            # @EventHandler(<Thing>Created)
│   ├── <Thing>Routes.java             # plain route methods (not a @Component)
│   └── Main.java                      # Tiko.create + register routes + start
├── src/main/resources/
│   ├── application.yml                # typed-config binding
│   └── schema.sql                     # if applicable
└── src/test/java/...
```

Bootstrap pattern (`Main.java`):
```java
Container container = Tiko.create(ConfigSources.classpath("application.yml"));
Runtime.getRuntime().addShutdownHook(new Thread(container::shutdown));
var routes = new ThingRoutes(container.get(ThingRepository.class), container.getEventBus());
Javalin app = container.get(Javalin.class);
app.post("/things", routes::handleCreate);
app.start(container.get(AppConfig.class).server().port());
```

Reference shape:
[`tiko-examples/15_quickstart`](https://github.com/tomas-samek/tiko-di/tree/main/tiko-examples/15_quickstart).

## Imperative publish & keeping the process alive

**To publish events from inside a component, inject `EventBus`** — it is a built-in
dependency, resolved by the container like any bean. No plain-class workaround:

```java
@Component(scope = Scope.SINGLETON)
public class OrderApi {
    private final EventBus events;
    @Inject public OrderApi(EventBus events) { this.events = events; }
    public void place(Order o) { events.publish(new OrderPlaced(o.id())); }
}
```

`Container` itself is **not** injectable (injecting it is service location). If you
genuinely need the container — e.g. plain route handlers that aren't components — pass
it into a hand-constructed class after bootstrap, as `ThingRoutes` does above. Prefer
injecting `EventBus` (or the specific collaborators) over reaching for `Container`.

**Keep a headless process alive with one idiom: `Tiko.daemon(...).awaitShutdown()`.**
`daemon(...)` installs a JVM shutdown hook (graceful `@PreDestroy` on `Ctrl+C` /
`SIGTERM`); `awaitShutdown()` blocks `main` until then. Do **not** improvise
`Thread.join()` / `CountDownLatch`.

```java
public static void main(String[] args) {
    TikoDaemon daemon = Tiko.daemon(ConfigSources.classpath("application.yml"));
    // resolve beans / start consumers via daemon.container() ...
    daemon.awaitShutdown();   // canonical keep-alive
}
```

An app that runs its own non-daemon foreground server (e.g. Javalin `app.start()`)
does not need `awaitShutdown()` — that server thread already keeps the JVM up; use
`Tiko.create(...)` with try-with-resources or a shutdown hook, as in the bootstrap
above.

## Typed config: keys are exact

`tiko-config` binds YAML to `@Configuration` records by the **exact component
name** — no kebab-case/snake_case normalization, no Spring-style relaxation.
`poolSize` binds from `poolSize`, never `pool-size` or `pool_size`. A wrong key
fails the build with a `ConfigValidationException` naming the bad path and (for
a near-miss) suggesting the right key (`Did you mean 'db.poolSize'?`). Write the
config to match the record and it binds on the first attempt.

This is the full quickstart pair, 1:1 — copy the shape, not the prose. The
records (`prefix = "app"` → nested sections, camelCase fields, `@Default` for
optionals):

```java
@Configuration(prefix = "app")
public record AppConfig(ServerConfig server, DbConfig db) {}

public record ServerConfig(@Default("0") int port) {}

public record DbConfig(
        String url,
        String user,
        String password,
        @Default("4") int poolSize) {}
```

#[[
The `application.yml` that binds against them — section names match the prefix
and field names, `${VAR:default}` for environment overrides, `poolSize`
camelCase exactly as declared:

```yaml
app:               # @Configuration(prefix = "app")
  server:
    port: ${SERVER_PORT:8080}
  db:
    url: ${DB_URL:jdbc:h2:mem:quickstart;DB_CLOSE_DELAY=-1;MODE=PostgreSQL}
    user: ${DB_USER:sa}
    password: ${DB_PASSWORD:}
    poolSize: 4    # exact key — NOT pool-size / pool_size
```
]]#

Read it with `Tiko.create(ConfigSources.classpath("application.yml"))` and
inject `AppConfig` (or a nested record) as a constructor parameter.

**Packages & file name.** `@Configuration` / `@Key` / `@Default` live in
`io.tiko.annotations`; `ConfigSources` is `io.tiko.config.ConfigSources` (the
`tiko-config` module — add it as a dependency). The config file name is **your
choice** — whatever you pass to `ConfigSources.classpath(...)`; pick **one** name and
use it consistently (this skill uses `application.yml`). That is separate from each
module's own defaults, which merge from its jar's `META-INF/tiko/defaults.yaml` (e.g.
`tiko-kafka` ships `tiko.kafka.*` defaults there) — see the Kafka section below.

## Cookbook table

Every recipe = one factory class. Construction shape + lifecycle is enough.

| Need | Library | Recipe |
|---|---|---|
| Connection pool | HikariCP | `@Produces DataSource` returning `HikariDataSource` (AutoCloseable — no `@PreDestroy`). |
| Schema migrations | Flyway | `@EventHandler(ApplicationStartedEvent)` calling `Flyway.configure().dataSource(ds).load().migrate()`. |
| Typed query DSL | jOOQ | `@Produces DSLContext` via `DSL.using(ds, dialect)`. No lifecycle. |
| In-process cache | Caffeine | `@Produces Cache<K,V>` via `Caffeine.newBuilder()...build()`. Named for qualifier. |
| HTTP layer | Javalin | `@Produces Javalin` + `@PreDestroy app.stop()`. Routes registered in `Main`. |
| Templates | FreeMarker | `@Produces freemarker.template.Configuration`. No lifecycle. |
| SDK client | any | `@Produces ClientType`. Add `@PreDestroy` if not `AutoCloseable`. |
| Messaging (Kafka) | tiko-kafka | `@KafkaSource`/`@KafkaSink` bridges — **not** a `@Produces` recipe. Write the shape below, not a "void consumer". |

See [`docs/orchestrator-model.md` §3](https://github.com/tomas-samek/tiko-di/blob/main/docs/orchestrator-model.md) for
the full code snippets and lifecycle notes per recipe.

## Kafka transport: write this shape first

The obvious instinct — a `void` method that consumes a record and calls a
service — **fails the processor**. A `@KafkaSource` is a *bridge into the local
event bus*, not a consumer: it has three hard compile-time rules.

1. Enclosing class is `@Component(scope = Scope.SINGLETON)`.
2. The method is **non-void** — it returns the local event payload.
3. It carries a sibling **`@EventTrigger`** on the same method.

The return value is published on the local bus **by its type**; an ordinary
`@EventHandler` for that type does the work. This is the canonical inbound
shape (verbatim from `tiko-examples/08_kafka_order_warehouse`):

```java
@Component(scope = Scope.SINGLETON)
public class OrderKafkaConsumer {

    @KafkaSource(topic = "orders")
    @EventTrigger(eventName = "OrderPlaced")   // sibling trigger is required
    public OrderPlaced fromKafka(OrderPlaced payload) {
        return payload;                        // non-void: becomes the local event
    }
}

@Component(scope = Scope.SINGLETON)
public class WarehouseService {
    @EventHandler
    public void on(OrderPlaced event) { /* the actual work — dispatched by type */ }
}
```

Outbound is the mirror: a `@KafkaSink` is subscribed by the runtime to its
parameter type — when that event is published locally, the return value is
serialized to the topic. Same `SINGLETON` rule; **do not** also add
`@EventHandler` (that double-fires). `partitionKey` names an accessor on the
return type for the message key.

```java
@Component(scope = Scope.SINGLETON)
public class OrderKafkaPublisher {

    @KafkaSink(topic = "orders", partitionKey = "orderId")
    public OrderPlaced toKafka(OrderPlaced event) {
        return event;
    }
}
```

Broker config binds to `tiko.kafka.*` (exact-key, camelCase —
`bootstrapServers`, not `bootstrap-servers`). Full contract, configuration, and
the poison-record story: [`docs/cookbooks/kafka.md`](https://github.com/tomas-samek/tiko-di/blob/main/docs/cookbooks/kafka.md).

## Anti-pattern redirect table

When the user reaches for a Spring reflex, route them here instead. The
redirect is what tiko **does** ship, not a "we deliberately don't wrap X"
shrug — name the tiko-native primitive.

| Spring reflex | Tiko-native primitive |
|---|---|
| `@RestController` | `@Produces Javalin` + plain route methods |
| `@Transactional` | Explicit transaction demarcation (EVENT-scoped Connection or auto-commit per call) |
| `@TransactionalEventListener` | `eventBus.publish(...)` after the write completes |
| `@Scheduled` (+ ShedLock) | `@EventHandler` on a `Tick` event published by a small scheduler thread |
| `@Async` on arbitrary methods | `@EventHandler(async = true)` for event-shaped work; `CompletableFuture` / virtual threads for ad-hoc |
| `@Retryable` | Small utility `Retry.withRetries(n, backoff, () -> op)` — visible code, no AOP |
| `@Value` | Typed `@Configuration` records via `tiko-config` |
| Spring Actuator endpoints | Routes on your HTTP layer calling `container.get(HealthChecker.class)` |
| Spring Security filter chain | Javalin `before` handler + `Context` attributes (security is the bucket least settled — see model doc §6) |

Full anti-pattern prose with code samples is in
[`docs/orchestrator-model.md` §3–§6](https://github.com/tomas-samek/tiko-di/blob/main/docs/orchestrator-model.md).

## What this skill does not cover

- The mechanics of writing a `@Component` or `@EventHandler` from scratch.
  Those are in `CLAUDE.md` and the API javadoc.
- Choosing between libraries (Javalin vs Spark, HikariCP vs Agroal). This
  skill names a canonical choice; swap freely if the project already has one.
- Anything outside the orchestrator model. For framework internals, read
  the source.

## Need a recipe the cookbook doesn't have?

If the user's library isn't in the table above, read
[`../tiko-cookbook-extension/SKILL.md`](../tiko-cookbook-extension/SKILL.md) —
the procedural skill for adding a new recipe. **Don't invent a recipe.**
The load-bearing rule of that sibling skill is *ask, don't fabricate*;
asking the user which `@Produces` signature, which version, which
lifecycle is always cheaper than baking a wrong default into the
project's apparent conventions.
