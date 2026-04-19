# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Tiko DI** is a modern, compile-time dependency injection framework for Java 17+ with event-driven architecture.

**Key Features:**
- **Compile-time validation** - Zero runtime DI exceptions
- **Minimal API surface** - Learn in 15 minutes
- **Event-driven** - Seamless local/distributed event handling
- **Java 17+ only** - Leverages records, sealed classes, pattern matching
- **No reflection at runtime** - All wiring via generated code

**Project Coordinates:**
- **Group ID**: `io.tiko`
- **Artifact ID**: `tiko`
- **Java Version**: 17+

## Build System

This project uses **Maven 3** as its build system.

### Common Commands

```bash
# Compile the project (triggers annotation processing)
mvn compile

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=ClassName

# Run a single test method
mvn test -Dtest=ClassName#methodName

# Package all modules
mvn package

# Clean build artifacts
mvn clean

# Full clean build
mvn clean install

# Skip tests during build
mvn install -DskipTests

# Build specific module
mvn compile -pl tiko-api
```

## Multi-Module Structure

```
tiko/
├── tiko-api/              # Core annotations & interfaces (~30KB target)
├── tiko-processor/        # Compile-time annotation processor
├── tiko-runtime/          # Minimal runtime container (~100KB target)
├── tiko-event-api/        # Event system abstractions
└── tiko-event-local/      # In-memory event bus implementation
```

### Module Dependencies

```
tiko-api (no dependencies)
  ↑
tiko-processor (depends on tiko-api, javapoet, auto-service)
  ↑ (annotation processor path)
tiko-runtime (depends on tiko-api)
  ↑
tiko-event-api (depends on tiko-api)
  ↑
tiko-event-local (depends on tiko-event-api, tiko-runtime)
```

## Core Architecture

### Design Philosophy

**"Compile-time safety with runtime simplicity"**

1. **Zero runtime DI exceptions** - All dependency errors caught at compile-time
2. **No global state** - Explicit Container instances
3. **Minimal boilerplate** - Simple patterns require zero configuration
4. **Clear error messages** - Suggest fixes, not just report problems
5. **No reflection** - Generated code is readable and debuggable

### Scope Management

Four scopes (via `Scope` enum) - from longest to shortest lifetime:
- `Scope.SINGLETON` - Application lifetime
- `Scope.REQUEST` - Request/transaction/batch scope (coarse-grained)
- `Scope.EVENT` - Single event processing (fine-grained)
- `Scope.PROTOTYPE` - Per injection (shortest)

**Scope hierarchy:**
```
SINGLETON (application)
    ↓
REQUEST (transaction, HTTP request, batch job)
    ↓
EVENT (single event handler execution)
    ↓
PROTOTYPE (per injection)
```

**Key distinction:** One REQUEST can process multiple EVENTs (e.g., batch processing, transaction with multiple messages).

**Cross-scope injection rules:**
- SINGLETON → SINGLETON: ✓ Direct injection
- SINGLETON → REQUEST: ✓ **Automatic proxy** (requires interface)
- SINGLETON → EVENT: ✓ **Automatic proxy** (requires interface)
- SINGLETON → PROTOTYPE: ✓ Direct injection (new instance each time)
- REQUEST → SINGLETON: ✓ Direct injection
- REQUEST → REQUEST: ✓ Direct injection
- REQUEST → EVENT: ✓ **Automatic proxy** (requires interface)
- REQUEST → PROTOTYPE: ✓ Direct injection (new instance each time)
- EVENT → SINGLETON: ✓ Direct injection
- EVENT → REQUEST: ✓ Direct injection
- EVENT → EVENT: ✓ Direct injection
- EVENT → PROTOTYPE: ✓ Direct injection (new instance each time)

**Proxy generation:** When shorter-lived scoped beans (REQUEST, EVENT) are injected into longer-lived scopes, the annotation processor generates a proxy handler class to avoid reflection on method invocation. The shorter-lived bean must implement an interface for proxy creation.

### Annotation Processing

The `tiko-processor` module scans `@Component` classes at compile-time and:

1. **Validates** dependency graph (missing deps, circular deps, scope violations)
2. **Generates** factory classes for each component
3. **Generates** `TikoContainerImpl` with all wiring
4. **Generates** event handler registration code
5. **Reports** clear compile-time errors with suggested fixes

**No runtime reflection or classpath scanning!**

### Event System

Event bus abstraction allows transparent switching between local and distributed implementations:

```java
// Same code works with both local and Kafka
@Component
public class OrderService {
    @EventHandler
    public void onOrderCreated(OrderCreatedEvent event) {
        // Handle event
    }
}
```

Configuration determines implementation:
```properties
tiko.event.bus=local    # or kafka
```

## Core Annotations (tiko-api)

**Package:** All annotations are in `io.tiko.annotations.*`

### Dependency Injection
- `@Component(scope, name, profiles)` - Mark classes for DI (SOURCE retention)
  - `scope` - Lifecycle scope (SINGLETON, REQUEST, EVENT, PROTOTYPE)
  - `name` - Optional qualifier
  - `profiles` - Optional active profiles
