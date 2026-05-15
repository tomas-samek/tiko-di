# Persistence cookbook (raw JDBC + HikariCP) — design

Status: draft (2026-05-15). First entry in the cookbook track established by the
2026-05-15 decision (see memory `project_cookbook_direction.md`). This spec
covers one cookbook page + one paired numbered example module; subsequent
cookbooks (security, resilience, Kafka surfacing, the "Non-goals" meta-doc)
get their own specs.

## Context

A reviewer stress-test asked whether Tiko can host a full microservices order
flow. The framework can, but reviewers consistently read silence on
persistence/security/resilience as "framework is incomplete" rather than
"framework is deliberately small". Cookbooks close that gap without expanding
Tiko's surface: each cookbook documents the recommended third-party integration
for one non-goal, paired with a runnable numbered example that proves the
recommendation compiles and runs against the current Tiko version.

Persistence is first because every service touches a database and the most-
asked Tiko question is "how do I scope my JDBC connection to a request?".

This cookbook also makes a Tiko-internal teaching point visible for the first
time: the *concrete difference between `REQUEST` and `EVENT` scopes*. In the
existing examples, the two scopes always collapse to the same lifetime (one
HTTP request → one event → one handler), so a reader can't tell why both
exist. The batch entry point in this example demonstrates them doing
genuinely different jobs:

- `REQUEST` owns the unit of work — the transaction, the JDBC connection, the
  audit context. One per batch.
- `EVENT` owns the single message being processed — current order id, in-flight
  payload, per-message correlator. N per batch, all inside the one REQUEST.

## Goals

- Ship a runnable `tiko-examples/10_persistence_jdbc/` module demonstrating
  REQUEST-scoped JDBC transactions wrapping both an HTTP entry point and a
  batch entry point that share one repository layer.
- Ship a `docs/cookbooks/persistence.md` page that documents the wiring
  patterns and explicitly states *why* Tiko doesn't ship persistence itself.
- Demonstrate Tiko's auto-proxy mechanism for the first time on a
  third-party JDK interface (`java.sql.Connection`), proving the pattern
  generalises beyond the custom-interface case shown in `01_basic_di`.
- Make the `REQUEST`-vs-`EVENT` scope distinction *physically visible* via
  the batch flow.
- Establish the cookbook shape (`docs/cookbooks/<topic>.md` + numbered example)
  as a reusable pattern for follow-up cookbooks.

## Non-goals

- No ORM. Raw JDBC only.
- No Flyway / Liquibase / migration tooling in the example itself
  (`schema.sql` + a `@PostConstruct` runner is enough to teach; the cookbook
  flags this as a simplification and points at Flyway/Liquibase for
  production).
- No Testcontainers in the example (H2 in-memory is enough to teach; cookbook
  flags Testcontainers PostgreSQL as the prod-test answer).
- No multi-DataSource / sharding scenarios. Single DB.
- No connection-leak diagnostics, no observability instrumentation beyond
  what Tiko's existing `RequestStartedEvent` / `RequestEndingEvent` already
  give for free.
- No new framework features. The cookbook uses Tiko's existing public API:
  `@Component`, `@Produces`, `@Configuration`, REQUEST scope, auto-proxy.

## Module layout

