# K2 Piece 9: Timeouts and Retries on `suspend` Handlers Implementation Plan

> **For agentic workers:** this plan is executed in the session that wrote it, task by task, test first. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `@EventHandler(timeout = ...)` and `retries` / `backoff` / `backoffStrategy` work on a
`suspend` handler. A timeout cancels the coroutine, a backoff suspends, and no thread is held
while either waits. Closes #680.

**Architecture:** one coroutine per delivery, as today. With a timeout or retries it runs the
attempts in a loop: each attempt is the handler, and its `@EventTrigger` publishing, in a unit of
work of its own, under `withTimeout` when a timeout is set; between attempts it suspends for the
backoff. The delivery keeps its place and stays a pending delivery from the publish until the
last attempt has finished. `tiko-runtime` keeps the arithmetic and the error routing it already
has for async handlers (`RetryPolicy`, the backoff delay, `EventHandlerError` with `attempts`)
and makes them reachable; the loop itself is in `tiko-coroutines`. The processor stops refusing
`timeout` and `retries` on a `suspend` handler and passes the handler's `RetryPolicy` on.

**Tech Stack:** kotlinx-coroutines 1.11.0 (`withTimeoutOrNull`, `delay`), existing build.

**Spec:** issue #680; `docs/superpowers/specs/2026-11-04-kotlin-ksp-coroutines-design.md`, K2
item 5. Roadmap: `docs/superpowers/plans/2026-11-18-kotlin-k2-coroutines-roadmap.md` (piece 9).

## Decisions (owner, 2026-11-19: both as recommended)

| # | Question | Decided |
|---|---|---|
| D11 | A `suspend` handler is waiting out a backoff when `close()` begins. | **As an async handler:** its retry is dropped at once with a `WARNING`, and `close()` does not wait for a backoff. The alternative is that `close()` waits for it like for any pending delivery, up to `shutdownTimeout`, and then cancels it. |
| D12 | Does a handler that waits out a backoff keep its place under the limit of #679? | **Yes.** It is in flight from the publish until its last attempt has finished, so it holds one place and is one pending delivery the whole time. The alternative, giving the place back for the backoff and asking for one again per retry, means a retry can itself meet the limit and be dropped, blocked or dead-lettered. |

## What follows from the spec, stated plainly

- **A timeout cancels; it does not interrupt.** The spec asks for a cancellation-based timeout.
  Cancellation takes effect at the handler's next suspension point. A handler that blocks a
  thread (a JDBC call, `Thread.sleep`) runs on to the end of that call, and its
  `EventHandlerError` with the `TimeoutException` is routed when it returns. An async Java
  handler's worker thread is interrupted instead. `docs/events.md` says so and names
  `runInterruptible` for blocking code.
- **A handler that `CALLER_RUNS` starts on the publisher's thread is time-boxed too**, from its
  first suspension on, unlike an async handler the executor runs inline.

## API of `tiko-coroutines` (called by generated code only)

`SuspendHandlers.launch` and `launchWithoutUnit` take the handler's policy in place of the
event, which is the payload of the wrapper they already get:

```kotlin
public fun <T> launch(
    container: Container, origin: Event<*>, handler: suspend () -> T, onSuccess: Consumer<T>?,
    errorHandler: ErrorHandler, info: EventHandlerInfo, policy: RetryPolicy?,
)
```

`policy` is `null` for a handler with neither a timeout nor retries. Seven parameters, as now.

## Global Constraints

- No new public API in `tiko-api`; the Javadoc of `@EventHandler.timeout` / `retries` gains the
  `suspend` case. New public members in `tiko-runtime` are for Tiko's own modules.
- `tiko-runtime` gets no Kotlin and no dependency on `tiko-coroutines`.
- Public prose follows the framing rule.
- Sonar pre-sweep before the first push: S107 (Kotlin too), S3776, S2142, S1181, S1452, S6213,
  and for S1192 a grep of each file for a literal before a line that repeats it is added.
- One CI-triggering push if possible; `@Timeout(60)` on every coroutine test class; waits in
  tests through Awaitility or a latch, no bare `Thread.sleep`.

## Review Focus

- A unit that is left open or closed twice: an attempt cancelled by its timeout, by `close()`,
  or failing in its `@EventTrigger` publishing.
- A place or a pending delivery that is never given back when the loop ends by an `Error`, a
  cancellation, or `close()` during a backoff.
- A timeout of the handler's own (`withTimeout` inside the handler) mistaken for the
  annotation's timeout.
