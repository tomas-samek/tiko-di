# Per-component shutdown timeout (#106)

## Motivation

`Container.shutdown()` runs every SINGLETON `@PreDestroy` and factory-produced
`AutoCloseable.close()` **inline on the shutdown thread**. A hook that hangs blocks
shutdown forever — a `SIGTERM` never completes. The event-executor drain is already
bounded (`shutdownTimeout`, #48); the per-component hooks are not.

## Conceptual framing

A `@PreDestroy` / `close()` call is the **teardown unit of work** for a component — an
EVENT in the unit-of-work model (#226). "Bound how long
this hook may run" is therefore one instance of the general "bound how long a unit of
work runs," which is also what **#107 (handler timeouts)** is. #106 and #107 are the
same concept applied to two units (teardown vs handler dispatch). #106 establishes the
shared primitive and the failure convention the resiliency cluster (#107–#111) inherits.

## Per-phase timeout policy

`@PostConstruct`, `@EventHandler`, and a teardown hook are all units the same
`BoundedExecution` primitive can bound — but the **on-timeout policy differs by phase**,
which is why they are a family of per-phase knobs, not one "event timeout":

- **Teardown (`@PreDestroy` / `close`, this issue):** best-effort — a timeout logs via
  `ErrorHandler` and shutdown **continues** (the bean is being destroyed anyway).
- **`@PostConstruct` (construction):** must succeed — a timeout means the bean isn't
  constructed, so it is **fail-fast** (a startup error), the opposite policy. A future
  `constructionTimeout` knob, not built here.
- **`@EventHandler` (dispatch):** route the failure, possibly retry — #107 / #108.

So `teardownTimeout` names the teardown (log-and-continue) policy specifically. Shared
mechanism, per-phase limits and policy. Kept lean: only teardown ships now.

## API

One new opt-in knob on `TikoOptions`:

```java
TikoOptions.builder().teardownTimeout(Duration.ofSeconds(5)).build();
```

- **Unset (default) = unbounded** → exactly today's inline behavior; zero overhead and
  zero behavior change unless the user opts in.
- Validated non-negative (mirrors `shutdownTimeout`). `Duration.ZERO` = give the hook no
  time → immediate timeout.
- Getter `teardownTimeout()` returns `null` when unset.
- **Scope: container shutdown only** (SINGLETON teardown). REQUEST/EVENT scope-exit
  teardown stays inline, untouched — that is a far more frequent path and is out of scope.

## Mechanism

A general runtime helper keeps the bounding logic in `tiko-runtime` (unit-testable) and
the generated code thin:

```java
// io.tiko.runtime.BoundedExecution
public static void run(
        Runnable task, Duration timeout, ErrorHandler errorHandler,
        Function<Throwable, ErrorContext> failure)
```

- `timeout == null` → run inline: `try { task.run(); } catch (Throwable t) {
  errorHandler.onError(failure.apply(t)); }` — identical to today, zero overhead.
- otherwise → submit `task` to a single-use daemon thread; `future.get(timeout)`; on
  `TimeoutException` → `future.cancel(true)` (interrupt) and route
  `failure.apply(theTimeoutException)`; on `ExecutionException` → route the cause;
  `finally` shut the thread down. Shutdown then continues to the next component.

`ContainerGenerator.createShutdownMethod` (the SINGLETON teardown — `emitComponentDestroy`
/ `emitFactoryDestroy`) emits a `BoundedExecution.run(...)` call passing
`this.options.teardownTimeout()` and a failure factory lambda
(`t -> new PreDestroyFailure(Bean.class, t)` / the `AutoCloseFailure` variant) instead of
the inline try/catch.
Multi-module is covered automatically — each per-module container's generated shutdown
uses the helper with its own `options`.

## Failure routing

A timed-out hook routes as `PreDestroyFailure` / `AutoCloseFailure` whose **cause is a
`TimeoutException`**, so observability distinguishes a timeout from a thrown failure. A
single WARN is emitted via the `ErrorHandler` channel (#116). This `TimeoutException`-caused
`ErrorContext` is the convention #107 reuses.

## Caveat

A hook that ignores interrupts cannot be forcibly killed — the same JVM contract as the
executor drain. Documented alongside `shutdownTimeout`.

## Testing

- `tiko-runtime` — `BoundedExecutionTest`: inline path (unset → runs, routes a thrown
  failure); bounded path (gated slow task → timeout → interrupt + routes a
  `TimeoutException`-caused failure; fast task → completes, no timeout). Deterministic via
  latches, no `Thread.sleep`.
- `tiko-processor` (compile-testing) — generated shutdown calls `BoundedExecution.run(...)`
  passing `options.teardownTimeout()` and the `PreDestroyFailure` / `AutoCloseFailure`
  factories.
- `tiko-runtime` — `TikoOptions` knob round-trips and rejects a negative duration.

## Out of scope

- REQUEST/EVENT scope-exit hook timeouts.
- Per-hook-type knobs (one timeout covers both `@PreDestroy` and `close()`).
- Per-component annotation override.
- `@PostConstruct` / construction timeout (fail-fast) and `@EventHandler` timeout (#107) —
  same `BoundedExecution` primitive, different per-phase policy; not built now (kept lean).
  Future knobs would be `constructionTimeout` / `handlerTimeout`.