```
tiko-examples/10_persistence_jdbc/
├── pom.xml
├── src/main/java/io/tiko/examples/persistence/
│   ├── config/
│   │   └── DbConfig.java                  @Configuration record
│   ├── infra/
│   │   ├── HikariDataSourceFactory.java   SINGLETON @Component, @Produces DataSource
│   │   ├── JdbcConnectionProvider.java    REQUEST @Component, @Produces Connection
│   │   ├── TransactionContext.java        REQUEST @Component, AutoCloseable
│   │   ├── TransactionalScope.java        utility — opens REQUEST scope + commit/rollback wrapper
│   │   └── SchemaInitializer.java         SINGLETON @Component, @PostConstruct runs schema.sql
│   ├── domain/
│   │   ├── Order.java                     record
│   │   ├── OrderItem.java                 record
│   │   └── OrderId.java                   record wrapping UUID
│   ├── repo/
│   │   └── OrderRepository.java           SINGLETON @Component, Connection auto-proxied in
│   ├── http/
│   │   ├── HttpEntry.java                 main() — Javalin app, POST/GET /orders
│   │   └── OrderHttpRoutes.java           bridge (not @Component; constructed in HttpEntry)
│   └── batch/
│       ├── BatchEntry.java                main() — loads fixture, runs in one REQUEST + N EVENTs
│       ├── CurrentOrderContext.java       EVENT @Component, holds in-flight order id for the current event
│       └── BatchAuditLogger.java          SINGLETON @Component, @EventHandler on EventStartedEvent, reads CurrentOrderContext via auto-proxy
├── src/main/resources/
│   ├── schema.sql
│   └── application.yml                    H2 in-memory by default
└── src/test/java/io/tiko/examples/persistence/
    ├── repo/OrderRepositoryTest.java      manual REQUEST scope + cross-connection verification
    ├── http/HttpEntryIT.java              full Javalin + container, real HTTP, asserts persistence
    └── batch/BatchEntryIT.java            full batch flow, asserts all-or-none commit

docs/cookbooks/persistence.md              the cookbook
docs/cookbooks/README.md                   cookbook index (new file)
```

## Domain

```sql
CREATE TABLE IF NOT EXISTS orders (
    id          UUID        PRIMARY KEY,
    customer    TEXT        NOT NULL,
    status      TEXT        NOT NULL,
    created_at  TIMESTAMP   NOT NULL
);

CREATE TABLE IF NOT EXISTS order_items (
    order_id    UUID        NOT NULL,
    line_no     INT         NOT NULL,
    sku         TEXT        NOT NULL,
    qty         INT         NOT NULL,
    PRIMARY KEY (order_id, line_no),
    FOREIGN KEY (order_id) REFERENCES orders(id)
);
```

Two tables so that the transaction story has something to roll back: an
`INSERT INTO orders` followed by N `INSERT INTO order_items`. A failure
half-way must roll both back. This is the cookbook's "why a transaction
matters" pivot.

## Component design

### `DbConfig` — configuration record

```java
@Configuration("db")
public record DbConfig(
        @Key("url") String url,
        @Key("user") String user,
        @Key("password") String password,
        @Key("pool-size") @Default("4") int poolSize) {}
```

Loaded from `application.yml`. H2 in-memory by default:

```yaml
db:
  url: "jdbc:h2:mem:tiko;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
  user: "sa"
  password: ""
  pool-size: 4
```

### `HikariDataSourceFactory` — pool wiring

SINGLETON `@Component`. Produces a SINGLETON `DataSource` via `@Produces`:

```java
@Component(scope = Scope.SINGLETON)
public class HikariDataSourceFactory {
    private final DbConfig config;

    @Inject HikariDataSourceFactory(DbConfig config) { this.config = config; }

    @Produces(scope = Scope.SINGLETON)
    public DataSource dataSource() {
        var hc = new HikariConfig();
        hc.setJdbcUrl(config.url());
        hc.setUsername(config.user());
        hc.setPassword(config.password());
        hc.setMaximumPoolSize(config.poolSize());
        return new HikariDataSource(hc);
    }
}
```

The DataSource itself is `AutoCloseable` — Tiko closes it at container
shutdown automatically, draining the pool.

### `JdbcConnectionProvider` — per-request connection

REQUEST `@Component`. Produces a REQUEST-scoped `Connection`:

```java
@Component(scope = Scope.REQUEST)
public class JdbcConnectionProvider {
    private final DataSource ds;

    @Inject JdbcConnectionProvider(DataSource ds) { this.ds = ds; }

    @Produces(scope = Scope.REQUEST)
    public Connection connection() throws SQLException {
        var c = ds.getConnection();
        c.setAutoCommit(false);
        return c;
    }
}
```

`java.sql.Connection` is an interface, so a SINGLETON consumer can take
`Connection` as a constructor parameter and Tiko's annotation processor
generates a proxy that delegates each method call to the current scope's
connection. This is the auto-proxy mechanism — already shown for a custom
interface in `01_basic_di`, demonstrated here on a JDK interface for the
first time.

