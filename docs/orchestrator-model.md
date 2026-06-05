# The orchestrator model

> Tiko orchestrates, it doesn't bundle — direct access, compile-time safe,
> nothing wrapped.

This document is the **tiko-build skill** in long form. An agent or human
walking through it should leave with a working mental model: where new code
goes, which library plugs in where, and which Spring reflexes to replace
with the tiko-native primitive.

Vocabulary follows [`orchestrator-vocabulary.md`](./orchestrator-vocabulary.md).
Recipes are demonstrated end-to-end in
[`tiko-examples/15_quickstart`](../tiko-examples/15_quickstart).

---

## 1. The three buckets — decision tree

Every concern in a new tiko-built service falls into exactly one bucket.
Classify first, then write code.

```
"I need X — how does tiko do it?"
│
├─ X is the container, scopes, event bus, compile-time wiring, lifecycle hooks
│  → CORE. Use a tiko primitive directly. See §2.
│
├─ X is an integration with an external system
│  (HTTP, DB, cache, templating, scheduling, retry, observability, security,
│   any SDK client)
│  → PLUG IN. Bring the library, expose it via @Produces. See §3 cookbook.
│
├─ X extends the event model itself
│  (new async modes, scheduling-as-tick-event, retry-as-event-loop)
│  → OPEN design question. File an issue; don't invent.
│
└─ default if uncertain → PLUG IN via @Produces.
```

**Do not** search for "tiko's equivalent of Spring's X." The plug-in bucket
is the answer for most X. Name the library you want; expose it as a
`@Produces` value; consume it as a constructor parameter.

---

## 2. Scaffolding a new service

The reference shape, mirrored by
[`tiko-examples/15_quickstart`](../tiko-examples/15_quickstart):

```
my-service/
├── pom.xml                            (tiko-api, tiko-processor, tiko-runtime, tiko-config + your libs)
├── src/main/java/com/example/svc/
│   ├── AppConfig.java                 (@Configuration root record)
│   ├── DataSourceFactory.java         (@Produces DataSource)
│   ├── SchemaInitializer.java         (@EventHandler(ApplicationStartedEvent))
│   ├── ThingRepository.java           (raw library API behind an interface)
│   ├── ThingCreated.java              (domain event record)
│   ├── ThingAuditor.java              (@EventHandler(ThingCreated))
│   ├── JavalinFactory.java            (@Produces Javalin)
│   ├── ThingRoutes.java               (plain route methods)
│   └── Main.java                      (Tiko.create + register routes + start)
└── src/main/resources/
    ├── application.yml                (typed-config binding)
    └── schema.sql                     (or Flyway migrations)
```

Conventions:

- One Java package per service; sub-packages only when the file count makes
  the root unreadable.
- `@Produces` factories live next to the code that consumes them. There is
  no `infra` ceremony unless the module is big enough that scrolling hurts.
- `Main.java` calls `Tiko.create(ConfigSources.classpath("application.yml"))`,
  resolves the produced server, registers routes, calls `.start(port)`.
  Shutdown hook calls `container.shutdown()`.

---

## 3. The `@Produces` cookbook

Every recipe is one factory class. Construction shape + lifecycle is all the
information you need to plug in any third-party library. The pattern repeats
across HikariCP, jOOQ, Caffeine, Javalin, FreeMarker, and any SDK client you
add later.

### 3.1 HikariCP `DataSource`

**When:** you need a connection pool to a relational database.

```java
@Component(scope = Scope.SINGLETON)
public class DataSourceFactory {
    private final AppConfig config;

    @Inject
    public DataSourceFactory(AppConfig config) { this.config = config; }

    @Produces(scope = Scope.SINGLETON)
    public DataSource dataSource() {
        var hc = new HikariConfig();
        hc.setJdbcUrl(config.db().url());
        hc.setUsername(config.db().user());
        hc.setPassword(config.db().password());
        hc.setMaximumPoolSize(config.db().poolSize());
        return new HikariDataSource(hc);
    }
}
```

`HikariDataSource` implements `AutoCloseable`, so the container drains the
pool at shutdown automatically. No `@PreDestroy` needed.

Reference: [`DataSourceFactory.java`](../tiko-examples/15_quickstart/src/main/java/io/tiko/examples/quickstart/DataSourceFactory.java).

