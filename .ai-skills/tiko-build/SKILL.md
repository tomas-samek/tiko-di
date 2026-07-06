---
name: tiko-build
description: Use when scaffolding or extending a service built on Tiko DI. Decision tree + @Produces cookbook + anti-pattern redirects so agents reach for the tiko-native primitive instead of searching for a Spring equivalent.
---

# tiko-build

> Tiko orchestrates, it doesn't bundle — direct access, compile-time safe,
> nothing wrapped.

This file is the **operational distillation** of
[`docs/orchestrator-model.md`](../../docs/orchestrator-model.md). The long
doc is the source of truth for prose; this file is the shape an agent reads
to act. **When adding a recipe, update both** — the table here, and the
recipe section there.

## The rule

When a user says *"I need X"*, classify X into one of three buckets:

| Bucket | What it means | What to do |
|---|---|---|
| **Core** | container, scopes, event bus, compile-time wiring, lifecycle | Use a tiko primitive directly. |
| **Plug in** | any integration with an external system (HTTP, DB, cache, templating, scheduling, retry, observability, security, SDK clients) | Bring the library; expose it as a `@Produces` value; consume as a constructor parameter. |
| **Open** | extending the event model itself (new async modes, scheduling-as-event, retry-as-loop) | File an issue. Don't invent. |

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
[`tiko-examples/15_quickstart`](../../tiko-examples/15_quickstart).

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

See [`docs/orchestrator-model.md` §3](../../docs/orchestrator-model.md) for
the full code snippets and lifecycle notes per recipe.

## API signature sheet — exact imports and signatures

Transcribed from source. Import from this table — never from memory.

### Exact packages

| Type | Package |
|---|---|
| `@Component` `@Inject` `@Named` `@Pick` `@Produces` `@PostConstruct` `@PreDestroy` `@EventHandler` `@EventTrigger` `@EventTriggers` `@Configuration` `@Default` `@Key` `BackoffStrategy` | `io.tiko.annotations` |
| `@KafkaSource` `@KafkaSink` | `io.tiko.kafka.annotations` — **NOT** `io.tiko.annotations` |
| `Container` `EventBus` `EventCallback` `Subscription` `Scope` `Provider` `TransportBootstrap` `ErrorHandler` `ConfigSource` | `io.tiko` |
| `Tiko` `TikoOptions` `TikoDaemon` | `io.tiko.runtime` |
| `ConfigSources` | `io.tiko.config` |
| `KafkaTransport` `KafkaSerializer` `KafkaConfig` | `io.tiko.kafka` |
| `JsonKafkaSerializer` | `io.tiko.kafka.serializer` |
| `FakeKafkaBroker` `FakeKafkaTransport` | `io.tiko.kafka.test` |

**The rule:** a `cannot find symbol` on an import means a wrong package,
not a missing feature — check this table first, then `javap` the resolved
jar. Never conclude an annotation or class does not exist because one
import guess failed. Kafka types additionally require the `tiko-kafka`
dependency and the `tiko-kafka-processor` annotation-processor path —
both ship **commented out** in the scaffolded pom; enable them first.

### Signatures you will call

```java
// Bootstrap (io.tiko.runtime)
static Container Tiko.create()
static Container Tiko.create(TikoOptions options)
static TikoDaemon Tiko.daemon(TikoOptions options)
void TikoDaemon.awaitShutdown()

// Options (io.tiko.runtime) — all builder methods return Builder
static TikoOptions.Builder TikoOptions.builder()
Builder configSource(ConfigSource source)
Builder errorHandler(ErrorHandler handler)
<T> Builder override(Class<T> type, Supplier<? extends T> supplier)
<T extends TransportBootstrap> Builder replaceTransport(Class<T> transport, Function<T, TransportBootstrap> replacement)
TikoOptions build()

// Config sources (io.tiko.config.ConfigSources)
static ConfigSource classpath(String resourcePath)
static ConfigSource classpathAll(String resourcePath)
static ConfigSource file(Path path)
static ConfigSource fromMap(Map<String, Object> data)
static ConfigSource layered(ConfigSource... sources)

// Event bus (io.tiko.EventBus)
<T> void publish(T event)
<T> Subscription subscribe(Class<T> eventType, EventCallback<T> callback)

// Fake broker (io.tiko.kafka.test) — in-process test seam
void FakeKafkaBroker.produce(String topic, byte[] payload, String... headerKv)
List<ProducerRecord<String, byte[]>> FakeKafkaBroker.produced(String topic)
Optional<ProducerRecord<String, byte[]>> FakeKafkaBroker.findProduced(String topic, String headerKey, String headerValue)
static FakeKafkaTransport FakeKafkaTransport.over(KafkaTransport original, FakeKafkaBroker broker)

// JSON serializer (io.tiko.kafka.serializer)
byte[] JsonKafkaSerializer.serialize(Object value)
<T> T JsonKafkaSerializer.deserialize(byte[] bytes, Class<T> type)
```

