# 09 — HTTP + Javalin Integration

How to put a Tiko-managed application behind an existing HTTP server, with a
clean separation between the **sync request → response path** (what the
client is waiting on) and **event-bus side effects** (audit, metrics,
notifications — the work that happens *because of* the request but the
response doesn't depend on).

## The dichotomy

Every HTTP request flows through two parallel tracks:

| Track | What runs | Wait? |
|---|---|---|
| **Sync request → response** | `TicketService.create` / `find`, response serialization | Yes — the HTTP client is on the wire |
| **Event-bus side effects** | `AuditLogger`, `MetricsCounter` (sync); `NotificationSender` (`async = true`) | No — the response is sent before async side effects finish |

The bridge layer (`TicketHttpRoutes`) does the minimum work the client is
waiting on, then publishes one `TicketCreated` event. Side effects subscribe
via `@EventHandler`. The HTTP response goes out as soon as the sync side
finishes — async side effects keep running on Tiko's framework executor.

## Request-scope wrapping (the middleware)

Every route is registered via `TikoJavalin.scoped(container, handler)`, a
six-line `Handler` decorator that opens a Tiko request scope around the entire
delegate body — including body parsing, the handler, the event publish, and
**response serialization** (so a custom Jackson serializer needing DI can
still reach Tiko-managed beans).

This gives the example three things for free:

- `RequestStartedEvent` / `RequestEndingEvent` fire automatically every HTTP
  request. `RequestTimer` subscribes to demonstrate per-request observability
  with zero per-route boilerplate.
- REQUEST-scoped beans (here: `RequestIdImpl`) are reachable from inside the
  handler chain. The bridge resolves `RequestId` per-request via
  `container.get(RequestId.class)` from inside the open scope, and stamps
  each request's UUID onto the published `TicketCreated` event.
- Sync `@EventHandler` subscribers run inside the same scope and can read
  REQUEST-scoped beans directly. Async subscribers run on a different thread,
  don't see the scope, and read whatever they need from the event payload.

## Build and run

```bash
mvn -pl tiko-examples/09_http_javalin -am package
java -jar tiko-examples/09_http_javalin/target/09_http_javalin-0.1.0.jar &

# In another shell:
curl -X POST http://localhost:8080/tickets \
     -H 'Content-Type: application/json' \
     -d '{"title":"my first ticket"}'

curl http://localhost:8080/tickets/<id-from-the-previous-response>
```

Default port is `8080`; override with `TIKO_HTTP_PORT=9090 java -jar ...`.

## Files at a glance

- `TicketService` — business logic. Pure Tiko bean, **no HTTP imports**.
- `TicketHttpRoutes` — bridge. The only file in the module that imports both
  `io.tiko` and `io.javalin`. Plain straight-line handlers; the
  `runInRequestScope` lives in the decorator, not here.
- `TikoJavalin` — `Handler scoped(Container, Handler)`. The middleware.
- `RequestIdImpl` — REQUEST-scoped `@Component` implementing `RequestId`.
  Resolved per-request via `container.get(RequestId.class)` from inside the
  scope opened by `TikoJavalin.scoped`. (Tiko's auto-proxy mechanism for
  REQUEST→SINGLETON injection is demonstrated in `01_basic_di`; this example
  uses container-lookup because the bridge isn't a `@Component`.)
- `RequestTimer` — `@EventHandler` on `RequestStartedEvent` /
  `RequestEndingEvent`. Demonstrates the framework's lifecycle events.
- `AuditLogger`, `MetricsCounter`, `NotificationSender` — the three
  side-effect handlers (two sync, one async).
- `Main` — bootstrap.

## What this example deliberately does NOT show

- **A native HTTP transport.** That's the follow-up issue tracked separately
  ("Issue 2" — native HTTP with compile-time request-response pipeline
  detection). The point of this example is that Tiko integrates cleanly with
  whatever HTTP server you already have.
- **Authentication, middleware chains, TLS.** Layer those in via Javalin's own
  facilities; they're orthogonal to the Tiko integration.
- **Multiple HTTP servers.** Javalin is one choice; the same pattern ports to
  Helidon Nima, Jetty embedded, the JDK's `HttpServer`, etc. Swap the import
  and the registration syntax; everything else stays.
