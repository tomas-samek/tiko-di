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
REQUEST scope = transaction lifetime, auto-proxy of REQUEST-scoped
resources into SINGLETON consumers. This cookbook shows that wiring with
raw JDBC + HikariCP — the lowest layer, easiest to follow. Higher-level
libraries layer on top of the same scaffolding.

## What you'll learn

1. **REQUEST = one DB transaction.** Open a REQUEST scope; everything
   inside it runs in one transaction; commit on clean exit, roll back
   on exception.
2. **EVENT = single message being processed.** Inside one batch (one
   REQUEST), multiple messages each get their own EVENT scope and their
   own per-message state — but share the one outer transaction.
3. **Auto-proxy on `java.sql.Connection`.** A SINGLETON repository can
   inject the REQUEST-scoped Connection directly; Tiko's annotation
   processor generates a proxy that resolves to the current scope on
   every method call.
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

## REQUEST-scoped Connection + auto-proxy

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
notices that a SINGLETON consumer wants a REQUEST-scoped bean, and
generates a per-method delegating proxy. Every call on the proxy
resolves to the current REQUEST scope's `Connection`. The repository
looks like it captured a connection at construction time; it didn't,
and that's the point.

If you call repository methods outside an active REQUEST scope, the
proxy fails with a scope-resolution error — the right behaviour: you
asked for a request-scoped resource without an open request.

## TransactionContext + decorator

Commit/rollback responsibility lives in a tiny REQUEST-scoped bean:

```java
@Component(scope = Scope.REQUEST)
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
        return container.supplyInRequestScope(() -> {
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
app.post("/orders", ctx -> TransactionalScope.run(container, () -> {
    routes.handleCreate(ctx);
    return null;
}));
```

One HTTP request = one REQUEST scope = one transaction. REQUEST and
EVENT collapse to the same lifetime here — there's no batching, just
one operation per request. The route handler does its work via
auto-proxied repositories; commit happens on success, rollback on any
thrown exception.

## Batch flow — where REQUEST and EVENT do different jobs

```java
TransactionalScope.run(container, () -> {
    var repo = container.get(OrderRepository.class);
    for (Order o : orders) {
        container.runInEventScope(() -> {
            // CurrentOrder is auto-proxied — container.get returns a proxy
            // wired to the current EVENT scope's CurrentOrderContext.
            var current = container.get(CurrentOrder.class);
            current.setOrderId(o.id());
            repo.insert(o);
        });
    }
    return orders.size();
});
```

**One REQUEST → one transaction → N EVENT scopes inside.** The
distinction earns its keep here:

- The `Connection` is REQUEST-scoped, so all N inserts run on the same
  connection in one transaction. Either every order commits, or none of
  them do.
- `CurrentOrderContext` is EVENT-scoped, so each iteration gets its own
  instance with its own `orderId`. `BatchAuditLogger` is a SINGLETON
  `@EventHandler(EventEndingEvent.class)` that injects `CurrentOrder` as
  a proxy and reads the current iteration's id at scope-end — no
  parameter threading.

This is also the first place in the examples tree where auto-proxy is
shown on an **EVENT-scoped** bean (REQUEST-scoped auto-proxy was already
shown in this cookbook's repository pattern). The same processor
mechanism handles both. Subscribing at `EventEndingEvent` rather than
`EventStartedEvent` is deliberate: the body of `runInEventScope` runs
between those two events, so reads of `CurrentOrder.orderId()` must
happen *after* the body has populated it.

## Async handlers + explicit REQUEST scope

`@EventHandler(async = true)` runs on Tiko's framework executor — a
different thread, no enclosing REQUEST scope. If the async handler
needs to touch the DB, it opens its own:

```java
@EventHandler(async = true)
public void onSomeEvent(SomeEvent e) {
    TransactionalScope.run(container, () -> {
        // persistence work — gets its own connection + transaction
        return null;
    });
}
```

No auto-elevation. This matches Tiko's "no runtime magic" positioning:
the transaction boundary is visible at the call site, not implied by
ambient state.

## Simplifications this cookbook makes

- **Schema management** — `src/main/resources/schema.sql` loaded by a
  `@PostConstruct` runner. Production should use **Flyway** or
  **Liquibase**.
- **Test database** — H2 in-memory with `MODE=PostgreSQL`. Production
  tests should use **Testcontainers PostgreSQL** for prod-like
  semantics (H2 covers most basics but not every PG-ism).
- **No connection-leak diagnostics** beyond what HikariCP gives you out
  of the box. Production setups configure `leakDetectionThreshold`.
- **No metrics** beyond Tiko's built-in `RequestStartedEvent` /
  `RequestEndingEvent`. Wire Micrometer or your metrics library of
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
`@Produces` factory for the connection/session source, a REQUEST-scoped
`@Produces` for the per-request handle, an auto-proxied interface
injected into SINGLETON repositories.
