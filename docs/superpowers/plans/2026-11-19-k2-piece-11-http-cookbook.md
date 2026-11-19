# K2 Piece 11: HTTP Cookbook with a Container-Owned Server Implementation Plan

> **For agentic workers:** this plan is executed in the session that wrote it, task by task, test first. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** one documented shape for an HTTP service on Tiko. Every request runs in a unit of work
of its own. The container starts and stops the server. A resource that must run from boot starts
with it. `main` is `Tiko.daemon(...)` plus `awaitShutdown()`. The skill, `15_quickstart`,
`09_http_javalin` and both transactional outbox examples follow it. Closes #686.

**Architecture:** no framework code changes. The server is a SINGLETON `@Component` that the
container builds at start:
- its constructor takes `UnitOfWork`, the route groups, the settings and any resource that must
  run from boot;
- `@PostConstruct` builds Javalin with the routes and binds the port;
- stopping happens on `ApplicationEndingEvent`, with `@PreDestroy` as a fallback.

Each route group wraps its handlers in a unit of work through `UnitOfWork`. A taken port then
fails `Tiko.daemon(...)` and `Tiko.create(...)`, and the container shuts down what it had already
started. This relies on behaviour that exists today:
- a single-module container constructs every SINGLETON `@Component` in `start()`;
- a failure there shuts down and rethrows;
- `ApplicationEndingEvent` is published before the container waits for async work.

**Tech Stack:** Javalin 7.2.3, Java 21, Kotlin 2.4.21 (Gradle outbox example), existing build.

