# K2 Piece 8: Backpressure for `suspend` Handlers Implementation Plan

> **For agentic workers:** this plan is executed in the session that wrote it, task by task, test first. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** the number of `suspend` handlers in flight has a limit, and when a publish reaches it
the container's `OverflowPolicy` decides what happens, as it does for an `async = true` handler
whose queue is full. Closes #679.

**Architecture:** `ContainerCoroutines` keeps one place per `suspend` handler in flight, from
its submission until its coroutine has completed, `queueCapacity` places in all. `SuspendHandlers`
asks for a place before it launches. With a place free it launches as today. With none free it
does what the policy says: start the handler on the publisher's thread, hold the publisher,
drop, throw, or hand the event to the `ErrorHandler`. `tiko-runtime` stays free of Kotlin: it
tells `tiko-coroutines` the limit, the policy and whether the calling thread is the container's
own, through `ContainerWork`.

**Tech Stack:** `java.util.concurrent.Semaphore`, kotlinx-coroutines 1.11.0
(`CoroutineStart.UNDISPATCHED`), existing build.

**Spec:** issue #679; `docs/superpowers/specs/2026-11-04-kotlin-ksp-coroutines-design.md`, K2
item 3 ("Dispatch"). Roadmap: `docs/superpowers/plans/2026-11-18-kotlin-k2-coroutines-roadmap.md`
(piece 8, decision D6b).

## Decisions (owner, 2026-11-19)

| # | Decision |
|---|---|
| D9 | The limit is the existing `TikoOptions.queueCapacity` (default 1024). An option of its own that falls back to `queueCapacity` may come later; it is not part of this piece. |
| D10 | `CALLER_RUNS` for a `suspend` handler: the publisher's thread runs the handler up to its first suspension, and the rest runs on the container's coroutine threads. The publisher does not wait for the handler to finish. |

**What D10 means for the limit, stated plainly.** Under `CALLER_RUNS`, which is the default,
the limit slows a publisher by the part of the handler that runs before its first suspension.
It does not cap the number of handlers in flight: a handler that suspends as its first step
holds the publisher for almost no time, and every publish past the limit still starts one. The
policies that keep the number at the limit are `BLOCK`, `DROP`, `THROW` and `ROUTE_TO_DLQ`. A
test pins this (Task 2), and `docs/events.md` says it next to the policy table. The alternative
that keeps the cap under the default is that the publisher waits until the handler it started
has completed; it holds the publisher's thread for the whole handler, which D10 rules out.

## What the policies do at the limit

"At the limit" means `queueCapacity` `suspend` handlers hold a place. The count is separate
from the async executor's queue: the same number, two counters.

