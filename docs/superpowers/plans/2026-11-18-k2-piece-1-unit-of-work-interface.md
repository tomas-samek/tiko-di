# K2 Piece 1: Injectable `UnitOfWork` Interface Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a component can take `io.tiko.UnitOfWork` in its constructor (or a `@Produces` method
in its parameters) and open one unit of work per call, without ever holding the `Container`.

**Architecture:** `UnitOfWork` is a `tiko-api` interface with the two methods `Container`
already has; `Container` extends it. The processor treats it as a built-in dependency, the way
it treats `EventBus` since #314: nothing is looked up, generated code constructs the value. The
value is a small runtime class, `ContainerUnitOfWork`, that asks the application's container on
every call, the way `ContainerPicker` does since #732. So a component in one module of a
multi-module application opens the application's unit, and the object it holds cannot be cast
to `Container`.

**Tech Stack:** Java 21, Maven, JavaPoet (existing), compile-testing and kctfork (existing),
Gradle + KSP for the Kotlin example.

**Spec:** issue #685 (owner's decision of 2026-11-12). Roadmap:
`docs/superpowers/plans/2026-11-18-kotlin-k2-coroutines-roadmap.md` (piece 1). The interface
name `UnitOfWork` is the owner's decision D1 of 2026-11-18.

## Global Constraints

- `Container` stays non-injectable: a constructor parameter of type `io.tiko.Container` is still
  a compile error.
- `UnitOfWork` exposes exactly `runInEventScope(Runnable)` and `supplyInEventScope(Supplier<T>)`,
  with the contract `Container` documents today, including the `IllegalStateException` when a
  unit is already open on the thread.
- A `Container` can be passed wherever a `UnitOfWork` is expected.
- No reflection in framework or generated code. No new dependency.
- Javac and KSP behave the same; the logic lives in core-model code (`FrontendBoundaryTest`).
- `@Named UnitOfWork` and `@Pick` on it are not the built-in: they take the normal
  unresolved-dependency path, as `@Named EventBus` does.
