# K2 Piece 3: `tiko-coroutines`, the Context Element and the `suspend` Unit Function Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Kotlin code opens a unit of work that survives suspension:
`unitOfWork.inEventScope { ... }` runs its block in one unit across suspension points and
dispatcher changes, and the unit closes once when the block's coroutine completes, fails or is
cancelled.

**Architecture:** a new module, `tiko-coroutines`, written in Kotlin on
`kotlinx-coroutines-core`. It holds one internal class, a `CopyableThreadContextElement`, and one
public function. The element owns a `CarriedUnit` (piece 2): it opens the unit when its coroutine
first runs, installs it on every resume, takes it off on every suspension, and closes it from
`Job.invokeOnCompletion`. `inEventScope` is `withContext(element)`. A `launch` / `async` child
gets a copy of the element, and so a unit of its own; `withContext` keeps the element, and so
the unit. Nothing in `tiko-api`, the processor's model or generated code depends on Kotlin.

**Tech Stack:** Kotlin 2.4.21 (the version the build already pins), `kotlin-maven-plugin`
2.4.21, `kotlinx-coroutines-core` 1.11.0 (latest release on Maven Central on 2026-11-18),
`kotlinx-coroutines-test` 1.11.0 (test), Dokka Maven plugin 2.3.0 (release profile only),
JUnit 5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-11-04-kotlin-ksp-coroutines-design.md`, "Milestone K2",
items 1 and 2. Roadmap: `docs/superpowers/plans/2026-11-18-kotlin-k2-coroutines-roadmap.md`
(piece 3). Spike results: comment on #677 (2026-11-17). Issue: #677 (not closed by this piece).

## Decisions this plan assumes

The owner confirms or changes these when approving the plan.

| # | Decision | Assumed |
|---|---|---|
| D2 | Artifact name (permanent on Maven Central) | `tiko-coroutines`, package `io.tiko.coroutines` |
| D3 | Building on `CopyableThreadContextElement`, which kotlinx marks `@ExperimentalCoroutinesApi` and `@DelicateCoroutinesApi` | Accepted. The tested kotlinx version is named in the docs, and a test class pins the kotlinx behaviour the design rests on. **Changed from the roadmap:** the Tiko BOM does not pin `kotlinx-coroutines-core`. A pin would force Tiko's version on an application that gets coroutines from Ktor or its own BOM; the BOM leaves `kotlin-stdlib` out for the same reason. |
| D4 | Name of the `suspend` function | One generic function, `inEventScope { }` |

My own choices, listed so the owner can overrule them:

- **Javadoc jar.** Maven Central requires one for every artifact, and the javadoc plugin makes
  none for a Kotlin-only module. The release profile builds it with Dokka. If Dokka fails on the
  build JDKs, the fallback is a javadoc jar that holds only a pointer to the documentation.
- **No Kotlin formatter.** Spotless stays Java-only in this piece.
- **Kotlin explicit API mode**, so nothing becomes public by omission.

## What changed while implementing

- **The single-thread guard of `CarriedUnit` is gone** (piece 2 had it, and the owner had
  approved it). kotlinx installs the element on the thread a coroutine is resumed on before the
  thread it left has taken it off: 8,048 of 159,769 installs in a measurement on
  kotlinx-coroutines 1.11.0. The guard would have failed about one dispatcher change in twenty.
  `CarriedUnit` now keeps the set of threads that have it; a close that is left to them is run
  by the last one to take the unit off. The alternative, making the second thread wait for the
  first, would block dispatcher threads on a timing heuristic.
- **The contract test gained a case:** an element handed to a coroutine is copied, also for a
  `LAZY` coroutine that never starts. The first version of the test assumed the handed-over
  instance runs.
- **No `tiko-test` dependency.** The tests boot the container with `Tiko.create()`; a test-only
  compile generates an ordinary container.
- **Tests carry a one-minute limit.** Run against an empty stand-in for `inEventScope`, a test
  that waited for a coroutine blocked the build instead of failing.

## Global Constraints

- `tiko-api`, `tiko-processor` and generated code keep no Kotlin dependency. The processor jars
  work on javac without Kotlin on the path.
- One unit, no nesting (ARCH-5): `inEventScope` called while a unit of work is open on the
  calling thread throws `IllegalStateException`, as `runInEventScope` does.
- A unit of work always closes (SEC-14): on normal completion, on an exception, on cancellation,
  and on cancellation followed by a suspending `NonCancellable` cleanup, exactly once.
- A unit of work is single-threaded: a `launch` / `async` child never runs in its parent's unit.
- `kotlinx-coroutines-core` is a compile-scope dependency of `tiko-coroutines` and is managed in
  the root `dependencyManagement`; it is not in the Tiko BOM (D3).
- New dependencies follow `.claude/rules/dependencies.md`: latest stable version, license check
  (kotlinx.coroutines and Dokka are Apache-2.0).
- The module is published: it is in the BOM, in `tiko-coverage`, in every list of published
  artifacts, and `mvn -Prelease -Dgpg.skip=true -DskipTests verify` passes.
- CI builds the module on the whole JDK matrix. The Kotlin compiler targets JVM 21.
- Public prose follows the framing rule. Commits: one conventional summary line, no body.
- Before the first push: sweep for the recurring Sonar rules, S1181 included.

## Review Focus

1. **`inEventScope` inside a unit of work.** Inside another `inEventScope`, inside a blocking
   `runInEventScope` on the same thread, and inside a child coroutine's own unit: each throws
   `IllegalStateException` and leaves the open unit usable. Covered by Task 3.
2. **Cancellation before the block ever runs** (a `LAZY` coroutine that is cancelled, a scope
   cancelled before dispatch). No unit is opened, nothing is published. Covered by Task 3.
3. **A child that outlives its parent.** The parent's unit closes when the parent completes; the
   child keeps its own unit until it completes. Covered by Task 3.
4. **A `UnitOfWork` that cannot carry a unit** (a hand-written implementation, a container
   generated before 0.8.0). `inEventScope` fails with a message that names the cause. Covered by
   Task 3.
5. **The same thread, another context.** `withContext(CoroutineName("x"))` inside the unit
   re-installs the element on the thread that already has the unit; the unit and the thread's
   state are unchanged afterwards. Covered by Tasks 2 and 3.

---

### Task 1: the runtime answers "is a unit open on this thread?" and exposes the current origin

**Files:**
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/UnitCarrier.java`
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java`,
  `ContainerUnitOfWork.java`, `Tiko.java` (`BootHandle`), `CarriedUnit.java`,
  `EventChainContext.java`
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java`
  and the golden files
