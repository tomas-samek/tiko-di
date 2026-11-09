# Kotlin K1.5 (`tiko-test` on KSP) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `@TikoTest`, `@TestComponent` shadowing and `replaceTransport` work on a Gradle + KSP
test compile. On both javac and KSP, no thread or Kafka consumer a container started outlives
the test that booted it without the test naming it.

**Architecture:**
- **Test-compile detection.** It already runs through one core seam,
  `ProcessingEnv.classpath()` (`ClasspathProbe`), which is empty on KSP since K1. KSP can't read
  classpath resources, but it can list compiled classes in a package. So the main module's
  facts (its container, its component classes) are read from the generated `io.tiko.generated`
  classes the main compile already emits. One class in `tiko-processor`,
  `MainModuleOnClasspath`, decides both facts for both frontends: resources first (javac,
  unchanged), then compiled generated classes (KSP).
- **Leak guarantee.** Every thread a container starts comes from a container-owned tracking
  `ThreadFactory`. That covers the async event executor, teardown threads and Kafka consumer
  threads. When `close()` finishes, the threads still alive are handed to a handler. The default
  handler logs a WARNING, which also covers Cucumber hooks and hand-managed containers.
  `@TikoTest` installs its own handler and fails the test that leaked, naming each thread and
  where it's stuck.

**Tech Stack:** Java 21, Maven, KSP2 2.3.13 + kctfork (existing), JUnit 5.10.1 +
`junit-platform-testkit` (new, test), Gradle + KSP (example build).

**Spec:** `docs/superpowers/specs/2026-11-04-kotlin-ksp-coroutines-design.md` (section
"Milestone K1.5"). The owner added the leak guarantee on 2026-11-09. In their words, the Spring
pain was "threads and consumers that survived the test" plus not being able to tell which test
caused the clash (Cucumber and repository suites closing contexts before or after the suite).

## Global Constraints

- **Java users see no generated-code change.** Every existing compile-testing suite in
  `tiko-processor` and `tiko-kafka-processor` passes unchanged. Tasks 2 and 3 also diff
  `tiko-examples/*/target/generated-sources` against `origin/main`: the diff must be empty.
- **Detection logic exists once.** It lives in `MainModuleOnClasspath`. Frontend adapters only
  report facts (`readResource`, `compiledClassesIn`); they never decide what those facts mean
  (spec anti-goal "two diverging copies").
- **New public surface is exactly this:**
  - `ClasspathProbe.compiledClassesIn(String)`, in the processor-internal core module;
  - `default ThreadFactory threadFactory()` on `io.tiko.Container`;
  - `TikoOptions.Builder#onThreadsOutlivingClose(Consumer<List<Thread>>)`.

  Nothing else becomes public. Each addition goes into
  `.ai-skills/tiko-build/reference/api-signatures.md` in the task that adds it.
- **Framework thread names don't change** (`tiko-event-async-N`, `tiko-teardown`,
  `tiko-kafka-consumer-<topic>`, `tiko-event-async-test-N`). Ops dashboards and existing tests
  key on them.
- No `Thread.sleep` in tests (Awaitility). `System.Logger` only. Annotations stay `SOURCE`.
  `var` for locals.
- **Process:**
  - one PR per task, each targeting `main`; no stacked PRs, and the next PR opens only after
    the previous one is merged;
  - milestone #19 on every issue and PR;
  - single-line `type(scope): subject (#N)` commits;
  - parallel worktrees use `mvn verify`, never `install`, and never `git stash`.

## Review Focus

1. **A main module that depends on another Tiko module's jar.** On KSP the test compile sees
   both modules' generated classes. It must still pick `TestContainerImpl_` and must be able to
   shadow a component from either module. Pinned by
   `testCompileSeesComponentsOfEveryCompiledModule` (Task 2).
2. **A test-only module (`@TestComponent`, no main container on the classpath).** It keeps the
   `TikoContainerImpl_` prefix, as on javac today. Pinned by
   `testOnlyModuleKeepsTheMainContainerName` (Task 2).
