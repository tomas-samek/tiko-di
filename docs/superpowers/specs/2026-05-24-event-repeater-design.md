# `@EventRepeater` — periodic self-restarting event triggers

**Status:** Design (v1)
**Date:** 2026-05-24
**Author:** Tomáš Samek

## Problem

Tiko handlers can declaratively chain to subsequent events via `@EventTrigger`. There is no declarative way to express *periodic* emission of events — specifically the "self-restarting heartbeat" pattern:

> When event `E` is processed, schedule a repeating tick to fire one or more events on a period. If `E` arrives again before the next tick, restart the timer.

This pattern shows up in health checks, watchdog timers, staleness detection, "refresh until next user action," and any keep-alive where real activity should suppress the synthetic tick.

Users today have to wire `ScheduledExecutorService`, manage cancellation/restart by hand, and publish to the event bus manually — exactly the boilerplate Tiko's declarative chaining was meant to remove.

## Goals

- Declarative, compile-time-validated periodic event emission attached to an existing `@EventHandler`.
- Reuse `@EventTrigger`'s vocabulary (name, async, spread, guard) for what gets fired on each tick.
- "Originating event resets the timer" semantics by default.
- Cached payload semantics — the handler's last return value is the tick payload; `void` handlers tick fresh no-arg instances of the trigger target.
- Tick events keep the `Event<T>` origin chain back to the originating event, so `getOriginChain()` / `findInChain(...)` work on tick handlers.
- Plays nicely with `TikoOptions` for executor configuration and clean shutdown.

## Non-goals (v1)

- REQUEST and EVENT scoped handlers. v1 supports SINGLETON only; shorter-scoped timers have weirder lifecycle semantics and are deferred.
- Cron-style scheduling. Period is a single `Duration`. No "every Tuesday at 09:00."
- Multiple-event-class restart sets (`restartOn = {A.class, B.class}`). v1 restarts on the originating handler's own event type only.
- Programmatic cancel handles. No injected `PeriodicHandle.reset()` API; everything is declarative.
- A standalone `@Periodic` annotation independent of a handler. If you need "fire at boot," subscribe to `ApplicationStartedEvent` and add `@EventRepeater` to that handler.

The following are deferred candidates for v2 once v1 ships and gets real use:

- REQUEST/EVENT-scoped repeaters (per-scope lifecycle, cancellation on scope exit).
- `restartOn = {OtherEvent.class}` to allow a different event class to reset the timer.
- Cron-style scheduling for "every Tuesday at 09:00" use cases.
- Programmatic `PeriodicHandle` for handlers that want explicit reset/cancel control.

## Public API

### `@EventRepeater` annotation

```java
package io.tiko.annotations;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface EventRepeater {

    /** Period between ticks. Friendly duration string (e.g. "30s", "5m", "PT1H"). Required, must parse to > 0. */
    String every();

    /** Delay before the first tick. Default {@code "0s"} fires immediately after handler return. Same parsing rules as {@code every()}. */
    String initialDelay() default "0s";

    /** One or more triggers fired on each tick. Reuses all @EventTrigger knobs (name, async, spread, guard). */
    EventTrigger[] trigger();
}
```

**Retention is `SOURCE`** — the annotation processor reads it to generate timer-scheduling code; the runtime never reflects on it. Matches Tiko's annotation-retention rule.

### Usage

**Minimum case — fire the same event class on a period:**

```java
@EventHandler
@EventRepeater(
    every = "30s",
    trigger = @EventTrigger(eventName = "HealthCheckRequestedEvent")
)
public HealthState onHealthCheckRequested(HealthCheckRequestedEvent event) {
    return computeHealth();
}
```

**Different tick event with cached payload:**

```java
@EventHandler
@EventRepeater(
    every = "1m",
    initialDelay = "1m",
    trigger = @EventTrigger(eventName = "HealthCheckTick", async = true)
)
public HealthState onHealthCheckRequested(HealthCheckRequestedEvent event) {
    return computeHealth();   // last return value becomes the HealthCheckTick payload on each tick
}
```

**Multiple ticks per period:**

```java
@EventHandler
@EventRepeater(
    every = "30s",
    trigger = {
        @EventTrigger(eventName = "MetricsSampled", async = true),
        @EventTrigger(eventName = "HealthCheckTick")
    }
)
public Snapshot onSampleRequested(SampleRequestedEvent event) {
    return takeSnapshot();
}
```

## Semantics

### When the timer starts