- Test: `tiko-runtime/src/test/java/io/tiko/runtime/CarriedUnitOpeningTest.java`,
  `tiko-processor/src/test/java/io/tiko/processor/CarriedUnitBootTest.java`,
  `DetachedEventScopeTest.java`

**Interfaces:**
- Produces:
  ```java
  public interface UnitCarrier {
      CarriedUnit openCarriedUnit(Event<?> origin);          // piece 2
      /** Whether a unit of work is open on the calling thread, in this application. */
      boolean hasUnitOpen();
  }

  // EventChainContext
  /** The event the calling thread's work descends from, or null. */
  public static Event<?> current();
  ```
- Generated container: `hasUnitOpen()` returns `Boolean.TRUE.equals(__unitFrameOpen.get())`.
  `AggregatingContainer`: true when any module that is a `UnitCarrier` answers true.
  `BootHandle` and `ContainerUnitOfWork` forward, with the error of piece 2 when the container
  cannot carry a unit.

- [ ] **Step 1: Failing tests.** Runtime: `anApplicationHasAUnitOpenWhenAnyModuleHasOne`,
  `theUnitOfWorkAComponentHoldsAnswersForTheApplication`, `currentOriginIsWhatEnterSet`. Boot
  test probe: false outside a unit, true inside `runInEventScope`, true while a carried unit is
  installed, false after `restore`. Emission test: `public boolean hasUnitOpen()`.
- [ ] **Step 2: Run, see them fail. Step 3: implement. Step 4: regenerate the golden files and
  read the diff (one new method).**
- [ ] **Step 5:** `mvn -o -pl tiko-runtime,tiko-processor install`, rc=0.
- [ ] **Step 6: Commit** `feat(runtime): a container tells whether a unit of work is open on the calling thread (#677)`

---

### Task 2: the module and its build, proven by the kotlinx contract test

**Files:**
- Create: `tiko-coroutines/pom.xml`
- Create: `tiko-coroutines/src/test/kotlin/io/tiko/coroutines/CoroutineContextElementContractTest.kt`
- Modify: root `pom.xml` (`<module>` after `tiko-test`; `kotlinx-coroutines-core` and
  `kotlinx-coroutines-test` in `dependencyManagement`; `kotlin-maven-plugin` and
  `dokka-maven-plugin` in `pluginManagement`; `kotlinx.coroutines.version`, `dokka.version`)
