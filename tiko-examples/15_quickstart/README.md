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
| `POST` | `/notes` | Body `{"text":"…"}`. Inserts a row, publishes `NoteCreated`, returns `201` + the stored note. |
| `GET`  | `/notes/{id}` | Returns the row, or `404`. |

A `NoteAuditor` `@EventHandler(NoteCreated)` increments a counter every time
the bus delivers — the integration test asserts on it to prove the chain.

## Run it

```bash
mvn -pl tiko-examples/15_quickstart verify   # tests on a random port
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
| [`DataSourceFactory.java`](src/main/java/io/tiko/examples/quickstart/DataSourceFactory.java) | HikariCP `DataSource` via `@Produces` |
| [`SchemaInitializer.java`](src/main/java/io/tiko/examples/quickstart/SchemaInitializer.java) | Flyway-style migration via `@EventHandler(ApplicationStartedEvent)` |
| [`NoteRepository.java`](src/main/java/io/tiko/examples/quickstart/NoteRepository.java) | Raw JDBC against an injected `DataSource` — no wrapper |
| [`JavalinFactory.java`](src/main/java/io/tiko/examples/quickstart/JavalinFactory.java) | HTTP layer via `@Produces` (replaces `@RestController`); `@PreDestroy` lifecycle |
| [`NoteRoutes.java`](src/main/java/io/tiko/examples/quickstart/NoteRoutes.java) | Plain route methods, no annotation-driven dispatch |
| [`NoteAuditor.java`](src/main/java/io/tiko/examples/quickstart/NoteAuditor.java) | Event-driven workflow via `@EventHandler` (replaces `@TransactionalEventListener` / `@Async`) |
| [`Main.java`](src/main/java/io/tiko/examples/quickstart/Main.java) | Bootstrap shape: `Tiko.create(ConfigSources.classpath(...))` |

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
