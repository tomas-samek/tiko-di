# Example 01: Basic Dependency Injection

The comprehensive feature tour. `Main` walks through nine titled sections covering every
core DI capability; the `expose/`, `teardown/`, and `trigger/` subpackages carry test
fixtures that pin contracts the runtime demo can't show in stdout.

## How to run

```
mvn -pl tiko-examples/01_basic_di exec:java \
    -Dexec.mainClass=io.tiko.examples.basic.Main
```

Assumes you've run `mvn install` once at the repo root. To run the unit + integration
tests for this example only:

```
mvn -pl tiko-examples/01_basic_di test
```

## What `Main` demonstrates (the nine sections)

| # | Section | Capability |
|---|---|---|
| 1 | `INITIALIZING CONTAINER` | SINGLETON eager init + `@PostConstruct` in dependency order; auto-generated proxies for cross-scope deps |
| 2 | `RETRIEVING COMPONENTS` | `container.get(Class)` resolution |
| 3 | `DEMONSTRATING REQUEST SCOPE` | `runInRequestScope` + nested `runInEventScope`; one REQUEST can wrap many EVENTs; each scope gets a fresh `RequestContext` / `EventContext` |
| 4 | `DEMONSTRATING LIFECYCLE EVENTS` | `ApplicationStartedEvent` / `RequestStartedEvent` / `EventStartedEvent` (and the matching `Ending` counterparts) published automatically by the container |
| 5 | `DEMONSTRATING PROVIDER<T>` | Lazy lookup, breaking circular deps, on-demand PROTOTYPE instances |
| 6 | `DEMONSTRATING EVENT CHAINING` | `@EventTrigger`: return-as-payload, guards, `spread = true`, full origin chain via `Event<?>` wrapper (the trigger code itself lives in the `trigger/` subpackage; this section narrates the flow) |
| 7 | `AUDIT LOG` | `EventBus` + `@EventHandler` cross-scope wiring of the AuditService |
| 8 | `SCOPE SUMMARY` | Scope hierarchy + cross-scope injection rules cheat sheet |
| 9 | `SHUTTING DOWN CONTAINER` | `@PreDestroy` fires in **reverse-dep LIFO** order (issue #151); `MessageService` destroyed before `MessageRepository` |

## Component map (the runtime-demo side)

The classes `Main` directly exercises:

```
MessageRepository (SINGLETON, @PostConstruct + @PreDestroy)
  └ no deps

MessageService (SINGLETON, @PostConstruct + @PreDestroy)
  └ depends on MessageRepository

AuditService (SINGLETON, @PostConstruct, @EventHandler)
  ├ depends on RequestContext (REQUEST → auto-proxied, interface required)
  └ depends on EventContext  (EVENT   → auto-proxied, interface required)

RequestContextImpl (REQUEST)   implements RequestContext
EventContextImpl   (EVENT)     implements EventContext

MessageCreatedEvent (record) — payload published by MessageService and observed by AuditService
```

A handful of other root-package classes (`Polyglot`, `PickerConsumer`, `Greeter` / `EnglishGreeter` / `SpanishGreeter`, `Speaker` / `DefaultSpeaker`, `Cache` / `CacheFactories`, `Timestamp` / `TimestampFactory`, `AsyncPing` / `AsyncRecorder` / `AsyncThrower`, `Ping`, `Ticket` / `TicketBooth`, `LifecycleRecorder`, `ShutdownTestCounter`, `ThrowingHandler`) exist as test fixtures for the unit-test layer — they verify `@Named` / `@Pick` qualifier resolution, `@Produces` factory methods, async handler dispatch, and error-routing edges. None of them is required for `Main` to run.

## Subpackage tour (the test-fixture side)

The three subpackages house JUnit 5 fixtures that pin contracts the runtime demo doesn't visualise:

- **`expose/`** — `@Component(expose = {...})` interface routing. Verifies the
  permissive default (every implemented interface is routable), the `expose = {...}`
  whitelist mode, and that multi-interface beans resolve to the same scope-cached
  instance regardless of which interface the caller asks for.

- **`teardown/`** — Lifecycle teardown contract. `LifoSingletonA`/`B`/`C`,
  `LifoRequestA`/`B`/`C`, `LifoEventA`/`B`/`C`, and `LifoFactoryChain*` pin LIFO
  destruction across SINGLETON `@Component` beans, REQUEST/EVENT scopes, and
  `@Produces` factory-produced AutoCloseables (issues #151, #189). `AutoCloseable*Holder`,
  `FakePool*`, `ExplicitWinsBean`, and `ThrowingPreDestroy*` cover implicit
  `close()`, factory cleanup, explicit-over-implicit precedence, and error routing
  through `DefaultErrorHandler`.

- **`trigger/`** — `@EventTrigger` declarative event chains. `OrderTriggerService`
  exercises return-as-payload (`OrderCreatedEvent → OrderValidatedEvent →
  OrderProcessedEvent`), guard predicates (`AmountGuard` on `GuardTestEvent`),
  `spread = true` over `List` payloads (`BatchReceivedEvent → BatchItemEvent`),
  multi-trigger fan-out, and async dispatch. Test classes assert on the
  `Event<?>` wrapper's origin chain.

## Expected output

```
======================================================================
Tiko DI - Basic Example
======================================================================

1. INITIALIZING CONTAINER
----------------------------------------------------------------------
[AuditService] Constructor called
[AuditService] RequestContext type: io.tiko.generated.RequestContextImplProxy
[AuditService] EventContext type: io.tiko.generated.EventContextImplProxy
[AuditService] @PostConstruct - Audit service ready
[MessageRepository] Constructor called
[MessageRepository] @PostConstruct - Initializing repository
[MessageService] Constructor called with repository: io.tiko.examples.basic.MessageRepository@...
[MessageService] @PostConstruct - Service initialized
[MessageService] Repository has 2 messages

2. RETRIEVING COMPONENTS
----------------------------------------------------------------------

3. DEMONSTRATING REQUEST SCOPE
----------------------------------------------------------------------

>>> Request 1: Creating multiple messages
[MessageRepository] Saved message 3: [1] First message
[RequestContext] Created for request: REQ-...
[EventContext] Created for event: EVT-...
[AUDIT] Request=REQ-..., Event=EVT-..., User=user-123 created message 3: First message
[MessageRepository] Saved message 4: [2] Second message
[EventContext] Created for event: EVT-...
[AUDIT] Request=REQ-..., Event=EVT-..., User=user-123 created message 4: Second message
Request 1 complete - processed 2 messages

>>> Request 2: Creating another message
[MessageRepository] Saved message 5: [3] Third message
[RequestContext] Created for request: REQ-...
[EventContext] Created for event: EVT-...
[AUDIT] Request=REQ-..., Event=EVT-..., User=user-456 created message 5: Third message
Request 2 complete - total messages: 3

4. DEMONSTRATING LIFECYCLE EVENTS
----------------------------------------------------------------------
Lifecycle events are automatically published by the container:
  - ApplicationStartedEvent (on container start)
  - RequestStartedEvent/RequestEndingEvent (on request scope)
  - EventStartedEvent/EventEndingEvent (on event scope)
  - ApplicationEndingEvent (on container shutdown)

These enable metrics, logging, and tracing without cluttering business logic.

5. DEMONSTRATING PROVIDER<T> (Lazy Injection)
----------------------------------------------------------------------
Provider<T> enables:
  - Lazy initialization of expensive dependencies
  - Breaking circular dependencies
  - Getting new PROTOTYPE instances on demand

6. DEMONSTRATING EVENT CHAINING
----------------------------------------------------------------------
@EventTrigger enables declarative event workflows:

>>> Publishing OrderCreatedEvent...
    Event chaining flow:
    1. OrderCreatedEvent published
    2. Handler validates -> returns ValidationResult
    3. ValidationResult triggers next handler
    4. Handler processes payment -> returns PaymentProcessedEvent
    5. Origin chain: [OrderCreatedEvent, ValidationResult, PaymentProcessedEvent]

7. AUDIT LOG
----------------------------------------------------------------------
[AUDIT] Request=REQ-..., Event=EVT-..., User=user-123 created message 3: First message
[AUDIT] Request=REQ-..., Event=EVT-..., User=user-123 created message 4: Second message
[AUDIT] Request=REQ-..., Event=EVT-..., User=user-456 created message 5: Third message

8. SCOPE SUMMARY
----------------------------------------------------------------------
Scope hierarchy (longest to shortest lifetime):
  SINGLETON - Application lifetime (e.g., services, repositories)
  REQUEST   - Transaction/batch scope (e.g., DB transaction, HTTP request)
  EVENT     - Single event processing (e.g., one message, one event handler)
  PROTOTYPE - Per injection (e.g., DTOs, temporary objects)

Cross-scope injection:
  SINGLETON -> REQUEST/EVENT = Automatic proxy (requires interface)
  REQUEST -> EVENT = Automatic proxy (requires interface)
  Any scope -> PROTOTYPE = New instance each time

9. SHUTTING DOWN CONTAINER
----------------------------------------------------------------------
[MessageService] @PreDestroy - Service shutting down
[MessageService] Processed 3 messages
[MessageRepository] @PreDestroy - Cleaning up repository

======================================================================
Example completed successfully
======================================================================
```

`REQ-...` / `EVT-...` IDs are random per run; everything else is stable. Construction
order under "INITIALIZING CONTAINER" follows hash-bucket iteration over the SINGLETON
set, which is why `AuditService` appears before `MessageRepository` despite the latter
being the deeper dep — `Main` only triggers eager construction, not a dep-graph walk.
Teardown under section 9 IS dep-graph-ordered (LIFO) per the contract in #151.

## Key behaviour the output shows

- **Cross-scope auto-proxy**. `AuditService` (SINGLETON) sees its `RequestContext` /
  `EventContext` dependencies through proxy types
  (`io.tiko.generated.RequestContextImplProxy` etc.) instead of the concrete impls. Each
  call to a proxy method resolves to the current scope's instance.
- **One request, many events**. Request 1 wraps two `runInEventScope` blocks — two
  distinct `EventContext` instances under a single `RequestContext`. Request 2 starts a
  fresh `RequestContext`.
- **LIFO destroy order**. Section 9 destroys `MessageService` before
  `MessageRepository` because Service depends on Repository — the LIFO contract the
  framework documents.