- `@Inject` - Mark injection points (RUNTIME retention)
- `@Named("qualifier")` - Disambiguate implementations at injection point

### Scopes (Scope enum - longest to shortest)
- `Scope.SINGLETON` - Application lifetime
- `Scope.REQUEST` - Request/transaction/batch lifecycle (coarse-grained)
- `Scope.EVENT` - Single event processing lifecycle (fine-grained)
- `Scope.PROTOTYPE` - New instance per injection (default, shortest)

### Lifecycle
- `@PostConstruct` - Called after construction
- `@PreDestroy` - Called before destruction

### Advanced
- `@Produces(scope, name, profiles)` - Factory method within component classes

### Event System
- `@EventHandler(async, eventType)` - Mark method as event handler (RUNTIME retention)
- `@EventTrigger(eventName, async, spread, guard)` - Declaratively trigger events after handler completes (RUNTIME retention)
- `@EventTriggers(value)` - Container for multiple @EventTrigger annotations
- `EventTriggerGuard` - Interface for conditional event triggering
- `Event<T>` - Event wrapper for origin tracking and event chain traversal
- `EventCallback<T>` - Functional interface for programmatic subscription

### Core Interfaces
- `Container` - Main DI container interface
- `Provider<T>` - Lazy dependency resolution
- `EventBus` - Event publishing/subscribing
- `Tiko` - Factory for creating containers

### Lifecycle Events (io.tiko.events)

The container automatically publishes lifecycle events for async hooks without cluttering main processing code:

**Application Lifecycle:**
- `ApplicationStartedEvent(Instant timestamp)` - Published when container starts
- `ApplicationEndingEvent(Instant timestamp, Duration uptime)` - Published before shutdown

**Request Scope Lifecycle:**
- `RequestStartedEvent(String requestId, Instant timestamp)` - Published on scope entry
- `RequestEndingEvent(String requestId, Instant timestamp, Duration duration)` - Published on scope exit

**Event Scope Lifecycle:**
- `EventStartedEvent(String eventId, Instant timestamp)` - Published on scope entry
- `EventEndingEvent(String eventId, Instant timestamp, Duration duration)` - Published on scope exit

**Usage Pattern:**
```java
@Component(scope = Scope.SINGLETON)
public class MetricsCollector {
    @EventHandler
    public void onRequestStarted(RequestStartedEvent event) {
        metrics.incrementActiveRequests();
        logger.debug("Request {} started", event.requestId());
    }

    @EventHandler
    public void onRequestEnding(RequestEndingEvent event) {
        metrics.decrementActiveRequests();
        metrics.recordDuration(event.duration());
        logger.debug("Request {} completed in {}",
            event.requestId(), event.duration());
    }

    @EventHandler
    public void onApplicationEnding(ApplicationEndingEvent event) {
        logger.info("Application ran for {}", event.uptime());
        metrics.flush();
    }
}
```

**Design rationale:** Lifecycle events enable separation of concerns - keep metrics, logging, and tracing separate from business logic. All events are Java records with timestamps and durations for easy integration with observability tools.

### Event Chaining with @EventTrigger

Declarative event workflows where handlers automatically trigger subsequent events:

**Basic pattern:**
```java
@EventHandler
@EventTrigger(eventName = "OrderValidated")
public ValidationResult onOrderCreated(OrderCreatedEvent event) {
    // Return value becomes payload of OrderValidated event
    return validateOrder(event);
}
```

**Key features:**
- **Return value as payload** - Handler's return becomes next event's data
- **Multiple triggers** - Use multiple `@EventTrigger` on one method
- **Async control** - `async = true` for parallel processing
- **Spread collections** - `spread = true` emits each item separately
- **Conditional triggering** - Guards (`EventTriggerGuard`) enable branching logic
- **Origin tracking** - `Event<T>` wrapper tracks full event lineage

**Event origin chain:**
```java
@EventHandler
public void handler(PaymentProcessedEvent event, Event<?> wrapper) {
    // Access full chain
    List<Object> chain = wrapper.getOriginChain();
    // [OrderCreatedEvent, ValidationResult, PaymentProcessedEvent]

    // Find specific event in chain
    Optional<OrderCreatedEvent> original =
        wrapper.findInChain(OrderCreatedEvent.class);
}
```

**Guards for conditional triggering:**
```java
public class HighValueGuard implements EventTriggerGuard {
    public boolean shouldTrigger(Object result, Object originalEvent) {
        return ((OrderDetails) result).amount() > 10000;
    }
}

@EventTrigger(eventName = "HighValueAlert", guard = HighValueGuard.class)
```

**Implementation notes:**
- Events only trigger if handler completes successfully (no exceptions)
- Guards evaluated in order (AND logic for multiple guards)
- Spread works with Collections, arrays, and Iterables
- Event wrapper parameter is optional (second param after event)
- Framework wraps all events internally in `Event<T>` for tracking

## Development Guidelines

### Code Generation Strategy

