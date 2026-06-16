# Events

Tiko's event system is a first-class part of the container. Handler code is the same shape whether the event was published locally or — when a distributed transport ships — arrives from a remote source. This page is the full reference: publish/subscribe, async, error handling, lifecycle events, and the declarative `@EventTrigger` chains.

For a runnable example, see [`tiko-examples/03_events`](../tiko-examples/03_events).

## Publish / subscribe

```java
// Define event
public record UserRegisteredEvent(String userId, String email) {}

// Publish events — inject EventBus directly (it is a built-in dependency).
@Component(scope = Scope.SINGLETON)
public class UserService {
    private final EventBus events;

    @Inject
    public UserService(EventBus events) {
        this.events = events;
    }

    public void registerUser(String email) {
        String userId = createUser(email);
        events.publish(new UserRegisteredEvent(userId, email));
    }
}

// Handle events
@Component(scope = Scope.SINGLETON)
public class NotificationService {
    @EventHandler
    public void onUserRegistered(UserRegisteredEvent event) {
        sendWelcomeEmail(event.email());
    }
}

@Component(scope = Scope.SINGLETON)
public class AnalyticsService {
    @EventHandler
    public void onUserRegistered(UserRegisteredEvent event) {
        trackUserRegistration(event.userId());
    }
}
```

The same handler code works against any `EventBus` implementation. The in-memory bus (`LocalEventBus` in `tiko-runtime`) ships in core. The Kafka transport (`tiko-kafka` + `tiko-kafka-processor`) is a separate module that bridges via `@KafkaSource` / `@KafkaSink` — see [`tiko-examples/08_kafka_order_warehouse`](../tiko-examples/08_kafka_order_warehouse) for a runnable cross-JVM demo. The universal transport-adapter pattern documented in [`docs/superpowers/specs/2026-05-12-kafka-event-bus-design.md`](./superpowers/specs/2026-05-12-kafka-event-bus-design.md) generalises to HTTP / scheduler / file / gRPC.

## Trade-off positions

These are explicit, not accidents of implementation. A handler that depends on different semantics will be wrong on at least one transport.