#### Redirect: `@Transactional` → explicit transaction demarcation

Tiko has no AOP layer, so there is no annotation that wraps a method in a
transaction. Pick the shape that matches the unit-of-work boundary:

- **One transaction per HTTP request / unit of work.** Open an EVENT-scoped
  `Connection` provider, route every repository through it, and commit /
  rollback at the scope boundary. See
  [`tiko-examples/10_persistence_jdbc/.../TransactionalScope.java`](../tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/infra/TransactionalScope.java)
  for the canonical pattern.
- **Auto-commit per repository call.** Each repository method opens a
  connection from the pool and closes it. Fine for single-statement work and
  the simplest reference shape — see
  [`NoteRepository.java`](../tiko-examples/15_quickstart/src/main/java/io/tiko/examples/quickstart/NoteRepository.java).

Hiding commit / rollback under an annotation is exactly the kind of
invisible control flow tiko deliberately doesn't do. Transaction boundaries
are visible code.

### 3.2 Flyway migrations on startup

**When:** you need declarative schema migrations applied before the service
serves traffic.

```java
@Component(scope = Scope.SINGLETON)
public class FlywayMigrator {
    private final DataSource ds;

    @Inject
    public FlywayMigrator(DataSource ds) { this.ds = ds; }

    @EventHandler
    public void onApplicationStarted(ApplicationStartedEvent ev) {
        Flyway.configure().dataSource(ds).load().migrate();
    }
}
```

`ApplicationStartedEvent` fires synchronously during `Tiko.create(...)` after
all `SINGLETON`s are wired and before the call returns. Migrations therefore
complete before `Main` registers any HTTP route.

The same shape works for ad-hoc DDL — see
[`SchemaInitializer.java`](../tiko-examples/15_quickstart/src/main/java/io/tiko/examples/quickstart/SchemaInitializer.java),
which runs `schema.sql` instead of calling Flyway.

### 3.3 jOOQ `DSLContext`

**When:** you want jOOQ's typed query DSL on top of an existing
`DataSource`.

```java
@Component(scope = Scope.SINGLETON)
public class DslContextFactory {
    private final DataSource ds;

    @Inject
    public DslContextFactory(DataSource ds) { this.ds = ds; }

    @Produces(scope = Scope.SINGLETON)
    public DSLContext dsl() {
        return DSL.using(ds, SQLDialect.POSTGRES);
    }
}
```

Repositories take `DSLContext` directly. No lifecycle hook needed — the
context holds no resources of its own.

### 3.4 Caffeine cache

**When:** you want an in-process cache for a single deployment.

```java
@Component(scope = Scope.SINGLETON)
public class UserCacheFactory {
    @Produces(scope = Scope.SINGLETON, name = "users")
    public Cache<UUID, User> userCache() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .build();
    }
}
```

Consume with `@Inject @Named("users") Cache<UUID, User> cache`. If you want
hit/miss metrics, hold the produced `Cache` and expose them on your health
endpoint (see §6).

### 3.5 Javalin HTTP server

**When:** you need an HTTP layer.

```java
@Component(scope = Scope.SINGLETON)
public class JavalinFactory {
    private Javalin app;

    @Produces(scope = Scope.SINGLETON)
    public Javalin javalin() {
        this.app = Javalin.create();
        return app;
    }

    @PreDestroy
    public void shutdown() {
        if (app != null) app.stop();
    }
}
```

Routes are registered in `Main` after `Tiko.create(...)` returns — see
[`Main.java`](../tiko-examples/15_quickstart/src/main/java/io/tiko/examples/quickstart/Main.java).
Route handlers go in a plain class
([`NoteRoutes.java`](../tiko-examples/15_quickstart/src/main/java/io/tiko/examples/quickstart/NoteRoutes.java))
that is **not** a `@Component` — it depends on `EventBus`, which tiko
exposes off the `Container` rather than via DI.

#### Redirect: `@RestController` → register the HTTP layer via `@Produces`

There is no annotation-driven dispatch. Routes are plain methods you wire to
URL patterns in `Main`. No reflection, no scanning, no surprise endpoints.

#### Redirect: Spring Actuator endpoints → routes on your HTTP layer

Tiko ships no `/actuator/*`. Write the route you want and have it call a
plain `HealthChecker` `@Component`:

```java
app.get("/health", ctx -> ctx.json(container.get(HealthChecker.class).snapshot()));
```

Visible, debuggable, and the response shape is yours.

### 3.6 FreeMarker template engine

**When:** you want server-side template rendering.

```java
@Component(scope = Scope.SINGLETON)
public class FreeMarkerFactory {
    @Produces(scope = Scope.SINGLETON)
    public freemarker.template.Configuration freemarker() {
        var cfg = new freemarker.template.Configuration(
                freemarker.template.Configuration.VERSION_2_3_32);
        cfg.setClassForTemplateLoading(getClass(), "/templates");
        cfg.setDefaultEncoding("UTF-8");
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        return cfg;
    }
}
```

Inject `freemarker.template.Configuration` into a `Renderer` `@Component`
that wraps `cfg.getTemplate(name).process(model, writer)`.

### 3.7 Generic SDK client

**When:** any third-party library exposes a "client" object that holds a
connection or thread pool.

```java
@Component(scope = Scope.SINGLETON)
public class S3ClientFactory {
    private final AppConfig config;

    @Inject
    public S3ClientFactory(AppConfig config) { this.config = config; }

    @Produces(scope = Scope.SINGLETON)
    public S3Client s3() {
        return S3Client.builder()
                .region(Region.of(config.aws().region()))
                .build();
    }
}
```

If the client is `AutoCloseable`, the container closes it at shutdown. If
not, add `@PreDestroy` on the factory and stop / close it there.

### 3.8 Kafka

Kafka is the one external system that lives in a tiko-native module
(`tiko-kafka`) because it adds new topology primitives — `@KafkaSource` and
`@KafkaSink` with compile-time validation — not because tiko wraps the
client. For Kafka work, follow the `tiko-kafka` documentation and the
`08_kafka_order_warehouse` example. The rest of this cookbook does not
apply.

---

## 4. Event-driven workflow recipes

The orchestrator model puts business reactions on the event bus instead of
behind hidden annotations. The bus is `EventBus`; subscribers are
`@EventHandler` methods. This is where the model diverges most visibly from
the annotation-driven equivalent.

### 4.1 React after a write

```java
// In NoteRoutes (or any post-write call site):
repo.insert(note);
eventBus.publish(new NoteCreated(note.id(), note.createdAt()));
```

```java
@Component(scope = Scope.SINGLETON)
public class NoteAuditor {
    @EventHandler
    public void onNoteCreated(NoteCreated event) { /* react */ }
}
```

Reference:
[`NoteAuditor.java`](../tiko-examples/15_quickstart/src/main/java/io/tiko/examples/quickstart/NoteAuditor.java).

#### Redirect: `@TransactionalEventListener` → publish after the write

Publish the event explicitly after the write completes. If the transaction
rolled back, control never reaches `publish(...)` — so the subscriber never
fires. There is no implicit phase coupling between transaction lifecycle and
event delivery, because there is no AOP layer to couple them.

This is the recipe where the orchestrator model is visibly cleaner, not
merely different: the temporal contract is straight-line code instead of an
annotation-driven phase machine.

### 4.2 Scheduled work

```java
@Component(scope = Scope.SINGLETON)
public class TickScheduler {
    private final ScheduledExecutorService exec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "tick-scheduler");
                t.setDaemon(true);
                return t;
            });
    private final EventBus bus;

    @Inject
    public TickScheduler(Container container) { this.bus = container.getEventBus(); }

    @PostConstruct
    public void start() {
        exec.scheduleAtFixedRate(
                () -> bus.publish(new MinuteTick(Instant.now())),
                0, 1, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void stop() { exec.shutdownNow(); }
}

@Component(scope = Scope.SINGLETON)
public class CleanupJob {
    @EventHandler
    public void onMinuteTick(MinuteTick t) { /* periodic work */ }
}
```

#### Redirect: `@Scheduled` (and ShedLock) → tick events on the bus

Run a tiny scheduler thread that publishes a `Tick` event; subscribers are
plain `@EventHandler`s. For distributed locking, plug in your own
coordination (Redis, your own DB advisory lock, etc.) — tiko deliberately
doesn't ship a coordination primitive. The model stays event-driven
end-to-end and the lock is visible code, not an annotation parameter.

### 4.3 Ad-hoc async work

