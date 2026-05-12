# Events

Tiko's event system is a first-class part of the container. Handler code is the same shape whether the event was published locally or — when a distributed transport ships — arrives from a remote source. This page is the full reference: publish/subscribe, async, error handling, lifecycle events, and the declarative `@EventTrigger` chains.

For a runnable example, see [`tiko-examples/03_events`](../tiko-examples/03_events).

## Publish / subscribe

```java
// Define event
public record UserRegisteredEvent(String userId, String email) {}

// Publish events
@Component(scope = Scope.SINGLETON)
public class UserService {
    private final EventBus events;

    @Inject
    public UserService(Container container) {
        this.events = container.events();
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

The same handler code is intended to work against any `EventBus` implementation — the in-memory bus ships today (`LocalEventBus` in `tiko-runtime`); a Kafka-backed bus is on the Phase 2 roadmap.

## Trade-off positions

These are explicit, not accidents of implementation. A handler that depends on different semantics will be wrong on at least one transport.

- **Delivery semantics — at-least-once, handlers must be idempotent.** The weaker guarantee wins so handler code is portable across transports. Local delivery is still typically synchronous in-process today, but the contract Tiko promises the handler is the same one Kafka offers. A handler that breaks under redelivery is buggy regardless of transport.
- **Ordering — per-source FIFO only, no cross-source merge.** Local events preserve publisher order. Future distributed transports preserve their own intra-partition order. A handler subscribed to both sees them in arrival order at the handler — there is no synthesized global ordering.
- **Backpressure — publishers never block on handler work.** Async handlers run on a bounded executor; `bus.publish(...)` returns once the event is enqueued. Synchronous in-process delivery is the default for handlers without `async = true`, and remains an option per handler — it is not the publisher's responsibility to throttle.
- **Transactional semantics — request-scope buffering built in, outbox recommended for crash safety.** Events published inside `runInRequestScope` are buffered and only released when the scope exits successfully; on failure they are dropped. Persistence-backed outbox (for crash safety across the JVM boundary) is the consumer's responsibility — Tiko does not own a database.
- **Error handling — log + isolate by default, per-handler policy configurable.** A throwing handler does not propagate to the publisher and does not break sibling handlers. See [Error handling](#error-handling) below.

## Error handling

If an `@EventHandler` method throws, the exception is routed to the configured `ErrorHandler` (default: logs at `WARNING` via `java.util.logging`). It does not propagate to the publisher and does not prevent other handlers from running.

The framework itself has zero logging-binding dependencies — JUL is in the JDK, so `Tiko.create()` works without adding any logging artifact to your classpath.

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

## Lifecycle events

The container automatically publishes lifecycle events that you can subscribe to for metrics, logging, tracing, and cleanup. They keep observability concerns out of your business logic.

| Event                       | When it fires                          |
|-----------------------------|----------------------------------------|
| `ApplicationStartedEvent`   | After container start                  |
| `ApplicationEndingEvent`    | Before container shutdown              |
| `RequestStartedEvent`       | When entering a request scope          |
| `RequestEndingEvent`        | Before exiting a request scope         |
| `EventStartedEvent`         | When entering an event scope           |
| `EventEndingEvent`          | Before exiting an event scope          |

All are Java records with timestamps and (where relevant) durations.

### Example — metrics collection

```java
@Component(scope = Scope.SINGLETON)
public class MetricsCollector {
    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final List<Duration> requestDurations = new CopyOnWriteArrayList<>();

    @EventHandler
    public void onApplicationStarted(ApplicationStartedEvent event) {
        logger.info("Application started at {}", event.timestamp());
    }

    @EventHandler
    public void onRequestStarted(RequestStartedEvent event) {
        int active = activeRequests.incrementAndGet();
        logger.debug("Request {} started, {} active requests", event.requestId(), active);
    }

    @EventHandler
    public void onRequestEnding(RequestEndingEvent event) {
        activeRequests.decrementAndGet();
        requestDurations.add(event.duration());
        logger.debug("Request {} completed in {}", event.requestId(), event.duration());
    }

    @EventHandler
    public void onApplicationEnding(ApplicationEndingEvent event) {
        logger.info("Application ran for {}, processed {} requests",
                event.uptime(), requestDurations.size());
        double avgMs = requestDurations.stream()
                .mapToLong(Duration::toMillis)
                .average()
                .orElse(0.0);
        logger.info("Average request duration: {}ms", avgMs);
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

    @EventHandler public void onRequestStarted(RequestStartedEvent e) { tracer.startSpan("request", e.requestId()); }
    @EventHandler public void onEventStarted(EventStartedEvent e)     { tracer.startSpan("event", e.eventId()); }
    @EventHandler public void onEventEnding(EventEndingEvent e)       { tracer.finishSpan(e.eventId(), e.duration()); }
    @EventHandler public void onRequestEnding(RequestEndingEvent e)   { tracer.finishSpan(e.requestId(), e.duration()); }
}
```

## Event chains with `@EventTrigger`

Declarative event workflows: an `@EventHandler` can automatically trigger one or more follow-on events when it completes successfully. The handler's return value becomes the payload of the next event.

### Basic chain

```java
@Component(scope = Scope.SINGLETON)
public class OrderWorkflow {
    @EventHandler
    @EventTrigger(eventName = "OrderValidated")
    public ValidationResult onOrderCreated(OrderCreatedEvent event) {
        // Return value becomes payload of OrderValidated
        return validateOrder(event.order());
    }

    @EventHandler
    @EventTrigger(eventName = "PaymentProcessed")
    public PaymentResult onOrderValidated(ValidationResult validation) {
        return processPayment(validation.orderId());
    }

    @EventHandler
    @EventTrigger(eventName = "OrderShipped")
    public ShipmentResult onPaymentProcessed(PaymentResult payment) {
        return shipOrder(payment.orderId());
    }

    @EventHandler
    public void onOrderShipped(ShipmentResult shipment) {
        logger.info("Order {} shipped!", shipment.orderId());
    }
}

// Publishing OrderCreatedEvent triggers the entire chain automatically
container.events().publish(new OrderCreatedEvent(order));
```

### Multiple triggers

```java
@EventHandler
@EventTrigger(eventName = "InventoryReserved")
@EventTrigger(eventName = "NotificationSent", async = true)
@EventTrigger(eventName = "AnalyticsTracked", async = true)
public OrderDetails onOrderCreated(OrderCreatedEvent event) {
    // All three follow-ons get the same payload (the return value)
    return getOrderDetails(event.orderId());
}
```

### Spread collections

```java
@EventHandler
@EventTrigger(eventName = "IndividualOrderProcessed", spread = true)
public List<Order> onBatchReceived(BatchReceivedEvent event) {
    // Each order in the list triggers a separate IndividualOrderProcessed event
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
