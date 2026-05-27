# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Tiko DI** is a modern, compile-time dependency injection framework for Java 21+ with event-driven architecture.

**Key Features:**
- **Compile-time validation** - Zero runtime DI exceptions
- **Minimal API surface** - Learn in 15 minutes
- **Event-driven** - Seamless local/distributed event handling
- **Java 21+ only** - Leverages records, sealed classes, pattern matching
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
├── tiko-runtime/          # Runtime container — `Tiko` bootstrap, `TikoContainerImpl`,
│                          # `AggregatingContainer`, `LocalEventBus`, `DefaultErrorHandler`
└── tiko-config/           # YAML-backed @Configuration injection (optional)
```

Event abstractions (`EventBus`, `@EventHandler`, `@EventTrigger`, `Event<T>`, etc.) live
in **tiko-api**; the in-memory implementation is in `tiko-runtime`. There is no separate
`tiko-event-api` / `tiko-event-local` split.

### Module Dependencies

```
tiko-api (no dependencies)
  ↑
tiko-processor (depends on tiko-api, javapoet, auto-service)
  ↑ (annotation processor path)
tiko-runtime (depends on tiko-api)
  ↑
tiko-config (depends on tiko-api, snakeyaml)
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

**Automatic JVM shutdown hook:** `Tiko.create()` registers a JVM shutdown hook by default, so
`ApplicationEndingEvent` and `@PreDestroy` fire on `Ctrl+C` / `SIGTERM` without a user-wired
`Runtime.addShutdownHook`. `ApplicationEndingEvent` fires *before* `@PreDestroy`, so external
cleanup (stop an HTTP server, flush a buffer) belongs in an `@EventHandler(ApplicationEndingEvent)`
subscriber. Opt out with `TikoOptions.builder().registerShutdownHook(false)` for embedded use or tests.

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

### Coding Style

The project uses **Spotless + Palantir Java Format** (configured in the root `pom.xml`).
The `spotless:check` goal is bound to the **validate** phase, so any Maven invocation —
including `mvn compile`, `mvn test`, `mvn package` (the CI command), and `mvn install` —
fails fast on style violations.

- **Format before committing:** `mvn spotless:apply` (excludes `tiko-bom` because it
  intentionally has no parent: `mvn -pl '!tiko-bom' spotless:apply` from the root).