| Policy | A publish at the limit |
|---|---|
| `CALLER_RUNS` | The handler starts on the publisher's thread, in its own unit of work, and runs there up to its first suspension. From there it runs on `tiko-coroutine-N` threads. It holds no place. `publish` returns when the handler has suspended or finished. |
| `BLOCK` | The publisher's thread waits for a place, then the handler is launched as usual. A publish from one of the container's own threads (an async worker, a coroutine thread) does not wait, because the places it would wait for are held by handlers like itself (#727): it gets the `CALLER_RUNS` behaviour. |
| `DROP` | The event is not delivered to this handler; a `WARNING` is logged. |
| `THROW` | `publish` throws `EventQueueOverflowException`, after the event's other handlers got it (as #714). |
| `ROUTE_TO_DLQ` | The `ErrorHandler` gets `EventDispatchRejected(event)`. |

Whatever the policy: once the container has begun to close, a publish is dropped with the
`WARNING` every delivery dropped at shutdown gets (already so since piece 7), and a publisher
waiting under `BLOCK` is released and its event dropped the same way.

The limit and the policy apply to `suspend` handlers also when the application supplied its own
`eventExecutor(...)`: a `suspend` handler never runs on that executor. With such an executor its
threads are not the container's own, so under `BLOCK` they wait for a place like any publisher.

## Global Constraints

- No new public API in `tiko-api`. New public members in `tiko-runtime` are for Tiko's own
  modules and say so in their Javadoc, as `ContainerWork` does.
- `tiko-runtime` gets no Kotlin and no dependency on `tiko-coroutines`.
- Logging through `System.Logger` with the lazy holder (`.claude/rules/framework-code.md`).
- Public prose follows the framing rule: say what a policy does; none of the banned vocabulary.
- Sonar pre-sweep before the first push: S107 (Kotlin parameters too), S1192, S3776, S2142
  (interruption in the `BLOCK` wait keeps the interrupt flag), S1181, S1452, S6213.
- One CI-triggering push if possible; `@Timeout(60)` on every coroutine test class.

## Review Focus

- A place that is never given back: a handler that fails, is cancelled by `close()`, or is
  launched into a scope that was stopped between the check and the launch.
- A publisher that waits under `BLOCK` while the container closes, or is interrupted.
- `CALLER_RUNS` on a publisher's thread that has a unit of work open: the publisher's unit and
  its origin chain are what they were once `publish` returns, also when the handler throws
  before its first suspension.
- `BLOCK` when every place is held by a handler that itself publishes to a `suspend` handler.
- A handler started on the publisher's thread while `close()` runs.

---

### Task 1: the runtime tells what the limit, the policy and its own threads are

**Files:**
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/ContainerWork.java`
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/OwnedThreads.java`, and where it is created (`Tiko.java`, `AggregatingContainer.java`)
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/EventChainContext.java`
- Test: `tiko-runtime/src/test/java/io/tiko/runtime/ContainerWorkTest.java`

**Interfaces (produces):**
- `public int ContainerWork.queueCapacity()` and `public OverflowPolicy ContainerWork.onOverflow()`: the values of the boot's `TikoOptions`.
- `public boolean ContainerWork.isOwnThread()`: whether the calling thread is a thread this boot created, a worker of its default event executor or one of its virtual threads.
- `public static void EventChainContext.routeDispatchRejected(Object event, ErrorHandler errorHandler)`: the existing private method, made public.
- `public static void EventChainContext.logDroppedAtTheLimit(Object event)`: the `WARNING` for `DROP`, naming the event's type and the policy.

- [ ] **Step 1:** failing rows in `ContainerWorkTest`: `tellsTheBootsQueueCapacityAndOverflowPolicy` (options `queueCapacity(3)`, `onOverflow(DROP)`), `aThreadTheBootCreatedIsItsOwn` (true on a virtual thread of `virtualThreads(...)` and on an async worker, false on the test's thread), `aRejectedDispatchReachesTheErrorHandlerAsEventDispatchRejected`.
- [ ] **Step 2:** run them, see them fail to compile.
- [ ] **Step 3:** implement; `spotless:apply`.
- [ ] **Step 4:** `mvn -o -pl tiko-runtime install`, all green.
- [ ] **Step 5:** commit `feat(runtime): ContainerWork tells the overflow limit, the policy and the boot's own threads (#679)`.

### Task 2: places for `suspend` handlers and the five policies

**Files:**
- Modify: `tiko-coroutines/src/main/kotlin/io/tiko/coroutines/ContainerCoroutines.kt`
- Modify: `tiko-coroutines/src/main/kotlin/io/tiko/coroutines/SuspendHandlers.kt`
- Test: `tiko-coroutines/src/test/kotlin/io/tiko/coroutines/SuspendHandlerOverflowTest.kt` (new; on `BootedContainerTest` with `queueCapacity(2)` and the policy under test; it calls `SuspendHandlers.launch` as generated code does)

**Interfaces:**
- Consumes: Task 1.
- Produces (internal): `ContainerCoroutines` hands out places (`Semaphore(work.queueCapacity())`); a place is given back in the job's `invokeOnCompletion`, next to the pending delivery's release. A handler started under `CALLER_RUNS` is launched with `CoroutineStart.UNDISPATCHED`, counts as a pending delivery and holds no place. The signatures of `SuspendHandlers.launch` and `launchWithoutUnit` stay as they are, so generated code does not change.

- [ ] **Step 1:** write the failing tests. Each fills the two places with handlers that wait on a `CompletableDeferred`, then publishes a third time:
  - `belowTheLimitAHandlerIsLaunchedOffThePublishersThread`
  - `callerRunsStartsTheHandlerOnThePublishersThreadAndFinishesItOnAContainerThread`: thread before the first suspension is the test's, after it `tiko-coroutine-`; `launch` returned while the handler was suspended.
  - `callerRunsLeavesThePublishersOpenUnitAndOriginAsTheyWere`: publish inside `runInEventScope`; the handler sees another `RequestId`, the publisher the same one before and after; also with a handler that throws before it suspends, which reaches the `ErrorHandler` and not the publisher.
  - `underCallerRunsTheLimitDoesNotCapHandlersInFlight`: ten publishes with two places, handlers that suspend first; `pendingAsyncDeliveries()` is 10.
  - `blockHoldsThePublisherUntilAPlaceIsFree`: the publishing thread is still in `launch` after 200 ms, returns after one handler is released, and at most two handlers ran at a time.
  - `blockFromAContainerThreadDoesNotWait`: a handler that holds a place publishes a third; it completes without a place being freed.
  - `blockReleasesAWaitingPublisherWhenTheContainerCloses` and `blockKeepsTheInterruptFlagOfAnInterruptedPublisher`: the third handler never runs.
  - `dropDeliversNothingAndLeavesNothingPending`
  - `throwGivesThePublisherAnEventQueueOverflowException`
  - `routeToDlqGivesTheErrorHandlerTheEventAsEventDispatchRejected`
  - `aPlaceIsFreeAgainAfterAHandlerCompletedFailedOrWasCancelled` (parameterized over the three)
  - `aHandlerOfALifecycleEventTakesAPlaceToo` (`launchWithoutUnit`)
- [ ] **Step 2:** run, see them fail for the reason each names.
- [ ] **Step 3:** implement.
- [ ] **Step 4:** `timeout 400 mvn -o -pl tiko-coroutines clean install`, three times, all green.
- [ ] **Step 5:** security review against SEC-14 (a unit never open on a thread that does not hold it; nothing left open after `close()`), with the Review Focus list.
- [ ] **Step 6:** commit `feat(coroutines): a limit on suspend handlers in flight, with the overflow policies (#679)`.

### Task 3: end to end, documentation, full build

**Files:**
- Modify: `tiko-coroutines/src/test/java/io/tiko/coroutines/KspSuspendHandlerTest.java`: two scenarios in the compiled program, `throwAtTheLimit` (the publisher gets the exception and a synchronous handler of the same event still ran) and `deadLetterAtTheLimit`; the failure test also asserts `kind() == EXCEPTION`.
- Modify: `docs/events.md`: the policy table gains what each policy does for a `suspend` handler, with the sentence on `CALLER_RUNS` and the cap; the `suspend` handlers section replaces its pointer to #679; the sentence on a supplied executor says the limit and the policy still apply to `suspend` handlers.
- Modify: `.ai-skills/tiko-build/reference/kotlin.md` and its archetype copy; `OverflowPolicy` Javadoc; `docs/architecture-invariants.md` and `docs/security-model.md` anchors.
- Modify: the spec's K2 item 3 (the sentence on the publisher, and D9/D10); the roadmap (pieces 7 and 8, decisions).

- [ ] **Step 1:** scenarios and assertions, red then green.
- [ ] **Step 2:** documentation; record under "What changed while implementing" in this plan what turned out different.
- [ ] **Step 3:** `mvn -o clean install` for the whole reactor (in the background, result from the log); `kotlin-ktor-exposed` on Gradle.
- [ ] **Step 4:** commit `docs: backpressure for suspend handlers (#679)`, Sonar pre-sweep, push, PR with `Closes #679`, a PR comment with the choices made here, CI and Sonar open issues.

## What changed while implementing

- **`SuspendHandlerOverflowTest` boots its own container** for each test, with two places and
  the policy under test, and is not built on `BootedContainerTest`, whose container is booted
  before a test can say which policy it wants. It waits with Awaitility, which the module now
  has as a test dependency.
- **`EventChainContext.logDroppedAtTheLimit(event, why)`** takes the reason, because a publisher
  interrupted while it waits under `BLOCK` is a drop too, with a `WARNING` of its own, as the
  async executor's `BLOCK` does it.
- **Once `close()` has begun to stop the coroutines, a publish is refused before the policy is
  asked**, so `CALLER_RUNS` starts nothing on a publisher's thread then. One more test pins it.
- **A row in `MultiModuleContainerWorkTest`** (`05_multi_module`) pins that a multi-module
  application's work gets the application's `queueCapacity` and `onOverflow`.
- **The part of a handler that runs on a publisher's thread is not interrupted by `close()`**:
  that thread is not the container's. It is cancelled at its first suspension. An async handler
  that `CALLER_RUNS` runs on a publisher's thread is in the same position today.

## My reading of one acceptance line

"An exception thrown after a suspension reaches the `ErrorHandler` as `EventHandlerError`, and
`ROUTE_TO_DLQ` dead-letters it." I read it as two facts, each with a test: a failure is an
`EventHandlerError` of kind `EXCEPTION` whatever the policy (delivered in piece 7, asserted
again here), and with `ROUTE_TO_DLQ` an event that meets the limit is handed to the
`ErrorHandler` as `EventDispatchRejected`.

## Out of scope

- An option of its own for the limit (D9).
- Timeouts and retries on `suspend` handlers (#680, piece 9).
- A metric for `suspend` handlers in flight beyond `pendingAsyncDeliveries()`.