### `TransactionContext` — commit/rollback owner

REQUEST `@Component`, implements `AutoCloseable`. Holds the connection
*directly* (not via the proxy — same scope, no indirection needed):

```java
@Component(scope = Scope.REQUEST)
public class TransactionContext implements AutoCloseable {
    private final Connection connection;
    private boolean committed = false;

    @Inject TransactionContext(Connection connection) { this.connection = connection; }

    public void commit() throws SQLException { connection.commit(); committed = true; }
    public void rollback() throws SQLException { connection.rollback(); }

    @Override
    public void close() throws SQLException {
        if (!committed) connection.rollback();
        // Tiko's implicit-AutoCloseable handling on the @Produces Connection
        // returns it to the Hikari pool — TransactionContext.close() runs first
        // (reverse-creation order: this depends on Connection, so this tears
        // down first), so rollback lands before pool return. We deliberately
        // do NOT call connection.close() here — that's Tiko's job for the
        // @Produces output.
    }
}
```

Tiko's existing implicit-AutoCloseable handling invokes `close()` at REQUEST
scope teardown for both `TransactionContext` (because it implements
`AutoCloseable` and has no `@PreDestroy`) and the produced `Connection`
(because the `@Produces` return type is `AutoCloseable`). The `committed`
flag is the safety net: if handler code exits normally but forgot to call
`commit()` (a bug), the scope teardown rolls back rather than silently
leaving an uncommitted transaction. The intended commit path is via
`TransactionalScope.run(...)`.

### `TransactionalScope` — the entry-point helper

A utility class (not a `@Component`). Opens a REQUEST scope, looks up the
`TransactionContext`, runs the user's work, and commits on success / rolls
back on exception:

```java
public final class TransactionalScope {
    private TransactionalScope() {}

    public static <T> T run(Container container, Supplier<T> work) {
        return container.supplyInRequestScope(() -> {
            var tx = container.get(TransactionContext.class);
            try {
                T result = work.get();
                tx.commit();
                return result;
            } catch (Throwable t) {
                try { tx.rollback(); } catch (SQLException sx) { t.addSuppressed(sx); }
                throw t instanceof RuntimeException re ? re : new RuntimeException(t);
            }
        });
    }
}
```

Both entry points use this. For Javalin specifically, a thin adapter
`TransactionalScope.javalin(container, handler)` composes
`TikoJavalin.scoped` from `09_http_javalin` with the commit/rollback
wrapper — but to keep this example self-contained (and to avoid pulling
`09_http_javalin` as a Maven dep), the adapter is inlined into the HTTP
entry's route registration in `HttpEntry` rather than published as a
separate utility class. Cookbook explicitly notes the duplication and
flags it as the candidate for a future shared library.

### `OrderRepository` — repository pattern

SINGLETON. Takes `Connection` via constructor (auto-proxied):

```java
@Component(scope = Scope.SINGLETON)
public class OrderRepository {
    private final Connection connection;

    @Inject OrderRepository(Connection connection) { this.connection = connection; }

    public void insert(Order order) throws SQLException { ... }
    public Optional<Order> findById(UUID id) throws SQLException { ... }
}
```

The `connection` field looks like a captured-at-construction object but the
proxy resolves to the current REQUEST scope's connection on every method
call. Cookbook explains this explicitly — it's the part most likely to
confuse readers.

### `SchemaInitializer` — bootstrap

SINGLETON `@Component`, `@PostConstruct` loads `schema.sql` from the
classpath and executes it. Uses `CREATE TABLE IF NOT EXISTS` so re-running
against an existing DB is a no-op.

## HTTP entry

`HttpEntry` (a `main` class) mirrors `09_http_javalin/Main`:

```java
public final class HttpEntry {
    private HttpEntry() {}

    public static void main(String[] args) {
        Container container = Tiko.create();
        var routes = new OrderHttpRoutes(container);

        Javalin app = Javalin.create();
        app.post("/orders", ctx -> TransactionalScope.run(container, () -> {
            routes.handleCreate(ctx);
            return null;
        }));
        app.get("/orders/{id}", ctx -> TransactionalScope.run(container, () -> {
            routes.handleGet(ctx);
            return null;
        }));
        app.start(portFromEnv());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            container.shutdown();
        }, "tiko-persistence-shutdown"));
    }
}
```