### Annotation attributes (with defaults)

```java
@Component(Scope scope = Scope.PROTOTYPE, String name = "", String[] profiles = {},
           Class<?>[] expose = {}, boolean exposeSelf = true)
@Produces(Scope scope = Scope.PROTOTYPE, String name = "", String[] profiles = {})
@EventHandler(boolean async = false, Class<?> eventType = Object.class, String timeout = "",
              int retries = 0, String backoff = "", BackoffStrategy backoffStrategy = BackoffStrategy.FIXED)
@EventTrigger(String eventName = "", boolean async = false, boolean spread = false,
              Class<? extends EventTriggerGuard>[] guard = EventTriggerGuard.AlwaysAllow.class)
@Configuration(String prefix)            // required
@Default(String value)                   // required
@Key(String value)                       // required — overrides the YAML key for one record component
@Named(String value)                     // required
@Pick(Class<?> value)                    // required
@KafkaSource(String topic,               // required
             String consumerGroup = "", Class<? extends KafkaSerializer> serializer = KafkaSerializer.Default.class,
             CommitMode commitMode = CommitMode.PER_RECORD)
@KafkaSink(String topic,                 // required
           String partitionKey = "", Class<? extends KafkaSerializer> serializer = KafkaSerializer.Default.class)
```

`timeout` / `backoff` take ISO-8601 durations (`"PT5S"`); `timeout` and
`retries` require `async = true`.

### Config keys — the two rules and the `tiko.kafka.*` table

1. **Your `@Configuration` records:** YAML keys bind to record component
   names **exactly** — camelCase as declared (`poolSize`, never
   `pool-size`). No kebab-case or snake_case aliasing, by design.
2. **`@Key("...")` overrides that** for a single component. Modules use it
   for kebab-case public keys; `tiko-kafka`'s `KafkaConfig` does exactly
   that, so the real broker keys are:

| `tiko.kafka.*` key | shipped default |
|---|---|
| `bootstrap-servers` | `localhost:9092` |
| `consumer-group` | `tiko-app` |
| `serializer` | `json` |
| `auto-offset-reset` | `earliest` |
| `poll-timeout` | `PT0.5S` |
| `shutdown-timeout` | `PT5S` |
| `producer-properties` | `{}` |
| `consumer-properties` | `{}` |
| `poison-record-policy` | `SEEK` (`SKIP` opt-in) |

Write these keys kebab-case exactly as above (they are `@Key`-declared;
`serializer` is the one plain camelCase-free field name). A key that
matches neither a component name nor a `@Key` value fails validation at
`Tiko.create(...)` with a nearest-key suggestion.

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

Broker config binds to `tiko.kafka.*` with **kebab-case** keys
(`bootstrap-servers`, not `bootstrapServers`) — they are `@Key`-declared;
see the key table in the API signature sheet above. Full contract, configuration, and
the poison-record story: [`docs/cookbooks/kafka.md`](../../docs/cookbooks/kafka.md).

### Testing Kafka bridges: use the fake broker, never a real one in unit/IT scope

Do NOT try to disable the transport by deleting `META-INF/services` files, hiding the SPI
with classloader tricks, or hand-rebuilding `KafkaBootstrapSupport`. The supported seam is
one option + one helper:

```java
FakeKafkaBroker broker = new FakeKafkaBroker();
try (Container c = Tiko.create(TikoOptions.builder()
        .configSource(ConfigSources.classpath("application.yaml"))
        .replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker))
        .build())) {
    broker.produce("orders", new JsonKafkaSerializer().serialize(event)); // drive @KafkaSource
    c.getEventBus().publish(outboundEvent);                               // drive @KafkaSink
    assertThat(broker.produced("notifications")).hasSize(1);
}
```

- `configSource(...)` is still required if the app declares any `@Configuration`
  (including `tiko-kafka`'s own) — set it exactly like the app's `Main` does, or
  `Tiko.create` fails config validation before the transport substitution runs.

Inbound consumption is asynchronous (background poll thread): assert with Awaitility
(`await().atMost(...)`), never `Thread.sleep`. Reference ITs:
`tiko-examples/08_kafka_order_warehouse/*/src/test/java/.../FakeBroker*IT.java`.

**If your module builds a shaded jar:** failsafe defaults to running ITs against the
packaged fat jar, which duplicates bundled dependency classes on the classpath and fails
container boot with `duplicate @Configuration prefix 'tiko.kafka'`. Add
`<classesDirectory>${project.build.outputDirectory}</classesDirectory>` to the
`maven-failsafe-plugin` configuration — see the poms under
`tiko-examples/08_kafka_order_warehouse/*/pom.xml` for the exact block.

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
[`docs/orchestrator-model.md` §3–§6](../../docs/orchestrator-model.md).

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
