# HTTP + Javalin integration example — design

Status: draft (2026-05-14). Issue 1 of two for Tiko's HTTP story. Issue 2 (native
HTTP transport with compile-time request-response pipeline detection) is captured
separately and lands later under Phase 4. This document specifies Issue 1 only:
a pure example + docs that show how Tiko integrates with an already-existing HTTP
server.

## Context

Tiko's event-driven model is a good fit for HTTP services, but the integration
isn't obvious to a new user. Two things confuse people on day one:

1. **HTTP is sync request-response.** A request expects a response on the same
   connection. Tiko's event bus is fire-and-forget — publishing an event doesn't
   produce a return value the HTTP handler can wait on. Naive readers try to
   force the event bus to carry the response and end up with correlation IDs,
   timeouts, and broken mental models.
2. **HTTP responses are often a thin slice of the work.** Creating an order is
   *also* an audit-log entry, a metrics counter, an outbound email, a cache
   invalidation. Some of those need to land before the HTTP response; most
   don't. Users coming from Spring conflate them.

This example exists to teach the dichotomy with code that runs:

- **The main request → response path** is a synchronous Tiko-injected method
  call. No event bus involved. The HTTP framework's handler invokes it directly.
- **Event-based side effects** are everything that happens *because of* the
  request but the response doesn't depend on. The handler publishes one event;
  multiple subscribers react; the HTTP response is unaffected.

Both halves use Tiko's existing public API. No new modules, no new annotations,
no new processor work. The deliverable is a runnable example + docs.

## Goals

- Show a real Tiko `Container` running alongside an embedded Javalin HTTP server
  in one JVM, with no friction in the wiring.
- Make the sync-vs-async-side-effects dichotomy unmistakable in both the code
  and the README.
- Mirror the structural conventions of `08_kafka_order_warehouse` so a reader
  familiar with the Kafka example recognises the bridge-class pattern.
- End-to-end runnable via `mvn package && java -jar ...shaded.jar`, plus an
  in-JVM integration test that exercises `POST` and `GET` over a real socket.

## Non-goals

- No `@HttpRoute` / `@HttpSource` / `@HttpSink` annotations. That's Issue 2.
- No new module in `tiko-api`, `tiko-runtime`, `tiko-config`, or the processor.
  If genuine ergonomic friction surfaces in real use, a small `tiko-http-bridge`
  module becomes a follow-up; speculatively shipping helpers before that
  evidence is YAGNI.
- No multi-JVM topology, no Docker, no Testcontainers. The integration test
  uses a randomly bound port in the same JVM.
- No authentication, middleware, TLS, observability dashboards. Side effects in
  the example are limited to audit / metrics / mock notifications — enough to
  show the pattern, not a production checklist.
- No `@Configuration` record. The port is hardcoded with one env-var override.
  Adding config injection would be educational, but it's a separate teaching
  concern from the sync-vs-events dichotomy this example exists to teach.

## Module layout

One new Maven module:

```
tiko-examples/
  09_http_javalin/
    pom.xml
    src/main/java/io/tiko/examples/http/
      Main.java
      TicketService.java
      TicketHttpRoutes.java
      CreateTicketRequest.java
      Ticket.java
      TicketCreated.java
      AuditLogger.java
      MetricsCounter.java
      NotificationSender.java
    src/test/java/io/tiko/examples/http/
      TicketServiceTest.java
      TicketHttpIntegrationTest.java
    README.md
```

Dependencies: `io.tiko:tiko-api`, `tiko-runtime`, `tiko-processor` (provided),
`io.javalin:javalin` (one jar plus its own slim deps, current 6.x).
`maven-shade-plugin` produces a runnable jar mirroring the order-service /
warehouse-service shading in `08_kafka_order_warehouse`.

## Components

The bridge layer is where the HTTP and event-bus worlds meet — business beans
(`TicketService`) stay HTTP-free; `TicketHttpRoutes` is the only file that
imports both `io.tiko` and `io.javalin`. Every Tiko bean below is
`@Component(scope = Scope.SINGLETON)` unless noted; `Main` is a plain class
with `main(String[] args)` and is NOT a Tiko bean (it instantiates the
container, not the other way around).

**`TicketService`** — business logic. Stores `Ticket`s in a thread-safe
`ConcurrentHashMap<UUID, Ticket>`. Methods: `Ticket create(CreateTicketRequest)`
and `Optional<Ticket> find(UUID)`. **Has no Javalin or HTTP imports.** This is
the bean a future Issue-2 native-HTTP refactor would target as the handler.

**`TicketHttpRoutes`** — the bridge layer. Injects `TicketService` and
`EventBus`. Exposes two methods:

- `handleCreate(io.javalin.http.Context ctx)` — parses
  `CreateTicketRequest` from the JSON body, calls `ticketService.create(...)`,
  publishes `TicketCreated` on the event bus, writes the created `Ticket` to
  the response, sets status 201.
- `handleGet(io.javalin.http.Context ctx)` — parses the `{id}` path parameter,
  calls `ticketService.find(...)`, writes the result to the response (200 if
  present, 404 if absent). **Does not publish any event** — by design, to make
  the "events are a choice, not automatic" point obvious.

