# Persistence with Tiko — raw JDBC + HikariCP

> Runnable example: [`tiko-examples/10_persistence_jdbc/`](../../tiko-examples/10_persistence_jdbc/).

## Why Tiko doesn't ship persistence

Tiko's scope is **compile-time DI + event orchestration**. Persistence is
intentionally out of scope:

- The persistence space is big — JDBC, JPA/Hibernate, JOOQ, JDBI, Spring
  Data, R2DBC — each with its own release cadence and CVE pressure.
  A small team can't keep first-class integration modules honest across
  all of them.
- Tying Tiko to a single persistence library would force every adopter
  into that choice. Tying Tiko to all of them turns Tiko into a 1%-resourced
  Spring Boot competitor instead of an orthogonal alternative.

What Tiko *does* offer is the wiring patterns: `@Produces` factories,
EVENT scope (one unit of work) = transaction lifetime, auto-proxy of
EVENT-scoped resources into SINGLETON consumers. This cookbook shows that wiring with
raw JDBC + HikariCP — the lowest layer, easiest to follow. Higher-level
libraries layer on top of the same scaffolding.

## What you'll learn

1. **One unit of work = one DB transaction.** Open an EVENT scope (an
   HTTP request, a batch run, a consumed message); everything inside it
   runs in one transaction; commit on clean exit, roll back on exception.
2. **A batch is one unit with a loop.** EVENT scopes don't nest, so a
   batch processes its items inside the one unit — all-or-none.
3. **Auto-proxy on `java.sql.Connection`.** A SINGLETON repository can
   inject the EVENT-scoped Connection directly; Tiko's annotation
   processor generates a proxy that resolves to the current unit's
   connection on every method call.
4. **Transaction decorator pattern.** A single helper
   (`TransactionalScope.run(...)`) opens the scope, commits on success,
   rolls back on exception. Both HTTP and batch entries use it.

## Library choice

**Raw JDBC + HikariCP.** Universal, no codegen, no ORM. Every Java
developer knows the API. The cookbook's job is to teach the *Tiko-side*
wiring, not the persistence library — picking the lowest layer keeps
the persistence noise out of the way.

For higher-level abstractions on top of this wiring, see "Beyond raw JDBC"
at the bottom of this page.

## DataSource wiring

The pool is a SINGLETON `@Component` that produces a `DataSource` via
`@Produces`:

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
        hc.setAutoCommit(false);
        return new HikariDataSource(hc);
    }
}
```

`DbConfig` is a `@Configuration` record bound from `application.yml`.
`HikariDataSource` implements `AutoCloseable`, so Tiko drains the pool
at container shutdown automatically.

> **Not every pool is `AutoCloseable`.** The implicit-cleanup-at-shutdown above relies on the
> pool implementing `AutoCloseable`, which `HikariDataSource` does. If you reach for a different
> pool — e.g. embedded H2's `org.h2.jdbcx.JdbcConnectionPool` (a self-contained pooled
> `DataSource` with no first-party module) — note it is **not** `AutoCloseable`; it exposes
> `dispose()` instead. Release it with an explicit `@PreDestroy`, or the pool leaks at shutdown.
> The hook must be `public` (the generated factory calls it from `io.tiko.generated`):
>
> ```java
> @Component(scope = Scope.SINGLETON)
> public class H2PoolFactory {
>     private JdbcConnectionPool pool;
>
>     @Produces(scope = Scope.SINGLETON)
>     public DataSource dataSource(DbConfig config) {
>         pool = JdbcConnectionPool.create(config.url(), config.username(), config.password());
>         return pool;
>     }
>
>     @PreDestroy
>     public void close() { if (pool != null) pool.dispose(); }
> }
> ```

## EVENT-scoped Connection + auto-proxy

```java
@Component(scope = Scope.EVENT)
public class JdbcConnectionProvider {
    private final DataSource ds;

    @Inject JdbcConnectionProvider(DataSource ds) { this.ds = ds; }

    @Produces(scope = Scope.EVENT)
    public Connection connection() throws SQLException {
        var c = ds.getConnection();
        c.setAutoCommit(false);
        return c;
    }
}
```

The interesting part — repositories inject `Connection` directly:

```java
@Component(scope = Scope.SINGLETON)
public class OrderRepository {
    private final Connection connection;   // ← proxy

    @Inject OrderRepository(Connection connection) { this.connection = connection; }

    public Optional<Order> findById(UUID id) throws SQLException {
        try (var ps = connection.prepareStatement("SELECT ...")) { ... }
    }
}
```

`java.sql.Connection` is an interface. The Tiko annotation processor
notices that a SINGLETON consumer wants an EVENT-scoped bean, and
generates a per-method delegating proxy. Every call on the proxy
resolves to the current unit's `Connection`. The repository
looks like it captured a connection at construction time; it didn't,
and that's the point.

If you call repository methods outside an open unit of work, the
proxy fails with `NoActiveEventScopeException` — the right behaviour:
you asked for a per-unit resource with no unit open.

## TransactionContext + decorator

Commit/rollback responsibility lives in a tiny EVENT-scoped bean:

```java
@Component(scope = Scope.EVENT)
public class TransactionContext implements AutoCloseable {
    private final Connection connection;
    private boolean committed = false;
    private boolean rolledBack = false;

