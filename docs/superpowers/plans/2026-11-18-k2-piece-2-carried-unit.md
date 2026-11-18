# K2 Piece 2: A Unit of Work That Can Be Carried Between Threads Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** the runtime can open a unit of work without running a task in it, put it on a
thread, take it off again, put it on another thread, and close it exactly once. No Kotlin and no
coroutine dependency: this is the container side the coroutine context element of piece 3 calls.

**Architecture:** today a unit exists only for the duration of one `Runnable`: its state is two
thread-locals in each generated container (`eventScoped`, `__unitFrameOpen`) and the origin
chain in `EventChainContext`. A generated container gains two small operations on its own frame:
swap the frame on the calling thread for another one, and tear the installed frame down. A new
runtime class, `CarriedUnit`, holds one frame per module plus the unit's id, start time and
origin, and does the rest: install, restore, lifecycle events, the close. Opening goes through
`TikoOptions`, as an async handler's detached unit does since #811, so in a multi-module
application the unit spans every module and the aggregate publishes the one lifecycle pair.
`runInDetachedFrame` is rebuilt on the same swap, so saving and restoring a frame has one
implementation.

**Tech Stack:** Java 21, Maven, JavaPoet, compile-testing (all existing). No new dependency.

**Spec:** `docs/superpowers/specs/2026-11-04-kotlin-ksp-coroutines-design.md`, section "Facts
about today's runtime the builder needs" (a container-internal SPI that captures and installs
all three thread-locals) and K2 item 1. Roadmap:
`docs/superpowers/plans/2026-11-18-kotlin-k2-coroutines-roadmap.md` (piece 2). Issue: #677
(this piece is part of it and does not close it).

## Global Constraints

- Behaviour of applications that never carry a unit does not change. `runInEventScope`,
  `supplyInEventScope` and async handlers behave as before; their tests stay as they are.
- ARCH-5 holds: while a carried unit is installed on a thread, `runInEventScope` on that thread
  throws `IllegalStateException`, as it does inside any open unit.
- SEC-14 holds: a carried unit closes exactly once, and its EVENT components are torn down in
  reverse creation order with the unit installed, as in `__closeEventScope` today.