Upon successful return of the `@EventHandler` method. (Aborted handlers — exceptions — do not schedule.)

### When the timer ticks

After `initialDelay()` from the schedule point, then every `every()` thereafter, until cancelled.

### When the timer restarts

Any publish of the originating event type that invokes this handler — anywhere in the application — cancels the in-flight schedule and (post-handler-completion) starts a fresh one.

### Payload on each tick

- Handler returns a value → that value is cached and used as the payload for each tick's `@EventTrigger`.
- Handler returns `void` → each `trigger.eventName` must resolve to a no-arg-constructible type (no-component record, or class with a public no-arg constructor); each tick instantiates a fresh marker via that constructor. This is the natural model for heartbeats / pings where the tick carries no data.
- Spread (`@EventTrigger(spread = true)`) applies as usual to the cached value.
- Guards (`@EventTrigger(guard = …)`) are evaluated per-tick with `(payload, originatingEvent)`.

### Origin chain

Tick events chain back to the event that scheduled them. Mechanism: at handler completion the generated code captures both the return value (or absence thereof) **and** the current `Event<?>` wrapper (the one the event bus established for this handler invocation). The tick runnable publishes through `EventChainContext.publishWithOrigin(bus, payload, capturedWrapper)` — the same primitive `@EventTrigger(async = true)` already uses to thread origin across executor boundaries.

Downstream handlers can call `event.findInChain(OriginatingEvent.class)` on a tick and find the event that started the heartbeat, just as they would for a sync `@EventTrigger` continuation.

### Shutdown

`ApplicationEndingEvent` cancels all active repeater schedules. The scheduled executor drains within `TikoOptions.shutdownTimeout`.

### Duration parsing

`every()` and `initialDelay()` parse via the same coercer used for `tiko.shutdownTimeout` in `tiko-config`. Once #113 (friendly Duration syntax) lands, `"30s"` / `"5m"` / `"1h"` are accepted alongside ISO-8601 (`"PT30S"`, `"PT5M"`). Until then, only ISO-8601 works — implementation order should sequence #113 first.

## Internals

### TikoOptions addition

```java
TikoOptions.builder()
    .scheduledExecutor(ScheduledExecutorService)  // default: single-thread, named "tiko-repeater"
    .build();
```

Tiko-default executor: a single-thread `ScheduledExecutorService` whose thread factory matches whatever `TikoOptions.eventExecutor`'s default uses today (virtual or platform). The repeater inherits that policy rather than choosing independently — keeps thread-model decisions in one place.

Ticks publish through the standard event bus, so any existing `eventExecutor` configuration applies to the resulting handler dispatch (sync vs async per `@EventTrigger.async`).

### Generated code shape

For each handler carrying `@EventRepeater`, the processor adds — into the same generated class that already wires the `@EventHandler` and `@EventTrigger` calls (`EventRegistry_<hash>.java`):

- A per-handler `ScheduledFuture<?>` field, a payload-cache field, and a captured-wrapper field (the `Event<?>` from the originating bus dispatch — needed for the origin-chain story above).
- A wrapper around the handler's dispatch that:
  1. Invokes the handler as today.
  2. On success, caches the return value (or marks the payload-cache as void) **and** captures the current `Event<?>` wrapper via the existing `EventChainContext` accessor.
  3. Cancels any prior `ScheduledFuture` for this handler.
  4. Schedules a new `tickHandler_<n>()` runnable at fixed delay using `TikoOptions.scheduledExecutor`.
- A `tickHandler_<n>()` runnable that, for each declared `@EventTrigger`:
  - For non-void handlers: publishes the cached payload via `EventChainContext.publishWithOrigin(...)` (or `publishSpreadWithOrigin` / `publishAsync` per `spread` / `async` flags).
  - For void handlers: instantiates a fresh `new TriggerEventClass()` per tick and publishes via the same origin-aware primitive.
  - Evaluates guards per-tick before publishing.
- Shutdown wiring: `cancel(false)` on each future from the container's existing `@PreDestroy` / `ApplicationEndingEvent` path.

### Concurrency

The cancel-old-future + replace + schedule-new sequence races when the same handler runs on two threads concurrently (the bus delivers two originating events in close succession). Naïve "no locking" can leave both futures alive or schedule out of order.

v1 implementation must address this — likely `AtomicReference<ScheduledFuture<?>>` with a CAS replace, or a per-handler `ReentrantLock` around the replace-and-schedule region. The payload-cache and captured-wrapper fields should be `volatile` (or sit behind the same lock) so the tick thread sees a consistent snapshot.

