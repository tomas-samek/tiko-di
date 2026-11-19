# HTTP service with Tiko — a server the container owns

> Runnable examples:
> - [`tiko-examples/15_quickstart/`](../../tiko-examples/15_quickstart/): the smallest whole service.
> - [`tiko-examples/09_http_javalin/`](../../tiko-examples/09_http_javalin/): draining async work at shutdown.
> - [`tiko-examples/17_transactional_outbox/`](../../tiko-examples/17_transactional_outbox/): HTTP → database → Kafka.
>
> All three run on Javalin 7.2.3 (Jetty 12.1). The Kotlin version of the outbox,
> [`kotlin-transactional-outbox`](../../tiko-examples-gradle/kotlin-transactional-outbox/), has the same shape.

## Why Tiko doesn't ship an HTTP module

Tiko's scope is **compile-time DI + event orchestration**. An HTTP server is a library you
bring, and Javalin, Jetty or the JDK's `HttpServer` already route requests. A Tiko service needs
three things from Tiko for that:

- a unit of work around each request;
- the server started and stopped with the container;
- the components a route calls.

Each of them is a constructor parameter or an annotation on code you write, so the shape stays
in your code, where you can read and change it.

## What you'll learn

1. How a route runs each request in **one unit of work** through the injected `UnitOfWork`.
2. How the **server is a component** the container starts at boot and stops at shutdown.
3. What a **port already in use** does, and what happens to a **request in flight** at shutdown.
4. How a **resource that must run from boot** starts with the server.
5. What `main` looks like, what changes in a **multi-module** application, and how to **test** the
   service.

## Dependencies

```xml
<dependency><groupId>io.github.tomas-samek</groupId><artifactId>tiko-api</artifactId></dependency>
<dependency><groupId>io.github.tomas-samek</groupId><artifactId>tiko-runtime</artifactId></dependency>
<dependency><groupId>io.github.tomas-samek</groupId><artifactId>tiko-config</artifactId></dependency>
<dependency><groupId>io.javalin</groupId><artifactId>javalin</artifactId><version>7.2.3</version></dependency>
```

The Tiko versions come from `tiko-bom`. Add `tiko-processor` on the annotation-processor path,
as for any Tiko module.

## One unit of work per request

A unit of work is what `Scope.EVENT` components live for. A route group takes `UnitOfWork`, a
built-in dependency like `EventBus`, and wraps each handler so that it runs in a unit of its own.
Javalin 7 has no handler that wraps a request, so the route group applies a small helper of its
own when it registers its paths:

```java
final class RequestUnits {
    static Handler inUnit(UnitOfWork unitOfWork, Handler handler) {
        return ctx -> unitOfWork.runInEventScope(() -> {
            try {
                handler.handle(ctx);
            } catch (RuntimeException failure) {
                throw failure; // Javalin's own responses (BadRequestResponse, ...) pass as they are.
            } catch (Exception failure) {
                throw new IllegalStateException("Request failed", failure);
            }
        });
    }
}

@Component(scope = Scope.SINGLETON)
public class NoteRoutes {
    private final UnitOfWork unitOfWork;
    private final NoteRepository repo;
    private final EventBus eventBus;
    private final RequestId requestId;   // EVENT-scoped: a proxy that resolves the current request's

    @Inject
    public NoteRoutes(UnitOfWork unitOfWork, NoteRepository repo, EventBus eventBus, RequestId requestId) { /* ... */ }

    public void register(RoutesConfig routes) {
        routes.post("/notes", inUnit(unitOfWork, this::handleCreate));
        routes.get("/notes/{id}", inUnit(unitOfWork, this::handleGet));
    }

    public void handleCreate(Context ctx) {
        ctx.header("X-Request-Id", requestId.value());
        // insert, eventBus.publish(new NoteCreated(...)), respond
    }
}
```

- **Everything in the request shares the unit**, from body parsing to response serialization.
  So does every synchronous `@EventHandler` of an event the route publishes. In the quickstart
  the auditor reads the same `RequestId` the route read, and two requests get two different ids:
  `QuickstartIT.twoRequestsRunInTwoUnits`.
