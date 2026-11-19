# K2 Piece 7: `suspend` `@EventHandler` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a Kotlin `@EventHandler` may be a `suspend fun`. Each delivery runs as a coroutine in
the container's scope, in a unit of work of its own, on the container's virtual threads. A
failure reaches the `ErrorHandler`, `@EventTrigger` fires after the handler completes, and
`close()` waits for the handler or cancels it.

**Architecture:** the processor already knows `MethodDecl.isSuspend()`; today
`KotlinConstructRules.suspendUntilK2` rejects such a handler. It is accepted instead and treated
as async. Generated code stays Java: for a `suspend` handler the event registry calls one static
function of `tiko-coroutines`, `SuspendHandlers.launch(...)`, and passes the handler call as a
Java lambda that takes the `Continuation` (a Kotlin `suspend () -> T` is that interface on the
JVM, which the spike proved). `launch` puts the coroutine into the container's scope of piece 6,
so the unit, the pending-delivery count and the shutdown behaviour come from there.

**Tech Stack:** existing: Kotlin 2.4.21, KSP 2.3.13, kctfork (KSP in tests),
kotlinx-coroutines 1.11.0, JavaPoet.

**Spec:** issues #677 (as edited on 2026-11-18) and #678 (the item on a `suspend` handler
running when `close()` starts). Roadmap:
`docs/superpowers/plans/2026-11-18-kotlin-k2-coroutines-roadmap.md` (piece 7).

## What changed while implementing

- **KSP tells a written value from a default** (`KSValueArgument.origin` is `SYNTHETIC` for a
  default), also for an annotation read from a class file, so the alternative of Task 1, Step 0
  was not needed.
- **The three new errors are one rule**, `KotlinConstructRules.suspendHandler`, checked in the
  order `async = false`, `timeout` / `retries`, the dependency. They are reported as
  `KOTLIN_UNSUPPORTED`, like the other Kotlin construct rules.
- **A handler of a lifecycle event keeps its origin without a unit** through a small element of
  its own (`OriginElement`), not through an empty carried unit: `inEventScope` inside such a
  handler must not be refused as "a unit is already open".
- **The end-to-end tests are a Java test class** (`KspSuspendHandlerTest`) that compiles one
  Kotlin program with KSP and runs its scenarios through a new helper in the shared KSP test
  support, `KspProgram.run`. The program boots its own container, so it can set an
  `ErrorHandler` and a shutdown timeout.
- **The launcher is two functions, not one with an `inUnit` flag**: `SuspendHandlers.launch` and
  `SuspendHandlers.launchWithoutUnit`, each with seven parameters. Generated code picks the one
  for a lifecycle-event handler by name.
- **The example's `Notifier.send` is a `suspend fun`**, which is what gives the handler a reason
  to suspend.

## Global Constraints

- Generated code is Java and has no reflection. It refers to `tiko-coroutines` and to
  `kotlin.coroutines.Continuation` only in a module that has a `suspend` handler.
- A module with a `suspend` handler and without `tiko-coroutines` on its classpath gets a
  located compile error that names the dependency, not a javac error in generated code.