3. **A thread that exits a few milliseconds after `close()` returns** (interrupted, finishing
   its last poll). It must not be reported. Pinned by `threadThatExitsWithinTheGraceIsNotReported`
   (Task 3).
4. **Two `@TikoTest` classes running in parallel.** Each leak report names only the test whose
   container owns the thread. Pinned by `leakIsAttributedToItsOwnTestUnderParallelExecution`
   (Task 6).
5. **`close()` called twice, or a boot that failed halfway.** The handler is called at most
   once, and a half-booted container still releases its executor. Pinned by
   `secondCloseReportsNothing` (Task 3) and `executorIsReleasedWhenBootFails` (Task 6).

---

### Task 1: `ClasspathProbe.compiledClassesIn` on both frontends

**Files:**
- Modify: `tiko-processor-core/src/main/java/io/tiko/processor/core/ClasspathProbe.java`
- Modify: `tiko-processor-core/src/main/java/io/tiko/processor/core/ksp/KspProcessingEnv.java:99`
- Modify: `tiko-processor-core/src/main/java/io/tiko/processor/core/javac/JavacClasspathProbe.java`
- Test: `tiko-processor-core/src/test/java/io/tiko/processor/core/ksp/KspDeclarationFactsTest.java`, `.../javac/JavacModelTest.java`

**Interfaces:**
- Produces: `List<ClassDecl> compiledClassesIn(String packageName)` on `ClasspathProbe`. It
  returns the top-level classes of the package that come from the compile classpath, never from
  this round's sources, sorted by qualified name.
  - KSP: `resolver.getDeclarationsFromPackage(packageName)`, filtered to `KSClassDeclaration`
    whose origin is `KOTLIN_LIB` or `JAVA_LIB`, built as `ClassDecl` through `KspModel`.
  - javac: always `List.of()`, javadoc'd as "javac reads the resources instead". This keeps
    Java behaviour byte-identical.
  - Update the interface javadoc: `readResource` is empty on KSP; `compiledClassesIn` is empty
    on javac.

- [ ] **Step 1: Write the failing tests.**
  - In `KspDeclarationFactsTest`, add the fact `compiledClassesInListsLibraryClasses`. A library
    built with `KspCompiler.library(...)` holds `lib.A` and `lib.B`; the round's source holds
    `lib.C`. Then `env.classpath().compiledClassesIn("lib")` maps to qualified names
    `List.of("lib.A", "lib.B")`.
  - Also add `compiledClassesInUnknownPackageIsEmpty`.
  - In `JavacModelTest`, assert that `compiledClassesIn("java.util")` is empty.
- [ ] **Step 2:** Run `mvn -B -pl tiko-processor-core verify -Dtest='KspDeclarationFactsTest,JavacModelTest'`.
  Expected: compile FAIL (no such method).
- [ ] **Step 3:** Implement as in Interfaces. `getDeclarationsFromPackage` is `@KspExperimental`;
  opt in at the call site only. **If KSP2 does not list library classes this way, stop and
  report NEEDS_DECISION.** The rest of the plan depends on it.
- [ ] **Step 4:** Rerun the command from Step 2; expect PASS. Then run
  `mvn -B -pl tiko-processor-core,tiko-processor,tiko-kafka-processor verify -DskipITs`; expect
  BUILD SUCCESS.
- [ ] **Step 5:** Commit `feat(processor-core): list compiled classes of a package on the classpath (#N)`.

### Task 2: `MainModuleOnClasspath`: one test-compile detection for both frontends

**Files:**
- Create: `tiko-processor/src/main/java/io/tiko/processor/collect/MainModuleOnClasspath.java`
- Modify: `tiko-processor/src/main/java/io/tiko/processor/TikoPipeline.java:547-570`
  (`mainDescriptorFqnOnClasspath`); `.../collect/ComponentCollector.java:80-95`
  (`mainComponentFqnsOnClasspath`) and its two callers