- **An async handler runs in a unit of its own**, as everywhere in Tiko. It reads what it needs
  from the event.
- **`EventStartedEvent` / `EventEndingEvent` fire for every request**, including a `404`.
  `09_http_javalin`'s `RequestTimer` uses them for per-request timing.
- **Calling `runInEventScope` where a unit is already open throws `IllegalStateException`.**
  Units of work do not nest, so a handler does not open a second one.

The container is a `UnitOfWork` too. Code that already holds the container, such as a test, can
pass it wherever the interface is expected. Components take the interface.

## The server is a component

The server is a SINGLETON `@Component`. It takes the route groups and its settings, binds its port
in `@PostConstruct`, and stops on `ApplicationEndingEvent`:

```java
@Component(scope = Scope.SINGLETON)
public class HttpServer {
    private static final long STOP_TIMEOUT_MS = 5_000;

    private final ServerConfig config;
    private final NoteRoutes notes;
    private volatile Javalin app;

    @Inject
    public HttpServer(AppConfig config, NoteRoutes notes) {
        this.config = config.server();
        this.notes = notes;
    }

    @PostConstruct
    public void start() {
        app = Javalin.create(cfg -> {
                    cfg.jetty.modifyServer(server -> server.setStopTimeout(STOP_TIMEOUT_MS));
                    notes.register(cfg.routes);
                })
                .start(config.port());
    }

    @EventHandler
    public void onApplicationEnding(ApplicationEndingEvent event) {
        stop();
    }

    @PreDestroy
    public void stop() {
        Javalin running = app;
        app = null;
        if (running != null) running.stop();
    }

    public int port() {
        return app.port();
    }
}
```

Nothing produces `Javalin` and nothing resolves it. A server is where the application's requests
come in, not a value other components take, so it has no `@Produces` method. A second route
group is one more constructor parameter and one more `register(cfg.routes)` call.

## When it starts, and a port already in use

A single-module container constructs every SINGLETON `@Component` when it starts, and with it
everything those constructors take. So the server binds its port inside `Tiko.daemon(...)` or
`Tiko.create(...)`, before either returns.

- **A port already in use fails boot.** `Javalin.start` throws `JavalinBindException`, and
  `Tiko.daemon(...)` throws it on. The container shuts down what it had already started. With
  `main` as below, the process exits with the error.
  - **No thread the container started survives.** `onThreadsOutlivingClose` is not called, and
    the produced connection pool is closed: `TakenPortIT` in the quickstart.
  - **Javalin 7.2.3 leaves one thread of its own.** With every instance it starts a thread that
    sleeps 5 s, then logs "you never started it" when the server did not start, and ends. A
    failed process therefore exits up to 5 s after the error.
- **Start in `@PostConstruct`, not on `ApplicationStartedEvent`.** The event bus isolates a
  handler that throws, so a server started from that event would fail to bind, log it, and leave
  the service running without HTTP.