**Spec:** issue #686. Roadmap: `docs/superpowers/plans/2026-11-18-kotlin-k2-coroutines-roadmap.md`,
piece 11. The interface it builds on is `io.tiko.UnitOfWork` (piece 1, #685).

## Decisions (owner, 2026-11-19: both as recommended)

| # | Question | Decided |
|---|---|---|
| D14 | The skill, the README, `docs/orchestrator-model.md` §3.5 and the cookbook-extension skill teach the HTTP layer as `@Produces Javalin` (a `JavalinFactory`) that `main` resolves and starts. What replaces it? | **A server `@Component` that owns Javalin.** It takes the route groups and `UnitOfWork`, starts in `@PostConstruct`, and stops on `ApplicationEndingEvent`. Nothing produces `Javalin`, and nothing resolves it. A server is where the application's work comes in, not a value other components take. One class then holds its whole lifecycle, and the "Plug in" rule for libraries stays as it is for the DataSource, caches and clients. **Alternative:** keep `@Produces Javalin`, add a small SINGLETON `@Component` that takes it and starts it, and stop it on `ApplicationEndingEvent`. That keeps the `@Produces` shape, but splits start and stop across two classes. |
| D15 | Schema migrations run today on `ApplicationStartedEvent` (the skill's Flyway row, `orchestrator-model.md` §3.2, the quickstart's `SchemaInitializer`). That event comes after every SINGLETON is built, so once the container starts the server, the server takes requests before the schema exists. What changes? | **The `DataSource` producer migrates before it returns the pool**: `Flyway.configure().dataSource(ds).load().migrate()`, or `schema.sql` in the quickstart. Everything that takes the `DataSource` sees the schema, the server included. A failed migration fails boot: today a failing `ApplicationStartedEvent` handler is logged, and the service runs on. The outbox examples get the same change (Task 3). **Alternative:** keep the event and start the server on `ApplicationStartedEvent` after it. A taken port would then no longer fail boot, and the order between the two handlers would have to be arranged. |

## What follows, stated plainly

- **The server starts while the container starts.** It binds in `@PostConstruct`, so a taken port
  fails boot. A handler of `ApplicationStartedEvent` would not do: the bus isolates a failing
  handler, so the service would run on without HTTP.
- **The server stops before the container waits for async work.** `ApplicationEndingEvent` comes
  first in `close()`, so no new request opens a unit while async handlers drain. After a failed
  boot the container publishes no `ApplicationEndingEvent`. `@PreDestroy` then stops a server that
  had already started, for example when a SINGLETON built after it fails.
- **A route group opens the unit.** Javalin 7 has no handler that wraps a request, so each route
  group wraps its own handlers. Each example has one small helper for this: it takes `UnitOfWork`
  and a Javalin `Handler`, and returns a `Handler`.
- **A resource that must run from boot is a constructor parameter of the server**: the outbox
  relay in both outbox examples. The relay needs the schema to exist before it starts. Today that
  forces a `Provider` in the Kotlin example and an explicit `container.get` in the Java one.
  Instead, the database producer creates the schema before it returns the `DataSource`. A produced
  database is then ready to use, and no ordering is left to arrange.
- **A multi-module application builds SINGLETONs on first use** (`docs/di-and-scopes.md`). There
  the server does not start at boot until `main` resolves it. The cookbook says so and shows the
  one line.
- **Kotlin and `suspend` routes stay with the Ktor cookbook** (#682). This page is Javalin and
  Java. The Kotlin outbox example follows the same shape in Kotlin.

## Interfaces

None in the framework. Each example has its own copy of:
- `final class <Name>HttpServer` (SINGLETON `@Component`), with
  - `@PostConstruct void start()`,
  - `@EventHandler void onApplicationEnding(ApplicationEndingEvent e)` and `@PreDestroy void stop()`
    (both idempotent),
  - `int port()` for tests;
- `static Handler inUnit(UnitOfWork unitOfWork, Handler handler)`, the route helper. In `09` it is
  `TikoJavalin.scoped(UnitOfWork, Handler)`.

Settings carry the port, and tests set it to `0` with
`ConfigSources.layered(ConfigSources.classpath(...), ConfigSources.fromMap(...))`.

## Global Constraints

- Public prose follows the framing rule (no "gap", "missing", "not yet supported",
  "limitation", "tiko's equivalent of"); no "coming from Spring" mappings.
- `@Annotation` in GitHub text in backticks; PR body compact.
- Examples stay plain Javalin + Tiko: no wrapper library, no new module (#686 out of scope).
- `.ai-skills/tiko-build/SKILL.md` and its archetype copy stay identical.
- Test-first: each example's new test fails before its change.

## Review Focus

1. **A port already in use.** Expected: `Tiko.create(...)` throws, no container thread survives
   (`onThreadsOutlivingClose` is not called), and no Javalin or Jetty thread survives, so a
   `main` that rethrows lets the JVM exit. Covered by a test in `15_quickstart` (Task 1). If
   Javalin leaves its threads behind after a failed bind, `start()` stops the server before it
   rethrows.
2. **A request in flight when `close()` begins.** Expected: it finishes. The async work it
   started is drained as today (`09`'s drain test, Task 2). What `Javalin.stop()` does with a
   request in flight is measured on 7.2.3 and written into the cookbook as measured.
3. **Two requests.** Expected: different EVENT instances, and one instance within a request.
   Covered by a test in `15_quickstart` (Task 1).
4. **A SINGLETON built after the server fails.** Expected: the server is stopped (`@PreDestroy`)
   and boot fails. Not pinned by a test, because the examples have no such component. The
   cookbook states the rule, and `stop()` is idempotent.
5. **The outbox relay starts at boot without `Provider`.** Covered by the Kotlin outbox e2e test
   and the Java `OutboxRecoveryIT` (Task 3), which start the services as processes.

---

### Task 1: `15_quickstart` follows the shape

**Files:**
- Create `tiko-examples/15_quickstart/src/main/java/io/tiko/examples/quickstart/`:
  - `HttpServer.java`;
  - `RequestId.java` (interface) and `RandomRequestId.java` (EVENT).
- Delete `JavalinFactory.java`.
- Modify `NoteRoutes.java`:
  - it takes `UnitOfWork` and `RequestId`;
  - it wraps its handlers;
  - it sets an `X-Request-Id` header;
  - `NoteCreated` carries the id.
- Modify `NoteCreated.java` and `NoteAuditor.java` (the auditor records the ids it saw).
- Per D15, delete `SchemaInitializer.java`. `DataSourceFactory.dataSource()` runs `schema.sql`
  before it returns the pool.
- Modify `Main.java`: `Tiko.daemon(...).awaitShutdown()`.
- Modify `README.md`.
- Test `QuickstartIT.java`.

- [ ] Write the tests:
  - `twoRequestsRunInTwoUnits`: two POSTs. Their `X-Request-Id` headers differ, and each header
    equals the id the auditor saw for that note, which is the same unit.
  - `aTakenPortFailsBootAndLeavesNoThread`: hold a `ServerSocket`, then call `Tiko.create` with
    that port and with `onThreadsOutlivingClose` recording. Assert that create throws, that
    nothing was recorded, and that no live thread has a Jetty or Javalin name (names measured
    first).
  - The existing tests move to the server component: the port comes from `HttpServer.port()`, and
    no `container.get(Javalin.class)` is left.
- [ ] Run `mvn -o -pl tiko-examples/15_quickstart verify`. Expected: the new tests fail.
- [ ] Implement as listed. `HttpServer.start()` stops Javalin before it rethrows a failed `start`,
  if the thread check shows that is needed.
- [ ] Run `verify` again until it is green, then run `mvn -o -q exec:java` with the port taken.
  Expected: it exits with the bind error.
- [ ] Commit: `docs(examples): the quickstart's HTTP server is a component the container starts (#686)`.

### Task 2: `09_http_javalin` follows the shape

**Files:**
- `TicketHttpRoutes.java` becomes a SINGLETON `@Component`. It takes `UnitOfWork`, `EventBus`,
  `TicketService` and `RequestId` (proxy), so it no longer needs the container.
- `TikoJavalin.scoped(UnitOfWork, Handler)`.
- New `TicketHttpServer.java` and `HttpSettings.java`
  (`@Configuration(prefix = "http")`, `port: ${TIKO_HTTP_PORT:8080}` in `config.yaml`).
- `Main.java` keeps the drain demo. The server is the container's, and closing the container
  stops it before the drain.
- `ShutdownSequenceProbe.java`: its comment points at `TicketHttpServer`.
- `README.md`.
- Tests: `TicketHttpIT`, `HttpAsyncDrainTest` and `ShutdownSequenceTest` use port `0`, and
  nothing in them builds Javalin.

- [ ] Change the tests first. They resolve `TicketHttpServer` for the port. Run them and see them
  fail to compile.
- [ ] Implement, then run `mvn -o -pl tiko-examples/09_http_javalin verify` until green.
  `mvn -o -q exec:java` prints the drain lines.
- [ ] Commit: `docs(examples): 09 opens a unit per request through UnitOfWork, in a server the container owns (#686)`.

### Task 3: both outbox orders services follow the shape

**Files:**
- `tiko-examples/17_transactional_outbox/orders`:
  - new `OrdersHttpServer.java`, which takes `UnitOfWork`, `OrderStore`, `Settings` and
    `OutboxRelay`;
  - `Main.java` becomes `Tiko.daemon(...).awaitShutdown()`;
  - `Resources.java` creates the schema in the database producer;
  - `OrderStore.java` loses its `@PostConstruct`;
  - `OrderOutboxTest` uses port `0`.
- `tiko-examples-gradle/kotlin-transactional-outbox/orders`:
  - the same changes in `OrdersHttpServer.kt`, `Main.kt`, `Resources.kt` and `OrderStore.kt`;
  - no `Provider`, no `daemon.stop()`.
- Both READMEs, where they describe startup.

- [ ] Run the existing tests as the baseline: `OrderOutboxTest`, then `OutboxRecoveryIT` and the
  Kotlin `:e2e:test -PrequireDocker`. These test the processes, so this task changes the code they
  run, not the tests.
- [ ] Implement both.
- [ ] Run `mvn -o -pl tiko-examples/17_transactional_outbox/orders,tiko-examples/17_transactional_outbox/e2e -am verify`
  and `./gradlew clean build :e2e:test -PrequireDocker` in the Kotlin example. Expected: green,
  with the e2e tests not skipped.
- [ ] Commit: `docs(examples): the outbox orders services start their server and relay with the container (#686)`.

### Task 4: cookbook, skill, docs, full build, PR

**Files:**
- New `docs/cookbooks/http.md`. Sections:
  - dependencies;
  - one unit per request;
  - the server component;
  - when it starts and what a taken port does;
  - stopping, with the in-flight request as measured;
  - a resource that runs from boot;
  - `main`;
  - multi-module;
  - testing on port `0`;
  - the outbox example as the HTTP → database → Kafka reference.
- `docs/cookbooks/README.md`: an entry for the new page.
- `.ai-skills/tiko-build/SKILL.md` and its archetype copy: the bootstrap pattern, the HTTP routes
  block and the cookbook table row.
- Per D14 and D15, also `docs/orchestrator-model.md` §3.2 and §3.5 with its tree, the skill's
  Flyway row, the cookbook-extension skill's
  worked example 2, and the `README.md` HTTP row and rows 09 and 15.
- The roadmap status.

- [ ] Write the docs. The page links to the examples' files, not copies of them.
- [ ] Run a sweep: `grep -rn "JavalinFactory\|container.get(Javalin" --include=*.md --include=*.java`
  is empty outside dated plans.
- [ ] Run the full `mvn -o clean install` and the Kotlin outbox Gradle build.
- [ ] Commit `docs: HTTP service cookbook, and the skill teaches the server the container owns (#686)`,
  push, open the PR, post the choices comment, and watch CI and Sonar.

## What changed while implementing

- **A produced pool was never closed (#845).** The quickstart's taken-port test found the
  `HikariDataSource` still running after the failed boot. A value a `@Produces` method returned
  was closed only when the method's declared type was `AutoCloseable`, and `DataSource` is not.
  That was fixed first, in its own PR (#846), and this piece builds on it.
- **Javalin starts a thread of its own with every instance.** It sleeps 5 s, warns when the
  server never started, and ends. The taken-port test waits up to 10 s for the threads the failed
  boot started, and the cookbook states what this does to a failed process.
- **A request in flight at `stop()` loses its response with Javalin's defaults**, measured on
  7.2.3: the handler runs to its end, but the connection is closed first. With
  `server.setStopTimeout(...)` the client gets the response, and an idle keep-alive connection
  adds about a second to `stop()`. All four servers set it. The tests close their `HttpClient`
  before the container, and the cookbook has the table. No test pins it: that would need a slow
  route in an example.
- **The orders tests now see the relay start at boot.** `OrderOutboxTest` overrides
  `OutboxRelay` with one over a `MockProducer`, and an order placed through `OrderStore` reaches
  it without the test resolving the relay.
- **`09`'s port is `http.port` in `config.yaml`** (`HttpSettings`). A section is required even
  when all its fields have defaults, so the tests pass port `0` through `OnAFreePort`.
- **The skill's lifecycle sample in `reference/events.md` was an HTTP server** stopped in
  `close()`. It is now a scheduler, so the HTTP shape is taught in one place.

## Out of scope

- An HTTP integration module, or a shared `inUnit` helper in a Tiko artifact (#686).
- Ktor and `suspend` routes (#682, done in pieces 4 + 5).
- Eager SINGLETONs in multi-module containers (#46).