- Modify: `tiko-bom/pom.xml` (`tiko-coroutines`), `tiko-coverage/pom.xml` (dependency)
- Modify: `.github/workflows/*.yml` only if the build needs it

**Interfaces:**
- `tiko-coroutines/pom.xml`: dependencies `tiko-api`, `tiko-runtime`, `kotlin-stdlib`,
  `kotlinx-coroutines-core` (compile); `tiko-processor`, `tiko-test`, `junit-jupiter`,
  `assertj-core`, `kotlinx-coroutines-test` (test). `kotlin-maven-plugin` compiles
  `src/main/kotlin`, and `src/test/kotlin` together with `src/test/java`, before
  `maven-compiler-plugin`; `jvmTarget` 21; `-Xexplicit-api=strict`. Java test fixtures are
  compiled by javac with `tiko-processor`, as in `tiko-test`. Sonar is told the Kotlin source
  directories. In the `release` profile Dokka attaches the javadoc jar.

- [ ] **Step 1: Write the contract test.** It uses a recording `CopyableThreadContextElement`
  defined in the test and asserts, on kotlinx itself:
  - `copyForChild` runs for `launch` and `async` (same dispatcher, another dispatcher,
    `CoroutineStart.UNDISPATCHED`, `Dispatchers.Unconfined`);
  - it does not run for `withContext`, `coroutineScope`, `supervisorScope`, `withTimeout`;
  - `mergeForChild` runs when a coroutine that has the element is given one with the same key
    (`withContext(element)`, `launch(element)`);
  - `withContext(CoroutineName("x"))` on the same dispatcher calls `updateThreadContext` again on
    the thread that already has the element installed, and `restoreThreadContext` after it;
  - `updateThreadContext` and `restoreThreadContext` are paired on every resume and suspension;
  - `invokeOnCompletion` on the coroutine's `Job` fires once on completion, on an exception and
    on cancellation, and for a `LAZY` coroutine that is cancelled before it starts
    `updateThreadContext` was never called.
  Parameterized by construct name, so a failure names the construct.
- [ ] **Step 2: Add the module and the build wiring.** The first build runs online: the Kotlin
  Maven plugin 2.4.21 is not in the local repository.
- [ ] **Step 3: Run** `mvn -pl tiko-coroutines -am install` (rc=0, the contract test ran), then
  the release-profile check `mvn -Prelease -Dgpg.skip=true -DskipTests -pl tiko-coroutines -am verify`
  (rc=0, `tiko-coroutines-<version>-javadoc.jar` and `-sources.jar` exist and are not empty).
  If Dokka fails, switch to the fallback javadoc jar and say so in the PR.
- [ ] **Step 4: Commit** `build: add the tiko-coroutines module and pin the kotlinx behaviour it rests on (#677)`

---

### Task 3: the context element and `inEventScope`

**Files:**
- Create: `tiko-coroutines/src/main/kotlin/io/tiko/coroutines/UnitOfWorkElement.kt` (internal)
- Create: `tiko-coroutines/src/main/kotlin/io/tiko/coroutines/UnitOfWorkCoroutines.kt`
- Create: Java fixtures under `tiko-coroutines/src/test/java/io/tiko/coroutines/fixtures/`
  (`RequestId` + EVENT `FreshRequestId` with a `@PreDestroy` that reports to a SINGLETON
  `CloseLog`; SINGLETON `Requests` that takes `UnitOfWork` and `RequestId`)
- Test: `tiko-coroutines/src/test/kotlin/io/tiko/coroutines/InEventScopeTest.kt`,
  `ChildCoroutineUnitTest.kt`

**Interfaces:**
- Consumes: `UnitCarrier.openCarriedUnit`, `hasUnitOpen`, `CarriedUnit.install` / `restore` /
  `close`, `EventChainContext.carriedUnitOpener(carrier)` (the first version of this plan had a public `current()`; its `Event<?>` return type is what Sonar rule S1452 rejects).
- Produces:
  ```kotlin
  /**
   * Runs [block] in a unit of work of its own that lasts until the block's coroutine completes.
   * The unit's EVENT-scoped instances are the same before and after every suspension and on
   * every dispatcher. A `launch` or `async` started inside runs in a unit of its own.
   *
   * @throws IllegalStateException if a unit of work is already open, or this [UnitOfWork]
   *   cannot carry a unit between threads
   */
  public suspend fun <T> UnitOfWork.inEventScope(block: suspend CoroutineScope.() -> T): T
  ```
