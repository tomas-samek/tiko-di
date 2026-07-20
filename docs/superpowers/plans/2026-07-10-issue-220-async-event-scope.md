# Async @EventHandler Fresh EVENT Unit (#220) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generated async `@EventHandler` dispatch runs each invocation (each retry attempt) inside its own EVENT unit — frame, gated lifecycle events, ordered teardown — closing the local/distributed asymmetry.

**Architecture:** Two generator changes, zero public-API change: the generated container gains a package-private `runInDetachedEventScope(Runnable)` (save/clear frame ThreadLocals → delegate to the existing `runInEventScope` → restore), and `EventRegistryGenerator` wraps the async dispatch body in it (moving `__handler` resolution inside), except for handlers of `io.tiko.events.*` types which keep the unwrapped shape (compile-time recursion guard).

**Tech Stack:** JavaPoet generators in `tiko-processor`; Google compile-testing for generator tests; behavior tests in `tiko-examples/07_async_start` (new test tree) with AssertJ + Awaitility.

**Spec:** `docs/superpowers/specs/2026-07-10-issue-220-async-event-scope-design.md`

## Global Constraints

- Branch: `feat/issue-220-async-event-scope` (checked out; spec committed at `f728fcb`).
- `tiko-api` and the `Container` interface are untouched. The new method is package-private in `io.tiko.generated`.
- `EventChainContext` (`runAsyncWithTimeout` / `runAsyncWithRetry` / `runOnce` / DLQ choke points) is untouched — the wrapper travels inside the `body` lambda.
- Scope bracket outermost, chain context inside (matches the Kafka path).
- House test rules: JUnit 5 + AssertJ only; camelCase test names; no `@Disabled`; **no bare `Thread.sleep`** — Awaitility `await().atMost(...).until(...)`; no cross-test static-state bleed (reset in `@BeforeEach`).
- Maven at `W:\tools\apache-maven\bin\mvn.cmd` (not on PATH). Log discipline: `*> W:\workspace\220-<step>.log; $LASTEXITCODE`, inspect via PowerShell Select-String (UTF-16 logs). Spotless remedy: `& W:\tools\apache-maven\bin\mvn.cmd -pl '!tiko-bom' spotless:apply`, re-run.
- Bundled skill copies are regenerated only via `ArchetypeDocSync` (from `tiko-archetype/`: `mvn -q test-compile` then `java -cp target/test-classes io.tiko.archetype.ArchetypeDocSync`) — never hand-edited.
- Commits: single-line conventional, no body, no Co-Authored-By.
- Read/Edit/Write tools for all file changes.

---