All code is generated in the `io.tiko.generated` package:
- `TikoContainerImpl` - Main container implementation
- `<ComponentName>Factory` - Factory for each component
- `EventRegistry` - Event handler wiring

### Error Message Format

Compile-time errors should:
1. Show the problematic code location
2. Explain what's wrong
3. Suggest at least one fix

Example:
```
ERROR: UserService.java:15
REQUEST-scoped bean 'RequestContext' must implement an interface for proxy generation

  @Inject
  public UserService(RequestContext context) {
                     ^^^^^^^^^^^^^^

When injecting REQUEST-scoped beans into SINGLETON, proxying is required.

Suggested fixes:
1. Extract interface: interface IRequestContext { }
2. Make RequestContext implement the interface
```

### Java 17+ Feature Usage

- **Records** - For immutable DTOs, configuration, metadata
- **Sealed classes** - For scope hierarchies, injection point types
- **Pattern matching** - For clean switch statements in processor
- **Text blocks** - For code generation templates

### Testing Strategy

- Unit tests for annotation processor logic
- Integration tests with actual `@Component` classes
- Test both valid and invalid scenarios
- Verify error messages are helpful

## Common Patterns

### Constructor Injection (Preferred)
```java
@Component(scope = Scope.SINGLETON)
public class UserService {
    @Inject
    public UserService(UserRepository repository) {
        this.repository = repository;
    }
}
```

### REQUEST and EVENT Scopes Together
```java
// REQUEST scope - transaction/batch context
public interface TransactionContext {
    String getTransactionId();
}

@Component(scope = Scope.REQUEST)
public class TransactionContextImpl implements TransactionContext {
    private final String txId = UUID.randomUUID().toString();
    public String getTransactionId() { return txId; }
}

// EVENT scope - single event context
public interface EventContext {
    String getEventId();
}

@Component(scope = Scope.EVENT)
public class EventContextImpl implements EventContext {
    private final String eventId = UUID.randomUUID().toString();
    public String getEventId() { return eventId; }
}

// Automatic proxy for cross-scope injection
@Component(scope = Scope.SINGLETON)
public class OrderService {
    @Inject
    public OrderService(TransactionContext txCtx, EventContext evtCtx) {
        // Both get proxies automatically
    }
}

// Batch processing - one REQUEST, multiple EVENTs
container.runInRequestScope(() -> {
    // One transaction for all orders
    for (Order order : orders) {
        container.runInEventScope(() -> {
            // Each order gets its own event context
            orderService.process(order);
        });
    }
});
```

### Event Handling
```java
@Component(scope = Scope.SINGLETON)
public class NotificationService {
    @EventHandler
    public void onUserRegistered(UserRegisteredEvent event) {
        sendWelcomeEmail(event.getUser());
    }
}
```

### Factory Methods with @Produces
```java
// Factory component with dependencies
@Component(scope = Scope.SINGLETON)
public class DatabaseConfiguration {

    private final ConfigProvider config;

    @Inject
    public DatabaseConfiguration(ConfigProvider config) {
        this.config = config;
    }

    @Produces(scope = Scope.SINGLETON, name = "mysql")
    public DataSource mysqlDataSource() {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.getMysqlUrl());
        hikariConfig.setUsername(config.getMysqlUsername());
        hikariConfig.setPassword(config.getMysqlPassword());
        return new HikariDataSource(hikariConfig);
    }

    @Produces(scope = Scope.SINGLETON, name = "postgres")
    public DataSource postgresDataSource() {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.getPostgresUrl());
        return new HikariDataSource(hikariConfig);
    }
}

// Injecting factory-produced components
@Component(scope = Scope.SINGLETON)
public class UserRepository {
    @Inject
    public UserRepository(@Named("mysql") DataSource dataSource) {
        // Uses mysqlDataSource from factory
    }
}

// Factory methods can have dependencies injected
@Component
public class ServiceFactories {
    @Produces(scope = Scope.SINGLETON)
    public CacheService cacheService(RedisClient redis, MetricsCollector metrics) {
        CacheService service = new CacheServiceImpl(redis);
        service.enableMetrics(metrics);
        return service;
    }
}

// Static factory method with validation (same class)
@Component
public class Database {
    private final Connection connection;

    // Private constructor - force factory usage
    private Database(Connection connection) {
        this.connection = connection;
    }

    @Produces(scope = Scope.SINGLETON)
    public static Database create(ConfigProvider config) {
        // Validation before construction
        if (config.getUrl() == null || config.getUrl().isEmpty()) {
            throw new IllegalArgumentException("Database URL is required");
        }

        // Complex initialization
        Connection conn = DriverManager.getConnection(
            config.getUrl(),
            config.getUsername(),
            config.getPassword()
        );
        conn.setAutoCommit(false);

        return new Database(conn);
    }
}
```

## Development Environment

The project is configured for IntelliJ IDEA but supports any Java IDE.

**IntelliJ IDEA Setup:**
1. Import as Maven project
2. Enable annotation processing: Settings → Build → Compiler → Annotation Processors
3. Build project to trigger code generation
4. Generated code appears in `target/generated-sources/annotations`