Deferring the final design to implementation: #120 (shared MQ events) will surface broader cross-instance coordination needs and may inform the right primitive to share between bus dispatch and repeater scheduling.

### Multi-module / `AggregatingContainer`

Repeaters live in the module that declares the handler. Shutdown ordering follows the existing `AggregatingContainer.shutdown()` path — each child container cancels its own futures.

## Compile-time validation

The processor emits a clear error if any of the following fail:

- `every()` does not parse as a `Duration`, or parses to `<= 0`.
- `initialDelay()` does not parse, or parses to `< 0`.
- The annotated method is not also `@EventHandler`.
- The annotated method's enclosing component is not `Scope.SINGLETON`.
- Any `trigger[].eventName` does not resolve (existing `@EventTrigger` validation).
- Any `trigger[].guard` class is unknown or does not implement `EventTriggerGuard`.
- The handler returns `void` **and** any `trigger[].eventName` resolves to a type without a no-arg constructor (i.e. a record with components, or a class with no public no-arg ctor). Error message points users at three fixes: return the desired payload from the handler, switch the tick event to a no-component record, or add a no-arg constructor.

Error format follows the standard from CLAUDE.md (location, what's wrong, at least one suggested fix).

## Testing strategy

- **`compile-testing` cases** (in `tiko-processor`):
  - Happy path: handler with `@EventRepeater`, generated code compiles and contains the expected scheduling wiring.
  - Each validation failure above produces the expected diagnostic.
- **Runtime tests** (in `tiko-runtime`):
  - Tick fires after `initialDelay`, then on period — verified with Awaitility.
  - Originating event resets the timer (tick suppressed when handler re-fires within period).
  - Cached payload is used for non-void handlers; fresh no-arg marker instance for void handlers.
  - Spread, async, and guard semantics from `@EventTrigger` carry through unchanged on each tick.
  - Tick events chain origin to the originating event: a downstream handler can `event.findInChain(OriginatingEvent.class)` and find it.
  - `ApplicationEndingEvent` cancels all active repeaters; no ticks after container shutdown.
  - `TikoOptions.scheduledExecutor` override is honored (use a fake one and assert ticks scheduled on it).
  - Concurrent originating-event dispatches don't leave duplicate futures alive (whichever concurrency primitive the implementation picks).
- **Example module:**
  - `tiko-examples/14_event_repeater/` — health-check pattern, narrates the cancel-on-fresh-event behavior in `Main`'s output. Wires `@EventRepeater` into a tiny realistic scenario rather than a toy demo.

## Files affected

New:
- `tiko-api/src/main/java/io/tiko/annotations/EventRepeater.java`
- `tiko-examples/14_event_repeater/`
- `tiko-processor/src/test/java/io/tiko/processor/EventRepeaterValidationTest.java`
- `tiko-runtime/src/test/java/io/tiko/runtime/EventRepeaterRuntimeTest.java`

Modified:
- `tiko-runtime/src/main/java/io/tiko/runtime/TikoOptions.java` — add `scheduledExecutor` field/builder
- `tiko-processor/src/main/java/io/tiko/processor/generator/EventRegistryGenerator.java` — fold repeater dispatch wrapping and tick-runnable emission into the existing generator
- `CLAUDE.md` — add `@EventRepeater` to the Event System section under "Core Annotations"
- `tiko-examples/README.md` — list the new example

## Acceptance

- A SINGLETON handler annotated with `@EventRepeater(every = "30s", trigger = @EventTrigger(eventName = "Tick"))` produces a `Tick` event immediately after handler completion (per default `initialDelay = "0s"`), then every 30s thereafter, with the cached payload.
- An explicit `initialDelay = "30s"` defers the first tick by one full period.
- Re-firing the originating event before the next tick elapses suppresses the pending tick and starts a fresh clock from the new handler completion.
- Compile-time errors for: unparseable `every`, missing `@EventHandler`, non-SINGLETON scope, unknown `trigger.eventName`, unknown `trigger.guard`, `void` handler with a trigger target lacking a no-arg constructor.
- A `void` handler ticks fresh no-arg instances of each `trigger.eventName` target.
- Tick events report the originating event via `Event<T>.findInChain(...)` — origin chain is preserved across the scheduling boundary.
- Container shutdown cancels all active timers; no ticks fire after `ApplicationEndingEvent`.
- Example module demonstrates the pattern end-to-end with narrated `Main` output.