- `UnitOfWorkElement(carrier, origin)`: `updateThreadContext` opens the `CarriedUnit` on the
  first call, registers `close()` with `context[Job].invokeOnCompletion`, and returns
  `unit.install()`; `restoreThreadContext` calls `unit.restore(displaced)`; `copyForChild()`
  returns a new element for the same carrier; `mergeForChild` throws the ARCH-5
  `IllegalStateException`.
- `inEventScope` checks `hasUnitOpen()` before `withContext(element)`, so the nested call fails
  at the call site with the message `runInEventScope` uses.

- [ ] **Step 1: Failing tests** on a booted container (`Tiko.create` with test wiring; the
  container is closed after each test and reports no thread outliving it):
  - `InEventScopeTest`: `sameInstanceBeforeAndAfterSuspension`,
    `sameInstanceAfterADispatcherChange`, `sameInstanceUnderWithContextOnTheSameDispatcher`,
    `theThreadIsCleanAfterTheBlock`, `returnsTheBlocksValue`,
    `closesOnceWhenTheBlock` (parameterized: completes, throws, is cancelled while suspended, is
    cancelled and runs a suspending `NonCancellable` cleanup that still sees its instance),
    `aCoroutineCancelledBeforeItRanOpensNoUnit`, `twoConcurrentCallsGetTwoUnits`,
    `nestedCallIsRejected` (parameterized: inside `inEventScope`, inside `runInEventScope`,
    inside a child's unit), `aUnitOfWorkThatCannotCarryAUnitSaysSo`,
    `oneLifecyclePairPerCall`.
  - `ChildCoroutineUnitTest`: `aChildRunsInAUnitOfItsOwn` (parameterized: `launch`, `async`,
    another dispatcher, `UNDISPATCHED`), `withContextKeepsTheUnit`,
    `eachElementOfMapAsyncGetsAUnit`, `aChildsUnitClosesWhenTheChildCompletes`,
    `aChildThatOutlivesItsParentKeepsItsUnit`, `aLazyChildThatNeverRunsOpensNoUnit`.
- [ ] **Step 2: Run, see them fail. Step 3: implement. Step 4: run the module** (rc=0).
- [ ] **Step 5: Security review** of the diff against SEC-14 with the `tiko-security` skill.
- [ ] **Step 6: Commit** `feat(coroutines): inEventScope runs a block in one unit of work across suspension (#677)`

---

### Task 4: documentation, artifact lists, full build

**Files:**
- Modify: `docs/di-and-scopes.md` (a section on a unit of work in coroutines: `inEventScope`,
  what stays the same across suspension, a child gets its own unit, the nested call is rejected,
  the tested kotlinx version and that the element is built on an experimental kotlinx API)
- Modify: `.ai-skills/tiko-build/reference/kotlin.md` and its archetype copy;
  `.ai-skills/tiko-build/reference/api-signatures.md` and its archetype copy (packages table)
- Modify: every list of modules or published artifacts: `README.md`, `CLAUDE.md`,
  `docs/releasing.md`, `.ai-skills/tiko-release/SKILL.md`, the release workflow if it lists
  artifacts, `tiko-examples-gradle/README.md` if it lists dependencies
- Modify: `docs/architecture-invariants.md` (ARCH-15: the coroutine element as the user of the
  carried unit), `docs/security-model.md` (SEC-14: completion paths of a coroutine), the
  security skill's surface map
- Modify: the roadmap's status table

- [ ] **Step 1: Write the changes.** `grep` for "twelve", "12 artifacts" and for the module list
  to find every place.
- [ ] **Step 2: Full build** `mvn clean install` (rc=0) and the release-profile check for the
  whole reactor `mvn -Prelease -Dgpg.skip=true -DskipTests verify` (rc=0).
- [ ] **Step 3: Commit** `docs: document inEventScope and add tiko-coroutines to the published modules (#677)`

---

## After the tasks

- Sonar pre-sweep, push, PR (`Refs #677`), CI on the JDK matrix and Sonar, open issues checked.
- Not in this piece: the origin chain in a child and a child's lifecycle documentation (piece
  4), the Ktor example (piece 5), `suspend` handlers (piece 7).
