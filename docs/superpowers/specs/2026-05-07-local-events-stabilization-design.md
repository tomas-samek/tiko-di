# Local events stabilization — design

**Issues:** #43 (`@EventHandler(async = true)` ignored, unbounded executor), #44 (`LocalEventBus.publish()` doesn't isolate handler exceptions or log them)
**Milestone:** Phase 2 — Configuration & distributed events
**Prerequisite:** #47 (`Container.shutdown()` idempotency / construction-destruction race) lands first
**Followups:** #48 (configurable shutdown timeout), `LifecycleError` / `ConfigError` / `ScopeError` wiring (separate issue, not yet filed)

## Context

`LocalEventBus.publish()` does not isolate handler exceptions, contradicting the documented `@EventHandler` contract. The annotation processor silently ignores `@EventHandler(async = true)`, also contradicting the Javadoc. The existing `EventChainContext.ASYNC_EXECUTOR` (used today for `@EventTrigger(async = true)`) is an unbounded `newCachedThreadPool` — under burst load it spawns unlimited threads.

Both gaps undermine the framework's "compile-time safety with runtime simplicity" pitch: APIs that lie to callers, or framework infrastructure that's a footgun under load. They are tightly coupled because (a) the same override mechanism applies to both, and (b) the async dispatch path needs the error hook to avoid silently swallowing exceptions in `CompletableFuture` chains.

This spec covers both, in two sequential PRs sharing one design.

## Scope

### In scope

- New public API: `TikoOptions`, `ErrorHandler`, sealed `ErrorContext` hierarchy, `EventHandlerError`, `EventHandlerInfo`.
- `LocalEventBus.publish()` per-handler isolation (sync path).
- Generated dispatcher per-handler isolation with rich `EventHandlerInfo`.
- `Tiko.create(TikoOptions)` overload; existing `Tiko.create()` and `Tiko.create(ConfigSource)` overloads stay as convenience entry points.
- `@EventHandler(async = true)` honoured: routed to a container-owned `ExecutorService`.
- Default bounded executor with documented sizing and `CallerRunsPolicy`.
- User-supplied `ExecutorService` via `TikoOptions.eventExecutor(...)`.
- Retire static `EventChainContext.ASYNC_EXECUTOR`; `@EventTrigger(async)` and `@EventHandler(async)` share the same container executor.
- Async error path: `CompletableFuture.whenComplete(...)` routes failures to the configured `ErrorHandler`. No silent swallow anywhere.
- Container owns default-executor lifecycle; user owns supplied-executor lifecycle.
- Origin chain (`Event<?>`) preserved across async thread hops.
- Documentation updates: `@EventHandler` Javadoc rewrite, README error-handling section, new record/interface Javadocs.

### Out of scope