- Suspend variants are out of scope (#677, roadmap piece 3). No HTTP module, no nestable EVENT
  scope (#252).
- Commits: one conventional summary line, no body. Run `mvn -o -q -pl <module> spotless:apply`
  before each test run.

## Review Focus

1. **A component in one module of a multi-module application.** Its `UnitOfWork` must open the
   application's unit: EVENT components of every module are reachable in it and one lifecycle
   pair is published. Covered by Task 4 (`MultiModuleUnitOfWorkTest`).
2. **`UnitOfWork` taken during construction of the application.** The aggregate builds its
   modules while it is being constructed, so the application container must be looked up at call
   time, not captured in the constructor. Covered by Task 2.
3. **A unit opened while one is already open on the thread.** The call throws
   `IllegalStateException` and leaves the open unit usable. Covered by Task 4.
4. **`Provider<UnitOfWork>` and a `@Produces` parameter.** Both generate compiling code.
   Covered by Task 3.
5. **A failing `@PostConstruct` in a component that takes `UnitOfWork`.** `Tiko.create(...)`
   throws and what the container started is shut down. Covered by Task 4.

---

### Task 1: the `UnitOfWork` interface

**Files:**
- Create: `tiko-api/src/main/java/io/tiko/UnitOfWork.java`
- Modify: `tiko-api/src/main/java/io/tiko/Container.java` (the two methods and their Javadoc move)
- Test: `tiko-api/src/test/java/io/tiko/UnitOfWorkTest.java`

**Interfaces:**
- Produces:
  ```java
  package io.tiko;
  public interface UnitOfWork {
      void runInEventScope(Runnable runnable);
      <T> T supplyInEventScope(java.util.function.Supplier<T> supplier);
  }
  public interface Container extends UnitOfWork, AutoCloseable { /* the rest unchanged */ }
  ```

- [ ] **Step 1: Write the failing test**

  ```java
  class UnitOfWorkTest {
      @Test
      void containerIsAUnitOfWork() {
          assertThat(UnitOfWork.class).isAssignableFrom(Container.class);
      }

      @Test
      void unitOfWorkOpensUnitsAndNothingElse() {
          assertThat(UnitOfWork.class.getMethods())
                  .extracting(Method::getName)
                  .containsExactlyInAnyOrder("runInEventScope", "supplyInEventScope");
      }
  }
  ```

- [ ] **Step 2: Run it and see it fail**

  Run: `mvn -o -q -pl tiko-api test -Dtest=UnitOfWorkTest > <log> 2>&1; echo rc=$?`
  Expected: compilation failure, `UnitOfWork` does not exist.

- [ ] **Step 3: Add the interface and make `Container` extend it**

  The Javadoc of the two methods moves to `UnitOfWork` unchanged. The type Javadoc says what the
  interface is for (a component that opens one unit per inbound stimulus, such as an HTTP route
  or a scheduled job), that it is injectable, and that `Container` is not. `Container` keeps
  neither declaration; its own type Javadoc example stays valid.

- [ ] **Step 4: Run the test and the module**

  Run: `mvn -o -q -pl tiko-api test > <log> 2>&1; echo rc=$?`
  Expected: rc=0.

- [ ] **Step 5: Commit**

  `feat(api): add the UnitOfWork interface that Container extends (#685)`

---

### Task 2: `ContainerUnitOfWork`, the value generated code injects

**Files:**
- Create: `tiko-runtime/src/main/java/io/tiko/runtime/ContainerUnitOfWork.java`
- Test: `tiko-runtime/src/test/java/io/tiko/runtime/ContainerUnitOfWorkTest.java`

**Interfaces:**
- Consumes: `io.tiko.UnitOfWork` (Task 1); `TikoOptions.applicationContainer()` (package-private,
  existing); `StubContainer` (existing test double).
- Produces:
  ```java
  public final class ContainerUnitOfWork implements UnitOfWork {
      /** @param options the container's options, or null for a unit of work over container alone */
      public ContainerUnitOfWork(Container container, TikoOptions options);
  }
  ```
  Both methods delegate to `options.applicationContainer()` when it is set, otherwise to
  `container`. The lookup happens on every call.

- [ ] **Step 1: Write the failing tests**

  Names and assertions:
  - `opensTheUnitInItsOwnContainerWhenNoApplicationIsNamed`: options without an application;
    `runInEventScope` and `supplyInEventScope` reach the container, and the supplier's value is
    returned.
  - `opensTheUnitInTheApplicationsContainer`: `options.applicationContainer(application)` set;
    both calls reach `application` and none reaches `container`.
  - `looksTheApplicationUpOnEveryCall`: the object is constructed first, the application is set
    afterwards, and the next call reaches the application.
  - `worksWithoutOptions`: `new ContainerUnitOfWork(container, null)` delegates to `container`.

  Use two recording doubles built on `StubContainer` that count the calls of each method.

- [ ] **Step 2: Run and see them fail**

  Run: `mvn -o -q -pl tiko-runtime test -Dtest=ContainerUnitOfWorkTest > <log> 2>&1; echo rc=$?`
  Expected: compilation failure, `ContainerUnitOfWork` does not exist.

- [ ] **Step 3: Implement `ContainerUnitOfWork`**

  Mirror `ContainerPicker.application()`. The class Javadoc says it is what generated code hands
  to a component that takes `UnitOfWork`, and why it is not the container itself.

- [ ] **Step 4: Run the tests**

  Expected: rc=0.

- [ ] **Step 5: Commit**

  `feat(runtime): add ContainerUnitOfWork, a unit-of-work view of the application's container (#685)`

---

### Task 3: the processor treats `UnitOfWork` as a built-in dependency

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/model/DependencyModel.java`
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/ComponentFactoryGenerator.java`
  (`generateContainerGetCall(DependencyModel, ComponentModel)`)
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java`
  (`generateContainerGetCall(DependencyModel, Scope)`)
- Modify: `tiko-processor/src/main/java/io/tiko/processor/validation/DependencyGraphValidator.java`
- Modify: `tiko-processor/src/main/java/io/tiko/processor/validation/NullabilityValidator.java`
- Test: `tiko-processor/src/test/java/io/tiko/processor/UnitOfWorkInjectionTest.java`
- Test: `tiko-processor/src/test/java/io/tiko/processor/ksp/KspUnitOfWorkInjectionTest.java`

**Interfaces:**
- Consumes: `io.tiko.runtime.ContainerUnitOfWork(Container, TikoOptions)` (Task 2).
- Produces in `DependencyModel`:
  ```java
  public static final String UNIT_OF_WORK_TYPE = "io.tiko.UnitOfWork";
  public boolean isUnitOfWork();   // unqualified, not @Pick-ed, plain or Provider<UnitOfWork>
  public boolean isBuiltIn();      // isEventBus() || isUnitOfWork()
  ```
- Generated code:
  - in a component factory: `new io.tiko.runtime.ContainerUnitOfWork(container, container.options())`
  - in the container, for a `@Produces` parameter:
    `new io.tiko.runtime.ContainerUnitOfWork(this, options)`, and
    `() -> new io.tiko.runtime.ContainerUnitOfWork(this, options)` for `Provider<UnitOfWork>`.

- [ ] **Step 1: Write the failing javac tests** (`UnitOfWorkInjectionTest`, modelled on
  `EventBusInjectionTest`)

  - `unitOfWorkIsInjectableIntoAComponentConstructor`: compiles; the generated factory contains
    `new io.tiko.runtime.ContainerUnitOfWork(container, container.options())`.
  - `unitOfWorkIsInjectableIntoAProducesMethod`: compiles; the generated container contains
    `new io.tiko.runtime.ContainerUnitOfWork(this, options)`.
  - `providerOfUnitOfWorkIsInjectable`: a constructor taking `Provider<UnitOfWork>` and a
    `@Produces` method taking one both compile.
  - `namedUnitOfWorkIsNotTheBuiltIn`: `@Named("x") UnitOfWork` fails with the existing
    unresolved-dependency error.
  - `containerIsStillNotInjectable`: a constructor taking `io.tiko.Container` fails with the
    existing unresolved-dependency error.

- [ ] **Step 2: Write the failing KSP test** (`KspUnitOfWorkInjectionTest`, modelled on
  `KspBootTest`)

  - `kotlinComponentTakesUnitOfWork`: `class Server(private val unitOfWork: UnitOfWork)` compiles
    with exit code `OK`, and its generated factory contains `ContainerUnitOfWork`. The
    parameter is non-null in Kotlin, so this also shows `NullabilityValidator` treats the
    built-in as a non-null provider ("the container's UnitOfWork").

- [ ] **Step 3: Run and see them fail**

  Run: `mvn -o -q -pl tiko-processor test -Dtest='UnitOfWorkInjectionTest,KspUnitOfWorkInjectionTest' > <log> 2>&1; echo rc=$?`
  Expected: the positive cases fail with the unresolved-dependency error for
  `io.tiko.UnitOfWork`; the two negative cases already pass.

- [ ] **Step 4: Implement**

  `isUnitOfWork()` has the shape of `isEventBus()`. The two validators switch from
  `isEventBus()` to `isBuiltIn()`; `NullabilityValidator` names the provider by kind. The two
  generators add the branch next to the `EventBus` one. Update the Javadoc on
  `EVENT_BUS_TYPE`, which lists the built-in set.

- [ ] **Step 5: Check the other consumers of a dependency**

  `CircularDependencyDetector`, `ScopeValidator`, `ContainerGenerator.visitShutdownDependencies`
  and `TopologyWriter.writeDependencies` already see `EventBus` dependencies. Compile the
  component from Step 1 next to one that takes `EventBus` and compare how each appears in
  `topology.json`: `UnitOfWork` must appear the same way. If it does not, add the test first and
  then the fix.

- [ ] **Step 6: Run the module**

  Run: `mvn -o -q -pl tiko-processor test > <log> 2>&1; echo rc=$?`
  Expected: rc=0, golden files unchanged.

- [ ] **Step 7: Commit**

  `feat(processor): inject UnitOfWork as a built-in dependency on javac and KSP (#685)`

---

### Task 4: behaviour in a booted container

**Files:**
- Create: `tiko-test/src/test/java/io/tiko/test/fixtures/UnitRunner.java` (SINGLETON taking
  `UnitOfWork`), reuse the existing EVENT fixture `RequestContext` / `RequestContextImpl`
- Test: `tiko-test/src/test/java/io/tiko/test/UnitOfWorkInjectionTest.java`
- Create: fixtures in `tiko-examples/05_multi_module` (a SINGLETON taking `UnitOfWork` in one
  module, resolving an EVENT component of the other module)
- Test: `tiko-examples/05_multi_module/.../MultiModuleUnitOfWorkTest.java`
- Test: `tiko-processor/src/test/java/io/tiko/processor/UnitOfWorkServerStartTest.java`. It
  compiles its sources into a `@TempDir` and boots them through a `URLClassLoader`, as
  `FailedBootstrapCleanupTest` does. The sources must be a main module on their own: components
  declared in a test tree make a test-wiring container, where a failing `@PostConstruct`
  surfaces at first use and does not fail `Tiko.create(...)` (`docs/di-and-scopes.md`). Sources:
  `StartedFirst` (SINGLETON with a `@PreDestroy` that records it ran) and `PortBoundServer`
  (SINGLETON taking `UnitOfWork` and `StartedFirst`, whose `@PostConstruct` binds a
  `ServerSocket` to a port read from a system property)
- Modify: `tiko-examples-gradle/kotlin-quickstart` (one component taking `UnitOfWork`, one test
  method in `QuickstartTikoTest.kt`)

**Interfaces:**
- Consumes: everything from Tasks 1 to 3.

- [ ] **Step 1: Write the `@TikoTest` test** (`UnitOfWorkInjectionTest`, Maven path)

  - `eachCallOpensItsOwnUnit`: two calls of `unitOfWork.supplyInEventScope(...)` return different
    EVENT instances; two resolutions inside one call return the same instance.
  - `aUnitInsideAUnitIsRejected`: a call made inside a call throws `IllegalStateException`, and
    the outer unit still resolves its EVENT instance afterwards.
  - `theUnitClosesAfterTheCall`: the EVENT component's `@PreDestroy` has run when the call
    returns, also when the task throws.

- [ ] **Step 2: Write the multi-module test** (`MultiModuleUnitOfWorkTest`, modelled on
  `MultiModuleAsyncUnitTest`)

  - `aUnitOpenedInOneModuleIsTheApplicationsUnit`: inside the call, the EVENT component of the
    other module resolves, twice to the same instance; the recording bus sees exactly one
    `EventStartedEvent` / `EventEndingEvent` pair with one event id.

- [ ] **Step 3: Write the boot-failure test** (`UnitOfWorkServerStartTest`)

  - `aServerThatCannotStartFailsTheBootAndWhatStartedIsShutDown`: the test holds a
    `ServerSocket` on an ephemeral port and passes that port in; `Tiko.create(...)` throws; the
    `@PreDestroy` of `StartedFirst` has run; `TikoOptions.Builder#onThreadsOutlivingClose`
    reports no thread. The system property is cleared in a `finally`.
  - `aServerThatStartsServesEachRequestInItsOwnUnit`: with a free port the boot succeeds, and
    two calls through the server's `UnitOfWork` see different EVENT instances.

- [ ] **Step 4: Run the three tests**

  One Maven build at a time:
  `mvn -o -q -pl tiko-api,tiko-runtime,tiko-processor-core,tiko-processor,tiko-test install -DskipTests > <log> 2>&1; echo rc=$?`
  then each test module with `-Dtest=...`. Expected: they pass once Tasks 1 to 3 are installed.
  A test that fails here names behaviour the earlier tasks did not deliver: fix it in the task
  that owns the code, with a test at that level.

- [ ] **Step 5: The Kotlin example**

  Add to `kotlin-quickstart` a SINGLETON component that takes `UnitOfWork` and a test method
  showing two calls get different EVENT instances. Run
  `./gradlew build run` in `tiko-examples-gradle/kotlin-quickstart` after installing the
  snapshot to the local repository. Expected: `BUILD SUCCESSFUL`, the example's output unchanged.

- [ ] **Step 6: Commit**

  `test: cover UnitOfWork injection in a booted container, across modules and on Gradle + KSP (#685)`

---

### Task 5: documentation

**Files:**
- Modify: `docs/di-and-scopes.md` (the unit-of-work section: how a component opens a unit, with
  a Java snippet; `Container` stays out of constructors)
- Modify: `.ai-skills/tiko-build/reference/api-signatures.md` and its archetype copy under
  `tiko-archetype/src/main/resources/archetype-resources/.ai-skills/tiko-build/reference/`
  (packages table row and the signature block)
- Modify: `.ai-skills/tiko-build/reference/events.md` and its archetype copy (the passage that
  says to inject `EventBus` rather than reach for `Container` gains `UnitOfWork`)
- Modify: `tiko-archetype/src/main/resources/archetype-resources/.ai-skills/SKILL.md` and
  `.cursor/rules/tiko.md` if they repeat that passage
- Add to the PR: `docs/superpowers/plans/2026-11-18-kotlin-k2-coroutines-roadmap.md` and this
  plan

- [ ] **Step 1: Write the documentation changes**

  Public prose follows the framing rule: say what the interface does and when to take it.
  Examples lead with the interface, not with an implementation.

- [ ] **Step 2: Run the documentation tests**

  The archetype has tests that compare its copies with the skill's originals. Run
  `mvn -o -q -pl tiko-archetype test > <log> 2>&1; echo rc=$?`. Expected: rc=0.

- [ ] **Step 3: Full build**

  `mvn -o clean install > <log> 2>&1; echo rc=$?`. Expected: rc=0, `Skipped: 0` for the
  integration tests.

- [ ] **Step 4: Commit**

  `docs: document the injectable UnitOfWork interface and add the K2 roadmap (#685)`

---

## After the tasks

- Pre-sweep the changed files for the recurring Sonar rules before the first push.
- Push the branch, open the PR (`Closes #685`), wait for CI and Sonar, and check Sonar's open
  issues, not only the gate.
- Not in this PR: converting `09_http_javalin`, `15_quickstart` and the outbox examples to the
  interface. That is #686 (roadmap piece 11).