- A retry after `close()` began.
- `attempts()` off by one, and the backoff before the first retry.

---

### Task 1: the runtime makes the retry arithmetic and the close signal reachable

**Files:**
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/ContainerWork.java`, `OwnedThreads.java`, `EventChainContext.java`
- Test: `tiko-runtime/src/test/java/io/tiko/runtime/ContainerWorkTest.java`

**Interfaces (produces):**
- `default void ContainerWork.Stoppable.closeBegan()`: called once when `close()` begins to wait for the pending deliveries, at the moment async retries are dropped; does nothing by default.
- `public int ContainerWork.virtualThreadsRunning()`: how many of the boot's virtual threads are running their task now.
- `public static void EventChainContext.routeHandlerFailure(Throwable cause, ErrorHandler errorHandler, EventHandlerInfo info, Object event, int attempts)`: the existing routing with the attempt count; the four-argument form stays.
- `public static long EventChainContext.backoffDelayNanos(RetryPolicy policy, int attemptIndex)`: the existing private method, made public.

- [ ] **Step 1:** failing rows: `closeBeganIsToldOnceBeforeTheWaitForDeliveries`, `countsTheVirtualThreadsThatAreRunning`, `aFailureIsRoutedWithItsAttemptCount` (kind `EXHAUSTED` for 3, `EXCEPTION` for 1), `backoffDelayIsConstantForFixedAndDoublesForExponential` (parameterized).
- [ ] **Step 2:** see them fail to compile.
- [ ] **Step 3:** implement; `spotless:apply`.
- [ ] **Step 4:** `mvn -o -pl tiko-runtime install`, all green.
- [ ] **Step 5:** commit `feat(runtime): the retry arithmetic and the close signal for a module's own work (#680)`.

### Task 2: the attempt loop

**Files:**
- Modify: `tiko-coroutines/src/main/kotlin/io/tiko/coroutines/ContainerCoroutines.kt` (`closeBegan`, a backoff that ends early when `close()` begins)
- Modify: `tiko-coroutines/src/main/kotlin/io/tiko/coroutines/SuspendHandlers.kt` (the signature above, the loop)
- Modify: `tiko-coroutines/src/test/kotlin/io/tiko/coroutines/SuspendHandlerOverflowTest.kt` (the new signature)
- Test: `tiko-coroutines/src/test/kotlin/io/tiko/coroutines/SuspendHandlerResilienceTest.kt` (new)

- [ ] **Step 1:** failing tests:
  - `aHandlerThatOverrunsItsTimeoutIsCancelledAndReportedOnce`: cause `TimeoutException`, kind `TIMEOUT`, attempts 1; the handler's `finally` saw the cancellation; its unit closed once.
  - `aHandlerThatFinishesInTimeReportsNothing`
  - `aTimeoutOfTheHandlersOwnIsNotTheAnnotationsTimeout`
  - `aTimeoutTakesEffectAtTheNextSuspensionPoint`: a handler that blocks past its budget and then suspends is reported after the blocking call returned.
  - `retriesRunUntilAnAttemptSucceeds`: fails twice, succeeds; nothing reported; `onSuccess` ran once; three units, each closed once.
  - `anExhaustedBudgetIsReportedOnceWithItsAttemptCount`: `retries = 2`, attempts 3, kind `EXHAUSTED`, the last failure as cause.
  - `backoffWaitsBetweenAttempts` (parameterized over `FIXED` and `EXPONENTIAL`): the gaps between attempts are at least 100, 100 and 100, 200 ms.
  - `noThreadIsOccupiedWhileAHandlerWaitsOutItsBackoff`: during the backoff `virtualThreadsRunning()` is 0 and the delivery is pending.
  - `aTimedOutAttemptIsRetried`: the second attempt succeeds; two units, each closed once.
  - `aFailureWhilePublishingTriggersCountsAsAFailedAttempt`
  - `anErrorIsNotRetried` and `aCancelledHandlerIsNotRetried`
  - `aHandlerInBackoffKeepsItsPlace` (D12): one place, `DROP`; a second publish during the backoff is dropped.
  - `aBackoffIsDroppedWhenTheContainerCloses` (D11): `close()` returns long before the backoff would end; no further attempt; nothing stays pending.
  - `aHandlerOfALifecycleEventRetriesWithoutAUnit`