- `LifecycleError`, `ConfigError`, `ScopeError` wiring — these categories ship in follow-up PRs alongside the code that emits them. `ErrorContext` permits only `EventHandlerError` in this work; new permits are added when each emitter is designed.
- Async error-handler invocation (the `ErrorHandler` itself runs synchronously on whatever thread reported the error). Considered and dropped.
- Retry semantics, dead-letter queues.
- Configurable executor-shutdown timeout (followup #48; hardcoded to 10 seconds in PR 2).
- Distributed event bus (Kafka).
- Per-tenant executor isolation. The design is compatible with future tenancy — each tenant's container will own its own executor — but no work happens in this spec.
- Framework-runs-on-its-own-thread architecture redesign. Considered and dropped.

## New public API

All new types live in `io.tiko` (in module `tiko-api`).

### `EventHandlerInfo`

```java
package io.tiko;

public record EventHandlerInfo(
    Class<?> declaringClass,
    String methodName,
    Class<?> eventType,
    boolean async
) {}
```

Populated at codegen time from constants the processor already has. Zero runtime reflection. One allocation per error (the surrounding `EventHandlerError` record).

### Sealed `ErrorContext` hierarchy

```java
package io.tiko;

public sealed interface ErrorContext permits EventHandlerError {
    Throwable cause();
}

public record EventHandlerError(
    EventHandlerInfo handler,
    Object event,
    Throwable cause
) implements ErrorContext {}
```

Only `EventHandlerError` is permitted in this work. Future framework-error categories (`LifecycleError`, `ConfigError`, etc.) get their own permits added in follow-up PRs alongside the code that emits them. Adding a permit is a breaking change for users with exhaustive `switch` — but a compile-time-loud one, which is the desired contract: when a new framework error category appears, users are told to handle it.

Speculating on permit shapes now (without the wiring code that proves the shape is right) is deferred — design field shapes when designing the emitter.

### `ErrorHandler`

```java
package io.tiko;

@FunctionalInterface
public interface ErrorHandler {
    void onError(ErrorContext context);
}
```

**Contract** (locked into Javadoc):

- Invoked for both sync handler exceptions and async handler/trigger exceptions.
- Return type is `void` — the hook **cannot** influence dispatch flow. It is for logs, metrics, alerts. Use `@EventTrigger` + `EventTriggerGuard` to branch on outcomes; do not throw from handlers as a control-flow signal.
- Implementations should be fast and non-throwing. An exception thrown *from* the `ErrorHandler` is caught by the framework, logged at ERROR via slf4j, and otherwise suppressed — preventing error-handler-of-error-handler regress.
- Synchronous: invoked on whatever thread surfaced the error (publisher thread for sync handlers, executor thread for async handlers). No additional thread-hop.

**Default implementation**: lives in `tiko-event-local`, package-private class `Slf4jWarnErrorHandler`. Logs at WARN with structured fields: declaring class, method name, event class, throwable. Used when `TikoOptions.errorHandler(...)` is not set. Lives in `tiko-event-local` (not `tiko-api`) because slf4j is a transitive dependency there; `tiko-api` stays zero-dep by intent.

### `TikoOptions`

Builder pattern, immutable result, room to grow:

```java
package io.tiko;

import java.util.Objects;
import java.util.concurrent.ExecutorService;

public final class TikoOptions {
    private final ConfigSource configSource;
    private final ErrorHandler errorHandler;
    private final ExecutorService eventExecutor;     // PR 2 — does not exist in PR 1

    private TikoOptions(Builder b) {
        this.configSource = b.configSource;
        this.errorHandler = b.errorHandler;
        this.eventExecutor = b.eventExecutor;
    }

    public ConfigSource configSource()     { return configSource; }
    public ErrorHandler errorHandler()     { return errorHandler; }
    public ExecutorService eventExecutor() { return eventExecutor; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private ConfigSource configSource;
        private ErrorHandler errorHandler;
        private ExecutorService eventExecutor;

        public Builder configSource(ConfigSource s) {
            this.configSource = Objects.requireNonNull(s, "configSource");
            return this;
        }

        public Builder errorHandler(ErrorHandler h) {
            this.errorHandler = Objects.requireNonNull(h, "errorHandler");
            return this;
        }

        public Builder eventExecutor(ExecutorService e) {  // PR 2
            this.eventExecutor = Objects.requireNonNull(e, "eventExecutor");
            return this;
        }

        public TikoOptions build() { return new TikoOptions(this); }
    }
}
```

`Tiko` gains:

```java
public static Container create(TikoOptions options);
```

The existing `Tiko.create()` and `Tiko.create(ConfigSource)` are kept as convenience overloads. Internally they construct a default `TikoOptions` (no `errorHandler`, no `eventExecutor` — defaults applied by container). No deprecation in this work; revisit when `TikoOptions` accumulates more knobs.

### `Container.getEventExecutor()` (PR 2)

Added to the `Container` interface:

```java
ExecutorService getEventExecutor();
```

Returns the executor used for async event handling — either the user-supplied one or the default. Generated dispatcher code uses this to submit async tasks. Public so user code (e.g. an `@EventHandler` that wants to schedule its own work on the framework executor) can read it; users mutating the executor's state through this reference is their responsibility.

`Container.getErrorHandler()` is **not** added to the public interface — it's only needed internally by generated code. Generated container exposes it as a package-private accessor or via a known internal interface; the public `Container` API stays as small as possible.

## PR breakdown

### PR 1 (#44) — Sync error isolation

**New types:** `TikoOptions` (with only `configSource` and `errorHandler` fields), `ErrorHandler`, `ErrorContext` sealed interface, `EventHandlerError`, `EventHandlerInfo`.

**Modified files:**
- `tiko-api/src/main/java/io/tiko/Tiko.java` — new `create(TikoOptions)` overload; existing overloads delegate to it with default options. Reflectively constructs `Slf4jWarnErrorHandler` when `TikoOptions.errorHandler()` is `null`, then passes the resolved handler into the generated container constructor (already a reflective code path).
- `tiko-event-local/src/main/java/io/tiko/event/local/LocalEventBus.java` — `publish()` gains per-callback try/catch. The bus does **not** take `ErrorHandler` at construction; the catch path logs programmatic-subscriber failures directly via slf4j WARN (mimicking the default handler's text). The rich `ErrorHandler` path is owned by generated dispatchers via `container.getErrorHandler()`. No constructor change — keeps the existing reflective no-arg construction in `Tiko.create()` untouched.
- `tiko-event-local/src/main/java/io/tiko/event/local/Slf4jWarnErrorHandler.java` (new, package-private) — default `ErrorHandler` impl. Constructed reflectively from `Tiko.create()` when no user override is supplied. Lives in `tiko-event-local` so `tiko-api` stays slf4j-free.
- `tiko-processor/src/main/java/io/tiko/processor/generator/EventRegistryGenerator.java` — emit `HANDLER_INFO_<n>` static constants; wrap each dispatcher's handler invocation in try/catch that routes to `container.getErrorHandler().onError(new EventHandlerError(...))`.
- `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java` — generate `getErrorHandler()` accessor; thread the configured `ErrorHandler` through container construction. Hook receives the value from `TikoOptions.errorHandler()` if set, else default.
- `tiko-api/src/main/java/io/tiko/annotations/EventHandler.java` — Javadoc rewrite (lines 66-68 and surrounds).
- `README.md` — new "Error handling" subsection in the events area.

**Test additions:**
- `LocalEventBus`: register two handlers on same event type, first throws, assert second runs and publisher does not see the exception.
- Default `ErrorHandler` (slf4j WARN): use slf4j-test/memory appender; assert the WARN line includes class/method/event type.
- Custom `ErrorHandler` via `TikoOptions.errorHandler(...)` flows through to generated dispatcher; recording impl asserts it received `EventHandlerError` with correct `EventHandlerInfo`.
- `ErrorHandler.onError` itself throws → exception swallowed and logged at ERROR; subsequent handlers still run.
- Programmatic `EventCallback` (not via `@EventHandler`) that throws → bus's defense-in-depth catch logs at WARN via slf4j (verifiable with a memory appender). Subsequent handlers on the same event still run.

**Trigger emission stays sync** in PR 1; nothing in the trigger codegen changes.

### PR 2 (#43) — Async dispatch + bounded executor

**New API on `TikoOptions`:** `eventExecutor` field + builder method.

**New API on `Container`:** `getEventExecutor()`.

**Modified files:**
- `tiko-api/src/main/java/io/tiko/TikoOptions.java` — add `eventExecutor` field/builder method.
- `tiko-api/src/main/java/io/tiko/Container.java` — add `getEventExecutor()` to the interface.
- `tiko-runtime/src/main/java/io/tiko/runtime/EventChainContext.java` — remove static `ASYNC_EXECUTOR`. Add `executor` / `errorHandler` / `info` parameters to `publishAsync`, `publishSpreadAsync`. The async helpers attach `whenComplete` and route exceptional completions to `errorHandler.onError(new EventHandlerError(info, payload, throwable))`.
- `tiko-runtime/src/main/java/io/tiko/runtime/DefaultEventExecutorFactory.java` (new) — package-private factory that builds the bounded `ThreadPoolExecutor` with the documented defaults. Used by generated container when `TikoOptions.eventExecutor` is absent.
- `tiko-processor/src/main/java/io/tiko/processor/generator/EventRegistryGenerator.java`:
  - `HANDLER_INFO_<n>.async` reflects the actual `@EventHandler(async)` value.
  - Dispatcher branches on `handler.async`: sync path unchanged from PR 1; async path wraps the handler invocation in `CompletableFuture.runAsync(..., container.getEventExecutor())`, re-enters the chain context inside the task, attaches `whenComplete` for error routing.
  - `emitTrigger` async branch passes `container.getEventExecutor()`, `container.getErrorHandler()`, and `HANDLER_INFO_<n>` into the new helper signatures.
- `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java`:
  - Generate `getEventExecutor()` field, accessor, constructor wiring (uses `TikoOptions.eventExecutor()` if set, else `DefaultEventExecutorFactory.create()`).
  - Track `boolean ownsEventExecutor` to know whether shutdown should call `executor.shutdown()`.
  - In the generated `shutdown()` method (now hardened by #47), if `ownsEventExecutor`, call `executor.shutdown(); executor.awaitTermination(10, SECONDS); if (!executor.isTerminated()) executor.shutdownNow();`.
- `tiko-api/src/main/java/io/tiko/annotations/EventHandler.java` — Javadoc on `async()` describes the executor and override.
- `README.md` — async events subsection mentioning defaults and override mechanism.

**Test additions:**
- `@EventHandler(async = true)` runs off the publisher thread (record `Thread.currentThread().getName()` in handler; assert it starts with `tiko-event-async-`).
- Default executor is bounded: submit 5000 async events; sample `ManagementFactory.getThreadMXBean()` filtered by name — assert peak count never exceeds `cores * 4`. (Sized to actually exercise the queue.)
- `TikoOptions.eventExecutor(custom)` is used; provide a recording executor that counts submissions; assert tasks were submitted to it.
- Async handler throws → `ErrorHandler` invoked with `EventHandlerError`. **Discard the future reference** in the test to verify silent-swallow is impossible even when nobody consumes the future.
- `@EventTrigger(async)` from inside an async handler: `eventWrapper.getOriginChain()` shows the full chain across thread hops.
- Container shutdown: default executor terminates; user-supplied executor is **not** shut down (assert it still accepts tasks post-`container.shutdown()`).

## Internal design

### `LocalEventBus.publish()` (PR 1)

```java
private static final Logger LOG = LoggerFactory.getLogger(LocalEventBus.class);

@Override
public <T> void publish(T event) {
    if (event == null) return;
    Class<?> eventType = event.getClass();
    List<EventCallback<?>> callbacks = handlers.get(eventType);
    if (callbacks == null) return;

    for (EventCallback<?> callback : callbacks) {
        @SuppressWarnings("unchecked")
        EventCallback<T> typedCallback = (EventCallback<T>) callback;
        try {
            typedCallback.handle(event);
        } catch (Exception e) {
            // Defense-in-depth: the generated dispatcher already catches its own throws
            // and routes them to ErrorHandler with a rich EventHandlerInfo. This branch
            // only fires for programmatic EventCallback subscribers (no @EventHandler,
            // no compile-time info). Log directly via slf4j — keeping the bus
            // independent of ErrorHandler keeps construction simple and the
            // responsibility split clean.
            LOG.warn("Programmatic event callback threw on event {}: {}",
                eventType.getName(), e.toString(), e);
        }
    }
}
```

Catches `Exception`, not `Throwable`. `Error`s (OOM, `StackOverflowError`) propagate — those mean the JVM is sick and surfacing them is the right move.

The rich `ErrorHandler` path lives in the generated dispatcher and uses `container.getErrorHandler()`. The bus is only responsible for the corner case where someone subscribes via `eventBus.subscribe(...)` programmatically without going through `@EventHandler`.

### Generated dispatcher (PR 1, sync only)

```java
private static void dispatch_OrderService_onOrderCreated_3(
        EventBus eventBus, TikoContainerImpl container, OrderCreatedEvent event) {
    Event<OrderCreatedEvent> __wrapper = EventChainContext.wrap(event);
    Event<?> __previous = EventChainContext.enter(__wrapper);
    try {
        OrderService __handler = container.getOrderService();
        try {
            __handler.onOrderCreated(event);
            // ... trigger emission as today, sync only ...
        } catch (Exception __t) {
            ErrorHandler __err = container.getErrorHandler();
            try {
                __err.onError(new EventHandlerError(HANDLER_INFO_3, event, __t));
            } catch (Exception __inner) {
                // ErrorHandler itself threw — log via the framework slf4j logger and suppress.
                LoggerFactory.getLogger("io.tiko.events").error("ErrorHandler.onError threw", __inner);
            }
        }
    } finally {
        EventChainContext.exit(__previous);
    }
}
```

### Generated dispatcher (PR 2, async path)

```java
private static void dispatch_OrderService_onOrderCreated_3(
        EventBus eventBus, TikoContainerImpl container, OrderCreatedEvent event) {
    Event<OrderCreatedEvent> __wrapper = EventChainContext.wrap(event);
    Event<?> __previous = EventChainContext.enter(__wrapper);
    try {
        OrderService __handler = container.getOrderService();
        ExecutorService __exec = container.getEventExecutor();
        ErrorHandler __err = container.getErrorHandler();

        CompletableFuture
            .runAsync(() -> {
                Event<?> __asyncPrev = EventChainContext.enter(__wrapper);
                try {
                    __handler.onOrderCreated(event);
                } finally {
                    EventChainContext.exit(__asyncPrev);
                }
            }, __exec)
            .whenComplete((__r, __t) -> {
                if (__t != null) {
                    Throwable __cause = (__t instanceof CompletionException && __t.getCause() != null)
                        ? __t.getCause() : __t;
                    try {
                        __err.onError(new EventHandlerError(HANDLER_INFO_3, event, __cause));
                    } catch (Exception __inner) {
                        LoggerFactory.getLogger("io.tiko.events").error("ErrorHandler.onError threw", __inner);
                    }
                }
            });
        // Future reference deliberately discarded — whenComplete already routed any error.
    } finally {
        EventChainContext.exit(__previous);
    }
}
```

`CompletionException` unwrapping: `CompletableFuture` wraps thrown exceptions in `CompletionException`. Users want to see their original throwable in `EventHandlerError.cause()`, so unwrap before passing.

### Default executor settings

| Knob | Value | Rationale |
|---|---|---|
| Core pool size | `Math.max(2, Runtime.getRuntime().availableProcessors() / 2)` | Keep capacity warm even on small machines |
| Max pool size | `Runtime.getRuntime().availableProcessors() * 4` | Bounded; accommodates short bursts |
| Keep-alive | `60` seconds | Reclaim idle threads |
| Queue | `LinkedBlockingQueue` capacity 1024 | Bounded — surfaces backpressure; large enough to swallow brief bursts |
| Rejection policy | `CallerRunsPolicy` | Slows publisher under sustained load instead of dropping events. Documented prominently. |
| Thread factory | daemon, named `tiko-event-async-{n}` | JVM exits cleanly even with stuck async tasks |

Implemented in `DefaultEventExecutorFactory`; documented in README and on `TikoOptions.Builder.eventExecutor` Javadoc.

### Executor lifecycle

- Container constructs default executor (or stores user-supplied) at construction time.
- `Container.shutdown()` (hardened by #47) shuts down only the default — user-supplied executors are not touched.
- Shutdown sequence (default only): `executor.shutdown(); executor.awaitTermination(10, SECONDS); if (!isTerminated()) executor.shutdownNow();`. The 10-second timeout is hardcoded; #48 tracks making it configurable.
- The sequence runs *after* `@PreDestroy` execution: in-flight async work that itself uses singletons should see them still alive when it completes (until shutdown initiates). After `executor.shutdown()`, no new tasks accepted; in-flight tasks drain.
- Order under #47's hardened shutdown:
  1. `stopped = true`
  2. Wait for active construction to drain
  3. Mark all singletons as `destroying`
  4. Publish `ApplicationEndingEvent` (handler exceptions tolerated by PR 1's bus isolation)
  5. Run `@PreDestroy` for each singleton (LIFO, per existing logic)
  6. Shut down default executor (PR 2)

### `EventChainContext` retirement

`ASYNC_EXECUTOR` static field removed. Helper signatures change:

```java
public static CompletableFuture<Void> publishAsync(
    EventBus bus, Object payload, Event<?> origin,
    ExecutorService executor, ErrorHandler errorHandler, EventHandlerInfo info);

public static CompletableFuture<Void> publishSpreadAsync(
    EventBus bus, Object payload, Event<?> origin,
    ExecutorService executor, ErrorHandler errorHandler, EventHandlerInfo info);
```

Inside, the same `runAsync(..., executor)` + `whenComplete` pattern routes exceptional completions to `errorHandler.onError(new EventHandlerError(info, payload, cause))`. `info` here is the `EventHandlerInfo` of the handler whose `@EventTrigger` triggered the publish — its identity is the most useful diagnostic for "which trigger failed."

Synchronous helpers (`publishWithOrigin`, `publishSpreadWithOrigin`) are unchanged.

### No-silent-swallow contract

Restated as a hard invariant the spec commits to:

> Every exception thrown from user code is observable. For `@EventHandler` methods (sync and async) and `@EventTrigger`-driven publishes, the exception is routed to `ErrorHandler.onError` exactly once before the framework drops the dispatch context. For programmatic `EventCallback` subscribers (no `@EventHandler` annotation, no compile-time identity), the exception is logged at WARN via slf4j with the event type. In no path does an exception silently disappear.

Enforcement points:
- Generated dispatcher's local try/catch (sync and async) → `ErrorHandler`.
- `LocalEventBus.publish()`'s defense-in-depth catch (programmatic subscribers) → slf4j WARN.
- `whenComplete` on every async-published future → `ErrorHandler` (futures may be discarded; the error has already been reported).
- `ErrorHandler.onError` throws are caught and logged at ERROR via slf4j — preventing handler-of-handler recursion.

## Documentation

### `@EventHandler` Javadoc

Replace the misleading paragraph at lines 66-68. New text:

> **Error handling:** If an event handler throws, the exception is routed to the configured `ErrorHandler` (default: slf4j WARN). It does not propagate to the publisher and does not prevent other handlers from running. Async handler exceptions are routed identically. The hook is for observability — do not throw from a handler to signal business state; use `@EventTrigger` and `EventTriggerGuard` instead.

### `@EventHandler(async)` Javadoc

> When `true`, the handler runs on the container's event executor (bounded `ThreadPoolExecutor` by default; override with `TikoOptions.eventExecutor(...)`). The publisher does not wait for the handler to complete. Async handler exceptions are routed to the configured `ErrorHandler`.

### `Tiko` Javadoc

Add an example for `Tiko.create(TikoOptions)`:

```java
TikoOptions opts = TikoOptions.builder()
    .errorHandler(ctx -> myMetrics.recordErrorContext(ctx))
    .eventExecutor(myCustomExecutor)
    .build();
try (Container container = Tiko.create(opts)) { ... }
```

### README

New short subsection "Error handling and the ErrorHandler hook" under the events area:

- What the default does (slf4j WARN).
- How to override (`TikoOptions.errorHandler`).
- Pattern-matching on `ErrorContext` example.
- Explicit "do not throw to drive workflow" guidance.

## Sequencing

1. **#47** — `Container.shutdown()` hardening — must merge to `main` first.
2. **PR 1 (#44)** — sync error isolation. Depends on #47.
3. **PR 2 (#43)** — async dispatch + bounded executor + retire static. Depends on PR 1 (uses `TikoOptions`, `ErrorHandler`, `EventHandlerError`).
4. **#48** (followup) — configurable shutdown timeout. Depends on PR 2.

## Open decisions punted to plan-writing

- Whether `Container.getErrorHandler()` is exposed on the public `Container` interface or kept package-private. (Recommendation: package-private; revisit if a user use-case appears.)
- Naming of the default `ErrorHandler` impl (`Slf4jWarnErrorHandler` proposed; alternatives welcome).
- Exact reflection wiring in `Tiko.create()` for resolving the default `ErrorHandler` and (PR 2) the default executor — single reflective method that builds either or two? Cosmetic, decide during implementation.