### Task 1: `runInDetachedEventScope` in the generated container

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java` (method registration at ~line 198–201; new `create...` method next to `createRunInEventScopeMethod` at ~line 989)
- Test: `tiko-processor/src/test/java/io/tiko/processor/DetachedEventScopeTest.java` (new)

**Interfaces:**
- Produces: generated-container method `void runInDetachedEventScope(Runnable task)` (package-private) — Task 2's generated registry calls it; Task 3's behavior rides on it.
- Consumes: existing generated members `eventScoped` (`ThreadLocal<Map<String,Object>>`), `__unitFrameOpen` (`ThreadLocal<Boolean>`), `runInEventScope(Runnable)`.

- [ ] **Step 1: Write the failing compile-testing test**

```java
package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class DetachedEventScopeTest {

    @Test
    void generatedContainerCarriesDetachedEventScopeMethod() throws IOException {
        JavaFileObject component = JavaFileObjects.forSourceLines(
                "io.example.Simple",
                "package io.example;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.Scope;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Simple {}");

        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(component);
        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("container not generated"));
        String content = new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(content).contains("void runInDetachedEventScope(Runnable task)");
        // Detachment: save both ThreadLocals, clear, delegate, restore in finally.
        assertThat(content).contains("eventScoped.get()");
        assertThat(content).contains("eventScoped.set(new LinkedHashMap<>())");
        assertThat(content).contains("runInEventScope(task)");
        assertThat(content).contains("__unitFrameOpen.set(__savedFrameOpen)");
        // Package-private: the signature line must not carry public/protected.
        assertThat(content).doesNotContain("public void runInDetachedEventScope");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `& W:\tools\apache-maven\bin\mvn.cmd test -pl tiko-processor -am "-Dtest=DetachedEventScopeTest" *> W:\workspace\220-t1-red.log; $LASTEXITCODE`
Expected: non-zero; the assertion on `runInDetachedEventScope` fails (method not generated).

- [ ] **Step 3: Implement the generator method**

In `ContainerGenerator.java`, next to `createRunInEventScopeMethod()` (~line 989), add:

```java
    /**
     * Creates the package-private {@code runInDetachedEventScope} method (#220). Async
     * {@code @EventHandler} dispatch runs inside its own EVENT unit; when the overflow policy
     * runs the task inline on a borrowed publisher thread ({@code CALLER_RUNS}), the caller's
     * open frame is suspended (saved and cleared) for the duration and restored afterwards —
     * detachment, not nesting. The public {@code runInEventScope} still throws on re-entry,
     * so the single-frame invariant (ARCH-5) is untouched.
     */
    private MethodSpec createRunInDetachedEventScopeMethod() {
        ParameterizedTypeName scopeMapType = ParameterizedTypeName.get(
                ClassName.get(Map.class), ClassName.get(String.class), ClassName.get(Object.class));
        MethodSpec.Builder method = MethodSpec.methodBuilder("runInDetachedEventScope")
                .addParameter(Runnable.class, "task");
        method.addStatement("$T __savedScope = eventScoped.get()", scopeMapType)
                .addStatement("$T __savedFrameOpen = __unitFrameOpen.get()", Boolean.class)
                .addStatement("eventScoped.set(new $T<>())", LinkedHashMap.class)
                .addStatement("__unitFrameOpen.set($T.FALSE)", Boolean.class);
        method.beginControlFlow("try")
                .addStatement("runInEventScope(task)")
                .nextControlFlow("finally")
                .addStatement("eventScoped.set(__savedScope)")
                .addStatement("__unitFrameOpen.set(__savedFrameOpen)")
                .endControlFlow();
        return method.build();
    }
```

Register it in the container-method list (~line 198–201), directly after `createRunInEventScopeMethod()`:

```java
        containerBuilder.addMethod(createRunInEventScopeMethod());
        containerBuilder.addMethod(createRunInDetachedEventScopeMethod());
```

- [ ] **Step 4: Run the test to verify it passes, plus the module suite**

Run: `& W:\tools\apache-maven\bin\mvn.cmd test -pl tiko-processor -am *> W:\workspace\220-t1-green.log; $LASTEXITCODE`
Expected: `0`, BUILD SUCCESS (new test passes, no existing generator test regresses).

- [ ] **Step 5: Commit**

```powershell
git add tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java tiko-processor/src/test/java/io/tiko/processor/DetachedEventScopeTest.java
git commit -m "feat(processor): generate package-private runInDetachedEventScope for async units (#220)"
```

---

### Task 2: Wrap async dispatch in the detached unit; lifecycle-type exclusion

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/EventRegistryGenerator.java` (dispatcher prelude ~line 180; async branch ~lines 199–270)
- Test: `tiko-processor/src/test/java/io/tiko/processor/EventRegistryAsyncScopeTest.java` (new)

**Interfaces:**
- Consumes: Task 1's `runInDetachedEventScope` (package-private, same generated package).
- Produces: async dispatch shape `container.runInDetachedEventScope(() -> { __handler resolution; chain enter/try/invoke+triggers/finally exit })`; unwrapped shape preserved for `io.tiko.events.*` handlers and for sync dispatch.

- [ ] **Step 1: Write the failing compile-testing tests**

```java
package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class EventRegistryAsyncScopeTest {

    @Test
    void asyncDispatchWrapsBodyInDetachedEventScopeWithHandlerResolutionInside() throws IOException {
        String registry = compileAndReadRegistry(
                JavaFileObjects.forSourceLines(
                        "io.example.AsyncHandler",
                        "package io.example;",
                        "import io.tiko.annotations.Component;",
                        "import io.tiko.annotations.EventHandler;",
                        "import io.tiko.Scope;",
                        "@Component(scope = Scope.SINGLETON)",
                        "public class AsyncHandler {",
                        "    @EventHandler(async = true)",
                        "    public void onPing(Ping event) {}",
                        "}"),
                JavaFileObjects.forSourceLines("io.example.Ping", "package io.example;", "public record Ping() {}"));

        assertThat(registry).contains("container.runInDetachedEventScope(");
        // Handler resolution binds to the async unit, not the publisher thread: it must appear
        // AFTER the wrapper opens.
        int wrapperAt = registry.indexOf("container.runInDetachedEventScope(");
        int resolutionAt = registry.indexOf("AsyncHandler __handler = container.");
        assertThat(resolutionAt).isGreaterThan(wrapperAt);
    }

    @Test
    void asyncLifecycleEventHandlerKeepsUnwrappedShape() throws IOException {
        String registry = compileAndReadRegistry(JavaFileObjects.forSourceLines(
                "io.example.UnitObserver",
                "package io.example;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.EventHandler;",
                "import io.tiko.Scope;",
                "import io.tiko.events.EventStartedEvent;",
                "@Component(scope = Scope.SINGLETON)",
                "public class UnitObserver {",
                "    @EventHandler(async = true)",
                "    public void onUnitStarted(EventStartedEvent event) {}",
                "}"));

        assertThat(registry).doesNotContain("runInDetachedEventScope");
    }

    @Test
    void syncDispatchShapeUnchanged() throws IOException {
        String registry = compileAndReadRegistry(
                JavaFileObjects.forSourceLines(
                        "io.example.SyncHandler",
                        "package io.example;",
                        "import io.tiko.annotations.Component;",
                        "import io.tiko.annotations.EventHandler;",
                        "import io.tiko.Scope;",
                        "@Component(scope = Scope.SINGLETON)",
                        "public class SyncHandler {",
                        "    @EventHandler",
                        "    public void onPing(Ping event) {}",
                        "}"),
                JavaFileObjects.forSourceLines("io.example.Ping", "package io.example;", "public record Ping() {}"));

        assertThat(registry).doesNotContain("runInDetachedEventScope");
    }

    private String compileAndReadRegistry(JavaFileObject... sources) throws IOException {
        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(sources);
        CompilationSubject.assertThat(c).succeeded();
        JavaFileObject registry = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("EventRegistry"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("EventRegistry not generated"));
        return new String(registry.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 2: Run to verify the first test fails**

Run: `& W:\tools\apache-maven\bin\mvn.cmd test -pl tiko-processor -am "-Dtest=EventRegistryAsyncScopeTest" *> W:\workspace\220-t2-red.log; $LASTEXITCODE`
Expected: non-zero — test 1 fails (`runInDetachedEventScope` absent); tests 2 and 3 already pass (they assert absence).

- [ ] **Step 3: Implement the generator change**

In `EventRegistryGenerator.java`:

**(a)** Compute the exclusion up front, where `handler` is in scope in the dispatcher-emitting method:

```java
        boolean lifecycleEvent = handler.getEventTypeName().startsWith("io.tiko.events.");
        boolean detachedUnit = handler.isAsync() && !lifecycleEvent;
```

**(b)** The prelude statement `method.addStatement("$T __handler = container.$L()", declaringClass, getterName)` (~line 180, currently emitted for every handler inside the outer try): emit it only when `!detachedUnit`:

```java
        if (!detachedUnit) {
            method.addStatement("$T __handler = container.$L()", declaringClass, getterName);
        }
```

**(c)** In the async branch, build `runBody` so that when `detachedUnit` is true, the handler resolution opens the body (before the chain-context enter), and the whole body is wrapped. Replace the current `runBody` construction with:

```java
            CodeBlock.Builder runBody = CodeBlock.builder();
            if (detachedUnit) {
                runBody.addStatement("$T __handler = container.$L()", declaringClass, getterName);
            }
            runBody.addStatement("$T<?> __asyncPrev = $T.enter(__asyncWrapper)", Event.class, CHAIN_CONTEXT);
            runBody.beginControlFlow("try");
            if (captureResult) {
                runBody.addStatement("$T __result = $L", TypeName.get(returnType), invocation);
                for (EventTriggerModel trigger : handler.getEventTriggers()) {
                    emitTriggerInto(runBody, trigger, index);
                }
            } else {
                runBody.addStatement(invocation);
            }
            runBody.nextControlFlow("finally");
            runBody.addStatement("$T.exit(__asyncPrev)", CHAIN_CONTEXT);
            runBody.endControlFlow();

            CodeBlock asyncBody = detachedUnit
                    ? CodeBlock.builder()
                            .add("container.runInDetachedEventScope(() -> {\n$L});\n", runBody.build())
                            .build()
                    : runBody.build();
```

**(d)** In the three dispatch emissions below (retry / timeout / plain), replace the `runBody.build()` argument with `asyncBody`:

```java
                                "$T.runAsyncWithRetry(() -> {\n$L}, new $T($L, $LL, $T.$L, $LL), __exec, __err, HANDLER_INFO_$L, event);\n",
                                CHAIN_CONTEXT,
                                asyncBody,
```
(and identically for the two `runAsyncWithTimeout` emissions).

- [ ] **Step 4: Run the new tests plus the full processor suite**

Run: `& W:\tools\apache-maven\bin\mvn.cmd test -pl tiko-processor -am *> W:\workspace\220-t2-green.log; $LASTEXITCODE`
Expected: `0`, BUILD SUCCESS. If existing async generator tests (`EventRegistryAsyncDispatchTest`, `EventHandlerRetryTest`, `EventHandlerTimeoutTest`, `EventRegistryAsyncTriggerTest`, `EventRegistryAsyncErrorObservableTest`) assert on the old body shape (e.g. `__handler` position), update those assertions to the new shape — the *behavioral* contracts they pin (helper routing, HANDLER_INFO, triggers) must keep passing unchanged.

- [ ] **Step 5: Commit**

```powershell
git add tiko-processor/src/main/java/io/tiko/processor/generator/EventRegistryGenerator.java tiko-processor/src/test/java/io/tiko/processor/EventRegistryAsyncScopeTest.java
git commit -m "feat(processor): async dispatch runs in a detached EVENT unit; lifecycle-event handlers excluded (#220)"
```

If Step 4 required updating existing test assertions, include those files in the same commit.

---

### Task 3: Behavior tests in `tiko-examples/07_async_start`

**Files:**
- Modify: `tiko-examples/07_async_start/pom.xml` (add test-scope deps — mirror the test-dependency block of `tiko-examples/12_testing/pom.xml`: `junit-jupiter`, `assertj-core`, `awaitility`; all BOM-managed, no versions)
- Create: `tiko-examples/07_async_start/src/test/java/com/example/asyncstart/scope/UnitProbe.java`
- Create: `tiko-examples/07_async_start/src/test/java/com/example/asyncstart/scope/UnitProbeImpl.java`
- Create: `tiko-examples/07_async_start/src/test/java/com/example/asyncstart/scope/ProbeLog.java`
- Create: `tiko-examples/07_async_start/src/test/java/com/example/asyncstart/scope/AsyncProbeHandlers.java`
- Create: `tiko-examples/07_async_start/src/test/java/com/example/asyncstart/scope/AsyncEventScopeIT... ` — **name it `AsyncEventScopeTest.java`** (surefire lane; no broker/Docker involved)

Note: components under `src/test/java` are compiled by the annotation processor into the test container for this module — the same pattern `12_testing` uses with `@TestComponent`-era fixtures. If `07_async_start`'s main sources define a container whose generated types collide, put the probe fixtures in the test tree only and verify `mvn test -pl tiko-examples/07_async_start` compiles a merged container; if the module's processor setup does NOT pick up test sources, relocate these fixtures into a new sibling example module `tiko-examples/16_async_scope` (pom cloned from `07_async_start`, registered in `tiko-examples/pom.xml`) — same file contents, and report the relocation.

**Interfaces:**
- Consumes: generated behavior from Tasks 1–2 (fresh unit per async dispatch/attempt, lifecycle publishes, teardown, detachment).

- [ ] **Step 1: Write the probe fixtures**

```java
// UnitProbe.java
package com.example.asyncstart.scope;

public interface UnitProbe {
    String id();
}
```

```java
// UnitProbeImpl.java
package com.example.asyncstart.scope;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PostConstruct;
import io.tiko.annotations.PreDestroy;
import java.util.UUID;

@Component(scope = Scope.EVENT)
public class UnitProbeImpl implements UnitProbe {
    private final String id = UUID.randomUUID().toString();

    @Override
    public String id() {
        return id;
    }

    @PostConstruct
    void created() {
        ProbeLog.created(id);
    }

    @PreDestroy
    void destroyed() {
        ProbeLog.destroyed(id);
    }
}
```

```java
// ProbeLog.java
package com.example.asyncstart.scope;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Static capture for probe lifecycle; reset per test to keep state from bleeding. */
public final class ProbeLog {
    private static final Queue<String> CREATED = new ConcurrentLinkedQueue<>();
    private static final Queue<String> DESTROYED = new ConcurrentLinkedQueue<>();

    private ProbeLog() {}

    static void created(String id) {
        CREATED.add(id);
    }

    static void destroyed(String id) {
        DESTROYED.add(id);
    }

    public static java.util.List<String> createdIds() {
        return java.util.List.copyOf(CREATED);
    }

    public static java.util.List<String> destroyedIds() {
        return java.util.List.copyOf(DESTROYED);
    }

    public static void reset() {
        CREATED.clear();
        DESTROYED.clear();
    }
}
```

```java
// AsyncProbeHandlers.java
package com.example.asyncstart.scope;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

@Component(scope = Scope.SINGLETON)
public class AsyncProbeHandlers {

    public record Touch() {}

    public record FlakyTouch() {}

    public record BlockedTouch() {}

    public static final Queue<String> TOUCHED_IDS = new ConcurrentLinkedQueue<>();
    public static final AtomicInteger FLAKY_ATTEMPTS = new AtomicInteger();
    public static volatile CountDownLatch blockGate = new CountDownLatch(0);

    private final UnitProbe probe;

    public AsyncProbeHandlers(UnitProbe probe) {
        this.probe = probe; // EVENT-in-SINGLETON: interface-backed proxy, resolves the current unit
    }

    @EventHandler(async = true)
    public void onTouch(Touch event) {
        TOUCHED_IDS.add(probe.id());
    }

    @EventHandler(async = true, retries = 2)
    public void onFlakyTouch(FlakyTouch event) {
        TOUCHED_IDS.add(probe.id());
        if (FLAKY_ATTEMPTS.incrementAndGet() < 3) {
            throw new IllegalStateException("flaky attempt " + FLAKY_ATTEMPTS.get());
        }
    }

    @EventHandler(async = true, timeout = "PT0.2S")
    public void onBlockedTouch(BlockedTouch event) throws InterruptedException {
        TOUCHED_IDS.add(probe.id());
        blockGate.await(); // interrupted on timeout breach -> InterruptedException -> unit teardown
    }

    public static void reset() {
        TOUCHED_IDS.clear();
        FLAKY_ATTEMPTS.set(0);
        blockGate = new CountDownLatch(0);
    }
}
```

- [ ] **Step 2: Write the behavior tests**

```java
// AsyncEventScopeTest.java
package com.example.asyncstart.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.events.EventEndingEvent;
import io.tiko.events.EventStartedEvent;
import io.tiko.runtime.Tiko;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AsyncEventScopeTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @BeforeEach
    void resetCaptures() {
        ProbeLog.reset();
        AsyncProbeHandlers.reset();
    }

    @Test
    void asyncHandlerGetsFreshUnitWithTeardown() {
        try (Container container = Tiko.create()) {
            container.getEventBus().publish(new AsyncProbeHandlers.Touch());
            await().atMost(WAIT).until(() -> ProbeLog.destroyedIds().size() == 1);

            assertThat(AsyncProbeHandlers.TOUCHED_IDS).hasSize(1);
            assertThat(ProbeLog.createdIds()).containsExactlyElementsOf(AsyncProbeHandlers.TOUCHED_IDS);
            assertThat(ProbeLog.destroyedIds()).containsExactlyElementsOf(ProbeLog.createdIds());
        }
    }

    @Test
    void eachRetryAttemptGetsItsOwnUnit() {
        try (Container container = Tiko.create()) {
            container.getEventBus().publish(new AsyncProbeHandlers.FlakyTouch());
            await().atMost(WAIT).until(() -> AsyncProbeHandlers.FLAKY_ATTEMPTS.get() == 3);
            await().atMost(WAIT).until(() -> ProbeLog.destroyedIds().size() == 3);

            assertThat(AsyncProbeHandlers.TOUCHED_IDS).hasSize(3).doesNotHaveDuplicates();
            assertThat(ProbeLog.destroyedIds()).containsExactlyInAnyOrderElementsOf(AsyncProbeHandlers.TOUCHED_IDS);
        }
    }

    @Test
    void asyncDispatchPublishesOneLifecyclePair() {
        try (Container container = Tiko.create()) {
            AtomicInteger started = new AtomicInteger();
            AtomicInteger ending = new AtomicInteger();
            container.getEventBus().subscribe(EventStartedEvent.class, e -> started.incrementAndGet());
            container.getEventBus().subscribe(EventEndingEvent.class, e -> ending.incrementAndGet());

            container.getEventBus().publish(new AsyncProbeHandlers.Touch());
            await().atMost(WAIT).until(() -> ending.get() >= 1);

            assertThat(started.get()).isEqualTo(1);
            assertThat(ending.get()).isEqualTo(1);
        }
    }

    @Test
    void timeoutBreachStillTearsDownTheUnit() {
        try (Container container = Tiko.create()) {
            AsyncProbeHandlers.blockGate = new java.util.concurrent.CountDownLatch(1);
            container.getEventBus().publish(new AsyncProbeHandlers.BlockedTouch());
            await().atMost(WAIT).until(() -> ProbeLog.destroyedIds().size() == 1);

            assertThat(ProbeLog.destroyedIds()).containsExactlyElementsOf(ProbeLog.createdIds());
        }
    }
}
```

- [ ] **Step 3: Write the detachment (CALLER_RUNS) and no-recursion tests**

Append to `AsyncEventScopeTest.java`:

```java
    @Test
    void callerRunsDetachmentPreservesTheOuterUnit() throws Exception {
        var options = io.tiko.runtime.TikoOptions.builder()
                .eventExecutorCoreSize(1)
                .eventExecutorMaxSize(1)
                .queueCapacity(1)
                .onOverflow(io.tiko.runtime.OverflowPolicy.CALLER_RUNS)
                .build();
        try (Container container = Tiko.create(options)) {
            var workerGate = new java.util.concurrent.CountDownLatch(1);
            AsyncProbeHandlers.blockGate = workerGate; // holds the single worker + fills the queue

            container.runInEventScope(() -> {
                UnitProbe outer = container.get(UnitProbe.class);
                String outerBefore = outer.id();

                // Saturate: one blocked in the worker, one queued...
                container.getEventBus().publish(new AsyncProbeHandlers.BlockedTouch());
                container.getEventBus().publish(new AsyncProbeHandlers.Touch());
                // ...and this one overflows -> CALLER_RUNS -> runs INLINE on this thread,
                // inside our open unit. Detachment must suspend and restore our frame.
                container.getEventBus().publish(new AsyncProbeHandlers.Touch());

                assertThat(outer.id()).isEqualTo(outerBefore); // outer unit intact
                workerGate.countDown();
            });
            await().atMost(WAIT).until(() -> ProbeLog.destroyedIds().size() >= 3);
        }
    }

    @Test
    void asyncLifecycleObserverDoesNotRecurse() {
        try (Container container = Tiko.create()) {
            container.getEventBus().publish(new AsyncProbeHandlers.Touch());
            // The unit's own EventStarted/Ending fire; LifecycleObserver (async, subscribed to
            // EventStartedEvent) must dispatch WITHOUT minting a new unit — so the total count
            // of started events stays exactly 1 no matter how long we watch.
            await().atMost(WAIT).until(() -> LifecycleObserver.SEEN.get() >= 1);
            await().pollDelay(Duration.ofMillis(300)).until(() -> true);
            assertThat(LifecycleObserver.STARTED_TOTAL.get()).isEqualTo(1);
        }
    }
```

Plus the observer fixture:

```java
// LifecycleObserver.java (same test package)
package com.example.asyncstart.scope;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.events.EventStartedEvent;
import java.util.concurrent.atomic.AtomicInteger;

@Component(scope = Scope.SINGLETON)
public class LifecycleObserver {
    public static final AtomicInteger SEEN = new AtomicInteger();
    public static final AtomicInteger STARTED_TOTAL = new AtomicInteger();

    @EventHandler(async = true)
    public void onUnitStarted(EventStartedEvent event) {
        SEEN.incrementAndGet();
        STARTED_TOTAL.incrementAndGet();
    }
}
```

Reset `LifecycleObserver.SEEN`/`STARTED_TOTAL` in `resetCaptures()` too. NOTE for the recursion test: the lifecycle-pair test above asserts `started == 1` while `LifecycleObserver` exists in the same container — if the observer's presence changes that test's counts (it should not, since the observer's dispatch mints no unit), reconcile by asserting on `EventStartedEvent` counts only, never on observer side effects, in that test.

- [ ] **Step 4: Run the module, iterate to green**

Run: `& W:\tools\apache-maven\bin\mvn.cmd test -pl tiko-examples/07_async_start -am *> W:\workspace\220-t3.log; $LASTEXITCODE`
Expected: `0`, BUILD SUCCESS, all tests executed (check the surefire summary lists `AsyncEventScopeTest`).

- [ ] **Step 5: Commit**

```powershell
git add tiko-examples/07_async_start
git commit -m "test(examples): behavioral coverage for async EVENT units - fresh per attempt, teardown, detachment, no recursion (#220)"
```

(If the fixtures had to relocate to a new `16_async_scope` module, adjust paths and say so.)

---

### Task 4: Documentation

**Files:**
- Modify: `docs/events.md` (async semantics section)
- Modify: `docs/di-and-scopes.md` (EVENT scope section)
- Modify: `.ai-skills/tiko-build/reference/events.md` (canonical chunk)
- Regenerate (tool only): `tiko-archetype/src/main/resources/archetype-resources/.ai-skills/tiko-build/reference/events.md`
- Check-only: `CLAUDE.md` scope section (must not contradict; the "async dispatch" line in Scope Management already matches)

**Interfaces:**
- Consumes: shipped behavior from Tasks 1–3.

- [ ] **Step 1: Add the async-unit section to `docs/events.md`**

Insert after the existing async-handler documentation (locate the `async = true` section):

```markdown
### Async handlers own their unit of work

An `@EventHandler(async = true)` invocation runs inside its **own fresh EVENT unit** on the
executor thread — the in-process mirror of transport consumption (a Kafka-consumed message
gets the same shape). Concretely:

- EVENT-scoped beans resolve inside the handler and bind to that invocation's unit;
  they are torn down (`@PreDestroy`, LIFO) when the handler completes — success, failure,
  or timeout.
- One `EventStartedEvent` / `EventEndingEvent` pair is published per invocation. With
  `retries`, **each attempt is its own unit** (fresh beans, its own lifecycle pair) — a
  retried attempt never sees the failed attempt's EVENT state.
- Handlers subscribed to the framework lifecycle events (`io.tiko.events.*`) dispatch
  *without* a unit — they observe units and must not mint new ones (this also makes
  lifecycle-observer recursion structurally impossible).
- The timeout budget (`timeout = ...`) covers the whole unit, including scope open and
  lifecycle publishes.

Multi-module note: in aggregated (multi-module) containers, async units get correct frames,
per-module bean isolation, and teardown — but publish no lifecycle events (module
containers are constructed silent; the aggregator is not in the async dispatch path).
```

- [ ] **Step 2: Add two sentences to `docs/di-and-scopes.md`** in the EVENT scope discussion:

```markdown
Async `@EventHandler` dispatch is a scope boundary: the handler runs in its own fresh
EVENT unit (per attempt, when retries are configured), so EVENT-scoped dependencies of an
async handler resolve to that unit and are torn down when it ends. The publishing side's
unit — if any — is left behind at the async hop.
```

- [ ] **Step 3: Add a short note to the canonical skill chunk** `.ai-skills/tiko-build/reference/events.md`, in the async/imperative-publish discussion:

```markdown
Async handlers own their unit: an `@EventHandler(async = true)` runs in a fresh EVENT
unit per invocation (per attempt with retries) — EVENT-scoped beans resolve inside it and
tear down when it completes, and each invocation publishes its own
`EventStartedEvent`/`EventEndingEvent` pair.
```

Then regenerate the bundled copy (from `tiko-archetype/`): `mvn -q test-compile`, `java -cp target/test-classes io.tiko.archetype.ArchetypeDocSync`.

- [ ] **Step 4: Verify gate + spotless, commit**

Run: `& W:\tools\apache-maven\bin\mvn.cmd test -pl tiko-archetype -am *> W:\workspace\220-t4.log; $LASTEXITCODE` → `0` (sync gate green).

```powershell
git add docs/events.md docs/di-and-scopes.md .ai-skills/tiko-build tiko-archetype/src/main/resources/archetype-resources/.ai-skills/tiko-build
git commit -m "docs: async handlers own their EVENT unit - semantics, exclusions, aggregated-setup note (#220)"
```

---

### Task 5: Full verify, follow-up issue, PR

- [ ] **Step 1: Full reactor**

Run: `& W:\tools\apache-maven\bin\mvn.cmd test *> W:\workspace\220-full.log; $LASTEXITCODE` → `0`, BUILD SUCCESS (spotless + all modules; Kafka e2e ITs skip without Docker as usual).

- [ ] **Step 2: File the aggregated-lifecycle follow-up issue** (sanctioned by the approved spec)

Title: `AggregatingContainer: async EVENT units publish no lifecycle events (publisher plumbing)`
Body (symptoms-only house style): async units opened by generated dispatch go through the module container, which is constructed with `publishLifecycleEvents=false` (#339) — the aggregator publishes unit lifecycle only for its own `runInEventScope` path, so async units in multi-module setups are invisible to unit metrics. Files: `tiko-runtime/.../AggregatingContainer.java`, `tiko-processor/.../ContainerGenerator.java`. Acceptance: in an aggregated setup, an async dispatch publishes exactly one `EventStartedEvent`/`EventEndingEvent` pair on the shared bus. Out of scope: single-container behavior (shipped by #220). Predecessor: #220 spec, accepted-gap decision.

- [ ] **Step 3: PR**

Body: what shipped (the two generator changes + behavior matrix), observable changes for users (EVENT beans in async handlers now work; unit metrics see async; `@PreDestroy` runs — release-note material), the four locked decisions, the accepted aggregated gap + follow-up issue link, footer `🤖 Generated with [Claude Code](https://claude.com/claude-code)`. Push, `gh pr create --body-file`, watch CI, query Sonar open-issues API, report. User merges.

---

## Self-Review Notes

- Spec coverage: Component 1 → Task 1; Component 2 → Task 2; interplay §3 → covered by not touching `EventChainContext` (constraint) + Task 3's retry/timeout/DLQ-adjacent tests; decisions 1–4 → Tasks 2/3 (per-attempt via wrapper placement, exclusion test, detachment test, gap documented in Task 4 + issue in Task 5); testing section → Tasks 2 (processor) + 3 (behavior; the DLQ-rejected-no-lifecycle assertion is covered implicitly by the lifecycle-pair test's exact count — if the implementer can cheaply saturate to a DROP/DLQ rejection they may add it, but it is not required for merge); docs → Task 4; acceptance → Task 5.
- Type consistency: `runInDetachedEventScope(Runnable)` identical in Task 1 emission, Task 1 test assertion, Task 2 wrapper emission and test assertions, Task 3 rationale. `eventScoped`/`__unitFrameOpen` names taken from current `ContainerGenerator` source.
- Known judgment point handed to the implementer explicitly: whether `07_async_start` test-tree components are processed into the test container (Task 3 preamble gives the relocation fallback with exact instructions).