- **Style facts** (handled automatically by the formatter — don't fight them by hand):
  4-space indent, K&R braces, deterministic import ordering, no unused imports, no
  trailing whitespace, files end with a newline.
- **IDE integration:** the [Palantir Java Format](https://github.com/palantir/palantir-java-format)
  IntelliJ plugin formats on save with the same rules; with that installed, the gate
  should never fire on local commits.
- **Don't bypass the gate.** If the formatter rewrites something in a way that hurts
  readability, fix it via small structural changes (extract a variable, split an
  expression) rather than disabling Spotless. The whole point is a single mechanical
  source of truth — local exceptions defeat that.

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

### Java 21+ Feature Usage

- **Records** — for immutable DTOs, descriptors, lifecycle events, error contexts.
- **Sealed classes / interfaces** — for closed type hierarchies (e.g. `ErrorContext`
  + non-sealed `TransportError` escape hatch).
- **Pattern matching for `instanceof`** — wherever a downcast follows a type check.
  No `if (x instanceof Foo) { Foo f = (Foo) x; ... }` patterns.
- **Switch expressions** — arrow syntax for non-trivial dispatch.
- **Text blocks** — for multi-line strings, especially in code-generation templates
  and diagnostic messages.
- **`var` for local variables** — prefer `var` when the right-hand side makes the
  type obvious (constructors, static factories, named getters returning typed
  results). Don't use `var` when the type isn't visible at the call site. Fields,
  parameters, and return types stay explicit (the language requires it).
- **Enum constants over string `.name()`** — compare
  `getKind() == ElementKind.RECORD_COMPONENT`, never
  `getKind().name().equals("RECORD_COMPONENT")`. Applies to any enum.
- **For-loops vs. Streams.** For-loops are correct when: the body throws checked
  exceptions (Stream APIs can't propagate them cleanly), the loop has side effects
  on a shared builder, recursion with early-return is needed, or reverse iteration
  / explicit indexing matters. Streams are correct for pure
  filter/map/reduce/collect pipelines.

### Annotation Retention

- Annotations the processor reads are `RetentionPolicy.SOURCE`. There is no good
  reason for a compile-time concern to leak into runtime bytecode.
- `@PostConstruct` and `@PreDestroy` are `RUNTIME` because the container invokes
  them via generated code at runtime — that is the bar for keeping `RUNTIME`.
- When adding a new annotation, audit it: if the bytecode doesn't read it, it must
  be `SOURCE`.

### Logging in Framework Code

- All framework output goes through `java.util.logging.Logger`. Never
  `System.err.println` or `e.printStackTrace()` in framework or generated code.
- Default namespace: `io.tiko.events`, or a per-subsystem name like
  `io.tiko.config`.
- Use the lazy-holder pattern to defer `LogManager` init cost on cold start:
  ```java
  private static final class LoggerHolder {
      static final Logger LOG = Logger.getLogger("io.tiko.events");
  }
  ```
- Annotation processor failures format their stack via
  `TikoAnnotationProcessor.formatStackTrace(Throwable)` (multi-line `\n  at …`
  with cause chain). Do not embed `Arrays.toString(e.getStackTrace())` in
  messager messages.

### Generated Code Markings

Every top-level type emitted by an annotation processor carries
`@javax.annotation.processing.Generated(...)`. Use the shared helper
`GeneratorAnnotations.generatedBy(GeneratorClass.class)` (in
`tiko-processor/util`). This lets IDEs grey out generated sources and coverage
tools exclude them.

### Interface Default Methods

Default methods on `tiko-api` interfaces are one-liners — typically a delegation
to another method on the same interface, or a return of a singleton instance.
Executable fallback logic (e.g. a JUL-backed error handler) goes in a
package-private named class (see `FallbackErrorHandler`), not inline in the
interface body. Keeps the API surface free of implementation.

### YAML Loading

`Yaml` instances must be constructed with an explicit `SafeConstructor`, even
though SnakeYAML 2.x's default constructor is already safe today. Spelling out
the safe-load intent makes it a code-level invariant rather than a
version-pinning one.

### Method Length

Methods longer than ~50 LOC that mix multiple distinct concerns (e.g. reading
three resources, decoding plus validating, parsing plus dispatching) should be
split into private helpers along concern boundaries. Helpers should be named for
what they do, not where they're called from.

### Testing Strategy

- **Framework: JUnit 5 only.** No JUnit 4, no mixing. AssertJ for assertions
  (no Hamcrest, no JUnit's `Assertions`).
- **Class naming:** `xxxTest` for unit tests, `xxxIT` for integration tests.
  Surefire runs `*Test` at the `test` phase (fast lane: `mvn test`). Failsafe runs
  `*IT` at the `integration-test` / `verify` phases (`mvn verify`). Both runners are
  wired in the root `pom.xml` and inherited by every module — no per-module plumbing.
  A misnamed `*IT.java` won't be discovered by surefire, so it will only run under
  `mvn verify`; check the output to confirm your IT actually ran.
- **Method naming: camelCase, no underscores.** New tests use
  `applicationStartedPublishedOnceAfterContainerBoot`, not
  `application_started_published_once`. Existing snake_case tests stay until
  they're touched for other reasons; don't churn the tree just to rename.
- **No `@Disabled` tests.** Fix or delete.
- **No bare `Thread.sleep`.** Either remove if the assertion is trivially
  satisfied (e.g. `>= Duration.ZERO`), or use Awaitility:
  `await().atMost(...).until(...)` for condition polls,
  `await().pollDelay(...).until(() -> true)` for intentional pauses where no
  observable signal exists.
- **Parameterize multi-assertion methods.** Multiple `assertThat` calls in one
  test body covering distinct cases → `@ParameterizedTest` + `@MethodSource`
  returning `Arguments.of(name, ...)` so failures report which row failed.
- **Annotation-processor coverage:** use Google's `compile-testing` to compile
  sample sources and assert on generated output. Negative paths (invalid scopes,
  missing deps, circular deps, bad config) need explicit tests.
- **No test side effects bleed across tests.** Reset system properties; use
  `@TempDir` for filesystem fixtures; no ThreadLocal contamination.
- **Verify error messages are helpful** — see "Error Message Format" above.

### Adding Dependencies

When introducing a new third-party dependency, look up the latest stable version
on Maven Central (or `mvnrepository.com`) before writing the `<version>` into
the pom. Don't copy a version from the first Stack Overflow result or a sibling
project — those go stale.

- Pin the version in `tiko-bom/pom.xml` and the root `pom.xml`
  `<dependencyManagement>` (the project is BOM-managed).
- Confirm the artifact's license is compatible (Apache 2.0, MIT, BSD, EPL all
  fine; LGPL/GPL/SSPL need a separate discussion).
- For existing dependencies, use `mvn versions:display-dependency-updates` to
  spot bumps, and `mvn versions:set -DprocessAllModules=true` for the bump
  itself. Don't string-replace versions in `pom.xml` by hand.

### Issue Writing

Issue bodies: scope + concrete file list + acceptance + out-of-scope. Rationale lives in linked predecessor issues, not duplicated. **No "## Probable cause" or "## Suggested fix" sections** — symptoms and observable facts only. Speculation in the body biases whoever picks the issue up; let them analyse the evidence with a clean head. Acceptance describes the user-visible outcome, not the proposed mechanism.

The full discipline lives in two playbooks:
- [docs/qa-playbook.md](./docs/qa-playbook.md) — surfacing bugs, structured QA passes, issue body template.
- [docs/issue-fix-playbook.md](./docs/issue-fix-playbook.md) — reading a filed issue without anchoring, four-phase fix workflow, common traps.

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