- **The server takes requests while the container is still starting.** It is up from
  `@PostConstruct`, before `ApplicationStartedEvent`. Anything a route needs is a constructor
  parameter of the route group or the server, so it is built first. For the database schema,
  see [below](#a-resource-that-runs-from-boot).

## When it stops

`close()`, and the JVM hook a `Tiko.daemon(...)` installs for `Ctrl+C` / `SIGTERM`, publish
`ApplicationEndingEvent` first. Then the container waits for async handlers, up to
`tiko.shutdownTimeout`, and then runs `@PreDestroy`. Stopping the server on the event means no new
request opens a unit while async work drains: `09_http_javalin`'s `HttpAsyncDrainTest` and
`ShutdownSequenceTest`.

After a failed boot there is no `ApplicationEndingEvent`. `@PreDestroy` then stops a server that
had already started, for example when a SINGLETON built after it fails. `stop()` runs once,
whichever comes first.

**A request in flight when the server stops.** Measured on Javalin 7.2.3:

| `stop()` with | the handler | the client | an idle keep-alive connection |
|---|---|---|---|
| Javalin's defaults | runs to its end | gets no response: the connection is closed | no delay |
| `server.setStopTimeout(5_000)` (above) | runs to its end | gets its response | adds about 1 s to `stop()` |

`stop()` waits for the handler either way. The stop timeout is what lets Jetty write the
response before it closes the connection, which is why the examples set it.

## A resource that runs from boot

A produced SINGLETON is built on first use. A resource that must run from boot, such as an
outbox relay or a scheduler, is therefore a constructor parameter of the server, or of another
SINGLETON `@Component`. The outbox orders service does this:

```java
@Inject
public OrdersHttpServer(UnitOfWork unitOfWork, OrderStore orders, Settings settings, OutboxRelay relay) {
    // The relay is taken only so that it runs from boot: it relays what the routes commit.
}
```

The relay needs the outbox table, and so do the routes. **The database producer creates the
schema before it returns**, so everything that takes the `DataSource` sees the tables, and no
ordering is left to arrange:

```java
@Produces(scope = Scope.SINGLETON)
public DataSource database(Settings settings) throws SQLException {
    var database = Database.open(settings.dbUrl());
    OrderStore.createSchema(database);
    return database;
}
```

Flyway goes in the same place: `Flyway.configure().dataSource(ds).load().migrate()` before the
producer returns the pool. A failed migration then fails boot. A `DataSource` producer that
returns an `AutoCloseable` pool, such as `HikariDataSource`, gets the pool closed at shutdown.

## `main`

```java
public static void main(String[] args) {
    Tiko.daemon(ConfigSources.classpath("application.yml")).awaitShutdown();
}
```

`Tiko.daemon(...)` builds the container, starts the server and the transports, and installs the
JVM hook that closes the container on `Ctrl+C` / `SIGTERM`. `awaitShutdown()` keeps `main` until
then. Nothing resolves the server, and there is no shutdown hook of your own and no
`try`/`finally` around a start.

## In a multi-module application

A container of several modules, each running the processor, constructs no SINGLETON when it
starts; see
[When singletons are constructed](../di-and-scopes.md#when-singletons-are-constructed). There
the server starts when `main` resolves it. A failure then comes from that call, after the daemon
is up, so `main` stops the daemon itself:

```java
var daemon = Tiko.daemon(ConfigSources.classpath("application.yml"));
try {
    daemon.container().get(HttpServer.class);
} catch (RuntimeException failure) {
    daemon.stop();
    throw failure;
}
daemon.awaitShutdown();
```

## Testing

A test boots the container as `main` does and reads the port from the server. Port `0` lets the
OS pick a free one:

```java
container = Tiko.create(TikoOptions.builder()
        .configSource(ConfigSources.layered(
                ConfigSources.classpath("application.yml"),
                ConfigSources.fromMap(Map.of("app", Map.of("server", Map.of("port", 0))))))
        .build());
port = container.get(HttpServer.class).port();
```

- **Close the test's `HttpClient` before the container.** Its idle keep-alive connection would
  hold the server's stop for about a second.
- **A test that a taken port fails boot** holds a `ServerSocket`, boots on its port, and checks
  two things: `onThreadsOutlivingClose` reported nothing, and no thread the boot started is still
  alive after Javalin's 5 s reminder. See `TakenPortIT`.

## The HTTP → database → Kafka reference

[`tiko-examples/17_transactional_outbox`](../../tiko-examples/17_transactional_outbox) follows
this shape end to end. Its orders service:

- accepts an order over HTTP and commits it, with an outbox row, in one database transaction,
  inside the request's unit;
- runs the relay from boot, which sends the outbox to Kafka;
- receives the warehouse's decision through a `@KafkaSource` bridge.

Its end-to-end test runs both services as processes against a real broker. The
[persistence cookbook](persistence.md) covers a transaction bound to the unit of work, and the
[Kafka cookbook](kafka.md) covers the bridges.