- **Delivery semantics — at-least-once to the bus, at-most-once per handler execution.** Delivery is at-least-once: a transport failure before the event reaches the bus (deserialize, bridge dispatch, publish) is redelivered, so handlers must be idempotent — a handler that breaks under redelivery is buggy regardless of transport. Handler *outcome* is not part of the acknowledgment: once an event is on the bus, the transport's job is done (Kafka commits the offset), and a failing handler routes to the `ErrorHandler` without triggering redelivery — see [Error handling](#error-handling).
- **Ordering — per-source FIFO only, no cross-source merge.** Local events preserve publisher order. Future distributed transports preserve their own intra-partition order. A handler subscribed to both sees them in arrival order at the handler — there is no synthesized global ordering.
- **Backpressure — publishers never block on handler work.** Async handlers run on a bounded executor; `bus.publish(...)` returns once the event is enqueued. Synchronous in-process delivery is the default for handlers without `async = true`, and remains an option per handler — it is not the publisher's responsibility to throttle.
- **Transactional semantics — request-scope buffering built in, outbox recommended for crash safety.** Events published inside `runInEventScope` are buffered and only released when the scope exits successfully; on failure they are dropped. Persistence-backed outbox (for crash safety across the JVM boundary) is the consumer's responsibility — Tiko does not own a database.
- **Error handling — log + isolate by default, per-handler policy configurable.** A throwing handler does not propagate to the publisher and does not break sibling handlers. See [Error handling](#error-handling) below.
- **Routing is by event type, not by name.** A handler subscribes to a payload *type*; an event reaches every `@EventHandler` of that type. Tiko deliberately has no name-keyed dispatch — model distinct intents as distinct types (`CustomerAdded` / `SupplierAdded`), not as one type fanned out by string name. `@EventTrigger(eventName = "...")` is therefore an optional trace label for the topology view, never a routing key. This keeps wiring compile-time-checked: a typo or rename can't silently misroute an event, because there is no name to mistype.

## Error handling

If an `@EventHandler` method throws, the exception is routed to the configured `ErrorHandler` (default: logs at `WARNING` via `java.lang.System.Logger`). It does not propagate to the publisher and does not prevent other handlers from running.

The same isolation defines the transport boundary: a handler failure does **not** rewind the transport. On Kafka, the offset commits once the event is successfully published to the bus — a throwing sync handler is routed to the `ErrorHandler` and the message is not redelivered, and an `async = true` handler executes entirely after the commit. Redelivery exists for failures *before* the bus (deserialize/bridge/publish — those seek back and replay); recovery for failed handler executions is the dead-letter direction tracked for the resiliency milestone. If a handler must never lose its input, do its critical work synchronously in the bridge path or persist intent before returning.

The framework itself has zero logging-binding dependencies — `System.Logger` is in the JDK, so `Tiko.create()` works without adding any logging artifact to your classpath. Its default routing is JUL; route through slf4j or log4j2 by adding their `LoggerFinder` bridge to your classpath. See the [Logging section](../README.md#logging) in the main README.

Override the default to wire metrics, alerts, or custom logging:

```java
TikoOptions opts = TikoOptions.builder()
        .errorHandler(ctx -> {
            switch (ctx) {
                case EventHandlerError e -> metrics.eventHandlerError(e.handler());
            }
        })
        .build();
try (Container container = Tiko.create(opts)) { ... }
```

If your stack already uses slf4j, route the framework's error logs through it with a one-line handler:

```java
import org.slf4j.LoggerFactory;

Logger slf4j = LoggerFactory.getLogger("io.tiko.events");
TikoOptions opts = TikoOptions.builder()
        .errorHandler(ctx -> slf4j.warn("Tiko {}: {}",
                ctx.getClass().getSimpleName(), ctx.cause().toString(), ctx.cause()))
        .build();
```

The hook is for **observability**, not control flow. Exceptions are an error path. To branch on handler outcomes, return a typed result from your `@EventHandler` and chain the next event with `@EventTrigger` (optionally guarded by an `EventTriggerGuard`).

## Async events

Mark a handler `@EventHandler(async = true)` to run it off the publisher thread:

```java
@EventHandler(async = true)
public void onSlowOperation(SlowEvent event) {
    // ... I/O, network, batch flush ...
}
```

The framework dispatches via a bounded `ThreadPoolExecutor` sized for typical small-to-medium services:

| Knob              | Value                                              |
|-------------------|----------------------------------------------------|
| Core pool size    | `max(2, cores / 2)`                                |
| Max pool size     | `cores * 4`                                        |
| Keep-alive        | 60 seconds                                         |
| Queue             | bounded `LinkedBlockingQueue` capacity 1024        |
| Rejection policy  | `CallerRunsPolicy` — slows publisher under overload|
| Thread name       | `tiko-event-async-{n}` (daemon)                    |

Workloads with extreme throughput or latency requirements can supply their own:

```java
ExecutorService myExecutor = ...;
TikoOptions opts = TikoOptions.builder()
        .eventExecutor(myExecutor)
        .build();
try (Container container = Tiko.create(opts)) { ... }
```

When you supply your own executor, **you own its lifecycle** — `Container.shutdown()` does not stop it. Async handler exceptions still route to the configured `ErrorHandler` regardless of which executor is in use.

### Execution timeouts

An async handler can declare a wall-clock budget with `timeout` (an ISO-8601 `Duration`):

```java
@EventHandler(async = true, timeout = "PT5S")
public void onSlowOperation(SlowEvent event) {
    // interrupted if it runs longer than 5 seconds
}
```

If the handler runs longer than the budget, its worker thread is interrupted, the executor slot is freed, and the overrun is routed to the `ErrorHandler` as an `EventHandlerError` whose `cause()` is a `java.util.concurrent.TimeoutException`. Subsequent events keep flowing.

- **Opt-in:** the default is no timeout.
- **Async-only:** `timeout` requires `async = true`. A timeout on a synchronous handler is a **compile-time error** — a sync handler runs on the publisher's thread (and its unit-of-work scope), which cannot be preempted; time-boxing requires the off-thread, own-scope execution that `async = true` provides.
- **Best-effort:** interruption can only stop a handler that respects `Thread.interrupt()` (e.g. is blocked on I/O or checks the flag). A handler running a tight uninterruptible loop will keep going — the timeout is reported, but Java cannot force-stop the thread.

### Retries with backoff

An async handler can retry on failure with `retries` + `backoff` + `backoffStrategy`:

```java
@EventHandler(async = true, retries = 3, backoff = "PT0.1S", backoffStrategy = BackoffStrategy.EXPONENTIAL)
public void onPayment(PaymentEvent event) {
    // re-invoked up to 3 times if it throws; 100ms, then 200ms, then 400ms apart
}
```

`retries = 3` means one initial call plus up to three retries (four attempts total). The first attempt that returns normally wins — no error is routed. Once the budget is exhausted, a **single** `EventHandlerError` is routed whose `attempts()` is the total number of attempts made (here, `4`). Backoff is `FIXED` (constant delay) or `EXPONENTIAL` (doubling) and is *scheduled*, so it never ties up an executor thread while waiting.

- **Opt-in:** the default is no retries (`retries = 0`).
- **Async-only:** `retries` requires `async = true` (a **compile-time error** otherwise) — retrying waits for the backoff between attempts, which would block the publisher's thread.
- **ISO-8601 backoff:** `backoff` is a `Duration` string (`"PT0.1S"`), like `timeout`.
- **Composes with `timeout`:** if both are set, each attempt is time-boxed and a timed-out attempt counts as a failed attempt to be retried.
- **Errors are not retried:** an `Error` (vs an `Exception`) stops the loop and is logged, never retried.
- **Idempotency is your responsibility** — a retried handler runs its side effects more than once. Make the work safe to repeat.

## Graceful shutdown drain

When `Container.shutdown()` runs, in-flight async event handlers are allowed to
finish within a configurable budget before the framework-owned executor is
forced via `shutdownNow()`. This means a server shutdown signal does not
abruptly cancel async side-effects already queued on the executor — they drain
cleanly within the configured window.

Events published *after* shutdown has begun are dropped rather than dispatched —
running handlers against singletons that have already been torn down would be worse —
but the drop is **observable, never silent**: each dropped delivery logs a `WARNING`
on `io.tiko.events` (#346). Delivery is not guaranteed once shutdown starts; if a
handler must not miss late events, publish before initiating shutdown. The one residual
edge is a task still queued when the drain budget expires and `shutdownNow()` cancels it —
that is the deliberate cost of bounding shutdown, not a silent application-level loss.

Two equivalent ways to configure the budget:

**Programmatically:**

```java
TikoOptions opts = TikoOptions.builder()
        .shutdownTimeout(Duration.ofSeconds(30))   // long-running batch handlers
        .build();
```

**Via YAML** (any source loaded by your `ConfigSource`):

```yaml
tiko:
  shutdownTimeout: PT30S    # ISO-8601 duration; PT5S = 5 seconds, PT5M = 5 minutes
```

The `tiko:` top-level section is reserved for framework-level config; see
[configuration.md](./configuration.md) for the namespace policy.

**Precedence:** programmatic > YAML > default 10 seconds.

`Duration.ZERO` skips the graceful wait and calls `shutdownNow()` immediately —
useful for test harnesses where you don't want to wait on a wedged handler. The
knob has no effect when you supply your own executor via `TikoOptions.eventExecutor(...)`
(you own that executor's lifecycle).

See [`tiko-examples/09_http_javalin`](../tiko-examples/09_http_javalin) for a
runnable demo that sources the timeout from `config.yaml`.

**Caveat:** a JVM `Error` (`OutOfMemoryError`, `StackOverflowError`) bypasses
this graceful drain — the JVM may tear down threads abruptly when in an
unrecoverable state. For everything short of a JVM-level fatal, `shutdownTimeout`
is the bound.

**v1 limitations:**

- Duration values use ISO-8601 syntax (`PT5S`, `PT30S`, `PT5M`). Friendly-syntax
  durations (`5s`, `30s`) are a planned enhancement.
- `${VAR}` interpolation on `tiko.shutdownTimeout` is not supported. Use the
  programmatic API if you need env-var resolution.

## Lifecycle events

The container automatically publishes lifecycle events that you can subscribe to for metrics, logging, tracing, and cleanup. They keep observability concerns out of your business logic.

| Event                       | When it fires                          |
|-----------------------------|----------------------------------------|
| `ApplicationStartedEvent`   | After container start                  |
| `ApplicationEndingEvent`    | Before container shutdown              |
| `EventStartedEvent`         | When entering a unit of work (EVENT scope)  |
| `EventEndingEvent`          | Before exiting a unit of work (EVENT scope) |

All are Java records with timestamps and (where relevant) durations.

### Container lifecycle: caller-managed vs daemon

Two lifecycle models, and the return type tells you which one you're in:

- **`Tiko.create(...)` → `Container` (AutoCloseable).** *You* own the lifecycle — close it with try-with-resources or an explicit `container.shutdown()`. No JVM hook is installed. Best for tests, embedded use, and request/job-scoped work.
- **`Tiko.daemon(...)` → `TikoDaemon` (not AutoCloseable).** For long-lived processes (servers). It registers a JVM shutdown hook that calls `shutdown()` on `Ctrl+C` / `SIGTERM`, so cleanup runs without you wiring `Runtime.addShutdownHook`. Resolve beans via `daemon.container()`; call `daemon.stop()` only for an explicit shutdown (tests, in-process restart). It is deliberately *not* AutoCloseable — the framework owns the lifecycle, so there's nothing for you to close.

In **both** models, `ApplicationEndingEvent` fires *before* any `@PreDestroy`, so the natural place to drain an external resource (stop an HTTP server, flush a buffer) is a subscriber:

```java
@Component(scope = Scope.SINGLETON)
public class HttpServerLifecycle {
    private final Javalin app;
    // ...
    @EventHandler
    public void onApplicationEnding(ApplicationEndingEvent event) {
        app.stop(); // drained before any bean's @PreDestroy runs
    }
}

// Long-lived server — the JVM hook shuts the container down on exit:
TikoDaemon daemon = Tiko.daemon(opts);
var routes = daemon.container().get(TicketRoutes.class);
// ... start serving ...
```

### Example — metrics collection

```java
@Component(scope = Scope.SINGLETON)
public class MetricsCollector {
    private final AtomicInteger activeUnits = new AtomicInteger(0);
    private final List<Duration> unitDurations = new CopyOnWriteArrayList<>();

    @EventHandler
    public void onApplicationStarted(ApplicationStartedEvent event) {
        logger.info("Application started at {}", event.timestamp());
    }

    @EventHandler
    public void onEventStarted(EventStartedEvent event) {
        int active = activeUnits.incrementAndGet();
        logger.debug("Unit {} started, {} active units", event.eventId(), active);
    }

    @EventHandler
    public void onEventEnding(EventEndingEvent event) {
        activeUnits.decrementAndGet();
        unitDurations.add(event.duration());
        logger.debug("Unit {} completed in {}", event.eventId(), event.duration());
    }

    @EventHandler
    public void onApplicationEnding(ApplicationEndingEvent event) {
        logger.info("Application ran for {}, processed {} units",
                event.uptime(), unitDurations.size());
        double avgMs = unitDurations.stream()
                .mapToLong(Duration::toMillis)
                .average()
                .orElse(0.0);
        logger.info("Average unit duration: {}ms", avgMs);
    }
}
```

### Example — distributed tracing

```java
@Component(scope = Scope.SINGLETON)
public class DistributedTracer {
    private final Tracer tracer;

    @Inject
    public DistributedTracer(Tracer tracer) {
        this.tracer = tracer;
    }

    @EventHandler public void onEventStarted(EventStartedEvent e) { tracer.startSpan("unit", e.eventId()); }
    @EventHandler public void onEventEnding(EventEndingEvent e)   { tracer.finishSpan(e.eventId(), e.duration()); }
}
```

## Event chains with `@EventTrigger`

Declarative event workflows: an `@EventHandler` can automatically trigger one or more follow-on events when it completes successfully. The handler's return value becomes the payload of the next event, and it is **routed by that return type** — the next handler subscribes to the type, not to a name. `@EventTrigger(eventName = "...")` is an optional human-readable label for the topology/tracing view only; it never affects which handlers run. The chain below works because each step returns a *distinct type*.

### Basic chain

```java
@Component(scope = Scope.SINGLETON)
public class OrderWorkflow {
    @EventHandler
    @EventTrigger
    public ValidationResult onOrderCreated(OrderCreatedEvent event) {
        // The returned ValidationResult is published to ValidationResult handlers.
        return validateOrder(event.order());
    }

    @EventHandler
    @EventTrigger
    public PaymentResult onOrderValidated(ValidationResult validation) {
        return processPayment(validation.orderId());
    }

    @EventHandler
    @EventTrigger
    public ShipmentResult onPaymentProcessed(PaymentResult payment) {
        return shipOrder(payment.orderId());
    }

    @EventHandler
    public void onOrderShipped(ShipmentResult shipment) {
        logger.info("Order {} shipped!", shipment.orderId());
    }
}

// Publishing OrderCreatedEvent triggers the entire chain automatically
container.getEventBus().publish(new OrderCreatedEvent(order));
```

### Multiple triggers

The annotation is repeatable, but since routing is by return type, **every repeat publishes the same return value to the same handlers**. Repeating `@EventTrigger` with different `eventName`s does not create different events — names don't route, so additional triggers of the same return type just deliver duplicates. For genuinely distinct downstream events, return distinct types from distinct handlers rather than stacking triggers on one method.

### Spread collections

```java
@EventHandler
@EventTrigger(spread = true)
public List<Order> onBatchReceived(BatchReceivedEvent event) {
    // Each Order in the list is published separately to Order handlers
    return event.orders();
}

@EventHandler
public void onIndividualOrder(Order order) {
    processOrder(order);
}
```

Spread works with `Collection`, arrays, and `Iterable`.

### Conditional triggering with guards

```java
public class HighValueGuard implements EventTriggerGuard {
    @Override
    public boolean shouldTrigger(Object handlerResult, Object originalEvent) {
        return handlerResult instanceof OrderDetails details
                && details.amount() > 10000;
    }
}

@EventHandler
@EventTrigger(eventName = "HighValueOrderAlert", guard = HighValueGuard.class)
public OrderDetails onOrderCreated(OrderCreatedEvent event) {
    // Alert only triggered for orders > $10,000
    return getOrderDetails(event.orderId());
}
```

Multiple guards on a single trigger evaluate in declaration order with AND semantics.

### Origin tracking with `Event<?>`

The framework wraps every event internally in `Event<T>`. Add an `Event<?>` parameter to a handler to access the full lineage.

```java
@EventHandler
public void onOrderShipped(ShipmentResult shipment, Event<?> eventWrapper) {
    List<Object> chain = eventWrapper.getOriginChain();
    // [OrderCreatedEvent, ValidationResult, PaymentResult, ShipmentResult]

    Optional<OrderCreatedEvent> original = eventWrapper.findInChain(OrderCreatedEvent.class);

    logger.info("Order created at {} shipped after {} events",
            original.map(OrderCreatedEvent::timestamp),
            eventWrapper.getChainDepth());
}
```

The `Event<?>` parameter is optional — most handlers don't need it. When present it must come after the payload parameter.

### Why chains instead of `bus.publish` calls?

- **Declarative** — the workflow is visible in one place, not scattered across handler bodies.
- **Origin-tracked** — the full chain is available for debugging and tracing without any per-handler bookkeeping.
- **Conditional** — guards model branching without `if/else` inside handlers.
- **Async-aware** — each link in the chain controls its own sync/async semantics.
- **Only on success** — a thrown handler never triggers its follow-ons, so partial-success states don't propagate.

## Using Tiko behind an existing HTTP server

Tiko has no opinion about which HTTP server you use. The recommended pattern
keeps your sync request → response path independent of the event bus, while
publishing one event per business action that subscribers can react to
without the HTTP client waiting on them.

See `tiko-examples/09_http_javalin/` for a runnable example with Javalin: a
tiny `Handler` decorator opens a Tiko request scope around each route,
the bridge bean stays plain straight-line code, and three subscribers
(audit, metrics, async notification) demonstrate the sync-vs-async-side-effect
axis. The pattern ports to Helidon, Jetty, the JDK's `HttpServer`, etc. — swap
the imports and registration syntax; everything else stays.