Each method takes a single `io.javalin.http.Context` parameter and returns
{@code void}, matching Javalin's `Handler` contract by method reference. The
class is `final`.

**`AuditLogger`** — `@EventHandler` on `TicketCreated`, synchronous. Writes
`[AUDIT] ticket <id> '<title>' created at <timestamp>` to stdout. Runs inline
before `EventBus.publish` returns.

**`MetricsCounter`** — `@EventHandler` on `TicketCreated`, synchronous.
Increments an `AtomicLong`. Exposes `long count()` for the integration test to
observe. Runs inline.

**`NotificationSender`** — `@EventHandler(async = true)` on `TicketCreated`.
Logs `[NOTIFY] would email about ticket <id>` after a tiny intentional delay
(documented in the README as "simulated email send latency"). Runs on the
framework's async executor; the HTTP handler does not wait on it. Exposes a
`CountDownLatch` for the integration test to wait on without a sleep.

**`Main`** — bootstrap. `public static void main(String[] args)` does:

1. Construct the Tiko `Container` via `Tiko.create()`. All singletons including
   `TicketHttpRoutes` get constructed; `ApplicationStartedEvent` fires.
2. `TicketHttpRoutes routes = container.get(TicketHttpRoutes.class);`
3. Construct a `Javalin` app, configure JSON via Jackson (Javalin's default).
4. Register routes: `app.post("/tickets", routes::handleCreate)` and
   `app.get("/tickets/{id}", routes::handleGet)`.
5. Read port from `TIKO_HTTP_PORT` env var (default 8080).
6. `app.start(port);`
7. Register a JVM shutdown hook that stops Javalin first (drains in-flight
   requests), then calls `container.shutdown()` (runs Tiko's `@PreDestroy` /
   `AutoCloseable.close()` cleanup, fires `ApplicationEndingEvent`).

## Data flow

**`POST /tickets`** — sync path with side effects:

1. Javalin receives the request, parses the JSON body into `CreateTicketRequest`
   via Jackson.
2. `routes.handleCreate(ctx)` runs on a Javalin worker thread.
3. It calls `ticketService.create(req)`. The new `Ticket` is created and
   stored. The bean's method returns it.
4. The bridge method publishes `new TicketCreated(ticket.id(), ticket.title(),
   Instant.now())` on the event bus.
5. `EventBus.publish` invokes sync subscribers in registration order:
   `AuditLogger` writes to stdout; `MetricsCounter` increments its `AtomicLong`.
6. The framework also schedules `NotificationSender.onTicketCreated` on the
   async executor — `publish` returns immediately after scheduling.
7. The bridge writes the created `Ticket` to `ctx` as JSON, sets status 201.
8. Javalin sends the response. The HTTP client unblocks with 201 + JSON body.
9. `NotificationSender` finishes its work whenever the executor gets to it.
   The client has been responded to already.

**`GET /tickets/{id}`** — pure sync, no events:

1. Javalin parses the path param.
2. `routes.handleGet(ctx)` calls `ticketService.find(id)`.
3. If present, writes the `Ticket` JSON with status 200.
4. If absent, sets status 404 with an empty body.
5. No event published. No subscribers fire. The point is to show that **the
   event bus is not implicitly involved in every endpoint**.

## Event payload sourcing

The example publishes `TicketCreated` constructed from the **response side** —
the canonical `Ticket` returned by `ticketService.create(req)`, not from the
raw `CreateTicketRequest`. The principle:

> The event represents *what actually happened*, not *what was asked for*.

Side effects (audit log, metrics, downstream notifications) need the
server-assigned identity and any computed fields — the new UUID, the storage
timestamp, defaulted values. The raw request lacks all of that, and feeding
subscribers a pre-commit view is a footgun: an audit log entry that doesn't
match the row in storage is worse than no entry at all.

When a side effect needs request-only data (the caller's IP, a correlation
header, the raw input for diff-tracking), the bridge stitches those into the
event record as additional fields. The bridge owns the "merge what the request
brought + what the service computed" step; subscribers always see one
consistent record.

**Why this example does not use `@EventTrigger`.** Today's `@EventTrigger`
fires *after* an `@EventHandler` completes — the method's return value becomes
the chained event's payload. Issue 1's bridge does not handle an incoming Tiko
event (the trigger is the HTTP request itself, which Issue 1 deliberately does
not model as a bus event — that's Issue 2). So the bridge publishes
explicitly. This is the same pattern any code uses when an external system
kicks off a workflow without first putting an event on the bus.

**Issue 2 unifies the two paths.** Once a native HTTP transport models a
request as an event in its own right, `@EventTrigger` falls into place: the
bridge method's return value can drive both the HTTP response *and* a chained
event in a single declaration. Issue 1 is the no-magic, public-API-only
version of the same pattern; Issue 2 turns it into a declarative one-liner
with compile-time pipeline analysis.

## Error handling

Two failure modes, each exercising existing Tiko behavior:

**Sync-path failure.** `TicketService.create` validates input and throws
`IllegalArgumentException("title must not be blank")` on bad data. The bridge
method catches once at the top of `handleCreate`, sets `ctx.status(400)` with
a small JSON body `{"error": "<message>"}`. Tiko's `ErrorHandler` is **not**
involved — this is user-domain error handling on the request path, where the
user has to decide HTTP status anyway. Documented as such in the README.

**Event-handler failure.** Tiko's existing event-bus contract handles this:
when a subscriber throws, the framework catches and routes through the
configured `ErrorHandler` (default `WARNING` log via JUL), and the **other
subscribers still fire** (handler-exception isolation already shipped in
PR #44). The HTTP response is unaffected because the response write happens
*after* the `publish` call returns — but the `publish` call returns whether or
not subscribers succeed. The example does not include a deliberately-throwing
fixture; the integration test trusts the behavior already covered by
`tiko-runtime`'s own tests. The README references the existing
`EventHandlerError` contract instead of duplicating it.

## Configuration

- **Port:** `TIKO_HTTP_PORT` env var, default `8080`. Read once in `Main`.
- **Javalin's JSON:** default (Jackson). No override.
- **Tiko side:** no `@Configuration` record. Container is constructed with
  `Tiko.create()` (no `TikoOptions` configured — the defaults are the point).

A `@Configuration record HttpServerConfig(int port, String bindAddress)` is a
natural extension and would be the next thing to add if the example grew. It's
deliberately left out for the MVP so the dichotomy stays the only teaching
goal.

## Testing

Two test classes, both running on the JVM unit-test path (Surefire, no
Failsafe split needed).

**`TicketServiceTest`** — JUnit 5 + AssertJ, no container. Validates business
logic in isolation: `create` returns a ticket with a generated UUID, `find`
returns it back, `find` on unknown ID returns empty. Camel-case test names per
the project rule.

**`TicketHttpIntegrationTest`** — boots a real `Container`, mounts the real
`TicketHttpRoutes` on a real `Javalin` instance bound to port `0` (OS picks).
Uses `java.net.http.HttpClient` to exercise the endpoints.

Three test methods:

- `postCreatesTicketAndReturns201` — `POST /tickets` with a valid body; asserts
  201, response body has an `id`, `MetricsCounter.count()` increased by exactly
  one, and `NotificationSender`'s `CountDownLatch` decremented within a generous
  timeout (`Awaitility` already on the example classpath via `tiko-runtime`'s
  test deps — or pulled in fresh; check whichever is cheaper).
- `getReturnsTicketAfterPost` — POST a ticket, capture its `id`, GET it,
  assert the body matches.
- `getReturns404ForUnknownId` — GET a random UUID, assert 404, assert
  `MetricsCounter.count()` did NOT change (proving the read path doesn't fire
  events).

The integration test boots / tears the container down per-test via JUnit's
`@BeforeEach` / `@AfterEach`. No Testcontainers, no Docker, no external state.

## Documentation

**Module-level `README.md`** — walks the reader through the example with the
sync-vs-events dichotomy as the table of contents. Includes the `mvn package`
+ `java -jar` invocation and example `curl` commands for both endpoints.

**Cross-reference in the main docs.** `docs/event-driven.md` (or whichever
existing page covers the event bus) gains a small section: "Using Tiko behind
an existing HTTP server" with a single paragraph + link to the example module.
The `docs/roadmap.md` ✅-list gains an entry for the shipped example.

## Out of scope (future work, separately tracked)

These are the natural follow-ups; none belong in this MVP:

- **`tiko-http-bridge` helper module** if real-use ergonomic friction shows up.
- **Issue 2 — native HTTP transport with compile-time request-response pipeline
  detection.** Already separately captured; Phase 4 placement.
- **More server adapters** — Helidon Nima, Jetty embedded, JDK
  `com.sun.net.httpserver.HttpServer`. The pattern this example demonstrates
  ports to all of them; second-tier examples can be added one at a time as
  demand surfaces.

## Risks & open questions

- **Javalin major-version churn.** Javalin 6 is current; 7 is on the horizon.
  An example pinned to 6.x will need a bump in the next year-ish. The example's
  glue surface is small (4 lines in `Main` plus two handler methods), so the
  bump is mechanical.
- **`Awaitility` vs project's existing dep matrix.** Confirm during
  implementation: if it's already in tiko-runtime's test deps, reuse; otherwise
  add to the example's test-scope deps. The integration test must not use a
  bare `Thread.sleep` per the project's standing rule.
- **`Main`'s shutdown-hook ordering.** Javalin's `stop()` is synchronous and
  drains in-flight requests; `container.shutdown()` is idempotent and
  drain-safe (#47). Order matters: Javalin first so no request is mid-flight
  during `@PreDestroy`. Implementation plan should make this explicit.

## Acceptance

- A new `tiko-examples/09_http_javalin/` module exists, builds, and produces a
  runnable shaded jar.
- The example is wired into the reactor — `mvn install` from the root builds
  it as part of CI.
- Both endpoints respond correctly to a manual `curl` session.
- The integration test passes on Java 21 / 25 / 26 in CI.
- The README clearly walks through the sync-vs-events dichotomy and the
  three side-effect subscribers.
