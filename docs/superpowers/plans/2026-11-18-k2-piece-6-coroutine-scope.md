# K2 Piece 6: The Container's Coroutine Scope and the Shutdown Order Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a booted container owns the coroutines it runs. They live in one scope per boot, run
on virtual threads the container creates, count as work `close()` waits for, and are cancelled
at a defined point of the shutdown. Nothing launches into the scope from application code yet:
`suspend` handlers are piece 7.

**Architecture:** `tiko-runtime` stays free of Kotlin. It gains a small entry point,
`ContainerWork`, through which another Tiko module asks the boot for three things: virtual
threads the boot tracks, one more pending delivery to count, and a place to keep state that
`close()` stops. `tiko-coroutines` keeps its scope there: a `SupervisorJob` on a dispatcher that
starts a virtual thread for every dispatch. A coroutine launched into it runs in a unit of work
of its own (the element of piece 3) and is a pending delivery until its `Job` completes.
`close()` waits for pending deliveries as it does today, then cancels the scope and waits for
the cancelled coroutines to finish, which closes their units, before it stops delivering events.

**Tech Stack:** Java 21 virtual threads (`Thread.ofVirtual()`,
`Executors.newThreadPerTaskExecutor`), kotlinx-coroutines 1.11.0, existing build.

**Spec:** issue #678; `docs/superpowers/specs/2026-11-04-kotlin-ksp-coroutines-design.md`, K2
items 3 and 4, with the sentence on the dispatcher replaced by the decision below. Roadmap:
`docs/superpowers/plans/2026-11-18-kotlin-k2-coroutines-roadmap.md` (piece 6).

## Decisions (owner, 2026-11-18)

