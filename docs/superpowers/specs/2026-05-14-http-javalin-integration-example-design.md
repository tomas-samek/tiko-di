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
      TikoJavalin.java
      TicketService.java
      TicketHttpRoutes.java
      CreateTicketRequest.java
      Ticket.java
      TicketCreated.java
      RequestId.java
      RequestIdImpl.java
      RequestTimer.java
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

**`TicketHttpRoutes`** — the bridge layer. Injects `EventBus`, `TicketService`,
and `RequestId` (a REQUEST-scoped bean — automatic proxy generation kicks in
for the cross-scope injection). Exposes two methods:

- `handleCreate(io.javalin.http.Context ctx)` — parses `CreateTicketRequest`
  from the JSON body, calls `ticketService.create(...)`, publishes
  `TicketCreated` on the event bus (stamped with `requestId.value()`), writes
  the created `Ticket` to the response, sets status 201.
- `handleGet(io.javalin.http.Context ctx)` — parses the `{id}` path parameter,
  calls `ticketService.find(...)`, writes the result to the response (200 if
  present, 404 if absent). **Does not publish any event** — by design, to make
  the "events are a choice, not automatic" point obvious.

The bridge methods are plain straight-line code — **they do not call
`runInRequestScope` themselves**. The request scope is applied as middleware at
registration time via `TikoJavalin.scoped(...)` (see below). This is the
ergonomic point: the user writes business code, the helper opens and closes
the scope around every matched handler.

Each method takes a single `io.javalin.http.Context` parameter and returns
void, matching Javalin's `Handler` contract by method reference. The class is
`final`.