- Modify: `tiko-processor/src/test/java/io/tiko/processor/ksp/KspProgram.java`, adding test
  wiring
- Test: `tiko-processor/src/test/java/io/tiko/processor/ksp/KspTestCompileTest.java` (new)

**Interfaces:**
- Consumes: `ClasspathProbe.compiledClassesIn` (Task 1).
- Produces:
  - `final class MainModuleOnClasspath`, with `static MainModuleOnClasspath read(ProcessingEnv env)`,
    `Optional<String> containerFqn()` and `Set<String> componentFqns()`.
  - `read` decides as follows:
    1. **Resources.** When `META-INF/tiko/container.properties` is readable, both facts come
       from it and from `components.txt`, exactly as today's two methods do. Move their bodies
       here.
    2. **Generated classes.** Otherwise they come from `compiledClassesIn("io.tiko.generated")`:
       - `containerFqn` is `io.tiko.generated.TikoContainerImpl_<h>` for the first class named
         `TikoModule_<h>` (sorted order);
       - `componentFqns` is the erased return type of `create()` on every class named
         `*Factory` whose public constructor's parameter type starts with
         `io.tiko.generated.TikoContainerImpl_`. Factories of a standalone `TestContainerImpl_`
         are excluded.
  - Both pipeline call sites use only this class. Delete the old static
    `ComponentCollector.mainComponentFqnsOnClasspath`.
  - `KspProgram` gains `static KspProgram bootTestWiring(KspResult result)`, which boots
    `Tiko.create(TikoOptions.builder().testWiring(true).build())` reflectively in the same
    loader setup.

- [ ] **Step 1: Write the failing tests** in `KspTestCompileTest`. Each compiles a main module
  with `KspCompiler.compile(...)`. It then compiles test sources against the main output roots
  as `libraries`, with the `TestComponent` annotation source from `TestContainerStandaloneTest`
  on the classpath. The tests:
  - `generatedClassesNameTheSameComponentsAsTheManifest`: the main compile's
    `resources().get("META-INF/tiko/components.txt")` lines equal
    `MainModuleOnClasspath.read(env).componentFqns()` read in the test compile. This is the
    parity check.
  - `testCompileEmitsAStandaloneTestContainer`:
    - `generatedJavaNames()` contains one `io.tiko.generated.TestContainerImpl_*`;
    - the resources contain `META-INF/tiko/test-container.properties`;
    - the resources contain `META-INF/tiko/test-shadows.properties` with a `demo.Clock=` line.
  - `implicitWalkShadowsTheMainComponent`: Kotlin main `open class Clock { open fun now() = "real" }`;
    test `@TestComponent class FakeClock : Clock() { override fun now() = "fake" }`. Then
    `bootTestWiring` over both outputs; `get("demo.Clock")` reflectively returns `now() == "fake"`.
  - `explicitValueShadowsAnInterfaceKey`: main `interface Clock` plus
    `@Component class SystemClock : Clock`; test `@TestComponent(Clock::class) class FixedClock : Clock`.
    Booted, `get("demo.Clock")` is a `demo.FixedClock`.
    - First confirm javac's two-phase compile does the same for the equivalent Java sources:
      a main compile, then a test compile against its output.
    - If javac doesn't, assert javac's actual behaviour here (parity) and record the gap in
      the task report. Don't fix javac in this task.
  - `testCompileSeesComponentsOfEveryCompiledModule` (Review Focus 1): two main modules
    compiled separately, both on the test classpath, and a test component shadowing a class of
    the first. Expect a `TestContainerImpl_` and a shadow line for that class.
  - `testOnlyModuleKeepsTheMainContainerName` (Review Focus 2): a test compile with no main
    output on the classpath generates `TikoContainerImpl_*` and no `test-container.properties`.
  - `testComponentInMainSourcesIsRejected`: a `@TestComponent` in a file under `src/main/kotlin`
    fails with the existing "is in production sources" error.
