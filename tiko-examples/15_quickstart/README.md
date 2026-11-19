# 15 — Quickstart (orchestrator-model reference app)

This module is the canonical output the **tiko-build skill** produces on a
plain-language prompt of the shape *"I need a small service that accepts a
JSON POST, writes a row to a database, and reacts to the write."* Every
`@Produces` factory, every `@EventHandler`, and the layout itself maps to a
named recipe in [`docs/orchestrator-model.md`](../../docs/orchestrator-model.md).

The point is to **show**, not tell: the orchestrator model is a working app
you can run, not a prose claim. Vocabulary follows
[`docs/orchestrator-vocabulary.md`](../../docs/orchestrator-vocabulary.md).

## What it does

One HTTP service backed by an in-memory H2 database:

| Verb | Path | Behaviour |
|---|---|---|
| `POST` | `/notes` | Body `{"text":"…"}`. Inserts a row, publishes `NoteCreated`, returns `201` + the stored note and the request's `X-Request-Id`. |
| `GET`  | `/notes/{id}` | Returns the row, or `404`. |

Every request runs in a unit of work of its own, so it gets its own EVENT-scoped
`RequestId`. A `NoteAuditor` `@EventHandler(NoteCreated)` records the request id of
every note the bus delivers. It runs inside the request's unit, so it reads the id
the route read; the integration test asserts on that to prove the chain.

The container owns the server. It builds `HttpServer` when it starts, and the server
binds its port then: with the port already in use, `main` fails and the process
exits. Closing the container stops the server first.

## Run it

```bash
mvn -pl tiko-examples/15_quickstart verify   # tests on a random port
mvn -pl tiko-examples/15_quickstart exec:java   # run straight from the classes
mvn -pl tiko-examples/15_quickstart package
java -jar tiko-examples/15_quickstart/target/15_quickstart-*.jar
# → POST http://localhost:8080/notes  '{"text":"hello"}'
```

Override `SERVER_PORT`, `DB_URL`, `DB_USER`, `DB_PASSWORD` via env vars for
anything other than the in-memory H2 default.

## Per-file recipe map

Each source file demonstrates exactly one named recipe from the skill. This
table is the contract between this module and the skill — when a recipe in
the skill changes, the corresponding file here changes too, and vice versa.

| File | Skill recipe |
|---|---|
| [`AppConfig.java`](src/main/java/io/tiko/examples/quickstart/AppConfig.java) + [`ServerConfig.java`](src/main/java/io/tiko/examples/quickstart/ServerConfig.java) + [`DbConfig.java`](src/main/java/io/tiko/examples/quickstart/DbConfig.java) | Typed `@Configuration` records (replaces `@Value` magic binding) |
| [`DataSourceFactory.java`](src/main/java/io/tiko/examples/quickstart/DataSourceFactory.java) | HikariCP `DataSource` via `@Produces`; the producer creates the schema before it returns the pool (Flyway-style migration) |
| [`NoteRepository.java`](src/main/java/io/tiko/examples/quickstart/NoteRepository.java) | Raw JDBC against an injected `DataSource` — no wrapper |
| [`HttpServer.java`](src/main/java/io/tiko/examples/quickstart/HttpServer.java) | HTTP server the container owns: route groups register inside `Javalin.create`; starts in `@PostConstruct`, stops on `ApplicationEndingEvent` |
| [`NoteRoutes.java`](src/main/java/io/tiko/examples/quickstart/NoteRoutes.java) | Route group as a `@Component`: paths in `register(RoutesConfig)`, plain handler methods, each in a unit of work of its own, no annotation-driven dispatch |
| [`RequestUnits.java`](src/main/java/io/tiko/examples/quickstart/RequestUnits.java) | One unit of work per request through the injected `UnitOfWork` |
| [`RequestId.java`](src/main/java/io/tiko/examples/quickstart/RequestId.java) + [`RandomRequestId.java`](src/main/java/io/tiko/examples/quickstart/RandomRequestId.java) | An EVENT-scoped component, one instance per request |
| [`NoteAuditor.java`](src/main/java/io/tiko/examples/quickstart/NoteAuditor.java) | Event-driven workflow via `@EventHandler` (replaces `@TransactionalEventListener` / `@Async`) |
| [`Main.java`](src/main/java/io/tiko/examples/quickstart/Main.java) | Bootstrap shape: `Tiko.daemon(ConfigSources.classpath(...)).awaitShutdown()` |

## What this module is not

- **Not a showcase.** No Kafka, no security, no UI. That's by design — a
  reference is small enough to read in one sitting.
- **Not a replacement for [09](../09_http_javalin) or [10](../10_persistence_jdbc).**
  Those modules drill deeper into specific concerns (HTTP drain semantics,
  EVENT-scoped JDBC connections, transactional scope). This module shows the
  smallest end-to-end shape so the skill can point at it as the starting
  template.

## Drift check

If you add a recipe to the skill and it isn't reflected in this module — or
you add a `@Produces` here without a paired recipe in
`docs/orchestrator-model.md` — fix the drift. The automated check is parked
under the milestone-wide benchmark (#269); the manual cross-link above is
the current guard.