- [ ] **Step 2:** run, see them fail for the reason each names.
- [ ] **Step 3:** implement.
- [ ] **Step 4:** `timeout 400 mvn -o -pl tiko-coroutines clean install`, three times, all green.
- [ ] **Step 5:** security review against SEC-14 with the Review Focus list.
- [ ] **Step 6:** commit `feat(coroutines): timeouts and retries on suspend handlers (#680)`.

### Task 3: the processor accepts them and passes the policy on

**Files:**
- Modify: `tiko-processor-core/src/main/java/io/tiko/processor/core/KotlinConstructRules.java` (the `timeout` / `retries` branches of `suspendHandler` go)
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/EventRegistryGenerator.java` (`emitSuspendDispatch`: the policy, or `null`, in place of `event`)
- Test: `tiko-processor/.../KotlinConstructRulesTest.java` (the two rows go), `tiko-coroutines/src/test/java/io/tiko/coroutines/KspSuspendHandlerTest.java`

- [ ] **Step 1:** in `KspSuspendHandlerTest`: the emission assertions for a plain handler (`..., HANDLER_INFO_n, null)`) and for one with `timeout = "PT0.2S", retries = 2, backoff = "PT0.05S", backoffStrategy = EXPONENTIAL` (`new RetryPolicy(2, 50000000L, BackoffStrategy.EXPONENTIAL, 200000000L)`); scenarios `timeoutThenRetry` and `exhausted` booted from the compiled Kotlin program; a second, small program with `timeout = "5s"` that fails to compile with the existing duration error. Red.
- [ ] **Step 2:** implement; processor golden files stay unchanged (no Java handler is `suspend`).
- [ ] **Step 3:** `tiko-processor-core` and `tiko-processor` builds, the second in the background; `tiko-coroutines` build.
- [ ] **Step 4:** commit `feat(processor): timeout and retries on suspend event handlers (#680)`.

### Task 4: documentation, full build, PR

**Files:**
- Modify: `docs/events.md` ("Execution timeouts", "Retries with backoff", the `suspend` handlers section, the shutdown steps), `.ai-skills/tiko-build/reference/kotlin.md` and its archetype copy, `api-signatures.md` if it states "async-only", the Javadoc of `@EventHandler.timeout` / `retries`, `docs/architecture-invariants.md` / `docs/security-model.md` anchors, the spec's item 5, the roadmap.

- [ ] **Step 1:** documentation; record under "What changed while implementing" what turned out different.
- [ ] **Step 2:** `mvn -o clean install` for the whole reactor (background); `kotlin-ktor-exposed` on Gradle.
- [ ] **Step 3:** commit `docs: timeouts and retries on suspend handlers (#680)`, Sonar pre-sweep, push, PR with `Closes #680`, a PR comment with the choices, CI and Sonar open issues.

## What changed while implementing

- **A `CancellationException` the handler throws while nothing cancelled its delivery is a
  failure of the attempt.** A `withTimeout` of the handler's own that runs out throws one, and
  until now such a handler ended without a word, as kotlinx treats a coroutine that throws it.
  It is now reported or retried like any exception. The delivery's own cancellation, by
  `close()`, is still not a failure. This also applies to a handler with no policy.
- **The collector's "requires `async = true`" checks let a `suspend` handler through**
  (`EventHandlerCollector.runsOnThePublishersThread`). The plan named only the Kotlin construct
  rule; without this a `suspend` handler would have needed `async = true` for a timeout.
- **`ContainerCoroutines` has one `launch`** for a delivery. The two functions that opened the
  unit for the whole coroutine went: a delivery opens the unit of each attempt itself.
- **The processor row is one**: a `suspend` handler with a timeout and retries is told about
  the missing `tiko-coroutines` dependency, like any other. It replaces the two rows that
  expected a refusal. A timeout that is not a duration is tested in `KspSuspendHandlerTest`,
  as planned: without `tiko-coroutines` on the classpath a `suspend` handler is not collected,
  so the processor's own tests never get that far. The end-to-end scenarios are three: a
  timed-out and a failed attempt retried until one succeeds, an exhausted budget, a timeout
  alone.
- **The generator builds `new RetryPolicy(...)` in one place** for async and `suspend` handlers.
- **A race in a test of piece 8 was fixed** (`afterTheCoroutinesWereStoppedAPublishAtTheLimitRunsNothing`):
  it stopped the coroutines without waiting for its two holders to have started, and a coroutine
  cancelled before it starts never runs its `finally`.

## Out of scope

- Jitter, and any resiliency option Java handlers do not have (#680).
- Interrupting a thread a timed-out `suspend` handler blocks.
- `suspend` `@KafkaSource` bridges (#681, piece 10).