```java
@Component(scope = Scope.SINGLETON)
public class SlowAuditor {
    @EventHandler(async = true)
    public void onNoteCreated(NoteCreated ev) { /* runs on the async executor */ }
}
```

For work that isn't event-shaped — fan-out, fire-and-forget — use a virtual
thread or `CompletableFuture` directly. There is no reason to invent an
event identity just to make something asynchronous.

#### Redirect: `@Async` on arbitrary methods → async `@EventHandler` or virtual threads

If the work is event-shaped (a thing happened, react), use
`@EventHandler(async = true)`. If it is ad-hoc fan-out, use
`Thread.ofVirtual().start(...)` or `CompletableFuture.runAsync(...)`. There
is no AOP layer to make a non-event method asynchronous behind a decorator;
the asynchrony is in the call site.

### 4.4 Retry

```java
public final class Retry {
    public static <T> T withRetries(int attempts, Duration backoff, Supplier<T> op) {
        RuntimeException last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return op.get();
            } catch (RuntimeException e) {
                last = e;
                try { Thread.sleep(backoff.toMillis()); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
        throw last;
    }
}

// Call site:
Retry.withRetries(3, Duration.ofMillis(200), () -> client.fetch(id));
```

For event-shaped retries, re-publish the event with an attempt counter in
the payload and let an `@EventHandler` decide whether to keep retrying.

#### Redirect: `@Retryable` → small utility wrapper

The retry policy is twelve lines of visible code, not an annotation
parameter. No proxy generation, no surprise stack frames in the trace, and
the policy can change per call site without redeploying the decorated
class.

---

## 5. Configuration

### 5.1 Typed `@Configuration` records

```java
@Configuration(prefix = "app")
public record AppConfig(ServerConfig server, DbConfig db) {}

public record ServerConfig(@Default("8080") int port) {}
public record DbConfig(String url, String user, String password,
                       @Default("4") int poolSize) {}
```

```yaml
# application.yml
app:
  server:
    port: ${SERVER_PORT:8080}
  db:
    url: ${DB_URL:jdbc:h2:mem:svc}
    user: ${DB_USER:sa}
    password: ${DB_PASSWORD:}
```

Bind by injecting `AppConfig`. Nested records are bound recursively. Keys
without a value or a `@Default` are caught at startup by `tiko-config`
validation, not silently null at runtime.

Reference:
[`AppConfig.java`](../tiko-examples/15_quickstart/src/main/java/io/tiko/examples/quickstart/AppConfig.java).

#### Redirect: `@Value` magic binding → typed `@Configuration` records

Configuration is a value with a compile-time-checked shape. There is no
late-bound string-to-field lookup and no SpEL. If the YAML doesn't supply a
required key, the container fails to start with the field name and source
location in the message.

---

## 6. Security — an open design question

Security is the bucket where the model is least settled. We don't ship a
filter chain, a `SecurityContext`, or a session manager — those would
bundle a position tiko hasn't yet earned by observation. **And no
battle-tested standalone library currently composes cleanly with tiko's
EVENT scope.** Expect rough edges around per-request principal handoff
until the scope-handoff story stabilizes.

### Pragmatic starting point

```java
// In Main, before app.start(port):
app.before(ctx -> {
    var token = ctx.header("Authorization");
    if (token == null || !validate(token)) {
        ctx.status(401).result("unauthorized");
        ctx.skipRemainingHandlers();
        return;
    }
    ctx.attribute("principal", decode(token));
});
```

Route handlers read `ctx.attribute("principal")`. This sidesteps the
scope-handoff rough edge by keeping the principal in Javalin's per-request
context rather than an EVENT-scoped bean.

#### Redirect: Spring Security filter chain → Javalin `before` handler + `Context` attributes

Start with a stateless check in a `before` handler. Reach for a third-party
auth library when the requirements actually demand it; expect to write a
small adapter into the `before` shape rather than dropping in a `SecurityConfig`.

---

## 7. When tiko doesn't have a tiko-native answer

If your concern doesn't fit Core, Plug in, or Open, that's a signal: open
an issue describing the use case. The model's small surface is intentional
and growing it requires real evidence of repeated need across users — not
a single feature-shape request from one codebase.

Do not invent an `@TikoTransactional` or a `@TikoScheduled` to fill the
shape. The point of the orchestrator model is that the bundled-features
shape isn't the one we're building toward.
