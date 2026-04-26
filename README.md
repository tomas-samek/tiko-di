# Tiko DI

> A modern, compile-time dependency injection framework for Java 17+ with event-driven architecture

[![Build](https://github.com/tomas-samek/tiko-di/actions/workflows/maven.yml/badge.svg?branch=main)](https://github.com/tomas-samek/tiko-di/actions/workflows/maven.yml)
[![Java](https://img.shields.io/badge/Java-17%20%7C%2021%20%7C%2025%20%7C%2026-blue.svg)](https://www.oracle.com/java/)
[![Maven](https://img.shields.io/badge/Maven-3.8+-red.svg)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

## Why Tiko?

Tiko combines the compile-time safety of Dagger with the simplicity of lightweight DI frameworks, while adding unique
event-driven capabilities that work seamlessly across local and distributed deployments.

### Key Features

- ⚡ **Zero Runtime DI Exceptions** - All dependency errors caught at compile-time
- 🪶 **Minimal API** - Learn in 15 minutes, no complex concepts
- 🎯 **No Reflection** - Pure generated code, GraalVM native image ready
- 🔄 **Event-Driven** - Transparent local/distributed event handling
- 🚀 **Fast Startup** - No classpath scanning or runtime proxy generation
- 📦 **Lightweight** - Core runtime targets ~100KB with zero dependencies beyond `tiko-api`
- 🔍 **Clear Errors** - Compile-time errors suggest fixes, not just report problems
- ☕ **Modern Java** - Leverages Java 17+ features (records, sealed classes, pattern matching)

## Quick Start

### Installation

Add to your `pom.xml`:

```xml

<dependencies>
    <!-- Core API -->
    <dependency>
        <groupId>io.tiko</groupId>
        <artifactId>tiko-api</artifactId>
        <version>0.1.0</version>
    </dependency>

    <!-- Runtime container -->
    <dependency>
        <groupId>io.tiko</groupId>
        <artifactId>tiko-runtime</artifactId>
        <version>0.1.0</version>
    </dependency>

    <!-- In-memory event bus (optional) -->
    <dependency>
        <groupId>io.tiko</groupId>
        <artifactId>tiko-event-local</artifactId>
        <version>0.1.0</version>
    </dependency>
</dependencies>

<build>
<plugins>
    <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
        <configuration>
            <annotationProcessorPaths>
                <path>
                    <groupId>io.tiko</groupId>
                    <artifactId>tiko-processor</artifactId>
                    <version>0.1.0</version>
                </path>
            </annotationProcessorPaths>
        </configuration>
    </plugin>
</plugins>
</build>
```

### Annotation Processing on Java 23+

Starting with **JDK 23**, `javac` no longer runs annotation processing implicitly. If none of `-processor`, `--processor-path`, `--processor-module-path`, or `-proc:full`/`-proc:only` is specified, the compiler **silently skips processing** — meaning Tiko's code generator never runs, no `TikoContainerImpl` is produced, and your app fails at runtime with a cryptic `ClassNotFoundException: io.tiko.generated.TikoContainerImpl` (or a `NoSuchElementException` from `Tiko.create()`).

> JDK 21 and 22 still run processing but emit a warning ("`Annotation processing is enabled because one or more processors were found on the class path...`"). JDK 23+ makes the new behavior the default.

**Maven (recommended):**

The snippet in [Installation](#installation) is already correct for JDK 23+ — `<annotationProcessorPaths>` passes `--processor-path` to `javac`, which satisfies the explicit-opt-in requirement. **Requires `maven-compiler-plugin` ≥ 3.13.0**; older versions of the plugin do not reliably forward the flag on JDK 23+.

If you are on an older plugin version and cannot upgrade, force processing explicitly:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.11.0</version>
    <configuration>
        <proc>full</proc> <!-- explicit opt-in for JDK 23+ -->
        <annotationProcessorPaths>
            <path>
                <groupId>io.tiko</groupId>
                <artifactId>tiko-processor</artifactId>
                <version>0.1.0</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

**Gradle:**

```groovy
dependencies {
    implementation "io.tiko:tiko-api:0.1.0"
    implementation "io.tiko:tiko-runtime:0.1.0"
    annotationProcessor "io.tiko:tiko-processor:0.1.0"
}
```

The `annotationProcessor` configuration sets `--processor-path` for you, which is sufficient for JDK 23+.

**Plain `javac`:**

```bash
javac -proc:full \
      --processor-path tiko-processor-0.1.0.jar \
      -cp tiko-api-0.1.0.jar:tiko-runtime-0.1.0.jar \
      -d out \
      src/main/java/com/example/*.java
```

**Verifying processing actually ran:**

After `mvn compile` (or the equivalent), confirm the generated container exists:

```bash
ls target/generated-sources/annotations/io/tiko/generated/
# Expected:
#   TikoContainerImpl.java
#   <YourComponent>Factory.java   (one per @Component)
#   EventRegistry.java
```

If that directory is empty or missing, processing was skipped — re-check the compiler plugin version and the `<annotationProcessorPaths>` / `annotationProcessor` declaration.

### Quick Example

```java
// 1. Define your components
@Component(scope = Scope.SINGLETON)
public class UserRepository {
  public User findById(String id) {
    // Database access...
    return null; // simplified for the example
  }
}

@Component(scope = Scope.SINGLETON)
public class UserService {
  private final UserRepository repository;

  @Inject
  public UserService(UserRepository repository) {
    this.repository = repository;
  }

  public User getUser(String id) {
    return repository.findById(id);
  }
}

// 2. Use the container
public class Main {
  public static void main(String[] args) {
    Container container = Tiko.create();
    UserService service = container.get(UserService.class);
    User user = service.getUser("123");
    container.shutdown();
  }
}
```

**That's it!** The annotation processor validates all dependencies at compile-time and generates the wiring code.

## Core Concepts

### Annotations

**Package:** All annotations are in `io.tiko.annotations.*`

**Dependency Injection:**

- `@Component(scope, name, profiles)` - Marks a class for dependency injection
    - `scope` - Lifecycle scope (SINGLETON, REQUEST, EVENT, PROTOTYPE)
    - `name` - Optional qualifier for disambiguation
    - `profiles` - Optional active profiles
- `@Inject` - Marks constructors for dependency injection (constructor-only, no field injection)
- `@Named("name")` - Qualifies injection when multiple implementations exist

**Scopes (via @Component parameter - from longest to shortest lifetime):**

- `Scope.SINGLETON` - Application lifetime (recommended for stateless services)
- `Scope.REQUEST` - Request/transaction/batch scope (coarse-grained unit of work)
- `Scope.EVENT` - Single event processing (fine-grained unit of work)
- `Scope.PROTOTYPE` - Per injection (default - shortest lifetime)

**Lifecycle:**

- `@PostConstruct` - Called after dependency injection
- `@PreDestroy` - Called before bean destruction

**Factory Methods:**

- `@Produces(scope, name, profiles)` - Marks factory methods that create components
    - Supports both instance methods (separate factory component) and static methods (same class)
    - Use for: validation, complex initialization, third-party classes, private constructors

### Scope Rules

| Injecting Into | Can Inject  | Notes                                  |
|----------------|-------------|----------------------------------------|
| `SINGLETON`    | `SINGLETON` | ✓ Direct injection                     |
| `SINGLETON`    | `REQUEST`   | ✓ Automatic proxy (requires interface) |
| `SINGLETON`    | `EVENT`     | ✓ Automatic proxy (requires interface) |
| `SINGLETON`    | `PROTOTYPE` | ✓ New instance each time               |
| `REQUEST`      | `SINGLETON` | ✓ Direct injection                     |
| `REQUEST`      | `REQUEST`   | ✓ Direct injection                     |
| `REQUEST`      | `EVENT`     | ✓ Automatic proxy (requires interface) |
| `REQUEST`      | `PROTOTYPE` | ✓ New instance each time               |
| `EVENT`        | `SINGLETON` | ✓ Direct injection                     |
| `EVENT`        | `REQUEST`   | ✓ Direct injection                     |
| `EVENT`        | `EVENT`     | ✓ Direct injection                     |
| `EVENT`        | `PROTOTYPE` | ✓ New instance each time               |

**Cross-scope injection:** Shorter-lived scoped beans (REQUEST, EVENT) injected into longer-lived scopes are automatically
proxied to resolve the current scope's instance. This requires the shorter-lived bean to implement an interface.

**Scope hierarchy:** SINGLETON > REQUEST > EVENT > PROTOTYPE (longest to shortest lifetime)

**Example - REQUEST and EVENT scopes:**

```java
// REQUEST scope - shared across all events in a request/transaction
public interface TransactionContext {
  String getTransactionId();
}

@Component(scope = Scope.REQUEST)
public class TransactionContextImpl implements TransactionContext {
  private final String transactionId = UUID.randomUUID().toString();

  public String getTransactionId() {
    return transactionId;
  }
}

// EVENT scope - unique per event processing
public interface EventContext {
  String getEventId();
  Instant getTimestamp();
}

@Component(scope = Scope.EVENT)
public class EventContextImpl implements EventContext {
  private final String eventId = UUID.randomUUID().toString();
  private final Instant timestamp = Instant.now();

  public String getEventId() {
    return eventId;
  }

  public Instant getTimestamp() {
    return timestamp;
  }
}

// Batch processing example
container.runInRequestScope(() -> {
  // One transaction for all events
  for (Order order : orders) {
    container.runInEventScope(() -> {
      // Each event gets its own context
      orderService.process(order);
      // Same TransactionContext, different EventContext per iteration
    });
  }
});
```

## Usage Examples

### Constructor Injection (Recommended)

```java

@Component(scope = Scope.SINGLETON)
public class OrderService {
  private final OrderRepository repository;
  private final PaymentService paymentService;

  @Inject
  public OrderService(OrderRepository repository, PaymentService paymentService) {
    this.repository = repository;
    this.paymentService = paymentService;
  }
}
```

### Named Qualifiers

```java

@Component(scope = Scope.SINGLETON, name = "mysql")
public class MySqlDatabase implements Database {
}

@Component(scope = Scope.SINGLETON, name = "postgres")
public class PostgresDatabase implements Database {
}

@Component(scope = Scope.SINGLETON)
public class DataService {
  @Inject
  public DataService(@Named("mysql") Database database) {
    // Uses MySqlDatabase
  }
}
```

### Fluent Lookup with `pick()`

`container.get(Class)` and `container.get(Class, String)` are the 80% shortcuts. For anything else — a fallback when the bean is missing, a lazy `Provider`, or future axes — go through `container.pick(Class)`:

```java
// Same as container.get(Greeter.class)
Greeter g = container.pick(Greeter.class).resolve();

// Named lookup — same as container.get(Greeter.class, "english")
Greeter english = container.pick(Greeter.class).withName("english").resolve();

// Lazy Provider that re-resolves each call (preserves scope semantics)
Provider<Greeter> p = container.pick(Greeter.class).withName("spanish").asProvider();

// Fallback when the bean is absent — no IllegalArgumentException
Greeter french = container.pick(Greeter.class)
    .withName("french")
    .orDefault(new NoopGreeter());
```

### Lifecycle Hooks

```java

@Component(scope = Scope.SINGLETON)
public class DatabaseConnection {
  private Connection connection;

  @PostConstruct
  public void connect() {
    this.connection = DriverManager.getConnection("jdbc:...");
    System.out.println("Connected to database");
  }

  @PreDestroy
  public void disconnect() {
    connection.close();
    System.out.println("Disconnected from database");
  }
}
```

### Request Scopes

```java

@Component(scope = Scope.REQUEST)
public class RequestContext {
  private final String requestId = UUID.randomUUID().toString();

  public String getRequestId() {
    return requestId;
  }
}

// Usage
container.runInRequestScope(() -> {
    RequestContext ctx = container.get(RequestContext.class);
    System.out.println("Request ID: " + ctx.getRequestId());
    // ctx is automatically cleaned up when scope exits
});
```

### Event Handling

```java
// Define event
public record UserRegisteredEvent(String userId, String email) {
}

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

**The power:** Same code works with in-memory events OR Kafka/distributed events. Just swap the implementation via
configuration!

### Lifecycle Events

The container automatically publishes lifecycle events that you can subscribe to for metrics, logging, tracing, and cleanup. These events allow you to keep side effects separate from your main business logic.

**Available Lifecycle Events:**

- `ApplicationStartedEvent` - Published when container starts up
- `ApplicationEndingEvent` - Published before container shuts down
- `RequestStartedEvent` - Published when entering request scope
- `RequestEndingEvent` - Published before exiting request scope
- `EventStartedEvent` - Published when entering event scope
- `EventEndingEvent` - Published before exiting event scope

**Example - Metrics Collection:**

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
        logger.debug("Request {} started, {} active requests",
            event.requestId(), active);
    }

    @EventHandler
    public void onRequestEnding(RequestEndingEvent event) {
        activeRequests.decrementAndGet();
        requestDurations.add(event.duration());
        logger.debug("Request {} completed in {}",
            event.requestId(), event.duration());
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

**Example - Distributed Tracing:**

```java
@Component(scope = Scope.SINGLETON)
public class DistributedTracer {
    private final Tracer tracer;

    @Inject
    public DistributedTracer(Tracer tracer) {
        this.tracer = tracer;
    }

    @EventHandler
    public void onRequestStarted(RequestStartedEvent event) {
        tracer.startSpan("request", event.requestId());
    }

    @EventHandler
    public void onEventStarted(EventStartedEvent event) {
        tracer.startSpan("event", event.eventId());
    }

    @EventHandler
    public void onEventEnding(EventEndingEvent event) {
        tracer.finishSpan(event.eventId(), event.duration());
    }

    @EventHandler
    public void onRequestEnding(RequestEndingEvent event) {
        tracer.finishSpan(event.requestId(), event.duration());
    }
}
```

**Why Lifecycle Events?**

- **Separation of Concerns** - Keep metrics/logging separate from business logic
- **Non-Invasive** - Add tracing without modifying existing code
- **Async-Friendly** - Handlers can run asynchronously without blocking main flow
- **Comprehensive** - Track application, request, and event lifecycles

### Event Chains with @EventTrigger

Create declarative event workflows without explicit EventBus calls. Event handlers can automatically trigger additional events when they complete successfully.

**Basic Event Chaining:**

```java
@Component(scope = Scope.SINGLETON)
public class OrderWorkflow {
    @EventHandler
    @EventTrigger(eventName = "OrderValidated")
    public ValidationResult onOrderCreated(OrderCreatedEvent event) {
        // Return value becomes payload of OrderValidated event
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

// Publishing OrderCreatedEvent triggers entire chain automatically
container.events().publish(new OrderCreatedEvent(order));
```

**Multiple Triggers:**

```java
@EventHandler
@EventTrigger(eventName = "InventoryReserved")
@EventTrigger(eventName = "NotificationSent", async = true)
@EventTrigger(eventName = "AnalyticsTracked", async = true)
public OrderDetails onOrderCreated(OrderCreatedEvent event) {
    // All three events triggered with same payload (return value)
    return getOrderDetails(event.orderId());
}
```

**Spread Collections:**

```java
@EventHandler
@EventTrigger(eventName = "IndividualOrderProcessed", spread = true)
public List<Order> onBatchReceived(BatchReceivedEvent event) {
    // Each order in the list triggers separate IndividualOrderProcessed event
    return event.orders();
}

@EventHandler
public void onIndividualOrder(Order order) {
    // Called once per order
    processOrder(order);
}
```

**Conditional Triggering with Guards:**

```java
public class HighValueGuard implements EventTriggerGuard {
    @Override
    public boolean shouldTrigger(Object handlerResult, Object originalEvent) {
        return handlerResult instanceof OrderDetails details
            && details.amount() > 10000;
    }
}

@EventHandler
@EventTrigger(
    eventName = "HighValueOrderAlert",
    guard = HighValueGuard.class
)
public OrderDetails onOrderCreated(OrderCreatedEvent event) {
    // Alert only triggered for orders > $10,000
    return getOrderDetails(event.orderId());
}
```

**Event Origin Tracking:**

```java
@EventHandler
public void onOrderShipped(ShipmentResult shipment, Event<?> eventWrapper) {
    // Access full event chain
    List<Object> chain = eventWrapper.getOriginChain();
    // chain: [OrderCreatedEvent, ValidationResult, PaymentResult, ShipmentResult]

    // Find original order creation event
    Optional<OrderCreatedEvent> original =
        eventWrapper.findInChain(OrderCreatedEvent.class);

    logger.info("Order created at {} shipped after {} events",
        original.map(OrderCreatedEvent::timestamp),
        eventWrapper.getChainDepth());
}
```

**Benefits:**

- **Declarative Workflows** - Define event chains without explicit publish() calls
- **Origin Tracking** - Full event lineage for debugging and distributed tracing
- **Async Control** - Mix sync/async processing per trigger
- **Conditional Logic** - Guards enable complex branching without if/else chains
- **Spread Collections** - Easily fan-out batch operations

## Comparison with Other Frameworks

| Feature               | Tiko          | Spring   | Guice   | Dagger 2     | Micronaut    |
|-----------------------|--------------|----------|---------|--------------|--------------|
| Dependency Validation | Compile-time | Runtime  | Runtime | Compile-time | Compile-time |
| Reflection at Runtime | None         | Heavy    | Medium  | None         | Minimal      |
| Startup Time          | Very Fast    | Slow     | Medium  | Very Fast    | Very Fast    |
| API Complexity        | Simple       | Complex  | Medium  | Complex      | Medium       |
| Runtime Size          | ~100KB       | ~5MB     | ~700KB  | ~50KB        | ~10MB        |
| Event System          | Built-in     | Built-in | None    | None         | Built-in     |
| Learning Curve        | 15 min       | Days     | Hours   | Hours        | Hours        |
| Boilerplate           | Minimal      | Medium   | Medium  | High         | Low          |

## Modules

### tiko-api (target ~30KB)

Core annotations and interfaces. This is the only compile-time dependency your code needs.

### tiko-processor

Annotation processor that runs at compile-time to validate dependencies and generate code.

### tiko-runtime (target ~100KB)

Minimal runtime container implementation. Zero dependencies beyond tiko-api.

### tiko-event-api

Event system abstractions (EventBus, EventHandler, Subscription).

### tiko-event-local

In-memory event bus implementation for single-instance deployments.

### tiko-event-kafka (Planned)

Kafka-backed event bus for distributed systems.

## Building from Source

### Prerequisites

- Java 17 or higher
- Maven 3.8 or higher

### Build Commands

```bash
# Clone the repository
git clone https://github.com/tomas-samek/tiko-di.git
cd tiko-di

# Build all modules
mvn clean install

# Run tests
mvn test

# Build without tests
mvn clean install -DskipTests

# Build specific module
mvn clean install -pl tiko-api
```

## Roadmap

### Current Status: Alpha

Core DI is functional end-to-end. The annotation processor generates factories, a container implementation per module, and proxies for cross-scope injection. The pieces below are implemented and covered by integration tests in `tiko-examples/01_basic_di`.

- ✅ Core API design
- ✅ Module structure
- ✅ Annotation processor: `@Component`, `@Produces`, `@EventHandler` collection and validation
- ✅ Dependency graph validation, circular-dependency detection, scope rules
- ✅ Compile-time ambiguity detection for unnamed providers of the same type
- ✅ Code generation: per-component factories, `TikoContainerImpl`, cross-scope proxies, event registry
- ✅ Runtime container: constructor injection, SINGLETON/REQUEST/EVENT/PROTOTYPE scopes, `@PostConstruct`/`@PreDestroy`, scope management (`runInRequestScope`/`runInEventScope` + `supplyIn*`)
- ✅ Container lookup API: `get(Class)`, `get(Class, String)` with interface dispatch, `getProvider(...)` (lazy, scope-preserving)
- ✅ `@Produces` factory methods: instance + static, named + unnamed, with dependency injection
- ✅ In-memory event bus (`tiko-event-local`) with `@EventHandler` subscription
- ✅ Multi-module aggregation via `AggregatingContainer` + `META-INF/tiko/` metadata
- ✅ `container.pick(Class)` fluent API for multi-axis lookup (`withName`, `resolve`, `asProvider`, `orDefault`)
- 🚧 Lifecycle events (`ApplicationStartedEvent`, `RequestStartedEvent`, etc.) — types defined; publishing wiring tracked in [#4](https://github.com/tomas-samek/tiko-di/issues/4)
- 🚧 `@EventTrigger` chains (declarative event workflows, guards, spread) — tracked in [#5](https://github.com/tomas-samek/tiko-di/issues/5)

### Planned Features

- **Phase 1** (Current)
    - Complete lifecycle-event publishing and verify `@EventTrigger` codegen ([#4](https://github.com/tomas-samek/tiko-di/issues/4), [#5](https://github.com/tomas-samek/tiko-di/issues/5))
    - Refactor `02_multi_module` example into api + impl + app with runtime-scope DI ([#6](https://github.com/tomas-samek/tiko-di/issues/6))

- **Phase 2** (Next)
    - Kafka event bus integration
    - Configuration injection (`@Value`)
    - Conditional beans
    - Profile isolation: compile-time `forbidProfiles` validation + Maven source-root convention to keep test-only `@Component`s out of prod jars

- **Phase 3** (Future)
    - AOP/Interceptors
    - Metrics and monitoring hooks
    - GraalVM native image optimization
    - IDE plugin for better developer experience

### Known Limitations

- `container.get(Class, String)` uses `isAssignableFrom` matching; `container.get(Class)` uses exact class or exact implemented-interface matching. The asymmetry is intentional for now but may be unified in Phase 1.
- Open issues are tracked in [GitHub Issues](https://github.com/tomas-samek/tiko-di/issues).

## Philosophy

Tiko is built on these principles:

1. **Compile-time safety** - Catch all errors the compiler can see. The only runtime exceptions Tiko throws fire at container startup - never during `container.get(...)` in a running application.
2. **Simplicity** - Minimal concepts, intuitive API
3. **Explicitness** - No magic, generated code is readable
4. **Performance** - Zero reflection, fast startup, low memory
5. **Modularity** - Use only what you need
6. **Event-driven** - First-class support for decoupled communication

## Contributing

Contributions are welcome! Please feel free to submit issues or pull requests.

### How to Contribute

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

### Reporting Issues

- Use GitHub Issues to report bugs or suggest features
- Include Java version, Maven version, and relevant code snippets
- Check existing issues before creating new ones

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

Inspired by the best aspects of existing DI frameworks:

- **Dagger 2** - Compile-time validation approach
- **Guice** - Clean, type-safe API design
- **Spring** - Comprehensive feature set and ecosystem thinking
- **Micronaut** - Cloud-native optimization strategies

## Contact

Tomas Samek - [GitHub](https://github.com/tomas-samek)

Project Link: [https://github.com/tomas-samek/tiko-di](https://github.com/tomas-samek/tiko-di)

---

**Tiko** - Dependency Injection done right. Finally.
