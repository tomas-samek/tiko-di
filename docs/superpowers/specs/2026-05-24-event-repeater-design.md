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
- Cached payload semantics — last handler return value (or originating event for `void` handlers) is the tick payload.
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
- Handler returns `void` → the originating event instance is used as the payload.
- Spread (`@EventTrigger(spread = true)`) applies as usual to the cached value.
- Guards (`@EventTrigger(guard = …)`) are evaluated per-tick with `(payload, originatingEvent)`.

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

- A per-handler `ScheduledFuture<?>` field plus a payload-cache field.
- A wrapper around the handler's dispatch that:
  1. Invokes the handler as today.
  2. On success, caches the return value (or the originating event for `void`).
  3. Cancels any prior `ScheduledFuture` for this handler.
  4. Schedules a new `tickHandler_<n>()` runnable at fixed delay using `TikoOptions.scheduledExecutor`.
- A `tickHandler_<n>()` runnable that re-runs each declared `@EventTrigger` (guard + spread + async/sync) against the cached payload.
- Shutdown wiring: `cancel(false)` on each future from the container's existing `@PreDestroy` /`ApplicationEndingEvent` path.

### Concurrency

- Each handler has its own future. A handler that fires while a tick is running: the tick's publish path is independent; the future-replace happens after handler return. No locking required beyond what the bus already provides.
- The payload cache is a single field written by the dispatch thread; readers (tick runnable) see a happens-before via the `ScheduledExecutorService`'s task submission. Mark the field `volatile`.

### Multi-module / `AggregatingContainer`

Repeaters live in the module that declares the handler. Shutdown ordering follows the existing `AggregatingContainer.shutdown()` path — each child container cancels its own futures.

## Compile-time validation

The processor emits a clear error if any of the following fail:

- `every()` does not parse as a `Duration`, or parses to `<= 0`.
- `initialDelay()` is non-empty but does not parse, or parses to `< 0`.
- The annotated method is not also `@EventHandler`.
- The annotated method's enclosing component is not `Scope.SINGLETON`.
- Any `trigger[].eventName` does not resolve (existing `@EventTrigger` validation).
- Any `trigger[].guard` class is unknown or does not implement `EventTriggerGuard`.

Error format follows the standard from CLAUDE.md (location, what's wrong, at least one suggested fix).

## Testing strategy

- **`compile-testing` cases** (in `tiko-processor`):
  - Happy path: handler with `@EventRepeater`, generated code compiles and contains the expected scheduling wiring.
  - Each validation failure above produces the expected diagnostic.
- **Runtime tests** (in `tiko-runtime`):
  - Tick fires after `initialDelay`, then on period — verified with Awaitility.
  - Originating event resets the timer (tick suppressed when handler re-fires within period).
  - Cached payload is used for non-void handlers; originating event for void.
  - Spread, async, and guard semantics from `@EventTrigger` carry through unchanged on each tick.
  - `ApplicationEndingEvent` cancels all active repeaters; no ticks after container shutdown.
  - `TikoOptions.scheduledExecutor` override is honored (use a fake one and assert ticks scheduled on it).
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
- Compile-time errors for: unparseable `every`, missing `@EventHandler`, non-SINGLETON scope, unknown `trigger.eventName`, unknown `trigger.guard`.
- Container shutdown cancels all active timers; no ticks fire after `ApplicationEndingEvent`.
- Example module demonstrates the pattern end-to-end with narrated `Main` output.
