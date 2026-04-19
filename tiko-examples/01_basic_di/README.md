# Example 01: Basic Dependency Injection

This example demonstrates the core features of Tiko DI framework.

## What This Example Demonstrates

### 1. Constructor Injection
- `MessageService` depends on `MessageRepository`
- `AuditService` depends on `RequestContext` and `EventContext`
- Dependencies injected via `@Inject` constructor

### 2. Lifecycle Methods
- `@PostConstruct` - Called after component construction and dependency injection
- `@PreDestroy` - Called before container shutdown
- Execution order respects dependency graph

### 3. Multiple Scopes

| Scope | Lifetime | Examples |
|-------|----------|----------|
| `SINGLETON` | Application lifetime | MessageRepository, MessageService, AuditService |
| `REQUEST` | Request/transaction | RequestContextImpl |
| `EVENT` | Single event processing | EventContextImpl |
| `PROTOTYPE` | Per injection | (not used in this example) |

### 4. Cross-Scope Injection with Automatic Proxies

`AuditService` (SINGLETON) injects:
- `RequestContext` (REQUEST scope) → **Automatic proxy generated**
- `EventContext` (EVENT scope) → **Automatic proxy generated**

The annotation processor will generate proxy classes that delegate to the current scope instance.

### 5. Event Handling

- `MessageCreatedEvent` - Simple event record
- `AuditService.onMessageCreated()` - Event handler with `@EventHandler`
- Events published via `EventBus`

## Component Graph

```
MessageRepository (SINGLETON)
    └─ No dependencies
    └─ Lifecycle: @PostConstruct, @PreDestroy

MessageService (SINGLETON)
    └─ Depends on: MessageRepository
    └─ Lifecycle: @PostConstruct, @PreDestroy

RequestContextImpl (REQUEST)
    └─ No dependencies
    └─ Implements: RequestContext interface (required for proxy)

EventContextImpl (EVENT)
    └─ No dependencies
    └─ Implements: EventContext interface (required for proxy)

AuditService (SINGLETON)
    └─ Depends on: RequestContext (proxy), EventContext (proxy)
    └─ Event handler: onMessageCreated(MessageCreatedEvent)
    └─ Lifecycle: @PostConstruct
```

## Expected Generated Code

When the annotation processor is implemented, it will generate:

### Factory Classes (Isolating)
```
io.tiko.generated.MessageRepositoryFactory
io.tiko.generated.MessageServiceFactory
io.tiko.generated.RequestContextImplFactory
io.tiko.generated.EventContextImplFactory
io.tiko.generated.AuditServiceFactory
```

### Proxy Classes
```
io.tiko.generated.RequestContextProxy
io.tiko.generated.EventContextProxy
```

### Container and Event Registry (Aggregating)
```
io.tiko.generated.TikoContainerImpl
io.tiko.generated.EventRegistry
```

## Running the Example

**Current state:** The example code is ready but won't compile until the annotation processor is implemented.

**After processor implementation:**

```bash
# Build the project
mvn clean compile

# Run the example
mvn exec:java -pl tiko-examples \
  -Dexec.mainClass="io.tiko.examples.basic.Main"
```

## Expected Output

```
======================================================================
Tiko DI - Basic Example
======================================================================

1. INITIALIZING CONTAINER
----------------------------------------------------------------------
[MessageRepository] Constructor called
[MessageRepository] @PostConstruct - Initializing repository
[MessageService] Constructor called with repository: MessageRepository@...
[MessageService] @PostConstruct - Service initialized
[MessageService] Repository has 2 messages
[AuditService] Constructor called
[AuditService] RequestContext type: io.tiko.generated.RequestContextProxy
[AuditService] EventContext type: io.tiko.generated.EventContextProxy
[AuditService] @PostConstruct - Audit service ready

2. RETRIEVING COMPONENTS
----------------------------------------------------------------------

3. DEMONSTRATING REQUEST SCOPE
----------------------------------------------------------------------

>>> Request 1: Creating multiple messages
[RequestContext] Created for request: REQ-abc12345
[EventContext] Created for event: EVT-xyz67890
[MessageRepository] Saved message 3: [1] First message
[AUDIT] Request=REQ-abc12345, Event=EVT-xyz67890, User=user-123 created message 3: First message
[EventContext] Created for event: EVT-def11111
[MessageRepository] Saved message 4: [2] Second message
[AUDIT] Request=REQ-abc12345, Event=EVT-def11111, User=user-123 created message 4: Second message
Request 1 complete - processed 2 messages

>>> Request 2: Creating another message
[RequestContext] Created for request: REQ-ghi22222
[EventContext] Created for event: EVT-jkl33333
[MessageRepository] Saved message 5: [3] Third message
[AUDIT] Request=REQ-ghi22222, Event=EVT-jkl33333, User=user-456 created message 5: Third message
Request 2 complete - total messages: 3

4. AUDIT LOG
----------------------------------------------------------------------
[AUDIT] Request=REQ-abc12345, Event=EVT-xyz67890, User=user-123 created message 3: First message
[AUDIT] Request=REQ-abc12345, Event=EVT-def11111, User=user-123 created message 4: Second message
[AUDIT] Request=REQ-ghi22222, Event=EVT-jkl33333, User=user-456 created message 5: Third message

5. SHUTTING DOWN CONTAINER
----------------------------------------------------------------------
[MessageService] @PreDestroy - Service shutting down
[MessageService] Processed 3 messages
[MessageRepository] @PreDestroy - Cleaning up repository

======================================================================
Example completed successfully
======================================================================
```

## Key Observations

1. **Initialization Order**: Components initialized in dependency order
   - `MessageRepository` first (no dependencies)
   - `MessageService` second (depends on repository)
   - `AuditService` last (depends on contexts via proxies)

2. **Proxy Injection**: Notice the proxy class names in AuditService constructor
   - `RequestContextProxy` instead of `RequestContextImpl`
   - `EventContextProxy` instead of `EventContextImpl`

3. **Scope Behavior**:
   - One REQUEST can contain multiple EVENTs (Request 1 processes 2 events)
   - Each request gets a new `RequestContext` instance
   - Each event gets a new `EventContext` instance
   - SINGLETON components reused throughout

4. **Lifecycle Order**: `@PreDestroy` called in reverse dependency order
   - `MessageService` destroyed before `MessageRepository`

## Design Validation

This example validates several annotation processor requirements:

- ✅ Dependency graph resolution
- ✅ Circular dependency detection (none present)
- ✅ Scope violation detection (REQUEST/EVENT injected into SINGLETON)
- ✅ Interface requirement for proxies (RequestContext, EventContext)
- ✅ Factory code generation pattern
- ✅ Event handler registration
- ✅ Lifecycle method ordering

## Next Steps for Processor Implementation

Use this example for test-driven development:

1. **Scanning Phase**: Find all `@Component` annotated classes
2. **Validation Phase**:
   - Build dependency graph
   - Check for cycles
   - Validate scope rules
   - Verify interfaces for proxies
3. **Generation Phase**:
   - Generate factory classes
   - Generate proxy classes
   - Generate container implementation
   - Generate event registry
4. **Incremental Compilation**: Use `originatingElements` to track dependencies