- [ ] **Step 2:** Run `mvn -B -pl tiko-processor-core,tiko-processor verify -Dtest=KspTestCompileTest -DskipITs`.
  Expected: FAIL. On KSP the test compile sees no main module, so there's no
  `TestContainerImpl_` and no shadow.
- [ ] **Step 3:** Implement `MainModuleOnClasspath` and route both call sites through it.
- [ ] **Step 4:** Rerun the command from Step 2; expect PASS. Then run
  `mvn -B clean verify -DskipITs`; expect BUILD SUCCESS with no edits to existing javac tests.
- [ ] **Step 5: Java output check.** In a temporary worktree of `origin/main`, run
  `mvn -B -q clean verify -DskipTests` there and here. Then
  `diff -r <main>/tiko-examples <branch>/tiko-examples` restricted to
  `target/generated-sources` prints nothing. Remove the worktree.
- [ ] **Step 6:** Commit `feat(processor): detect the main module on KSP test compiles (#N)`.

### Task 3: Container-owned threads and the leak report on close

**Files:**
- Modify: `tiko-api/src/main/java/io/tiko/Container.java`, adding the default
  `threadFactory()`
- Create: `tiko-runtime/src/main/java/io/tiko/runtime/OwnedThreads.java` (package-private)
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java` (shutdown
  sequence, about lines 640–680), `DefaultEventExecutorFactory.java:80`,
  `BoundedExecution.java:92`, `TikoOptions.java` (builder)
- Docs: `api-signatures.md`; the docs section describing `close()` / shutdown timeouts (find
  it with `grep -rn "shutdown-timeout" docs`)
- Test: `tiko-runtime/src/test/java/io/tiko/runtime/ThreadsOutlivingCloseTest.java` (new)

**Interfaces:**
- Produces:
  - `io.tiko.Container`: `default java.util.concurrent.ThreadFactory threadFactory() { return Thread.ofPlatform().daemon().factory(); }`.
    It's a one-line default, per the API rule. Javadoc: "threads the container starts and
    reports if they outlive `close()`; transports create their threads here".
  - `OwnedThreads implements ThreadFactory` creates daemon platform threads and tracks them
    weakly. It provides `List<Thread> stillAlive(Duration grace)`, which joins each live
    tracked thread within one shared grace deadline and returns those still alive.
  - The aggregate owns one `OwnedThreads`. Its `threadFactory()` returns it. The default event
    executor and `BoundedExecution` teardown threads take their threads from it, and their
    names stay unchanged.
  - `TikoOptions.Builder onThreadsOutlivingClose(Consumer<List<Thread>> handler)`. The default
    handler logs one WARNING on `io.tiko.events`:
    `"Container closed, but {n} thread(s) it started are still running: {name} (at {top frame}), …"`.
  - At the end of `shutdown()`, after the existing bounded waits, the aggregate calls
    `stillAlive(THREAD_EXIT_GRACE)` with `THREAD_EXIT_GRACE = Duration.ofSeconds(1)`. When that
    list is non-empty, it calls the handler once. A second `close()` reports nothing.

- [ ] **Step 1: Write the failing tests** in `ThreadsOutlivingCloseTest`, using the existing
  runtime test fixtures for a booted container:
  - `asyncHandlerStuckPastShutdownIsReported`: an async handler that ignores interrupts until a
    latch opens. After `close()`, the captured handler list holds one thread whose name starts
    with `tiko-event-async-`. Open the latch afterwards.
  - `cleanCloseReportsNothing`.
  - `threadThatExitsWithinTheGraceIsNotReported`: the thread exits about 100 ms after interrupt.
  - `secondCloseReportsNothing`.
  - `defaultHandlerLogsAWarningNamingTheThread`: a capturing `System.Logger`, using the existing
    logger test pattern in tiko-runtime.
  - `userSuppliedExecutorThreadsAreNotTracked`: an `eventExecutor(...)` supplied through options
    stays the caller's responsibility.
- [ ] **Step 2:** Run `mvn -B -pl tiko-api,tiko-runtime verify -Dtest=ThreadsOutlivingCloseTest -DskipITs`.
  Expected: compile FAIL.
- [ ] **Step 3:** Implement as in Interfaces.
- [ ] **Step 4:** Rerun the command from Step 2; expect PASS. Then run
  `mvn -B clean verify -DskipITs`; expect BUILD SUCCESS. Repeat the generated-sources diff from
  Task 2 Step 5; expect it empty.
- [ ] **Step 5:** Commit `feat(runtime): report threads that outlive container close (#N)`.

### Task 4: Kafka consumer threads belong to the container

**Files:**
- Modify: `tiko-kafka/src/main/java/io/tiko/kafka/runtime/ThreadPerTopicRunner.java:129`;
  `KafkaBootstrapSupport.java` (pass `container.threadFactory()` to the runners)
- Test:
  - `tiko-kafka/src/test/java/io/tiko/kafka/runtime/ConsumerThreadOwnershipTest.java` (new)
  - `tiko-kafka-it/src/test/java/io/tiko/kafka/runtime/integration/KafkaRealBrokerRoundTripIT.java`

**Interfaces:**
- Consumes: `Container.threadFactory()`, `onThreadsOutlivingClose` (Task 3).
- Produces: the consumer thread is created by `container.threadFactory()` and then named
  `tiko-kafka-consumer-<topic>`. The stop / join / close order in `ThreadPerTopicRunner.stop()`
  doesn't change. The generated bootstrap doesn't change either.

- [ ] **Step 1: Write the failing tests.**
  - `ConsumerThreadOwnershipTest` runs over `FakeKafkaTransport`:
    - `consumerStuckInABridgePastShutdownTimeoutIsReported`: a `@KafkaSource` bridge blocks on
      a latch longer than the Kafka shutdown timeout, set short in the test's config. After
      `close()`, the handler holds `tiko-kafka-consumer-orders`.
    - `cleanCloseLeavesNoConsumerThread`: the handler isn't called, and no live thread is named
      `tiko-kafka-consumer-orders`.
  - In the real-broker IT, add the same clean-close assertion after the round trip.
- [ ] **Step 2:** Run `mvn -B -pl tiko-kafka verify -Dtest=ConsumerThreadOwnershipTest -DskipITs`.
  Expected: FAIL. The stuck consumer isn't reported, because its thread isn't tracked.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Rerun the command from Step 2; expect PASS. Then run
  `mvn -B -pl tiko-kafka-it -am verify -DskipITs=false -Dit.test=KafkaRealBrokerRoundTripIT`
  (Docker required); expect PASS with `Skipped: 0`. Then run `mvn -B clean verify -DskipITs`.
- [ ] **Step 5:** Commit `feat(kafka): create consumer threads from the container (#N)`.

### Task 5: `replaceTransport` on a KSP-compiled Kafka service

**Files:**
- Move: `tiko-processor/src/test/java/io/tiko/processor/ksp/KspProgram.java` to the
  `tiko-processor-core` test-jar, package `io.tiko.processor.core.ksp`, next to `KspCompiler`.
  Update the imports of its users.
- Test: `tiko-kafka-processor/src/test/java/io/tiko/kafka/processor/ksp/KspFakeKafkaBootTest.java` (new)

**Interfaces:**
- Consumes: `KspProgram.bootTestWiring` (Task 2), `onThreadsOutlivingClose` (Task 3), consumer
  ownership (Task 4).
- Produces: `static KspProgram bootWithFakeKafka(KspResult result)`. It boots with
  `testWiring(true)` and `replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker))`,
  built reflectively inside the program's loader. It exposes `void send(String topic, String key, String json)`
  on the fake broker and `List<List<Thread>> leakReports()` from a capturing
  `onThreadsOutlivingClose`.

- [ ] **Step 1: Write the failing test.** `kotlinBridgeConsumesFromTheFakeBroker` compiles a
  Kotlin `@KafkaSource(topic = "orders")` bridge, publishing `OrderPlaced`, plus a recording
  `@EventHandler`, with both KSP processors. It boots with `bootWithFakeKafka`, sends one
  record, and waits with Awaitility until the handler's static list holds `"A1"`. After
  `close()`, `leakReports()` is empty.
- [ ] **Step 2:** Run `mvn -B -pl tiko-processor-core,tiko-processor,tiko-kafka-processor verify -Dtest=KspFakeKafkaBootTest -DskipITs`.
  Expected: FAIL (no `bootWithFakeKafka`).
- [ ] **Step 3:** Move `KspProgram` and add the method. The existing `tiko-processor` KSP tests
  must pass unchanged apart from imports.
- [ ] **Step 4:** Rerun the command from Step 2; expect PASS. Then run
  `mvn -B clean verify -DskipITs`; expect BUILD SUCCESS.
- [ ] **Step 5:** Commit `test(kafka-processor): boot a KSP-compiled bridge over the fake broker (#N)`.

### Task 6: `@TikoTest` closes on every path and fails the test that leaked

**Files:**
- Modify: `tiko-test/src/main/java/io/tiko/test/TikoTestExtension.java`,
  `CountingThreadPoolExecutor.java`
- Modify: `tiko-test/pom.xml`, `tiko-bom/pom.xml`, root `pom.xml`: add
  `org.junit.platform:junit-platform-testkit` `1.10.1` (matches JUnit 5.10.1), test scope, per
  `.claude/rules/dependencies.md`
- Docs: `docs/testing.md` (new subsection "Leaked threads fail the test")
- Test: `tiko-test/src/test/java/io/tiko/test/TikoTestTeardownTest.java` (new, `EngineTestKit`
  over nested fixture classes)

**Interfaces:**
- Consumes: `onThreadsOutlivingClose` (Task 3).
- Produces:
  - **Boot.** The executor is created, then `Tiko.create(opts)` runs. If it throws, the
    extension calls `executor.shutdownNow()` and rethrows.
  - **Backstop.** The container and executor are stored as one
    `ExtensionContext.Store.CloseableResource`, so JUnit closes them even when `afterEach` /
    `afterAll` never runs.
  - **Close.** `container.close()` runs in `try`; the `finally` calls `executor.shutdownNow()`
    plus `awaitTermination(1s)`.
  - **Leak check.**
    - Survivors are the container's report from the handler, plus `CountingThreadPoolExecutor`
      threads still alive. `CountingThreadPoolExecutor` tracks its own threads through its
      existing factory and adds `List<Thread> liveThreads()`.
    - If any survive, the extension throws `AssertionError` with this message (one block per
      thread, top 3 frames):
      `"<test class simple name>.<method> (or the class, for PER_CLASS): {n} thread(s) still running after its container closed:\n  {name}\n    at {frame}\n …"`.
    - If `close()` itself threw, the leak error is added as suppressed to that exception.

- [ ] **Step 1: Write the failing tests** in `TikoTestTeardownTest`. Use `EngineTestKit`, with
  fixtures as `static` nested classes excluded from normal discovery the way the project's
  other fixture classes are:
  - `containerIsClosedWhenTheTestFails`: a `@PreDestroy` flag is set after a failing test.
  - `executorIsReleasedWhenBootFails` (Review Focus 5): a component whose constructor throws.
    No `tiko-event-async-test-*` thread is alive afterwards.
  - `executorIsReleasedWhenCloseThrows`.
  - `leakedThreadFailsTheTestThatLeakedIt`: an async handler stuck on a latch. The fixture
    test's failure message starts with `"StuckHandlerFixture.publishesAndReturns: 1 thread(s) still running"`
    and contains `tiko-event-async-test-`.
  - `perClassLeakFailsTheClass`.
  - `leakIsAttributedToItsOwnTestUnderParallelExecution` (Review Focus 4): two fixture classes
    run with `junit.jupiter.execution.parallel.enabled=true`, and only one of them leaks. Only
    that class fails.
- [ ] **Step 2:** Run `mvn -B -pl tiko-test -am verify -Dtest=TikoTestTeardownTest -DskipITs`.
  Expected: FAIL.
- [ ] **Step 3:** Implement as in Interfaces and write the docs subsection.
- [ ] **Step 4:** Rerun the command from Step 2; expect PASS. Then run
  `mvn -B clean verify -DskipITs`. The existing `@TikoTest` suites in `tiko-test` and
  `tiko-examples/12_testing` must stay green; a leak they now reveal is a real finding to fix,
  not to suppress.
- [ ] **Step 5:** Commit `feat(test): fail the test whose container leaves threads running (#N)`.

### Task 7: The Kotlin example tests with `@TikoTest` on Gradle + KSP

**Files:**
- Modify: `tiko-examples-gradle/kotlin-quickstart/build.gradle.kts`, adding
  `kspTest("io.github.tomas-samek:tiko-processor:$tikoVersion")` and
  `testImplementation("io.github.tomas-samek:tiko-test:$tikoVersion")`
- Modify: `.../src/main/kotlin/io/tiko/examples/kotlin/Label.kt`, making it
  `open class Label { open fun text() = "audit" }`
- Create: `.../src/test/kotlin/io/tiko/examples/kotlin/TestLabel.kt`, with
  `@TestComponent class TestLabel : Label() { override fun text() = "test-audit" }`
- Create: `.../src/test/kotlin/io/tiko/examples/kotlin/QuickstartTikoTest.kt`
- Docs: `.ai-skills/tiko-build/reference/kotlin.md` (remove "don't work on KSP yet" at line 41
  and add the Gradle test wiring); `docs/testing.md` (a Kotlin / Gradle + KSP paragraph)

**Interfaces:**
- Consumes: Tasks 2 and 6.

- [ ] **Step 1: Write the failing test** in `QuickstartTikoTest`, annotated `@TikoTest`.
  `eventChainRunsInTheTestContainer(container: Container, bus: RecordingEventBus)` publishes
  `OrderPlaced("A1")` in an event scope; then
  `container.get(EventLog::class.java).chain() == listOf("OrderPlaced", "OrderValidated")`.
  `labelIsTheTestOverride(label: Label)` asserts `label.text() == "test-audit"`.
- [ ] **Step 2:** From the repo root, run `mvn -B -q install -DskipTests`. This is the one place
  `install` is allowed: run it alone, with no parallel agents, because Gradle reads mavenLocal.
  Then, with the `kspTest(...)` line still left out, run `./gradlew clean build` in the
  example. Expected: FAIL. Without a test compile the processor doesn't run, so the main
  container boots and `labelIsTheTestOverride` sees `"audit"`. Then add the line.
- [ ] **Step 3:** Run `./gradlew clean build run` and `ci/incremental-check.sh`; expect both
  green. CI already runs `./gradlew build run` on this example (`.github/workflows/maven.yml:127`).
- [ ] **Step 4:** Commit `feat(examples): test the Kotlin quickstart with @TikoTest on KSP (#N)`.

## Ordering

Order: Task 1 → 2 → 3 → 4 → 5 → 6 → 7.
- Tasks 1–2 (detection) and 3–4 (threads) are independent, but every PR targets `main` and
  waits for the previous merge.
- Task 5 needs 2, 3 and 4; Task 6 needs 3; Task 7 needs 2 and 6.

## Out of scope

- A JUnit Platform Suite / Cucumber integration of `@TikoTest`. The WARNING from Task 3 covers
  those harnesses for now.
- Threads a user's own component starts with its own executor. `@PreDestroy` owns them, so they
  aren't tracked.
- A `@TikoTest` hook for `replaceTransport`.

Each of these can become its own issue later.