**`TikoJavalin`** — final utility class with a single static method,
`Handler scoped(Container container, Handler delegate)`. Wraps a Javalin
`Handler` so that each invocation runs inside `container.runInRequestScope(...)`.
Translates the `Handler`'s checked-exception signature into the `Runnable`
shape `runInRequestScope` accepts (catch, wrap in `RuntimeException`, let
Javalin's exception mapping unwrap on its side). Six lines of real code, plus
imports — this is the "middleware" piece of the integration. Lives in the
example module rather than a shared library because Issue 1 deliberately ships
no framework code; if real users hit ergonomic friction we promote it to a
`tiko-http-bridge` module in a follow-up.

**`RequestId`** — interface with one method `String value()`. Two reasons it
exists: (a) cross-scope injection from SINGLETON into a REQUEST-scoped bean
requires an interface so the framework can generate a proxy, (b) it makes the
scoped state explicit at injection points.

**`RequestIdImpl`** — `@Component(scope = Scope.REQUEST)` implementing
`RequestId`. Generates a UUID at construction (once per HTTP request, since
the scope is opened once per `runInRequestScope` call). Both the bridge and
any other scoped collaborator can read the same per-request value.

**`RequestTimer`** — `@Component(scope = Scope.SINGLETON)`. Subscribes to
Tiko's framework lifecycle events with two `@EventHandler` methods:
`onRequestStarted(RequestStartedEvent)` and
`onRequestEnding(RequestEndingEvent)`. Logs `[REQ <framework-id>] started` and
`[REQ <framework-id>] completed in <duration>`. Demonstrates that **the
lifecycle events fire automatically for every HTTP request** — no per-route
wiring needed. Note: the framework's `RequestStartedEvent.requestId()` is a
distinct identifier from the application's `RequestId.value()`; both exist on
purpose. The framework ID identifies the *scope instance*; the application ID
is whatever the app considers a correlation key (could be derived from an
incoming `X-Request-Id` header, generated fresh, etc.). The example
generates fresh for simplicity.

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
4. Register routes wrapped by the middleware decorator:
   `app.post("/tickets", TikoJavalin.scoped(container, routes::handleCreate))`
   and
   `app.get("/tickets/{id}", TikoJavalin.scoped(container, routes::handleGet))`.
5. Read port from `TIKO_HTTP_PORT` env var (default 8080).
6. `app.start(port);`
7. Register a JVM shutdown hook that stops Javalin first (drains in-flight
   requests), then calls `container.shutdown()` (runs Tiko's `@PreDestroy` /
   `AutoCloseable.close()` cleanup, fires `ApplicationEndingEvent`).

## Middleware: applying the request scope via a Handler decorator

Every HTTP request to a registered route runs inside a Tiko request scope —
not because each bridge method opens one, but because the registration goes
through `TikoJavalin.scoped(container, handler)`. In `Main`:

```java
app.post("/tickets",     TikoJavalin.scoped(container, routes::handleCreate));
app.get("/tickets/{id}", TikoJavalin.scoped(container, routes::handleGet));
```

`TikoJavalin.scoped(...)` returns a new `Handler` whose `handle(ctx)`
method calls `container.runInRequestScope(() -> delegate.handle(ctx))`. From
Javalin's perspective it's just a `Handler` like any other; from Tiko's
perspective the scope is open for the entire delegate invocation.

The decorator covers the **entire** request lifecycle — body parsing,
business call, event publish, response serialization — for three reasons that
matter independently:

1. **Lifecycle events fire automatically.** `RequestStartedEvent` is published
   at scope entry and `RequestEndingEvent` (with `Duration`) at exit. The
   example wires `RequestTimer` to demonstrate this; users get per-request
   observability with zero per-route boilerplate.
2. **REQUEST-scoped beans are reachable inside the handler chain.** `RequestId`
   in the example demonstrates this; future apps will plug in their own
   per-request context (authenticated principal, correlation header, tenant
   selector). Without the decorator, any REQUEST-scoped injection from inside
   the bridge would throw "no scope active."
3. **Response serialization sees the scope too.** This is the subtle one. A
   custom Jackson serializer, a HATEOAS link builder, or a computed response
   field that delegates to a Tiko bean will run during `ctx.json(value)` —
   *after* the bridge has finished its own logic but *before* Javalin actually
   writes bytes. That serialization callback runs on the same thread as the
   bridge. If the scope exits before `ctx.json(...)` completes, anything
   DI-driven inside the serializer fails. The decorator pattern guarantees
   the scope stays open across the full delegate body, including serialization.

Exceptions thrown from any step propagate normally: the decorator catches the
Javalin `Handler`'s checked-exception declaration and wraps in
`RuntimeException`; `runInRequestScope` runs scope teardown (and
`RequestEndingEvent`) in a finally block regardless; the `RuntimeException`
re-surfaces to Javalin, which applies the user's configured exception mapper
and writes whatever error response that mapper produces. Cleanup is guaranteed
in all paths.

Sync `@EventHandler` subscribers fired by `EventBus.publish(...)` run on the
caller's thread and therefore *also* see the scope — they can read scoped
beans freely. Async subscribers run on the framework executor, on a different
thread, and the scope is thread-local — they cannot see it. Async handlers
that need request data read it from the event payload (which is why the
bridge stamps `requestId` onto `TicketCreated` — see "Event payload sourcing"
below).

## Data flow

**`POST /tickets`** — sync path with side effects, all inside one request
scope opened by the `TikoJavalin.scoped(...)` decorator:

1. Javalin's worker thread invokes the wrapped `Handler`.
2. The decorator calls `container.runInRequestScope(() -> delegate.handle(ctx))`.
   The framework publishes `RequestStartedEvent`;
   `RequestTimer.onRequestStarted` logs the start.
3. Inside the scope, the decorator's delegate is `routes::handleCreate`, so
   `handleCreate(ctx)` runs.
4. The bridge calls `ctx.bodyAsClass(CreateTicketRequest.class)`. Javalin (via
   Jackson) parses the JSON body.
5. The bridge calls `ticketService.create(req)`. The new `Ticket` is created
   and stored. The bean's method returns it.
6. The bridge reads the per-request UUID from the proxied `RequestId` bean
   and publishes `new TicketCreated(ticket.id(), ticket.title(),
   requestId.value(), Instant.now())` on the event bus.
7. `EventBus.publish` invokes sync subscribers in registration order:
   `AuditLogger` writes to stdout; `MetricsCounter` increments its `AtomicLong`.
   Both run inside the request scope.
8. The framework also schedules `NotificationSender.onTicketCreated` on the
   async executor — `publish` returns immediately after scheduling. The async
   handler will run later on a different thread; it reads `requestId` from
   the event payload, not from the (now-unavailable) scope.
9. Still inside the scope, the bridge calls `ctx.status(201).json(ticket)`.
   Jackson serializes (any DI-aware serializer machinery is reachable).
10. The bridge returns to the decorator, which exits the scope. The framework
    publishes `RequestEndingEvent` with the accumulated `Duration`;
    `RequestTimer.onRequestEnding` logs completion. Scoped beans
    (`RequestIdImpl`) are torn down.
11. Javalin sends the response bytes. The HTTP client unblocks with 201 +
    JSON body.
12. `NotificationSender` finishes its work whenever the executor gets to it.
    The client has been responded to already.

**`GET /tickets/{id}`** — pure sync, no domain events, but **still inside a
request scope** because the registration goes through the same
`TikoJavalin.scoped(...)` decorator:

1. Javalin invokes the wrapped `Handler`; decorator opens the scope.
2. `routes::handleGet` runs. The bridge reads the path param, calls
   `ticketService.find(id)`, and either writes `Ticket` JSON with status 200
   (serialization inside the scope) or sets status 404 with an empty body.
3. Bridge returns; decorator exits the scope; `RequestEndingEvent` fires;
   `RequestTimer` logs.
4. No domain event was published. **No `TicketCreated` subscribers fire.**
   The point is to show that the event bus is not implicitly involved in
   every endpoint — but the lifecycle events are.

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
consistent record. The example demonstrates this with the `requestId` field
on `TicketCreated`: the bridge reads it from the REQUEST-scoped `RequestId`
bean and stamps it onto the event. Async subscribers running off-thread can
still read the original request's ID from the payload, even though the
request scope has already torn down by then.

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

Four test methods:

- `postCreatesTicketAndReturns201` — `POST /tickets` with a valid body; asserts
  201, response body has an `id`, `MetricsCounter.count()` increased by exactly
  one, and `NotificationSender`'s `CountDownLatch` decremented within a generous
  timeout (`Awaitility` already on the example classpath via `tiko-runtime`'s
  test deps — or pulled in fresh; check whichever is cheaper).
- `getReturnsTicketAfterPost` — POST a ticket, capture its `id`, GET it,
  assert the body matches. Asserts the `requestId` on the recorded `TicketCreated`
  event differs across two separate POSTs (proving REQUEST-scoped state is
  truly per-request).
- `getReturns404ForUnknownId` — GET a random UUID, assert 404, assert
  `MetricsCounter.count()` did NOT change (proving the read path doesn't fire
  domain events).
- `lifecycleEventsFireForEveryHttpRequest` — counts the number of
  `RequestStartedEvent`s observed by `RequestTimer` (or by a test-only
  subscriber injected via a custom `ErrorHandler`-style hook) and asserts it
  matches the number of HTTP requests made, including the GET.

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
