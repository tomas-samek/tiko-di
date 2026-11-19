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

## One unit of work per request (the middleware)

Every route is registered via `TikoJavalin.scoped(unitOfWork, handler)`, a
tiny `Handler` decorator that opens one unit of work (an EVENT scope) around the entire
delegate body — including body parsing, the handler, the event publish, and
**response serialization** (so a custom Jackson serializer needing DI can
still reach Tiko-managed beans).

This gives the example three things for free:

- `EventStartedEvent` / `EventEndingEvent` fire automatically for every HTTP
  request. `RequestTimer` subscribes to demonstrate per-request observability
  with zero per-route boilerplate.
- EVENT-scoped beans (here: `RequestIdImpl`) are reachable from inside the
  handler chain. The bridge, a SINGLETON, takes `RequestId` in its constructor
  and gets a proxy that resolves the current request's instance; it stamps
  each request's UUID onto the published `TicketCreated` event.
- Sync `@EventHandler` subscribers run inside the same unit and can read
  EVENT-scoped beans directly. Async subscribers run on a different thread in
  their own fresh unit, so they don't see the request's beans and read whatever
  they need from the event payload.

## The container owns the server

`TicketHttpServer` is a SINGLETON `@Component` that takes the route group and
`HttpSettings`. The container builds it when it starts, and it binds its port in
`@PostConstruct`: a port already in use fails `Tiko.create(...)`. It stops on
`ApplicationEndingEvent`, which `close()` publishes before it waits for async
handlers, so the drain `Main` demonstrates runs with no new request coming in.
The [HTTP cookbook](../../docs/cookbooks/http.md) explains the shape.

## Build and run

```bash
mvn -pl tiko-examples/09_http_javalin -am package
java -jar tiko-examples/09_http_javalin/target/09_http_javalin-*.jar &

# In another shell:
curl -X POST http://localhost:8080/tickets \
     -H 'Content-Type: application/json' \
     -d '{"title":"my first ticket"}'

curl http://localhost:8080/tickets/<id-from-the-previous-response>
```

Default port is `8080` (`http.port` in `config.yaml`); override with
`TIKO_HTTP_PORT=9090 java -jar ...`.

## Files at a glance

- `TicketService` — business logic. Pure Tiko bean, **no HTTP imports**.
- `TicketHttpRoutes` — bridge, a SINGLETON `@Component`. Plain straight-line
  handlers; `register(RoutesConfig)` wraps each in the decorator, so the
  `runInEventScope` lives there, not in the handlers.
- `TicketHttpServer` — the HTTP server the container starts and stops;
  `HttpSettings` — its port.
- `TikoJavalin` — `Handler scoped(UnitOfWork, Handler)`. The middleware.
- `RequestIdImpl` — EVENT-scoped `@Component` implementing `RequestId`.
  `TicketHttpRoutes` takes `RequestId` and gets Tiko's auto-proxy, which
  resolves the instance of the unit `TikoJavalin.scoped` opened.
- `RequestTimer` — `@EventHandler` on `EventStartedEvent` /
  `EventEndingEvent`. Demonstrates the framework's lifecycle events.
- `AuditLogger`, `MetricsCounter`, `NotificationSender` — the three
  side-effect handlers (two sync, one async).
- `TicketCreatedRecorder` — test scaffold; a sync `@EventHandler` that captures
  every published `TicketCreated` so the integration test can assert per-request
  `requestId` distinctness. Lives in main sources so the annotation processor
  wires it like any other subscriber.
- `Main` — bootstrap and the drain demo.

## What this example deliberately does NOT show

- **A native HTTP transport.** A future direction is a Tiko-native HTTP layer
  with compile-time request-response pipeline detection — discussed in the
  design notes but not yet on the roadmap. The point of this example is that
  Tiko already integrates cleanly with whatever HTTP server you already have.
- **Authentication, middleware chains, TLS.** Layer those in via Javalin's own
  facilities; they're orthogonal to the Tiko integration.
- **Multiple HTTP servers.** Javalin is one choice; the same pattern ports to
  Helidon Nima, Jetty embedded, the JDK's `HttpServer`, etc. Swap the import
  and the registration syntax; everything else stays.
