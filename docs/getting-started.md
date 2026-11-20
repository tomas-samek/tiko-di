# Your first service

This page builds a small HTTP service on Tiko, step by step:
- a JSON API over an H2 database;
- one transaction per request;
- an event handler that reacts to every write;
- a test that runs it all over real HTTP.

The result is [`tiko-examples/15_quickstart`](../tiko-examples/15_quickstart). Each step names the
file it adds there, so you can compare as you go.

Prefer to start from the finished service? Copy `tiko-examples/15_quickstart` out of a
[release tag](https://github.com/tomas-samek/tiko-di/tags), 0.8.0 or later. It is a standalone Maven
project that builds against that release.

## 1. The project

You need Java 21+ and Maven 3.8+. Start from the [README's installation section](../README.md#installation):
- import the `tiko-bom` at the latest release;
- add `tiko-api`, `tiko-runtime` and `tiko-config`;
- put `tiko-processor` on the annotation-processor path.

Then add the libraries this service plugs in:

```xml
<dependency><groupId>com.zaxxer</groupId><artifactId>HikariCP</artifactId><version>6.2.1</version></dependency>
<dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><version>2.3.232</version></dependency>
<dependency><groupId>io.javalin</groupId><artifactId>javalin</artifactId><version>7.2.3</version></dependency>
<dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId><version>2.22.3</version></dependency>
<dependency><groupId>com.fasterxml.jackson.datatype</groupId><artifactId>jackson-datatype-jsr310</artifactId><version>2.22.3</version></dependency>
<dependency><groupId>org.slf4j</groupId><artifactId>slf4j-simple</artifactId><version>2.0.9</version></dependency>
```

Add JUnit 5 and AssertJ for the test; the BOM manages them in test scope. The quickstart's
[`pom.xml`](../tiko-examples/15_quickstart/pom.xml) has all of it, plus Failsafe for the tests
that start the server.

The compiler runs Tiko's processor. It checks the wiring at build time and writes the container
as plain Java: a component nobody provides, or a scope that does not fit, is a compile error.

## 2. Configuration

Typed configuration is a record annotated `@Configuration`. Its components bind to YAML keys
by name, and nested records bind to nested sections:

```java
@Configuration(prefix = "app")
public record AppConfig(ServerConfig server, DbConfig db) {}

public record ServerConfig(@Default("0") int port) {}

public record DbConfig(String url, String user, String password, @Default("4") int poolSize) {}
```

```yaml
# src/main/resources/application.yml
app:
  server:
    port: ${SERVER_PORT:8080}
  db:
    url: ${DB_URL:jdbc:h2:mem:quickstart;DB_CLOSE_DELAY=-1;MODE=PostgreSQL}
    user: ${DB_USER:sa}
    password: ${DB_PASSWORD:}
```

A required key left unset, or a value of the wrong type, fails boot with the file and line.
[`docs/configuration.md`](./configuration.md) has the rest.

## 3. The database

A library plugs in through a `@Produces` method: it returns the library's value, and every
constructor that takes the type receives it. `DataSourceFactory` produces the connection pool.
It creates the schema before it returns the pool, so everything that takes the `DataSource`
sees the tables. It also gives every unit of work a connection of its own, without auto-commit:

```java
@Component(scope = Scope.SINGLETON)
public class DataSourceFactory {
    @Inject public DataSourceFactory(AppConfig config) { /* ... */ }

    @Produces(scope = Scope.SINGLETON)
    public DataSource dataSource() { /* new HikariDataSource(...), then run schema.sql */ }

    @Produces(scope = Scope.EVENT)
    public Connection connection(DataSource ds) throws SQLException {
        var connection = ds.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }
}
```

Both values are `AutoCloseable`, so the container closes them for you:
- the pool when the container closes;
- the connection when its unit of work ends, which returns it to the pool.

Files: [`DataSourceFactory.java`](../tiko-examples/15_quickstart/src/main/java/io/tiko/examples/quickstart/DataSourceFactory.java),
[`schema.sql`](../tiko-examples/15_quickstart/src/main/resources/schema.sql).

## 4. A repository

`NoteRepository` is plain JDBC on the unit's `Connection`:

```java
@Component(scope = Scope.SINGLETON)
public class NoteRepository {
    @Inject public NoteRepository(Connection connection) { /* ... */ }

    public void insert(Note note) throws SQLException { /* INSERT INTO notes ... */ }
    public void insertTag(UUID noteId, String tag) throws SQLException { /* INSERT INTO note_tags ... */ }
    public Optional<Note> findById(UUID id) throws SQLException { /* SELECT ... */ }
}
```

The repository is a SINGLETON and the connection lives for one unit of work, so the processor
hands the repository a generated proxy. Every call resolves the connection of the unit that is
open: one connection per request, with no lookup in your code.
[`docs/di-and-scopes.md`](./di-and-scopes.md#cross-scope-injection) explains the scopes.

File: [`NoteRepository.java`](../tiko-examples/15_quickstart/src/main/java/io/tiko/examples/quickstart/NoteRepository.java).

## 5. One unit of work and one transaction per request

A unit of work is what EVENT-scoped components live for: here, one HTTP request.
`TransactionContext` is the unit's transaction. `RequestTransactions` wraps a Javalin handler so
that it runs in a unit of work and a transaction of its own:
- the transaction commits when the handler returns;
- it rolls back when the handler throws.

```java
@Component(scope = Scope.SINGLETON)
public class RequestTransactions {
    @Inject
    public RequestTransactions(UnitOfWork unitOfWork, Provider<TransactionContext> transaction) { /* ... */ }

    public Handler inTransaction(Handler handler) {
        return ctx -> unitOfWork.runInEventScope(() -> {
            var tx = transaction.get();
            try {
                handler.handle(ctx);
                tx.commit();
            } catch (RuntimeException failure) {
                rollback(tx, failure);
                throw failure;
            } catch (Exception failure) {
                rollback(tx, failure);
                throw new IllegalStateException("Request failed", failure);
            }
        });
    }
}
```

`UnitOfWork` is a built-in dependency: a component takes it to open units, and never holds the
container.

Files: [`TransactionContext.java`](../tiko-examples/15_quickstart/src/main/java/io/tiko/examples/quickstart/TransactionContext.java),
[`RequestTransactions.java`](../tiko-examples/15_quickstart/src/main/java/io/tiko/examples/quickstart/RequestTransactions.java).

## 6. Routes

A route group is a component with a `register(RoutesConfig)` method; Javalin 7 takes routes
only inside `Javalin.create(...)`. Each handler goes through `inTransaction`:

```java
@Component(scope = Scope.SINGLETON)
public class NoteRoutes {
    @Inject
    public NoteRoutes(RequestTransactions transactions, NoteRepository repo, EventBus eventBus, RequestId requestId) { /* ... */ }

    public void register(RoutesConfig routes) {
        routes.post("/notes", transactions.inTransaction(this::handleCreate));
        routes.get("/notes/{id}", transactions.inTransaction(this::handleGet));
    }

    public void handleCreate(Context ctx) {
        // insert the note, then each tag; a blank tag throws BadRequestResponse: 400, and the
        // request's transaction rolls the note back with it
        eventBus.publish(new NoteCreated(note.id(), note.createdAt()));
        ctx.status(201).json(note);
    }
}
```

`RequestId` is an EVENT-scoped component, so each request gets its own instance. The route sends
it back as `X-Request-Id`.

Files: [`NoteRoutes.java`](../tiko-examples/15_quickstart/src/main/java/io/tiko/examples/quickstart/NoteRoutes.java),
[`RequestId.java`](../tiko-examples/15_quickstart/src/main/java/io/tiko/examples/quickstart/RequestId.java),
[`RandomRequestId.java`](../tiko-examples/15_quickstart/src/main/java/io/tiko/examples/quickstart/RandomRequestId.java).

## 7. The server

The server is a component the container starts and stops:
- `@PostConstruct` binds the port while the container starts, so a port already in use fails boot;
- it stops on `ApplicationEndingEvent`, before the container waits for async work.

```java
@Component(scope = Scope.SINGLETON)
public class HttpServer {
    @Inject public HttpServer(AppConfig config, NoteRoutes notes) { /* ... */ }

    @PostConstruct
    public void start() {
        app = Javalin.create(cfg -> {
                    cfg.jetty.modifyServer(server -> server.setStopTimeout(5_000));
                    notes.register(cfg.routes);
                })
                .start(config.port());
    }

    @EventHandler public void onApplicationEnding(ApplicationEndingEvent event) { stop(); }

    @PreDestroy public void stop() { /* app.stop(), once */ }
}
```

The [HTTP cookbook](./cookbooks/http.md) explains each choice, including what the stop timeout
does for a request in flight.

File: [`HttpServer.java`](../tiko-examples/15_quickstart/src/main/java/io/tiko/examples/quickstart/HttpServer.java).

## 8. React to an event

`NoteRoutes` publishes `NoteCreated`. A handler takes it as an `@EventHandler` parameter; no
registration, no listener interface:

```java
@Component(scope = Scope.SINGLETON)
public class NoteAuditor {
    @Inject public NoteAuditor(RequestId requestId) { /* ... */ }

    @EventHandler
    public void onNoteCreated(NoteCreated event) {
        requestIds.add(requestId.value()); // the same RequestId the route read
    }
}
```

A handler runs synchronously, inside the publisher's unit of work. `@EventHandler(async = true)`
runs it on the event executor, in a unit of its own. [`docs/events.md`](./events.md) has the rest.

File: [`NoteAuditor.java`](../tiko-examples/15_quickstart/src/main/java/io/tiko/examples/quickstart/NoteAuditor.java).

## 9. `main`

```java
public static void main(String[] args) {
    Tiko.daemon(ConfigSources.classpath("application.yml")).awaitShutdown();
}
```

`Tiko.daemon(...)`:
- builds the container, which starts the server;
- installs the JVM hook that closes the container on `Ctrl+C` / `SIGTERM`.

`awaitShutdown()` keeps `main` until then.

Run it, and create a note:

```bash
mvn compile exec:java
curl -i -X POST localhost:8080/notes -H 'Content-Type: application/json' -d '{"text":"hello","tags":["first"]}'
```

## 10. Test it

A test boots the container the way `main` does, on a port the OS picks, and talks to it over real
HTTP:

```java
container = Tiko.create(TikoOptions.builder()
        .configSource(ConfigSources.layered(
                ConfigSources.classpath("application.yml"),
                ConfigSources.fromMap(Map.of("app", Map.of("server", Map.of("port", 0))))))
        .build());
port = container.get(HttpServer.class).port();
```

[`QuickstartIT`](../tiko-examples/15_quickstart/src/test/java/io/tiko/examples/quickstart/QuickstartIT.java)
checks four things:
- a note and its tags commit together;
- a request that fails after its first write leaves nothing behind;
- two requests get two units of work;
- the event handler ran in the request's unit.

[`TakenPortIT`](../tiko-examples/15_quickstart/src/test/java/io/tiko/examples/quickstart/TakenPortIT.java)
checks that a port already in use fails boot and leaves no thread behind.

For tests that replace a component, record events or run without a server,
[`tiko-test`](./testing.md) adds `@TikoTest`.

## Where next

- **Another library**: the "What you plug in" table in the [README](../README.md#what-you-plug-in),
  the [cookbooks](./cookbooks/README.md) (persistence, HTTP, Kafka, Ktor), and
  [`docs/orchestrator-model.md`](./orchestrator-model.md) for the recipes.
- **Kafka**: the [Kafka cookbook](./cookbooks/kafka.md) and
  [`tiko-examples/08_kafka_order_warehouse`](../tiko-examples/08_kafka_order_warehouse).
- **Kotlin**: [`tiko-examples-gradle/`](../tiko-examples-gradle) and the
  [Kotlin reference](../.ai-skills/tiko-build/reference/kotlin.md).
- **The reference**: [scopes and DI](./di-and-scopes.md), [events](./events.md),
  [configuration](./configuration.md), [testing](./testing.md).