`OrderHttpRoutes` is not a `@Component` (same constraint as `09_http_javalin`'s
bridge — needs `Container` to look up REQUEST-scoped beans). It takes the
container in its constructor and uses `container.get(OrderRepository.class)`
inside handlers.

## Batch entry

`BatchEntry` (alternative `main` class):

```java
public final class BatchEntry {
    private BatchEntry() {}

    public static void main(String[] args) {
        Container container = Tiko.create();
        var fixture = loadFixture();   // List<RawOrder>, hard-coded list or JSON file

        TransactionalScope.run(container, () -> {
            var repo = container.get(OrderRepository.class);
            for (var raw : fixture) {
                container.runInEventScope(() -> {
                    var ctx = container.get(CurrentOrderContext.class);
                    ctx.setOrder(raw);
                    repo.insert(toOrder(raw));
                });
            }
            return null;
        });

        container.shutdown();
    }
}
```

One REQUEST → one transaction. N EVENTs inside it, each holding its own
`CurrentOrderContext` (EVENT-scoped). All N inserts commit together or
all roll back together. This is the concrete REQUEST-vs-EVENT teaching
moment.

`CurrentOrderContext` is the EVENT-scoped state that makes the distinction
visible. To prove it actually works (and to demonstrate the auto-proxy on
EVENT scope, parallel to the proxy on REQUEST-scoped Connection), the
example also ships a small `BatchAuditLogger` SINGLETON `@Component` that
subscribes to `EventStartedEvent` (the framework's lifecycle event) and
injects `CurrentOrderContext` as a constructor parameter. Tiko's processor
auto-proxies the EVENT-scoped context into the SINGLETON, and on each batch
iteration the proxy resolves to that iteration's order. `BatchEntryIT`
asserts the audit logger saw exactly N orders in iteration order, which
proves both (a) EVENT scope opens N times inside the one REQUEST and
(b) per-iteration state is reachable from a SINGLETON without parameter
threading.

## Schema management

`schema.sql` in `src/main/resources`. `SchemaInitializer` loads and executes
on container start. Idempotent.

Cookbook explicitly flags this as a **simplification**: production setups
use Flyway or Liquibase. Pointers in the cookbook's "Beyond this cookbook"
section. The cookbook is *not* a Flyway tutorial.

## Test database

H2 in-memory with PostgreSQL mode (`MODE=PostgreSQL`):
`jdbc:h2:mem:tiko;DB_CLOSE_DELAY=-1;MODE=PostgreSQL`. No Docker required.

Cookbook flags this as a **simplification**: production tests use
Testcontainers PostgreSQL. Pointer in "Beyond this cookbook".

## Testing strategy

Three test files, each focused:

### `OrderRepositoryTest`

Unit-flavored integration: builds a real `Container`, opens a REQUEST scope
manually via `container.runInRequestScope(...)`, exercises `insert` and
`findById`. Critically: after committing, opens a *new* connection (not
through Tiko) and re-queries — proves the row landed in committed state, not
just in-session visibility.

### `HttpEntryIT`

Full integration: real container, real Javalin on `app.start(0)`, real HTTP
via JDK `HttpClient`. Three scenarios:

1. **Happy path:** POST a valid order → 201 → query via raw JDBC →
   `orders` and `order_items` both present.
2. **Validation failure mid-transaction:** POST a payload whose second line
   item has a poison value that throws `IllegalArgumentException` in the
   bridge between order-insert and item-insert → 500 → query via raw JDBC →
   *nothing* committed (both tables empty for that order id). This is the
   load-bearing assertion for the cookbook.
3. **GET happy path:** POST → GET → assert echoed.

### `BatchEntryIT`

Full batch flow:

1. **All-success:** load 10 fixture orders → run batch → assert 10 orders +
   their items present via raw JDBC query outside Tiko.
2. **Poison-record:** inject a poison record at index 5 → run batch →
   `IllegalArgumentException` propagates → assert via raw JDBC that
   *nothing* committed (the first 4 successful inserts roll back along
   with the rest). All-or-none semantics.

All tests use H2; `@BeforeEach` creates a fresh container + fresh in-memory
DB, `@AfterEach` shuts down container (which closes Hikari, which closes H2).

## Cookbook document (`docs/cookbooks/persistence.md`)

Structure:

1. **Why Tiko doesn't ship persistence** — one paragraph linking to the
   2026-04-27 "Integrations live in examples, not modules" decision.
   Names the trade-off: persistence stacks come with their own CVE
   pressure + breaking-change cadence; a small team can't keep
   integrations honest across Hibernate/JOOQ/JDBI/Spring Data
   release cycles. Recommended pattern: pick the library that fits your
   shop, wire it through Tiko's `@Produces` factories — this cookbook
   shows the wiring for raw JDBC, which generalises.
2. **What this cookbook teaches** — REQUEST = transaction, EVENT =
   per-message, Connection auto-proxy, transaction decorator pattern,
   all-or-none batch semantics.
3. **The library choice** — Raw JDBC + HikariCP. Rationale: universal,
   no codegen, smallest surface to teach Tiko-side wiring without library
   magic in the way.
4. **DataSource wiring via `@Produces`** — annotated code walkthrough of
   `HikariDataSourceFactory` and `DbConfig`.
5. **REQUEST-scoped Connection + auto-proxy** — the load-bearing pattern.
   Spells out *why* the auto-proxy works: `java.sql.Connection` is an
   interface, Tiko's processor generates a per-method delegating proxy
   that resolves to the current scope's instance on each call.
6. **`TransactionContext` + `TransactionalScope`** — commit on success,
   rollback on exception. AutoCloseable as the safety net for "handler
   forgot to call commit". Why `TransactionalScope` is a utility class
   rather than absorbed into `TikoJavalin.scoped`: it generalises
   beyond HTTP (batch uses the same helper).
7. **HTTP single-request flow** — when REQUEST and EVENT collapse 1:1,
   the simpler case. Annotated `HttpEntry` walkthrough.
8. **Batch flow** — when REQUEST = 1 and EVENT = N. The
   `runInEventScope` inside `runInRequestScope` pattern. Annotated
   `BatchEntry` walkthrough. This is where the REQUEST-vs-EVENT
   distinction earns its keep.
9. **Async handlers + explicit REQUEST scope** — three-line snippet for
   `@EventHandler(async = true)` that needs persistence. The "no
   auto-elevation" rule, explained.
10. **Simplifications this cookbook makes** — `schema.sql` vs Flyway,
    H2 vs Testcontainers PostgreSQL. Pointers to each.
11. **Beyond raw JDBC** — pointers for a higher-level abstraction on top
    of the wiring this cookbook teaches:
    - **JOOQ** — type-safe SQL DSL, compile-time SQL safety, no runtime
      reflection. Strongest philosophical neighbor for Tiko (same
      compile-time + generated-code stance). Mention the Maven codegen
      step as the trade-off.
    - **JDBI 3** — annotation-driven SQL mapper, lighter than full ORM,
      runtime reflection on mapper interfaces. Note the reflection
      trade-off.
    - **Hibernate** — full ORM, reflection-heavy. Explicit note that this
      is the most distant fit for Tiko's "no runtime reflection"
      positioning; included as a pointer because it's the most popular
      Java persistence library, not as a recommendation.

    Spring Data JDBC is **deliberately excluded** from the pointer list
    because it drags transitive Spring framework dependencies that don't
    fit Tiko's "small framework, no ambient" positioning even as a layered
    library.

## Risks

- **Auto-proxy on `java.sql.Connection`.** The Connection interface has
  ~40 methods; the generated proxy class will be larger than typical.
  Mitigation: the proxy generator is per-method and well-tested in
  `01_basic_di` on smaller interfaces. If size becomes a concern at
  build time, fall back to injecting `Provider<Connection>` and calling
  `provider.get()` inside repository methods. Not the load-bearing
  variant for the cookbook, but a documented fallback.
- **Scope teardown success/failure signal.** Tiko's REQUEST scope
  teardown doesn't expose to scoped beans whether the scope ended
  normally or via exception. The `committed` flag on `TransactionContext`
  works because `TransactionalScope.run(...)` explicitly calls
  `commit()` on success; the AutoCloseable fallback is rollback. Any
  user code that bypasses `TransactionalScope` and writes manual
  `runInRequestScope { ... }` loses the commit unless they call
  `commit()` themselves. Cookbook addresses this directly: the
  decorator is the supported entry point, manual scope opening is for
  experts.
- **H2-vs-PostgreSQL dialect leakage.** H2's `MODE=PostgreSQL` covers
  most basics but not every PG-ism. Some SQL written for the cookbook
  may pass H2 and fail real PG, or vice versa. Cookbook flags this as
  the reason Testcontainers PostgreSQL is the production answer.
- **Connection leaks on test failure.** If a test's `@AfterEach` skips
  due to `@BeforeEach` failure, the Hikari pool could leak across
  tests. Mitigation: each test creates a fresh container, fresh DB,
  fresh pool — pool lives no longer than the test method's container.

## Out of scope

- Modifying Tiko itself in any way. The cookbook uses public API only.
- Promoting `TransactionalScope` or the HTTP-Javalin adapter to a
  `tiko-persistence` or `tiko-http-bridge` module. Default per
  `project_integrations_via_examples` is "show, don't ship" until
  durable adoption pressure shows up.
- Documenting other persistence libraries (JOOQ, JDBI, Hibernate)
  beyond pointer-level mentions. Each gets its own cookbook iff a
  reviewer or user surfaces friction there.
- The other cookbooks in the track (security, resilience, Kafka
  surfacing, Non-goals meta-doc). Each is its own spec.

## Acceptance

- [ ] `tiko-examples/10_persistence_jdbc/` builds and tests pass under
      `mvn -pl tiko-examples/10_persistence_jdbc test` and the full
      reactor `mvn -pl '!tiko-bom' install`.
- [ ] `HttpEntryIT` validates both commit-on-success and rollback-on-
      mid-transaction-failure via cross-connection JDBC query.
- [ ] `BatchEntryIT` validates all-or-none commit across N events in
      one REQUEST scope, again via cross-connection query.
- [ ] `OrderRepository` injects `Connection` directly (not via
      `Provider`); the auto-proxy resolves on every method call.
- [ ] `BatchAuditLogger` (SINGLETON) injects `CurrentOrderContext`
      (EVENT) directly; `BatchEntryIT` asserts the logger captured N
      orders in iteration order, proving auto-proxy works for EVENT
      scope as well as REQUEST scope.
- [ ] `docs/cookbooks/persistence.md` exists with all 11 sections above,
      including the explicit "why Tiko doesn't ship this" opener and the
      revised §11 pointers (JOOQ, JDBI, Hibernate — no Spring Data).
- [ ] `docs/cookbooks/README.md` exists as the cookbook track index and
      lists this cookbook as entry #1, with placeholders for the
      planned siblings (security, resilience, Kafka surfacing, Non-goals
      meta).
- [ ] No changes outside `tiko-examples/10_persistence_jdbc/`, `docs/`,
      root `pom.xml` (BOM-managed new deps), and `tiko-examples/pom.xml`
      (one `<module>` entry).
- [ ] Spotless gate clean.
- [ ] CI green on all JDKs.

## References

- Memory `project_cookbook_direction.md` (2026-05-15) — the cookbook
  pattern this spec establishes.
- Memory `project_integrations_via_examples.md` (2026-04-27) — the
  "no first-class framework modules for HTTP/security/persistence"
  decision this cookbook is a consequence of.
- PR #91 (`09_http_javalin`) — the proof-of-pattern for "integration
  via example + paired README"; this cookbook generalises that into
  a docs+example pair format.
- CLAUDE.md "Scope Management" section — defines the REQUEST/EVENT
  scope hierarchy this cookbook makes physically visible for the
  first time.