| # | Decision |
|---|---|
| D6a | `suspend` handlers do not run on the async event executor. The container gets an extra executor for coroutines that starts a virtual thread for every dispatch. |
| D6b | A `suspend` handler in flight counts from its submission until its coroutine completes, and `OverflowPolicy` applies at submission. This piece delivers the count; the limit and the policies are piece 8 (#679). |
| D7 | Shutdown order: transports stop taking work in; `ApplicationEndingEvent`; wait for pending deliveries, suspended coroutines included, for at most `shutdownTimeout`; cancel the scope and wait for the cancelled coroutines to close their units; stop delivering; close transports; `@PreDestroy`. |

The spec said a `suspend` handler is launched on a dispatcher backed by the container's async
executor. The measurements that changed it (2026-11-18, kotlinx-coroutines 1.11.0):

- A coroutine resuming into a full async executor: `CALLER_RUNS` continued it on kotlinx's
  shared timer thread; `BLOCK` blocked that timer thread, and with it every `delay` and
  `withTimeout` in the process; `DROP` never resumed it; `THROW` and `ROUTE_TO_DLQ` cancelled it
  and finished it on a `Dispatchers.Default` worker.
- On a virtual-thread-per-dispatch executor: 2,000 concurrent coroutines each kept one unit
  across `delay`, `withContext` and `yield`, 2,000 children got units of their own, all 4,000
  units closed. A resume with a unit cost 2.6 µs (a fixed platform pool: 3.3 µs). 400 handlers
  blocking 100 ms each took 139 ms (a pool of 8: 5,455 ms).
- Blocking inside `synchronized` pins the carrier thread on JDK 21 (the same 400 handlers:
  2,069 ms on 22 cores) and does not on JDK 25 (114 ms).

## My own choices, for the owner to overrule

- **No option to supply the coroutine executor.** `TikoOptions.eventExecutor(...)` keeps
  governing async handlers only. An option can be added when someone needs it.
- **Cancelled coroutines get what is left of `shutdownTimeout`, and at least one second,** to
  finish. What still runs then is interrupted and reported as a thread outliving `close()`.
- **The issue texts are brought in line** (Task 5): #677, #678 and #680 say "bounded async
  executor" or "executor thread"; the plan lists the exact replacements.

## What changed while implementing

- **`docs/events.md` is not touched in this piece.** Nothing an application writes can run in
  the container's scope until `suspend` handlers exist, so the shutdown step has nothing
  user-visible to describe yet. It is written into "Graceful shutdown drain" in piece 7. The
  order is recorded here in ARCH-15, ARCH-19, SEC-14 and the spec.
- **After the work is stopped, `close()` waits once more for pending deliveries**, until the
  same deadline: a cancelled coroutine can publish to an async handler on its way out.
- **Virtual threads are tracked while they run**, in a concurrent set, not in the weak set the
  platform threads are kept in: the experiment measured the weak set at 0.65 µs per resume.

## Global Constraints

- `tiko-runtime` and generated code get no Kotlin dependency. `ContainerWork` is plain Java and
  is for Tiko's own modules, like `CarriedUnit`.
- An application that never launches a coroutine pays nothing: no thread, no executor and no
  scope is created until the first launch.
- A unit of work always closes (SEC-14): a coroutine cancelled by `close()` closes its unit, and
  so does one that is interrupted after the wait.
- `close()` stays bounded: it returns within its existing waits plus the cancellation wait above,
  also when a coroutine ignores cancellation.
- One failing coroutine does not cancel another (`SupervisorJob`).
- Threads the container starts for coroutines are named `tiko-coroutine-N`, and one still running
  after `close()` is reported through `onThreadsOutlivingClose`, as other threads are.
- Behaviour for applications without coroutines is unchanged; generated code changes only by
  the one shutdown call, shown in the golden files.
- Coroutine tests carry a time limit and do not order calls across threads.
- Commits: one conventional summary line. Sonar pre-sweep (S1181, S1452, S3077, S5164 included)
  before the first push.

## Review Focus

1. **`close()` while a coroutine is suspended and would finish in time.** It is waited for, not
   cancelled. Covered by Task 3.
2. **`close()` while a coroutine ignores cancellation** (blocked in `Thread.sleep`, or a loop
   that swallows `InterruptedException`). `close()` returns; the first is interrupted and its
   unit closes; the second is reported as a surviving thread. Covered by Task 3.
3. **A launch after `close()` has begun.** Nothing is started, the event is dropped observably,
   and no delivery stays counted. Covered by Task 2.
4. **A coroutine that fails.** Its failure reaches the launcher's failure callback once, its
   unit closes, its delivery is released, and its siblings keep running. Covered by Task 2.
5. **A multi-module application.** One scope for the application, units spanning every module,
   one shutdown. Covered by Task 3.

---

### Task 1: `ContainerWork` in `tiko-runtime`

**Files:**
- Create: `tiko-runtime/src/main/java/io/tiko/runtime/ContainerWork.java`
- Modify: `OwnedThreads.java`, `RetryScheduling.java`, `RetrySchedulingExecutor.java`,
  `DefaultEventExecutorFactory.java` (`TrackedEventExecutor`)
- Test: `tiko-runtime/src/test/java/io/tiko/runtime/ContainerWorkTest.java`

**Interfaces:**
- Produces:
  ```java
  /** What a Tiko module that runs work of its own for a booted container asks of that boot. */
  public final class ContainerWork {
      /** @param eventExecutor what container.getEventExecutor() returns; @throws IllegalStateException if it is not a Tiko boot's */
      public static ContainerWork of(ExecutorService eventExecutor);

      /** Virtual threads named prefix + N. One that still runs after close() is reported. */
      public ThreadFactory virtualThreads(String namePrefix);

      /** Counts one more pending delivery, which close() waits for. Run the result once to release it. */
      public Runnable retainDelivery();

      /** The boot's one instance of type, created on first use and stopped by close(). Empty once close() has begun. */
      public <T extends Stoppable> Optional<T> stoppedAtClose(Class<T> type, Supplier<T> create);

      public interface Stoppable {
          /** Called once by close(), after its wait for pending deliveries: stop what still runs, and wait for it until deadlineNanos. */
          void stop(long deadlineNanos);
      }
  }
  ```
- `OwnedThreads.stopWork(long deadlineNanos)`: stops every registered `Stoppable` once, with a
  deadline of what is left of the caller's, and at least one second. A `Stoppable` that throws
  does not keep the others from being stopped.
- A virtual thread from `virtualThreads` is known to the owner while it runs, so
  `stillAlive(...)` and the survivor report include it.

- [ ] **Step 1: Failing tests** (`ContainerWorkTest`): `anExecutorThatIsNotABootsIsRefused`;
  `aRetainedDeliveryIsPendingUntilReleasedOnce`; `closeWaitsForARetainedDelivery`;
  `theBootsOneInstanceIsCreatedOnce`; `stopWorkStopsEachInstanceOnceWithAtLeastASecond`;
  `nothingIsCreatedOnceCloseHasBegun`; `aStoppableThatThrowsDoesNotStopTheOthers`;
  `aVirtualThreadStillRunningIsReportedAndAFinishedOneIsNot`;
  `virtualThreadsAreNamedByPrefixAndAreVirtual`.
- [ ] **Step 2: Run, see them fail. Step 3: implement. Step 4:** `mvn -o -pl tiko-runtime install`.
- [ ] **Step 5: Commit** `feat(runtime): ContainerWork, what a Tiko module asks of a booted container to run work of its own (#678)`

---

### Task 2: the scope and the launcher in `tiko-coroutines`

**Files:**
- Create: `tiko-coroutines/src/main/kotlin/io/tiko/coroutines/ContainerCoroutines.kt` (internal)
- Test: `tiko-coroutines/src/test/kotlin/io/tiko/coroutines/ContainerCoroutinesTest.kt`

**Interfaces:**
- Consumes: `ContainerWork` (Task 1), `UnitOfWorkElement`, `EventChainContext.carriedUnitOpener`.
- Produces (internal; the entry for generated code comes with piece 7):
  ```kotlin
  internal class ContainerCoroutines(work: ContainerWork) : ContainerWork.Stoppable {
      /** Launches block in the container's scope, in a unit of work of its own. Null once close() has begun. */
      fun launchInUnit(carrier: UnitCarrier, onFailure: (Throwable) -> Unit, block: suspend CoroutineScope.() -> Unit): Job?
      override fun stop(deadlineNanos: Long)

      companion object {
          /** The scope of the boot that container belongs to; null once close() has begun. */
          fun of(container: Container): ContainerCoroutines?
      }
  }
  ```
- The scope is `SupervisorJob() + executor.asCoroutineDispatcher()` over
  `Executors.newThreadPerTaskExecutor(work.virtualThreads("tiko-coroutine-"))`.
- `launchInUnit` retains a delivery before it launches and releases it from the coroutine's
  `Job.invokeOnCompletion`; a failure other than cancellation goes to `onFailure` once.
- `stop`: cancel the scope; wait for its children until the deadline without relying on the
  kotlinx timer; `shutdown()` the executor, `shutdownNow()` if something still runs.

- [ ] **Step 1: Failing tests** (`ContainerCoroutinesTest`, on the booted fixture container):
  `aLaunchedCoroutineRunsInAUnitOfItsOwnOnAContainerThread` (virtual, named
  `tiko-coroutine-`); `itIsAPendingDeliveryUntilItCompletes` (`pendingAsyncDeliveries()` is 1
  while suspended, 0 after); `aFailingCoroutineReportsOnceClosesItsUnitAndLeavesItsSiblingRunning`;
  `nothingIsCreatedUntilTheFirstLaunch`; `aLaunchAfterCloseHasBegunStartsNothing`;
  `twoThousandCoroutinesKeepTheirUnits` (the load case of the experiment, as a regression test).
- [ ] **Step 2: Run, see them fail. Step 3: implement. Step 4: run the module three times.**
- [ ] **Step 5: Commit** `feat(coroutines): a scope per booted container on virtual threads, with launched coroutines counted as pending deliveries (#678)`

---

### Task 3: `close()` stops the coroutines at the decided point

**Files:**
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java` (`shutdown`),
  `EventChainContext.java` (the call generated code makes), `Tiko.java` (`BootHandle.shutdown`,
  for a container generated before this change)
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java`
  (`createShutdownMethod`), `ContainerGeneratorShutdownIdempotencyTest`, golden files
- Test: `tiko-coroutines/src/test/kotlin/io/tiko/coroutines/ShutdownOrderTest.kt`,
  `tiko-examples/05_multi_module/.../MultiModuleCarriedUnitTest.java` or a new Kotlin-free test
  there for the multi-module case through `ContainerWork`

**Interfaces:**
- `EventChainContext.stopContainerWork(ExecutorService executor, long deadlineNanos)`: for
  generated `__shutdown`, called right after `awaitPendingDeliveries(...)` and before
  `stopped.set(true)`; a no-op for an executor that is not a boot's.
- `AggregatingContainer.shutdown`: `ownedThreads.stopWork(...)` right after
  `ownedThreads.awaitDeliveries(...)`, before `options.closeTransports()`.
- `BootHandle.shutdown`: stops the work after the delegate has shut down, which does nothing
  when the container did it at the right point.

- [ ] **Step 1: Failing tests** (`ShutdownOrderTest`; fixtures record the order: a handler of
  `ApplicationEndingEvent`, the coroutine's `finally`, its unit's `EventEndingEvent`, a
  SINGLETON's `@PreDestroy`):
  - `aCoroutineThatFinishesInTimeIsWaitedForNotCancelled`
  - `aSuspendedCoroutineIsCancelledAfterTheWaitAndItsUnitClosesBeforeAnySingletonIsDestroyed`
    (order: ending event, cancellation seen in `finally`, unit ended, `@PreDestroy`)
  - `aCoroutineBlockedInSleepIsInterruptedAndItsUnitCloses`, with `close()` returning within
    `shutdownTimeout` plus the cancellation wait
  - `aCoroutineThatSwallowsInterruptsIsReportedAsAThreadOutlivingClose` (the handler gets a
    thread named `tiko-coroutine-N`)
  - `eventsACancelledCoroutinePublishesInItsFinallyAreStillDelivered`
  - emission: the generated `__shutdown` contains the call between the wait and
    `stopped.set(true)`.
- [ ] **Step 2: Run, see them fail. Step 3: implement, regenerate the golden files, read the diff
  (one statement).**
- [ ] **Step 4:** `mvn -o -pl tiko-runtime,tiko-processor,tiko-coroutines install`, and the
  multi-module example.
- [ ] **Step 5: Security review** against SEC-14 with the `tiko-security` skill.
- [ ] **Step 6: Commit** `feat: close() cancels the container's coroutines after its wait and before it stops delivering (#678)`

---

### Task 4: documentation

**Files:**
- Modify: `docs/events.md` ("Graceful shutdown drain": the new step between 3 and 4, what a
  cancelled coroutine can still do, and that a coroutine counts in
  `pendingAsyncDeliveries()`)
- Modify: `docs/di-and-scopes.md` and the Kotlin reference with its archetype copy only where
  they name the thread a unit's work runs on
- Modify: `docs/architecture-invariants.md` (the container owns the threads it starts:
  coroutine threads are virtual and tracked), `docs/security-model.md` (SEC-14: shutdown),
  the security skill's surface map
- Modify: `docs/superpowers/specs/2026-11-04-kotlin-ksp-coroutines-design.md` (K2 item 3
  "Dispatch": the extra executor, with a note that the owner changed it on 2026-11-18 and why)
- Modify: the roadmap (decisions D6 and D7, the measurements, pieces 7 to 9 reworded for the
  extra executor, status table)

- [ ] **Step 1: Write the changes.**
- [ ] **Step 2: Full build** `mvn -o clean install`, rc=0.
- [ ] **Step 3: Commit** `docs: the container's coroutine scope, its virtual threads and the shutdown order (#678)`

---

### Task 5: the issue texts

After the PR is open, with `gh issue edit --body-file` (bodies written to files; `@Annotation`
names in backticks). Each replacement is quoted in a comment on the issue, with the reason.

- **#677**, Scope: "`suspend` `@EventHandler` methods run through the existing bounded async
  executor, with a separate unit per invocation" becomes "`suspend` `@EventHandler` methods run
  on the container's coroutine executor (virtual threads), with a separate unit per invocation".
  Acceptance: "runs on the bounded async executor in its own unit" becomes "runs on a thread the
  container owns, in its own unit".
- **#678**, Scope: "The scope is created when the container starts and cancelled when it shuts
  down, with the same lifecycle the async executor has today" becomes "The scope is created the
  first time the container launches a coroutine and cancelled when the container shuts down. It
  runs on an executor of its own that starts a virtual thread per dispatch". (The scope is
  created on first use, so an application without coroutines gets none.)
- **#680**, Acceptance: "no executor thread is occupied by it (test with a single-thread
  executor)" becomes "no thread is occupied by it (the test counts the container's live
  coroutine threads during the backoff)".
- **#679** stays as written: backpressure applies when the handler is submitted.

---

## After the tasks

- Push, PR (`Refs #678`: the issue's item on a `suspend` handler still running at `close()`
  comes with piece 7), CI on the JDK matrix, Sonar open issues checked.
- Next: piece 7, `suspend` `@EventHandler` on this scope.