    @Inject TransactionContext(Connection connection) { this.connection = connection; }

    public void commit() throws SQLException { connection.commit(); committed = true; }
    public void rollback() throws SQLException { connection.rollback(); rolledBack = true; }

    @Override public void close() throws SQLException {
        if (!committed && !rolledBack) connection.rollback();
        // Tiko's implicit-AutoCloseable on the @Produces Connection returns it to the pool.
    }
}
```

The `committed`/`rolledBack` flags are the safety net: if handler code
forgets to commit, scope teardown rolls back rather than silently
leaving the transaction dangling.

The intended commit path is a thin static helper:

```java
public final class TransactionalScope {
    public static <T> T run(Container container, Supplier<T> work) {
        return container.supplyInEventScope(() -> {
            var tx = container.get(TransactionContext.class);
            try {
                T result = work.get();
                tx.commit();
                return result;
            } catch (RuntimeException e) {
                rollbackQuietly(tx, e); throw e;
            } catch (Throwable t) {
                rollbackQuietly(tx, t); throw new RuntimeException(t);
            }
        });
    }
}
```

Why a utility instead of a Javalin-specific decorator: this generalises
across transports. The batch entry uses the same `run(...)`.

## HTTP single-request flow

```java
Javalin app = Javalin.create(cfg -> cfg.routes.post("/orders", ctx -> TransactionalScope.run(container, () -> {
    routes.handleCreate(ctx);
    return null;
})));
```

One HTTP request = one EVENT scope = one transaction — one operation
per request. The route handler does its work via
auto-proxied repositories; commit happens on success, rollback on any
thrown exception.

## Batch flow — one unit of work, one transaction, a loop

```java
TransactionalScope.run(container, () -> {
    var repo = container.get(OrderRepository.class);
    var audit = container.get(BatchAuditLogger.class);
    for (Order o : orders) {
        repo.insert(o);          // same proxied Connection, same transaction
        audit.record(o.id());    // per-item observation, called directly
    }
    return orders.size();
});
```

**One EVENT scope → one transaction → N inserts.** The `Connection` is
EVENT-scoped, so every insert runs on the same connection in the one
transaction: either every order commits, or none of them do.

EVENT scopes don't nest (opening one inside another throws
`IllegalStateException`), so there is no per-item scope to hook into.
Per-item work — here `BatchAuditLogger.record(...)` — is called from the
loop body. When items are genuinely independent and each should commit
on its own, give each its own unit of work (one `TransactionalScope.run`
per item) and coordinate across them above the DI layer (outbox, saga).

## Async handlers already have their own unit of work

`@EventHandler(async = true)` runs on Tiko's framework executor, and each
invocation runs inside its **own fresh EVENT unit** (see
[events.md](../events.md#async-handlers-own-their-unit-of-work)). Its
EVENT-scoped `Connection` and `TransactionContext` are therefore that
invocation's own — a separate connection and transaction from the
publisher's.

Don't wrap the handler body in `TransactionalScope.run(...)`: that opens
a second unit inside the handler's, and EVENT scopes don't nest. Commit
through the unit's `TransactionContext` before the handler returns; if it
doesn't, `TransactionContext.close()` rolls back when the unit tears down.

## Simplifications this cookbook makes

- **Schema management** — `src/main/resources/schema.sql` loaded by a
  `@PostConstruct` runner. Production should use **Flyway** or
  **Liquibase**.
- **Test database** — H2 in-memory with `MODE=PostgreSQL`. Production
  tests should use **Testcontainers PostgreSQL** for prod-like
  semantics (H2 covers most basics but not every PG-ism).
- **No connection-leak diagnostics** beyond what HikariCP gives you out
  of the box. Production setups configure `leakDetectionThreshold`.
- **No metrics** beyond Tiko's built-in `EventStartedEvent` /
  `EventEndingEvent`. Wire Micrometer or your metrics library of
  choice to those events.

## Beyond raw JDBC

For higher-level abstractions on top of the wiring this cookbook
teaches, the recommended pointers are:

- **[JOOQ](https://www.jooq.org/)** — type-safe SQL DSL with generated
  code. Strongest philosophical neighbor for Tiko: compile-time +
  generated, no runtime reflection. Trade-off: needs a Maven codegen
  step.
- **[JDBI 3](https://jdbi.org/)** — annotation-driven SQL mapper,
  lighter than full ORM. Trade-off: runtime reflection on mapper
  interfaces.
- **[Hibernate](https://hibernate.org/)** — full ORM. The most popular
  Java persistence library. Trade-off: reflection-heavy, the most
  distant fit for Tiko's "no runtime reflection" positioning. Included
  as a pointer because it's the dominant choice, not as a
  recommendation.

Whatever you pick, the wiring stays the same shape: a SINGLETON
`@Produces` factory for the connection/session source, an EVENT-scoped
`@Produces` for the per-unit handle, an auto-proxied interface
injected into SINGLETON repositories.