- A `suspend` handler is always async. An explicit `async = false` on one is a located compile
  error (#677).
- `timeout`, `retries` and `backoff` on a `suspend` handler are a located compile error in this
  piece; they arrive with piece 9 (#680). No backpressure limit yet: piece 8 (#679).
- Each delivery owns its unit of work (ARCH-15); a handler of a framework lifecycle event
  (`io.tiko.events.*`) runs without one, as an async handler of such an event does.
- The unit closes once on success, failure and cancellation (SEC-14). The handler's coroutine is
  a pending delivery until then.
- A failure is routed as `EventHandlerError` with the handler's `EventHandlerInfo`; an `Error`
  is logged, not routed, as for async handlers. `@EventTrigger` fires only after success.
- Javac output is unchanged: a Java method is never `suspend`.
- Coroutine tests carry a time limit; one Maven build at a time; Sonar pre-sweep (S1181, S1452,
  S6213, S3077, S5164, S5853, S3776, S1192, S107) before the first push.

## Review Focus

1. **A handler that throws after a suspension.** The `ErrorHandler` gets `EventHandlerError`
   with the cause as thrown, once; the unit is closed; the delivery is released. Task 3.
2. **`close()` while a handler is suspended.** Waited for within `shutdownTimeout`, cancelled
   after it, unit closed before singletons are destroyed. Task 3.
3. **A publish after the container began to close.** The delivery is dropped with the existing
   `WARNING`, no coroutine starts, nothing stays counted. Task 3.
4. **A handler with the `Event<?>` wrapper and a trigger.** The wrapper is the handler's event;
   events the handler publishes, and the triggered one, descend from it. Task 3.
5. **A module that has no `suspend` handler.** Its generated registry has no reference to
   `io.tiko.coroutines` or `kotlin.*`. Task 2.

---

### Task 1: the model tells an explicit annotation value from a default

**Files:**
- Modify: `tiko-processor-core/src/main/java/io/tiko/processor/core/model/AnnotationRef.java`,
  `javac/JavacAnnotationRef.java`, `ksp/KspAnnotationRef.java`
- Test: the existing annotation tests of `tiko-processor-core` for both frontends (one case each)

**Interfaces:**
- Produces: `boolean AnnotationRef.isExplicit(String member)`: whether the source wrote a value
  for the member, as opposed to the annotation type's default.

- [ ] **Step 0: Verify on KSP 2.3.13** that a default argument can be told from a written one
  (`KSValueArgument.origin`, or the argument's absence from the source node). If it cannot, stop
  and bring the owner the alternative: `async = false` on a `suspend` handler is read as the
  default and the handler is async without an error.
- [ ] **Step 1: Failing tests** (`@EventHandler` vs `@EventHandler(async = false)`, javac and KSP).
- [ ] **Step 2: Implement. Step 3:** `mvn -o -pl tiko-processor-core install`.
- [ ] **Step 4: Commit** `feat(processor-core): AnnotationRef tells a value the source wrote from a default (#677)`

---

### Task 2: the processor accepts a `suspend` handler and generates its dispatch

**Files:**
- Modify: `tiko-processor-core/.../KotlinConstructRules.java` (`suspendUntilK2` keeps serving
  `@KafkaSource`; new rules below)
- Modify: `tiko-processor/.../validation/KotlinConstructValidator.java`,
  `collect/EventHandlerCollector.java`, `model/EventHandlerModel.java`,
  `generator/EventRegistryGenerator.java`
- Test: `tiko-processor/src/test/java/io/tiko/processor/ksp/KspSuspendHandlerRulesTest.java`
  (compile errors only: the generated Java of a positive case needs `tiko-coroutines`, which
  cannot be a test dependency of `tiko-processor` without a module cycle)

**Interfaces:**
- `EventHandlerModel.isSuspend()`; `isAsync()` is true for a `suspend` handler.
- New rules, each a located error with a fix:
  - `suspendHandlerNotAsync`: `@EventHandler(async = false)` on a `suspend fun`.
  - `suspendHandlerResilience`: `timeout` or `retries` on a `suspend fun`.
  - `suspendHandlerNeedsCoroutines`: the type `io.tiko.coroutines.SuspendHandlers` is not on the
    classpath; the fix names `io.github.tomas-samek:tiko-coroutines`.
- Generated dispatch of a `suspend` handler (inside the existing wrapper bracket):
  ```java
  io.tiko.coroutines.SuspendHandlers.launch(
      container,
      __wrapper,                        // the origin of the handler's unit
      true,                             // false for a handler of a framework lifecycle event
      __continuation -> container.getOrderListener().onPlaced(event, __continuation),
      __result -> { /* @EventTrigger publishes, as in an async body */ },   // null without a trigger
      container.getErrorHandler(), HANDLER_INFO_3, event);
  ```
  With the `Event<?>` wrapper parameter the call is `onPlaced(event, __wrapper, __continuation)`.

- [ ] **Step 1: Failing tests**: `asyncFalseOnASuspendHandlerIsRejected`,
  `aSuspendHandlerWithoutAnAsyncValueIsAccepted...` (reaches the dependency check),
  `timeoutOrRetriesOnASuspendHandlerIsRejected` (parameterized),
  `aSuspendHandlerWithoutTikoCoroutinesNamesTheDependency`,
  `aSuspendKafkaSourceIsStillRejected`. Messages follow the compile-error format (location,
  what is wrong, a fix). `KotlinConstructRulesTest` fragments for the old message are updated.
- [ ] **Step 2: Run, see them fail. Step 3: implement.**
- [ ] **Step 4:** `mvn -o -pl tiko-processor-core,tiko-processor install`; golden files unchanged
  (their fixtures have no `suspend` handler).
- [ ] **Step 5: Commit** `feat(processor): accept suspend event handlers and dispatch them through tiko-coroutines (#677)`

---

### Task 3: `SuspendHandlers.launch` and the end-to-end tests

**Files:**
- Create: `tiko-coroutines/src/main/kotlin/io/tiko/coroutines/SuspendHandlers.kt`
- Modify: `ContainerCoroutines.kt` (launch with a given origin; launch without a unit),
  `tiko-runtime/.../EventChainContext.java` (a public way to route a handler failure, the one
  async dispatch uses)
- Modify: `tiko-coroutines/pom.xml` (test: kctfork, the shared KSP test-support source root)
- Test: `tiko-coroutines/src/test/java/io/tiko/coroutines/KspSuspendHandlerTest.java` (Kotlin
  sources compiled with KSP in the test and booted, like the processor's `Ksp*Test` classes)

**Interfaces:**
- Produces, for generated code:
  ```kotlin
  public object SuspendHandlers {
      @JvmStatic
      public fun <T> launch(
          container: Container,
          origin: Event<*>,
          inUnit: Boolean,
          handler: suspend () -> T,
          onSuccess: Consumer<T>?,
          errorHandler: ErrorHandler,
          info: EventHandlerInfo,
          event: Any,
      )
  }
  ```
- Behaviour: the container is closing → the delivery is dropped through
  `EventChainContext.logDroppedDuringShutdown`. Otherwise the coroutine runs in the container's
  scope; `onSuccess` runs in the handler's unit after the handler returned; a failure of either
  is routed once as `EventHandlerError`.

- [ ] **Step 1: Failing tests** (`KspSuspendHandlerTest`):
  - `aSuspendHandlerRunsInItsOwnUnitOnAContainerThread` (same EVENT instance before and after
    `delay`, thread named `tiko-coroutine-`, the publisher's unit is another one)
  - `publishReturnsBeforeTheHandlerHasFinished`
  - `aFailureAfterASuspensionReachesTheErrorHandlerOnce` (`EventHandlerError`, cause as thrown,
    handler info of the method)
  - `aTriggerFiresAfterTheHandlerCompletesAndNotWhenItFails`
  - `theWrapperIsTheHandlersEventAndPublishedEventsDescendFromIt`
  - `aHandlerOfALifecycleEventRunsWithoutAUnit`
  - `closeWaitsForAHandlerThatFinishesInTime` and
    `closeCancelsAHandlerThatIsStillSuspendedAndItsUnitCloses` (#678's last item)
  - `aPublishAfterCloseBeganIsDroppedAndNothingStaysPending`
  - `pendingAsyncDeliveriesCountsASuspendedHandler`
- [ ] **Step 2: Run, see them fail. Step 3: implement. Step 4: run the module three times.**
- [ ] **Step 5: Security review** against SEC-14 with the `tiko-security` skill.
- [ ] **Step 6: Commit** `feat(coroutines): launch suspend event handlers in the container's scope (#677, #678)`

---

### Task 4: the Gradle example, documentation, full build

**Files:**
- Modify: `tiko-examples-gradle/kotlin-ktor-exposed` (`TicketNotifications.onOpened` becomes a
  `suspend fun`; `TicketApiTest` waits with `bus.awaitAsyncDispatch(...)`), its README section
- Modify: `docs/events.md` (a section on `suspend` handlers: always async, own unit, where they
  run, failures, triggers, what is not there yet with the issue numbers; "Graceful shutdown
  drain" gets the step that cancels the container's coroutines)
- Modify: `.ai-skills/tiko-build/reference/kotlin.md` and `api-signatures.md` with their
  archetype copies; `docs/cookbooks/ktor.md` (the sentence on handlers); `docs/di-and-scopes.md`
  if it names where a unit's work runs
- Modify: `docs/architecture-invariants.md`, `docs/security-model.md` anchors and tests; the
  roadmap's status table

- [ ] **Step 1: Example first** (a real Gradle + KSP compile of a `suspend` handler):
  `./gradlew clean build run --args=--smoke`.
- [ ] **Step 2: Write the documentation.**
- [ ] **Step 3: Full build** `mvn -o clean install`, rc=0.
- [ ] **Step 4: Commit** `docs: suspend event handlers, and the Ktor example announces tickets from one (#677, #678)`

---

## After the tasks

- Push, PR (`Closes #677`, `Closes #678`), CI on the JDK matrix and the Gradle job, Sonar open
  issues checked.
- Next: piece 8 (#679). It needs two decisions from the owner: the limit on `suspend` handlers in
  flight (the existing `queueCapacity`, or a number of its own), and what `CALLER_RUNS` means for
  a `suspend` handler.
