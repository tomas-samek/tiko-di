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

## Reference chunks — read on demand, not upfront

The depth lives in `reference/`. Open a chunk when its trigger fires; do
not read them all upfront.

| file | read when |
|---|---|
| [`reference/api-signatures.md`](reference/api-signatures.md) | writing any import, or unsure of a signature / annotation attribute / config key |
| [`reference/kafka.md`](reference/kafka.md) | consuming or producing Kafka, or writing the Kafka integration test |
| [`reference/config.md`](reference/config.md) | declaring `@Configuration` records or writing override YAML |
| [`reference/events.md`](reference/events.md) | publishing/subscribing events, lifecycle hooks, or a headless/daemon main |
| [`reference/kotlin.md`](reference/kotlin.md) | the service is written in Kotlin, or built with Gradle + KSP |

A `cannot find symbol` on an import means a wrong package, not a missing
feature — `reference/api-signatures.md` first, then `javap`.

## Kotlin (Gradle + KSP)

Tiko runs on Kotlin through KSP2, from the same processor jars Java uses. Gradle is the
supported build. Setup:

```kotlin
plugins {
    kotlin("jvm") version "2.4.21"
    id("com.google.devtools.ksp") version "2.3.13"   // KSP2 floor
}
dependencies {
    implementation("io.github.tomas-samek:tiko-api:<version>")
    implementation("io.github.tomas-samek:tiko-runtime:<version>")
    ksp("io.github.tomas-samek:tiko-processor:<version>")
}
```

- A construct that maps 1:1 onto Java is supported: final classes, `internal`, `@param:`
  use-site targets, default parameter values (never "optional" — all primary-constructor
  parameters are injected).
- New DI semantics are rejected with `KOTLIN_UNSUPPORTED`: top-level, `object` or
  `companion object` hosts for `@Produces` / `@EventHandler`, `object` components,
  extension functions, `@get:` / `@set:` DI annotations, `suspend` handlers.
- Nullability must match exactly. `T?` is legal only where the provider is a `@Produces`
  returning `T?`; it never means "optional" (`NULLABILITY_MISMATCH` otherwise).
- `@TikoTest` / `@TestComponent` work on KSP; add `kspTest(tiko-processor)` and `testImplementation(tiko-test)`.

The full table is in [`reference/kotlin.md`](reference/kotlin.md).

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
│   ├── <Thing>Routes.java             # @Component: register(RoutesConfig) + handler methods
│   └── Main.java                      # Tiko.create + start
├── src/main/resources/
│   ├── application.yml                # typed-config binding
│   └── schema.sql                     # if applicable
└── src/test/java/...
```

Bootstrap pattern (`Main.java`) — routes are already registered by the
`@Produces Javalin` factory, so `Main` only starts the server:
```java
Container container = Tiko.create(ConfigSources.classpath("application.yml"));
Runtime.getRuntime().addShutdownHook(new Thread(container::shutdown));
container.get(Javalin.class).start(container.get(AppConfig.class).server().port());
```

HTTP routes (Javalin 7 accepts them only inside `Javalin.create(config -> ...)`):
each route group is a `@Component` with a `register(RoutesConfig)` method, and
the factory takes it as a parameter.
```java
@Component(scope = Scope.SINGLETON)
public class ThingRoutes {
    @Inject
    public ThingRoutes(ThingRepository repo, EventBus eventBus) { /* store */ }

    public void register(RoutesConfig routes) {
        routes.post("/things", this::handleCreate);
    }

    public void handleCreate(Context ctx) { /* ... */ }
}

@Component(scope = Scope.SINGLETON)
public class JavalinFactory {
    private Javalin app;

    @Produces(scope = Scope.SINGLETON)
    public Javalin javalin(ThingRoutes things) {
        this.app = Javalin.create(cfg -> things.register(cfg.routes));
        return app;
    }

    @PreDestroy
    public void shutdown() { if (app != null) app.stop(); }
}
```

Reference shape:
[`tiko-examples/15_quickstart`](../../tiko-examples/15_quickstart).

`<Library>Factory.java` is a `@Produces` method on a `@Component` class:

```java
@Component(scope = Scope.SINGLETON)
public class CacheConfig {
    @Produces(scope = Scope.SINGLETON, name = "userCache")
    public Cache<String, User> userCache() {
        return Caffeine.newBuilder().maximumSize(10_000).build();
    }
}
```

## Cookbook table

Every recipe = one factory class. Construction shape + lifecycle is enough.

| Need | Library | Recipe |
|---|---|---|
| Connection pool | HikariCP | `@Produces DataSource` returning `HikariDataSource` (AutoCloseable — no `@PreDestroy`). |
| Schema migrations | Flyway | `@EventHandler(ApplicationStartedEvent)` calling `Flyway.configure().dataSource(ds).load().migrate()`. |
| Typed query DSL | jOOQ | `@Produces DSLContext` via `DSL.using(ds, dialect)`. No lifecycle. |
| In-process cache | Caffeine | `@Produces Cache<K,V>` via `Caffeine.newBuilder()...build()`. Named for qualifier. |
| HTTP layer | Javalin | `@Produces Javalin` taking each route-group `@Component` as a parameter: `Javalin.create(cfg -> things.register(cfg.routes))` + `@PreDestroy app.stop()`. |
| Templates | FreeMarker | `@Produces freemarker.template.Configuration`. No lifecycle. |
| SDK client | any | `@Produces ClientType`. Add `@PreDestroy` if not `AutoCloseable`. |
| Messaging (Kafka) | tiko-kafka | `@KafkaSource`/`@KafkaSink` bridges — **not** a `@Produces` recipe. Write the shape in [`reference/kafka.md`](reference/kafka.md), not a "void consumer". |

See [`docs/orchestrator-model.md` §3](../../docs/orchestrator-model.md) for
the full code snippets and lifecycle notes per recipe.

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

If the user's library isn't in the table above, ask the user which
`@Produces` signature, which version, which lifecycle — don't invent one.
To contribute the recipe upstream, see [the cookbook-extension skill](../../.ai-skills/tiko-cookbook-extension/SKILL.md).