- A unit of work is single-threaded (owner, #683). A carried unit is on at most one thread at a
  time; putting it on a second thread while it is on a first one is an error, not a wait.
- One lifecycle pair (`EventStartedEvent` / `EventEndingEvent`) per unit, with one event id,
  published while the unit is installed. A module under an aggregate stays silent.
- The new types live in `io.tiko.runtime` and are for generated code and Tiko's own modules.
  `tiko-api` does not change.
- No reflection. Framework logging through `System.Logger` with the lazy holder.
- A container generated before 0.8.0 has no frame to offer and stays out of a carried unit, as
  it stays out of a detached unit today.
- Commits: one conventional summary line, no body. `mvn -o -q -pl <module> spotless:apply`
  before each test run. One Maven build at a time.

## Review Focus

1. **The unit is installed on a thread that has a unit of its own open** (a `CALLER_RUNS`
   publisher thread, a test thread inside `runInEventScope`). After the carried unit is taken
   off, the thread's own unit is back, with its EVENT instances. Covered by Task 1 and Task 3.
2. **The unit is closed from a thread it is not installed on** (a completion callback). The
   close installs it for the teardown and puts back what the thread had. Covered by Task 1.
3. **A second thread installs the unit while a first one has it.** It fails with
   `IllegalStateException` naming the thread that holds it, and the first thread's unit is
   intact. Covered by Task 1.
4. **A lifecycle handler or a `@PreDestroy` throws during the close.** The remaining modules'
   frames are still torn down and the thread is left clean. Covered by Task 1 and Task 3.
5. **The unit is closed without ever having been installed** (a coroutine cancelled before it
   ran). Nothing is published and nothing is torn down. Covered by Task 1.

---

### Task 1: `UnitFrame`, the frame operations on `DetachedUnitFrame`, and `CarriedUnit`

**Files:**
- Create: `tiko-runtime/src/main/java/io/tiko/runtime/UnitFrame.java`
- Create: `tiko-runtime/src/main/java/io/tiko/runtime/CarriedUnit.java`
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/DetachedUnitFrame.java`
- Test: `tiko-runtime/src/test/java/io/tiko/runtime/CarriedUnitTest.java`

**Interfaces:**
- Produces:
  ```java
  /** One container's EVENT frame as a value: its instances, and whether a unit is open in it. */
  public record UnitFrame(Map<String, Object> scope, boolean open) {
      /** A frame of a unit that has just been opened: no instances yet. */
      public static UnitFrame opened();
  }

  public interface DetachedUnitFrame {
      void runInDetachedFrame(Runnable task, boolean publishLifecycle);          // existing

      /** Makes frame this container's EVENT frame on the calling thread; returns the one it replaced. */
      UnitFrame swapFrame(UnitFrame frame);

      /** Tears down the frame installed on the calling thread (destroy hooks in reverse creation order). */
      void closeFrame();
  }

  public final class CarriedUnit {
      /** For AggregatingContainer and TikoOptions. lifecycle publishes one event, isolating failures. */
      CarriedUnit(List<DetachedUnitFrame> modules, Event<?> origin, Consumer<Object> lifecycle);

      /** What a thread had before the unit was installed on it. */
      public static final class Displaced { /* frames in module order, previous origin */ }

      public Displaced install();            // first call publishes EventStartedEvent
      public void restore(Displaced displaced);
      public void close();                   // idempotent; publishes EventEndingEvent once
      public boolean isClosed();
  }
  ```
- `install()` throws `IllegalStateException` when the unit is closed, and when it is installed
  on another thread (the message names that thread). It swaps the frames in module order and
  enters the origin through the existing `EventChainContext.enter`; `restore` undoes both in
  reverse order (`EventChainContext.exit`) and releases the thread.
- An `install()` by the thread that already has the unit changes nothing, and its `restore`
  changes nothing either. (Found while implementing: `withContext` with a changed context on
  the same dispatcher re-installs a context element on the thread that already runs the
  coroutine, so this case is ordinary, not an error.)
- `close()`: installed on the calling thread, it tears down in place and leaves the restore to
  the caller. Installed nowhere, it installs itself, tears down and restores. Installed on
  another thread, the teardown is left to that thread, which runs it in `restore`. (Changed
  while implementing: the first version of this plan threw here, which would leave a unit that
  never closes, against SEC-14.) Never installed, it only marks the unit closed. The teardown
  publishes `EventEndingEvent` (id, end, duration) and then calls `closeFrame()` on every module
  in reverse order; a throwing module or a throwing publish does not stop the others, and the
  first failure is rethrown with the later ones suppressed.
- An `Error` from the first lifecycle publish takes the unit off the thread before it
  propagates: the caller has no `Displaced` to restore with.

- [ ] **Step 1: Write the failing tests** (`CarriedUnitTest`, with a recording
  `DetachedUnitFrame` double that keeps its frame in a `ThreadLocal`, like a generated container)

  - `installPutsEveryModulesFrameOnTheThreadAndRestorePutsBackWhatWasThere`
  - `theSameFramesAreInstalledOnAnotherThread` (thread A installs, puts a value into the scope,
    restores; thread B installs and reads it; threads joined, no sleeps)
  - `aThreadWithAFrameOfItsOwnGetsItBackAfterRestore`
  - `theOriginIsCurrentWhileInstalledAndThePreviousOneIsBackAfterRestore` (read through
    `EventChainContext.enter(null)` / `exit`)
  - `theFirstInstallPublishesTheStartedEventAndLaterInstallsPublishNothing`
  - `closeWhileInstalledPublishesTheEndingEventThenClosesFramesInReverseModuleOrder` (one event
    id in both events)
  - `closeFromAThreadTheUnitIsNotOnInstallsItForTheTeardownAndRestores`
  - `closeIsDoneOnce` (second call does nothing)
  - `aUnitThatWasNeverInstalledClosesWithoutEventsOrTeardown`
  - `installAfterCloseIsRejected`
  - `installOnASecondThreadWhileTheFirstHoldsTheUnitIsRejected` (the first thread waits on a
    latch; the message names it)
  - `closeWhileAnotherThreadHoldsTheUnitIsDoneByThatThreadWhenItTakesTheUnitOff`
  - `installingAgainOnTheThreadThatHoldsTheUnitChangesNothing`
  - `anErrorFromTheStartedPublishLeavesTheThreadAsItWas`,
    `anErrorFromTheEndingPublishStillTearsTheFramesDown`,
    `anErrorFromOneModulesTeardownDoesNotStopTheOthers` (added by the SEC-14 review in Task 4)
  - `aModuleThatFailsToCloseDoesNotStopTheOthers` (first failure rethrown, second suppressed,
    thread clean afterwards)

  Parameterize where a method would otherwise repeat one assertion over cases.

- [ ] **Step 2: Run and see them fail**

  Run: `mvn -o -q -pl tiko-runtime test -Dtest=CarriedUnitTest > <log> 2>&1; echo rc=$?`
  Expected: compilation failure, the types do not exist.

- [ ] **Step 3: Implement**

  `DetachedUnitFrame`'s Javadoc is rewritten for the three operations. `DetachedUnitTest`'s
  `FrameModule` double implements the two new methods as no-ops.

- [ ] **Step 4: Run the module**

  Run: `mvn -o -pl tiko-runtime install > <log> 2>&1; echo rc=$?`. Expected: rc=0.

- [ ] **Step 5: Commit**

  `feat(runtime): add CarriedUnit, a unit of work that is installed on a thread and taken off again (#677)`

---

### Task 2: opening a carried unit, for one module and for an application

**Files:**
- Create: `tiko-runtime/src/main/java/io/tiko/runtime/UnitCarrier.java`
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/TikoOptions.java`
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java`
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/Tiko.java` (`BootHandle`)
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/ContainerUnitOfWork.java`
- Test: `tiko-runtime/src/test/java/io/tiko/runtime/CarriedUnitOpeningTest.java`

**Interfaces:**
- Consumes: `CarriedUnit`, `DetachedUnitFrame` (Task 1).
- Produces:
  ```java
  /** A container, or a view of one, that opens units of work that can be carried between threads. */
  public interface UnitCarrier {
      /** @param origin the event the unit's work descends from, or null */
      CarriedUnit openCarriedUnit(Event<?> origin);
  }

  // TikoOptions, next to runInDetachedUnit; called by generated containers
  public CarriedUnit openCarriedUnit(DetachedUnitFrame module, Consumer<Object> moduleLifecycle, Event<?> origin);
  ```
- `TikoOptions.openCarriedUnit`: under an aggregate, the aggregate opens the unit over every
  module that is a `DetachedUnitFrame` and publishes through its own `publishUnitLifecycle`;
  otherwise the unit covers `module` alone and publishes through `moduleLifecycle`.
- `AggregatingContainer`, `BootHandle` and `ContainerUnitOfWork` implement `UnitCarrier`.
  `BootHandle` and `ContainerUnitOfWork` forward to the container they stand for, and throw
  `IllegalStateException` with a message naming the cause when it is not a `UnitCarrier` (a
  container generated before 0.8.0, or a hand-written one).

- [ ] **Step 1: Write the failing tests** (`CarriedUnitOpeningTest`)

  - `aSingleModuleOpensTheUnitOverItselfAndPublishesThroughItsOwnGate`
  - `anApplicationOpensTheUnitOverEveryModuleAndPublishesOnce` (two frame modules and one
    container without frames, which is passed by)
  - `theUnitOfWorkAComponentHoldsOpensTheApplicationsUnit` (`ContainerUnitOfWork` over a module,
    options naming the application)
  - `aContainerThatCannotCarryAUnitSaysSo` (`ContainerUnitOfWork` over a container that is not
    a `UnitCarrier`)

- [ ] **Step 2: Run and see them fail**, then **Step 3: implement**, **Step 4: run the module**
  (`mvn -o -pl tiko-runtime install`, rc=0).

- [ ] **Step 5: Commit**

  `feat(runtime): open a carried unit over one module or over every module of an application (#677)`

---

### Task 3: generated containers offer their frame

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java`
  (new `createSwapFrameMethod`, `createCloseFrameMethod`, `createOpenCarriedUnitMethod`;
  `createRunInDetachedFrameMethod` rebuilt on `swapFrame`; superinterface `UnitCarrier`)
- Modify: golden files under `tiko-processor/src/test/resources/golden/nullability/`
- Modify: `tiko-processor/src/test/java/io/tiko/processor/DetachedEventScopeTest.java`
- Test: `tiko-processor/src/test/java/io/tiko/processor/CarriedUnitBootTest.java` (compiles its
  sources into a `@TempDir` and boots them through a class loader, like
  `UnitOfWorkServerStartTest`)
- Test: `tiko-examples/05_multi_module/app/src/test/java/.../MultiModuleCarriedUnitTest.java`

**Interfaces:**
- Consumes: Tasks 1 and 2.
- Generated code:
  ```java
  @Override public UnitFrame swapFrame(UnitFrame frame) {
      UnitFrame __previous = new UnitFrame(eventScoped.get(), __unitFrameOpen.get());
      eventScoped.set(frame.scope());
      __unitFrameOpen.set(frame.open());
      return __previous;
  }
  @Override public void closeFrame() { __closeEventScope(); }
  @Override public CarriedUnit openCarriedUnit(Event<?> origin) {
      return this.options.openCarriedUnit(this, this::__publishGatedUnitLifecycle, origin);
  }
  ```
  `__publishGatedUnitLifecycle(Object)` publishes through `__publishUnitLifecycle` when
  `publishLifecycleEvents` is set. `runInDetachedFrame` becomes: swap in a closed empty frame,
  run `__runInEventScope`, swap the previous frame back in a `finally`.

- [ ] **Step 1: Write the failing boot test** (`CarriedUnitBootTest`; the probe class inside
  the compiled sources drives the container and returns what it saw)

  - `eventInstancesFollowTheUnitFromThreadToThread`: install on thread A and resolve an EVENT
    component through a SINGLETON's proxy; restore; install on thread B: the same instance.
  - `aThreadsOwnUnitIsBackAfterTheCarriedOneIsTakenOff`: inside `runInEventScope`, install the
    carried unit, see its instance, restore, see the thread's own instance again.
  - `withoutTheUnitInstalledAnEventComponentIsNotReachable`: after restore, the proxy call
    throws `NoActiveEventScopeException`.
  - `openingAUnitWhileTheCarriedOneIsInstalledIsRejected`: `runInEventScope` throws
    `IllegalStateException` (ARCH-5).
  - `closeRunsDestroyHooksOnceInAnOrdinaryUnitsOrder`: two EVENT components with `@PreDestroy`;
    the carried unit destroys them in the order an ordinary `runInEventScope` unit does, and a
    second `close()` adds nothing. (The order is compared with an ordinary unit, not written
    down: an EVENT component injected into another one through its interface is a proxy, so its
    instance is created on first use, not when its consumer is constructed.)
  - `theUnitPublishesOneLifecyclePair`: started on the first install, ending on close, one id.

- [ ] **Step 2: Write the failing emission assertions** in `DetachedEventScopeTest`: the
  container implements `UnitCarrier`, has `swapFrame`, `closeFrame` and `openCarriedUnit`, and
  `runInDetachedFrame` calls `swapFrame`.

- [ ] **Step 3: Run and see them fail**, then **Step 4: implement the generator changes**.

- [ ] **Step 5: Regenerate the golden files**

  With `-Dtiko.golden.update=true -Dspotless.check.skip=true`, then restore the test source the
  update touches and convert the golden files back to CRLF. Read the golden diff: it must show
  only the three new methods, the new superinterface and import, and the rebuilt
  `runInDetachedFrame`.

- [ ] **Step 6: The multi-module test** (`MultiModuleCarriedUnitTest`)

  - `aCarriedUnitHoldsTheEventComponentsOfEveryModuleOnEveryThread`: open through the booted
    container, install on two threads in turn, read `NoteDesk.notes()` on each: the same notes
    of both modules; one lifecycle pair.

- [ ] **Step 7: Run** `mvn -o -pl tiko-processor install` and the `05_multi_module` build;
  both rc=0. Existing `DetachedEventScopeTest`, `MultiModuleAsyncUnitTest` and the `16_async_scope`
  example tests pass unchanged in behaviour.

- [ ] **Step 8: Commit**

  `feat(processor): generated containers offer their EVENT frame to a carried unit (#677)`

---

### Task 4: documentation, invariants, full build

**Files:**
- Modify: `docs/architecture-invariants.md` (ARCH-15: the carried unit as the second way a unit
  is opened off the caller's frame; anchors `CarriedUnit`, `createSwapFrameMethod`; what a
  violation looks like: a unit on two threads at once, a close that leaves a module's frame
  open)
- Modify: `docs/security-model.md` (SEC-14: the carried unit's close paths)
- Modify: `docs/superpowers/plans/2026-11-18-kotlin-k2-coroutines-roadmap.md` (pieces 1 and 2
  marked done; the entry point piece 3 uses: `UnitCarrier.openCarriedUnit`)

No user-facing documentation changes: nothing here is application API.

- [ ] **Step 1: Write the changes.**
- [ ] **Step 2: Security review** of the diff against SEC-14 with the `tiko-security` skill
  (PR review mode).
- [ ] **Step 3: Full build** `mvn -o clean install > <log> 2>&1; echo rc=$?`. Expected: rc=0.
- [ ] **Step 4: Commit**

  `docs: record the carried unit in the architecture invariants and the security model (#677)`

---

## After the tasks

- Pre-sweep the changed files for the recurring Sonar rules before the first push.
- Push, open the PR (`Refs #677`, it does not close the issue), wait for CI and Sonar, check
  Sonar's open issues.
