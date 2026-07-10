# Async `@EventHandler` Dispatch Owns a Fresh EVENT Unit (#220) — Design

**Date:** 2026-07-10
**Issue:** #220 (re-scoped 2026-07-09: the REQUEST-forbid half dissolved with the 3-scope
unification; this spec covers the runtime half)
**Milestone:** 0.5.0 — async scope completion & ingest resilience (headline)

## Problem

The decided model — async dispatch is a *scope boundary*; an async `@EventHandler` runs in
its own fresh EVENT unit, the in-process mirror of transport consumption — is documented
(CLAUDE.md scope model, `ThreadPerTopicRunner`'s comment even promises it) but not enforced
at runtime. Generated async dispatch (`EventRegistryGenerator` → `EventChainContext`
helpers) submits the handler body to the executor with **no unit open**:

- Resolving an EVENT-scoped bean inside an async handler throws the #302 frame-not-open
  guard (or, worse, resolves in the *publisher's* frame: `__handler` is resolved on the
  publishing thread before submit, so an EVENT-scoped handler component binds to a unit
  that may be torn down by the time the handler runs).
- No `EventStartedEvent` / `EventEndingEvent` is published for async processing — async
  work is invisible to unit metrics.
- EVENT-scoped beans created during async processing are never torn down (no frame ⇒ no
  `@PreDestroy`).

The Kafka path already does this correctly per consumed message
(`ThreadPerTopicRunner:150` → `container.runInEventScope(...)`). Local async dispatch is
the one hole.

## Decisions (locked during brainstorm)

1. **Fresh unit per retry attempt.** With `@EventHandler(retries = ...)` (#108), each
   attempt runs in its own unit: a retried attempt never sees the failed attempt's dirty
   EVENT state; one `EventStarted`/`Ending` pair per attempt aligns with `attempts()` on
   `EventHandlerError`.
2. **Lifecycle-event handlers are excluded at compile time.** An async handler whose event
   type lives in package `io.tiko.events` dispatches with the *current* (unwrapped) shape.
   Rationale: lifecycle events are framework signals **about** units, not units of work; an
   observer of units must not mint new ones. This makes the recursion loop (async
   `EventStartedEvent` handler → new unit → new `EventStartedEvent` → …) structurally
   impossible at zero runtime cost. Same spirit as #339's aggregator gating.
3. **Detachment, not nesting (`CALLER_RUNS` edge).** When the overflow policy runs an async
   task inline on a borrowed publisher thread that already has a frame open, the async
   unit *suspends* the outer frame (save/clear ThreadLocals), runs, and restores it — the
   outer unit is invisible to the inner and intact afterwards. The public
   `runInEventScope` still throws on re-entry; ARCH-5 (EVENT is single-frame in 0.x) is
   untouched because the swap exists only inside generated dispatch.
4. **Aggregated-setup lifecycle gap accepted.** In multi-module (AggregatingContainer)
   setups, async units get correct frames, per-module bean isolation, and teardown via the
   module container — but publish no lifecycle events (module containers are constructed
   with `publishLifecycleEvents=false` per #339, and the aggregator is not in the async
   dispatch path). Documented; a follow-up issue for publisher-callback plumbing is filed
   at ship time.

## Components

### 1. Generated container: `runInDetachedEventScope(Runnable)`

New **package-private** method emitted by `ContainerGenerator` (registry and container
share `io.tiko.generated`; no `Container`-interface or `tiko-api` change):

- Save the current thread's frame state: the `eventScoped` map ThreadLocal and the
  `__unitFrameOpen` flag.
- Clear both (fresh-thread case: no-op).
- Delegate to the existing `runInEventScope(task)` — reusing, unchanged: the single-frame
  guard, the `publishLifecycleEvents`-gated `EventStartedEvent`/`EventEndingEvent` pair,
  ordered EVENT-bean teardown (`@PreDestroy`, LIFO), and its established
  teardown-exception behavior.
- Restore the saved state in `finally`.

### 2. Registry generator: async dispatch shape

In `EventRegistryGenerator`, for async handlers whose event type is **not** in
`io.tiko.events` (compile-time FQN package check on the handler's event type):

- Wrap the existing `runBody` (chain-context enter → invoke → `@EventTrigger` emissions →
  chain-context exit) in `container.runInDetachedEventScope(() -> { ...runBody... })`.
- Move `__handler` resolution (`container.<getter>()`) from the publisher thread **inside**
  the wrapper — the handler component and its dependencies bind to the async unit.
  EVENT-scoped handler components become first-class for async handlers.
- Ordering: scope bracket outermost, chain context inside — matching the Kafka path
  (lifecycle events publish outside the chain context there too).

Async handlers whose event type **is** in `io.tiko.events` keep the current generated
shape verbatim (no wrapper, `__handler` resolution stays where it is).

Sync dispatch is untouched. The `EventChainContext` helpers
(`runAsyncWithTimeout`/`runAsyncWithRetry`/`runOnce`, DLQ choke points) are untouched —
the wrapper travels inside the `body` lambda they already receive.

### 3. Interplay with shipped resiliency features (no changes to them)

- **Retries (#108):** the wrapper is inside the lambda that `attempt(...)` re-invokes →
  fresh unit per attempt by construction. Backoff waits happen outside any unit.
- **Timeouts (#107):** interrupt → handler throws → the exception unwinds through
  `runInEventScope`'s `finally` → teardown runs on the worker thread before the slot
  frees. A handler that ignores interruption defers teardown until it finishes (existing
  documented #107 limitation, now also covering teardown). The timeout budget covers the
  whole unit including scope open and lifecycle publishes (documented).
- **DLQ / overflow (#111):** a `ROUTE_TO_DLQ` rejection happens at submit — the task never
  runs, so no unit opens and no lifecycle events publish for dead-lettered dispatches.
  `BLOCK`/`DROP`/`THROW` policies are unaffected.
- **Error routing:** a handler exception propagates out of the unit *after* teardown
  (`EventEndingEvent` precedes error routing), then flows through the existing `runOnce`
  future into retry/`reportAsyncHandlerFailure` unchanged.

## Observable changes (release-note material — semantic fixes, not breaks)

- Async handlers can now resolve EVENT-scoped beans (previously: #302 guard
  `IllegalStateException`).
- Unit-metrics collectors see one `EventStarted`/`EventEnding` pair per async dispatch
  (per attempt, with retries). Previously async processing was invisible.
- `@PreDestroy` now runs for EVENT beans created during async processing.
- Per-dispatch overhead: ThreadLocal save/clear/restore plus two gated bus publishes.

## Testing

**Processor level** (compile-testing, `tiko-processor` test conventions):
- Generated async dispatch contains the `runInDetachedEventScope` wrapper with
  `__handler` resolution inside it.
- An async handler on an `io.tiko.events` type generates the unwrapped shape.
- Sync dispatch shape unchanged.

**Behavior level** (in `tiko-examples` — the repo's behavioral-test lane for generated-code
semantics; extend the existing scope/events-focused module if one covers async dispatch,
else add a numbered module — the plan pins the exact module after checking):
- Async handler resolving an EVENT bean gets a fresh instance per dispatch; `@PreDestroy`
  fires when the unit ends (probe bean records instance identity and destruction).
- With `retries`, each attempt sees a different EVENT-bean instance.
- One `EventStarted`/`EventEnding` pair per async dispatch; none for a
  `ROUTE_TO_DLQ`-rejected dispatch.
- `CALLER_RUNS` inline dispatch inside an open outer unit: outer unit's beans intact
  afterwards, inner unit torn down.
- Async handler subscribed to `EventStartedEvent`: bounded event count (no runaway).
- Timeout breach: teardown still runs (probe bean destroyed).
- Awaitility for all async assertions (no `Thread.sleep`), per house test rules.

## Documentation

- `docs/events.md` + `docs/di-and-scopes.md`: async-unit semantics section (fresh unit per
  dispatch/attempt, lifecycle-handler exclusion, aggregated-setup gap).
- Canonical skill chunk `.ai-skills/tiko-build/reference/events.md`: short note that async
  handlers own their unit; bundled copy regenerated via `ArchetypeDocSync` (never
  hand-edited).
- CLAUDE.md scope section: already states the model; verify no contradiction remains.

## Out of scope

- Sync publishes outside any unit stay bare (current behavior; not a unit).
- Aggregated-setup lifecycle-event plumbing (documented gap + follow-up issue).
- EVENT nestability / suspend-resume as public API (#252 family).
- Transports (already correct via `runInEventScope` per message).

## Acceptance

- All processor + behavior tests above green; full reactor `mvn test` green (spotless,
  sync gate included).
- Generated code for an async handler shows the wrapper; for a lifecycle-event async
  handler shows the unwrapped shape.
- `docs/` and skill chunk updated; bundled copy regenerated via the tool.
- Follow-up issue filed for the aggregated lifecycle gap.
