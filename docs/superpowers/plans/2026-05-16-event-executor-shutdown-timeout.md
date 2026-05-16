# Event-executor shutdown timeout via `TikoOptions` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `TikoOptions.shutdownTimeout(Duration)` lets users override the hardcoded 10-second `awaitTermination` window used when shutting down the framework-owned event executor; default stays 10s; `09_http_javalin` example gains a runnable drain demo.

**Architecture:** The 10s constant lives in TWO places: `AggregatingContainer.shutdown()` (multi-module path) AND inside generated `TikoContainerImpl_<hash>.shutdown()` (single-module path) — `ContainerGenerator` emits the same shape. Both need the timeout, so the generator gains a 5-arg constructor overload that accepts `Duration shutdownTimeout`; the existing 4-arg constructor stays as a delegating shim with the 10s default. `Tiko.create()` threads `options.shutdownTimeout()` to both the aggregator and the single-module reflective constructor call.

**Tech Stack:** Java 21, JUnit 5, AssertJ, JavaPoet, Google `compile-testing`.

**Spec:** `docs/superpowers/specs/2026-05-16-event-executor-shutdown-timeout-design.md` (committed at `d3ace66` on `feat/event-executor-shutdown-timeout`).

---

## File structure

```
tiko-runtime/src/main/java/io/tiko/runtime/
├── TikoOptions.java                       (modify — add shutdownTimeout field/builder/accessor)
├── AggregatingContainer.java              (modify — 4-arg ctor gains Duration; 5-arg reflective call for module containers)
└── Tiko.java                              (modify — thread options.shutdownTimeout() to both paths)

tiko-runtime/src/test/java/io/tiko/runtime/
├── TikoOptionsTest.java                   (modify — 4 new tests)
├── AggregatingContainerShutdownTimeoutTest.java   (create — forced + graceful runtime test)
└── StubContainer.java                     (modify — add 5-arg constructor)

tiko-processor/src/main/java/io/tiko/processor/generator/
└── ContainerGenerator.java                (modify — emit shutdownTimeout field + 5-arg ctor + use field in shutdown)

tiko-processor/src/test/java/io/tiko/processor/
└── ContainerGeneratorShutdownTimeoutTest.java     (create — assert generated code uses field, not hardcoded 10)

tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/
├── Main.java                              (rewrite — drain flow demo + Error caveat Javadoc)
└── SlowAuditService.java                  (create — slow async handler with latch)

tiko-examples/09_http_javalin/src/test/java/io/tiko/examples/http/
└── HttpAsyncDrainTest.java                (create — CountDownLatch e2e)

docs/events.md                             (modify — graceful drain subsection)
docs/roadmap.md                            (modify — "What ships today" closes #48)
```

---

## Task 1: `TikoOptions.shutdownTimeout(Duration)` field + builder + accessor (TDD)

**Files:**
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/TikoOptions.java`
- Modify: `tiko-runtime/src/test/java/io/tiko/runtime/TikoOptionsTest.java`

- [ ] **Step 1: Write the failing tests**

Open `tiko-runtime/src/test/java/io/tiko/runtime/TikoOptionsTest.java`. Existing imports already include AssertJ and `assertThatNullPointerException`. Add this import:

```java
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import java.time.Duration;
```

Append these 4 tests inside the class body:

```java
    @Test
    void builder_round_trips_shutdown_timeout() {
        TikoOptions options =
                TikoOptions.builder().shutdownTimeout(Duration.ofSeconds(2)).build();

        assertThat(options.shutdownTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void builder_shutdown_timeout_default_is_ten_seconds() {
        TikoOptions options = TikoOptions.builder().build();

        assertThat(options.shutdownTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void builder_rejects_negative_shutdown_timeout() {
        TikoOptions.Builder b = TikoOptions.builder();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> b.shutdownTimeout(Duration.ofSeconds(-1)))
                .withMessageContaining("shutdownTimeout");
    }

    @Test
    void builder_rejects_null_shutdown_timeout() {
        TikoOptions.Builder b = TikoOptions.builder();
        assertThatNullPointerException().isThrownBy(() -> b.shutdownTimeout(null));
    }
```

- [ ] **Step 2: Run, expect compile failure**

Run: `mvn -pl tiko-runtime test -Dtest=TikoOptionsTest`
Expected: compile failure — `shutdownTimeout` method does not exist.

- [ ] **Step 3: Add field/builder/accessor to `TikoOptions.java`**

In `TikoOptions.java`:

Add the import:
```java
import java.time.Duration;
```

Add a private field after `eventExecutor`:
```java
    private final Duration shutdownTimeout;
```

Update the private constructor:
```java
    private TikoOptions(Builder b) {
        this.configSource = b.configSource;
        this.errorHandler = b.errorHandler;
        this.eventExecutor = b.eventExecutor;
        this.shutdownTimeout = b.shutdownTimeout;
    }
```

Add the public accessor (after `eventExecutor()`):
```java
    /**
     * @return the configured graceful shutdown window for the framework-owned event executor;
     *         defaults to {@code Duration.ofSeconds(10)}. Has no effect when {@link #eventExecutor()}
     *         is set — the user owns the executor's lifecycle.
     */
    public Duration shutdownTimeout() {
        return shutdownTimeout;
    }
```

Add a field to the Builder (with the default initializer):
```java
        private Duration shutdownTimeout = Duration.ofSeconds(10);
```

Add the builder method (after `eventExecutor(...)`):
```java
        /**
         * Maximum time {@link io.tiko.Container#shutdown()} waits for the framework's event
         * executor to terminate gracefully before falling back to {@code shutdownNow()}.
         * Defaults to {@code Duration.ofSeconds(10)}.
         *
         * <p>Has <strong>no effect</strong> when {@link #eventExecutor(java.util.concurrent.ExecutorService)}
         * is set — the user owns the executor's lifecycle and the container does not stop it.
         *
         * <p>Note: a JVM {@link Error} (e.g. {@code OutOfMemoryError}) bypasses this graceful
         * drain. Threads may be torn down abruptly when the JVM is in an unrecoverable state.
         *
         * @param timeout non-negative duration; {@link Duration#ZERO} skips the graceful wait
         *                and calls {@code shutdownNow()} immediately
         * @throws IllegalArgumentException if {@code timeout} is negative
         * @throws NullPointerException if {@code timeout} is null
         */
        public Builder shutdownTimeout(Duration timeout) {
            Objects.requireNonNull(timeout, "shutdownTimeout");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException("shutdownTimeout must not be negative");
            }
            this.shutdownTimeout = timeout;
            return this;
        }
```

Also add a cross-reference paragraph to the existing `eventExecutor(...)` builder method's Javadoc:

```java
         * <p>See {@link #shutdownTimeout(Duration)} for the related drain window — that knob
         * has no effect when this executor is user-supplied (you own its lifecycle).
```

(Insert near the end of `eventExecutor`'s existing Javadoc, before the `@param` or `@throws` block if present, otherwise as the last paragraph.)

- [ ] **Step 4: Run tests, expect pass**

Run: `mvn -pl tiko-runtime test -Dtest=TikoOptionsTest`
Expected: `Tests run: 11, Failures: 0, Errors: 0` (7 existing + 4 new).

- [ ] **Step 5: Format + commit**

```
mvn -pl '!tiko-bom' spotless:apply
git add tiko-runtime/src/main/java/io/tiko/runtime/TikoOptions.java tiko-runtime/src/test/java/io/tiko/runtime/TikoOptionsTest.java
git commit -m "feat(runtime): TikoOptions.shutdownTimeout(Duration) for event executor drain"
```

---

## Task 2: `ContainerGenerator` emits 5-arg constructor + uses `shutdownTimeout` in shutdown (TDD)

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java`
- Create: `tiko-processor/src/test/java/io/tiko/processor/ContainerGeneratorShutdownTimeoutTest.java`

Why this comes before AggregatingContainer: the generated `TikoContainerImpl_<hash>` is reflectively constructed by both `AggregatingContainer.processContainerResource` (multi-module) and `Tiko.createSingleModuleContainer` (single-module). To avoid an intermediate broken state, the generator first emits an additional 5-arg constructor that takes `Duration shutdownTimeout`, while keeping the existing 4-arg constructor as a delegating shim with the 10s default. Then Task 3 switches AggregatingContainer to the 5-arg form, Task 4 wires `Tiko.create` to read from `TikoOptions`.

- [ ] **Step 1: Write the failing test `ContainerGeneratorShutdownTimeoutTest.java`**

Create `tiko-processor/src/test/java/io/tiko/processor/ContainerGeneratorShutdownTimeoutTest.java`:

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

/**
 * Verifies the generated single-module container honours TikoOptions.shutdownTimeout (#48):
 *
 * <ul>
 *   <li>A new 5-arg constructor accepts {@code Duration shutdownTimeout}.</li>
 *   <li>The existing 4-arg constructor still exists and delegates with the 10s default.</li>
 *   <li>The generated {@code shutdown()} reads from the field, not the hardcoded {@code 10, SECONDS}.</li>
 * </ul>
 */
class ContainerGeneratorShutdownTimeoutTest {

    @Test
    void generated_container_exposes_5_arg_ctor_and_uses_shutdown_timeout_field() throws IOException {
        JavaFileObject src = JavaFileObjects.forSourceLines(
                "io.example.Foo",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Foo {}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TikoContainerImpl was not generated"));

        String body = new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // 5-arg constructor with Duration parameter exists.
        assertThat(body).contains("java.time.Duration shutdownTimeout");

        // Existing 4-arg constructor still exists and delegates with the 10s default.
        // Match the delegation pattern: a `this(..., Duration.ofSeconds(10))` call.
        assertThat(body).contains("Duration.ofSeconds(10)");

        // Shutdown reads from the field, not the hardcoded 10 seconds.
        assertThat(body).contains("this.shutdownTimeout.toNanos()");
        assertThat(body).contains("NANOSECONDS");

        // Negative assertion: the old `awaitTermination(10, SECONDS)` literal is gone from shutdown.
        assertThat(body).doesNotContain("awaitTermination(10, ");
    }
}
```

- [ ] **Step 2: Run, expect failure**

Run: `mvn -pl tiko-processor test -Dtest=ContainerGeneratorShutdownTimeoutTest`
Expected: failures on the four `assertThat(body).contains(...)` clauses — the generator still emits `awaitTermination(10, SECONDS)` and has no `shutdownTimeout` field.

- [ ] **Step 3: Update `ContainerGenerator.java`**

Several edits to `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java`:

**Edit A — add a `shutdownTimeout` field generator** (place near `createOwnsEventExecutorField` around line 194):

```java
    /**
     * Creates the {@code Duration shutdownTimeout} field — the graceful-wait window
     * used by {@link #shutdown()} when the container owns the executor.
     */
    private com.palantir.javapoet.FieldSpec createShutdownTimeoutField() {
        return com.palantir.javapoet.FieldSpec.builder(
                        java.time.Duration.class, "shutdownTimeout", Modifier.PRIVATE, Modifier.FINAL)
                .build();
    }
```

(If JavaPoet's `FieldSpec` and `Modifier` are already imported, use the short names. The file imports vary — keep the existing import style.)

**Edit B — register the new field** in the spot that adds fields to the type (search the file for `createOwnsEventExecutorField` calls and add a `.addField(createShutdownTimeoutField())` alongside).

**Edit C — extend the constructor.** Find the existing 4-arg constructor at the `MethodSpec` builder that adds `EventBus`, `ErrorHandler`, `ExecutorService`, `boolean publishLifecycleEvents` parameters and the `this.errorHandler = errorHandler` / `this.ownsEventExecutor = ...` body. Refactor:

1. **Build the canonical 5-arg constructor** with the original four parameters plus `Duration shutdownTimeout`. Body assigns `this.shutdownTimeout = shutdownTimeout;` in addition to today's assignments.
2. **Keep the 4-arg constructor** as a public delegating shim that calls `this(eventBus, errorHandler, userEventExecutor, publishLifecycleEvents, java.time.Duration.ofSeconds(10));`. This preserves the discovery contract for any caller that hasn't been updated yet (Task 3 updates `AggregatingContainer`; Task 4 updates `Tiko.createSingleModuleContainer`).

Concretely, the 4-arg shim emits as something like:

```java
public TikoContainerImpl(
        io.tiko.EventBus eventBus,
        io.tiko.ErrorHandler errorHandler,
        java.util.concurrent.ExecutorService userEventExecutor,
        boolean publishLifecycleEvents) {
    this(eventBus, errorHandler, userEventExecutor, publishLifecycleEvents, java.time.Duration.ofSeconds(10));
}
```

And the 5-arg form contains all the existing body statements PLUS one new `this.shutdownTimeout = shutdownTimeout;`.

JavaPoet emission sketch (adapt to the existing builder pattern used in the file):

```java
MethodSpec ctor5 = MethodSpec.constructorBuilder()
        .addModifiers(Modifier.PUBLIC)
        .addParameter(ClassName.get(EventBus.class), "eventBus")
        .addParameter(ClassName.get(ErrorHandler.class), "errorHandler")
        .addParameter(ClassName.get(java.util.concurrent.ExecutorService.class), "userEventExecutor")
        .addParameter(TypeName.BOOLEAN, "publishLifecycleEvents")
        .addParameter(java.time.Duration.class, "shutdownTimeout")
        .addStatement("this.eventBus = eventBus")
        .addStatement("this.errorHandler = errorHandler")
        .addStatement("this.eventExecutor = userEventExecutor != null ? userEventExecutor : "
                + "io.tiko.runtime.DefaultEventExecutorFactory.create()")
        .addStatement("this.ownsEventExecutor = (userEventExecutor == null)")
        .addStatement("this.publishLifecycleEvents = publishLifecycleEvents")
        .addStatement("this.shutdownTimeout = shutdownTimeout")
        // ... plus any factory-field initializations the existing constructor body has
        .build();

MethodSpec ctor4 = MethodSpec.constructorBuilder()
        .addModifiers(Modifier.PUBLIC)
        .addParameter(ClassName.get(EventBus.class), "eventBus")
        .addParameter(ClassName.get(ErrorHandler.class), "errorHandler")
        .addParameter(ClassName.get(java.util.concurrent.ExecutorService.class), "userEventExecutor")
        .addParameter(TypeName.BOOLEAN, "publishLifecycleEvents")
        .addStatement("this(eventBus, errorHandler, userEventExecutor, publishLifecycleEvents, "
                + "$T.ofSeconds(10))", java.time.Duration.class)
        .build();
```

**Edit D — rewrite the shutdown block** (around line 1108–1118). Replace the hardcoded `10, SECONDS` with the field. Current shape:

```java
method.beginControlFlow("if (this.ownsEventExecutor)");
method.addStatement("this.eventExecutor.shutdown()");
method.beginControlFlow("try");
method.beginControlFlow("if (!this.eventExecutor.awaitTermination(10, $T.SECONDS))", timeUnit);
method.addStatement("this.eventExecutor.shutdownNow()");
method.endControlFlow();
method.nextControlFlow("catch ($T __ie)", InterruptedException.class);
method.addStatement("$T.currentThread().interrupt()", Thread.class);
method.addStatement("this.eventExecutor.shutdownNow()");
method.endControlFlow();
method.endControlFlow();
```

Replace with:

```java
method.beginControlFlow("if (this.ownsEventExecutor)");
method.addStatement("this.eventExecutor.shutdown()");
method.beginControlFlow("try");
method.beginControlFlow(
        "if (!this.eventExecutor.awaitTermination(this.shutdownTimeout.toNanos(), $T.NANOSECONDS))", timeUnit);
method.addStatement("this.eventExecutor.shutdownNow()");
method.endControlFlow();
method.nextControlFlow("catch ($T __ie)", InterruptedException.class);
method.addStatement("$T.currentThread().interrupt()", Thread.class);
method.addStatement("this.eventExecutor.shutdownNow()");
method.endControlFlow();
method.endControlFlow();
```

The `timeUnit` import remains unchanged (still `java.util.concurrent.TimeUnit`).

- [ ] **Step 4: Run the new test, expect pass**

Run: `mvn -pl tiko-processor test -Dtest=ContainerGeneratorShutdownTimeoutTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0`.

- [ ] **Step 5: Run the full processor test suite to confirm no regressions**

Run: `mvn -pl tiko-processor test`
Expected: BUILD SUCCESS. Any existing test that asserts on the OLD 4-arg-only constructor shape may need updating — if `ContainerGeneratorEventExecutorTest` (or similar) checks for the specific string `awaitTermination(10, ` in generated source, update it to `awaitTermination(this.shutdownTimeout.toNanos(),` and add `assertThat(body).contains("Duration.ofSeconds(10)")` to verify the default delegation. Do NOT loosen the test's intent.

- [ ] **Step 6: Format + commit**

```
mvn -pl '!tiko-bom' spotless:apply
git add tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java tiko-processor/src/test/java/io/tiko/processor/ContainerGeneratorShutdownTimeoutTest.java
```

If any existing processor test was updated for the new shutdown shape, include it in the same commit.

```
git commit -m "feat(processor): generated container accepts shutdownTimeout in new 5-arg ctor"
```

---

## Task 3: `AggregatingContainer` accepts `Duration shutdownTimeout` + uses 5-arg reflective call (TDD)

**Files:**
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java`
- Modify: `tiko-runtime/src/test/java/io/tiko/runtime/StubContainer.java`
- Create: `tiko-runtime/src/test/java/io/tiko/runtime/AggregatingContainerShutdownTimeoutTest.java`

- [ ] **Step 1: Install upstream modules so the test classpath sees the Task 2 changes**

```
mvn -pl '!tiko-bom' install -DskipTests
```

The processor's freshly-built classes need to be in local m2 so subsequent `tiko-runtime` test compiles pick them up cleanly.

- [ ] **Step 2: Write the failing test `AggregatingContainerShutdownTimeoutTest.java`**

Create `tiko-runtime/src/test/java/io/tiko/runtime/AggregatingContainerShutdownTimeoutTest.java`:

```java
package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.ErrorHandler;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Verifies AggregatingContainer.shutdown() honours the configured shutdownTimeout (#48).
 * Two scenarios:
 *
 * <ul>
 *   <li>Forced path: a long-running task plus a tight timeout → executor forced via
 *       shutdownNow() and isTerminated() goes true within ~3x the configured timeout.</li>
 *   <li>Graceful path: a quick task with the default 10s timeout → shutdown returns
 *       far below the 10s budget (no accidental "always wait the full window" regression).</li>
 * </ul>
 *
 * <p>The container resolves to StubContainer via {@code src/test/resources/META-INF/tiko/container.properties}
 * — it intentionally has no @Component code; only the executor shutdown path is exercised.
 */
class AggregatingContainerShutdownTimeoutTest {

    private static final ErrorHandler NOOP_ERROR_HANDLER = ctx -> {};

    @Test
    void shutdown_forces_executor_when_timeout_exceeded() throws Exception {
        EventBus bus = new LocalEventBus();
        AggregatingContainer container = new AggregatingContainer(
                bus,
                NOOP_ERROR_HANDLER,
                /* userEventExecutor= */ null,
                /* shutdownTimeout= */ Duration.ofMillis(50));

        // Submit a task that outlasts the timeout.
        container.getEventExecutor().submit(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });

        long startNanos = System.nanoTime();
        container.shutdown();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        assertThat(container.getEventExecutor().isTerminated()).isTrue();
        // Loose upper bound for CI jitter — well below the 500ms sleep, confirming shutdownNow fired.
        assertThat(elapsed).isLessThan(Duration.ofMillis(300));
    }

    @Test
    void shutdown_returns_promptly_when_executor_drains_well_within_default() throws Exception {
        EventBus bus = new LocalEventBus();
        AggregatingContainer container = new AggregatingContainer(
                bus,
                NOOP_ERROR_HANDLER,
                /* userEventExecutor= */ null,
                /* shutdownTimeout= */ Duration.ofSeconds(10));

        container.getEventExecutor().submit(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });

        long startNanos = System.nanoTime();
        container.shutdown();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        assertThat(container.getEventExecutor().isTerminated()).isTrue();
        // Guards against a regression where shutdown accidentally always waits the full budget.
        assertThat(elapsed).isLessThan(Duration.ofMillis(500));
    }
}
```

Note: `EventBus` is `io.tiko.EventBus` — imported via the test's own package since `AggregatingContainer` and `LocalEventBus` are in `io.tiko.runtime` (same package).

- [ ] **Step 3: Run, expect failure**

Run: `mvn -pl tiko-runtime test -Dtest=AggregatingContainerShutdownTimeoutTest`
Expected: compile failure — the 4-arg `AggregatingContainer(EventBus, ErrorHandler, ExecutorService, Duration)` constructor does not exist.

- [ ] **Step 4: Modify `AggregatingContainer.java` — add Duration field, 4-arg constructor, use timeout in shutdown**

In `tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java`:

**Edit A — add import** (file already imports `java.time.Duration` at line 13):

(No new import needed.)

**Edit B — add private final field** alongside `eventExecutor`:

```java
    private final Duration shutdownTimeout;
```

**Edit C — refactor constructors.** The current shape (line 50–95) is:

- 1-arg `(EventBus)` → delegates to 3-arg with `(null)` executor
- 2-arg `(EventBus, ErrorHandler)` → delegates to 3-arg with `(null)` executor
- 3-arg `(EventBus, ErrorHandler, ExecutorService)` → the main constructor

Make the 4-arg `(EventBus, ErrorHandler, ExecutorService, Duration)` the canonical form. All existing constructors delegate down to it. Concretely:

```java
    public AggregatingContainer(EventBus eventBus) {
        this(eventBus, ctx -> {}, null, Duration.ofSeconds(10));
    }

    public AggregatingContainer(EventBus eventBus, ErrorHandler errorHandler) {
        this(eventBus, errorHandler, null, Duration.ofSeconds(10));
    }

    public AggregatingContainer(
            EventBus eventBus, ErrorHandler errorHandler, java.util.concurrent.ExecutorService userEventExecutor) {
        this(eventBus, errorHandler, userEventExecutor, Duration.ofSeconds(10));
    }

    /**
     * Creates an aggregating container with a custom error handler, event executor, and
     * shutdown timeout. The {@code shutdownTimeout} caps how long {@link #shutdown()} waits
     * for the framework-owned executor to drain gracefully before calling {@code shutdownNow()}.
     * Has no effect when {@code userEventExecutor} is non-null (user owns its lifecycle).
     *
     * @param eventBus            shared event bus
     * @param errorHandler        error handler for event handler exceptions
     * @param userEventExecutor   optional user-supplied executor; null means framework-owned
     * @param shutdownTimeout     graceful drain window; non-negative
     */
    public AggregatingContainer(
            EventBus eventBus,
            ErrorHandler errorHandler,
            java.util.concurrent.ExecutorService userEventExecutor,
            Duration shutdownTimeout) {
        this.sharedEventBus = eventBus;
        this.errorHandler = errorHandler;
        this.eventExecutor = userEventExecutor != null ? userEventExecutor : DefaultEventExecutorFactory.create();
        this.ownsEventExecutor = (userEventExecutor == null);
        this.shutdownTimeout = shutdownTimeout;
        this.moduleContainers = new ArrayList<>();
        this.componentToContainerMap = new ConcurrentHashMap<>();

        try {
            discoverAndInitializeModuleContainers();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize aggregating container", e);
        }
    }
```

**Edit D — switch the reflective per-module-container construction to 5-arg.** In `processContainerResource` around line 142–147:

```java
        // Load and instantiate the container with 5-arg constructor (#48):
        // (EventBus, ErrorHandler, ExecutorService, boolean publishLifecycleEvents, Duration shutdownTimeout).
        // Per-module containers see a non-null executor so their internal ownsEventExecutor
        // becomes false — only the aggregator shuts the executor down. The shutdownTimeout
        // is forwarded so per-module containers built outside this aggregator path still
        // honour the configured drain window.
        Class<?> containerClass = Class.forName(implClassName, true, classLoader);
        Constructor<?> constructor = containerClass.getDeclaredConstructor(
                EventBus.class,
                ErrorHandler.class,
                java.util.concurrent.ExecutorService.class,
                boolean.class,
                Duration.class);
        Container moduleContainer = (Container) constructor.newInstance(
                sharedEventBus, errorHandler, eventExecutor, /* publishLifecycleEvents */ false, shutdownTimeout);
```

**Edit E — use `shutdownTimeout` in the shutdown block.** At line 377–387, replace:

```java
        if (ownsEventExecutor) {
            eventExecutor.shutdown();
            try {
                if (!eventExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    eventExecutor.shutdownNow();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                eventExecutor.shutdownNow();
            }
        }
```

With:

```java
        if (ownsEventExecutor) {
            eventExecutor.shutdown();
            try {
                if (!eventExecutor.awaitTermination(
                        shutdownTimeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS)) {
                    eventExecutor.shutdownNow();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                eventExecutor.shutdownNow();
            }
        }
```

- [ ] **Step 5: Update `StubContainer.java` to expose the 5-arg constructor**

Open `tiko-runtime/src/test/java/io/tiko/runtime/StubContainer.java`. The current constructor (line 21–22) is 4-arg. Add a 5-arg variant that matches the new reflective protocol:

```java
    // Tiko.createSingleModuleContainer + AggregatingContainer.processContainerResource
    // both reflectively look up the 5-arg constructor after #48.
    public StubContainer(
            EventBus eventBus,
            ErrorHandler errorHandler,
            ExecutorService executor,
            boolean publishLifecycle,
            java.time.Duration shutdownTimeout) {}
```

Keep the existing 4-arg constructor too — older callers (if any) and the existing single-module reflective call path still need it until Task 4 switches over. (After Task 4 it could be removed, but leaving it costs nothing.)

- [ ] **Step 6: Run the new test, expect pass**

Run: `mvn -pl tiko-runtime test -Dtest=AggregatingContainerShutdownTimeoutTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`. Both forced and graceful paths green.

- [ ] **Step 7: Run the full tiko-runtime test suite**

Run: `mvn -pl tiko-runtime test`
Expected: BUILD SUCCESS, no regressions.

- [ ] **Step 8: Format + commit**

```
mvn -pl '!tiko-bom' spotless:apply
git add tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java tiko-runtime/src/test/java/io/tiko/runtime/StubContainer.java tiko-runtime/src/test/java/io/tiko/runtime/AggregatingContainerShutdownTimeoutTest.java
git commit -m "feat(runtime): AggregatingContainer accepts shutdownTimeout, uses it in shutdown"
```

---

## Task 4: `Tiko.create` threads `options.shutdownTimeout()` to both paths

**Files:**
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/Tiko.java`

- [ ] **Step 1: Update the multi-module branch**

In `Tiko.java`, find around line 92–98:

```java
            Container container;
            if (moduleCount > 1) {
                container = new AggregatingContainer(eventBus, errorHandler, options.eventExecutor());
            } else {
                // Single module: Direct instantiation (does NOT call start yet)
                container = createSingleModuleContainer(eventBus, errorHandler, options.eventExecutor());
            }
```

Replace with:

```java
            Container container;
            if (moduleCount > 1) {
                container = new AggregatingContainer(
                        eventBus, errorHandler, options.eventExecutor(), options.shutdownTimeout());
            } else {
                // Single module: Direct instantiation (does NOT call start yet)
                container = createSingleModuleContainer(
                        eventBus, errorHandler, options.eventExecutor(), options.shutdownTimeout());
            }
```

- [ ] **Step 2: Update `createSingleModuleContainer` signature + reflective call**

Around line 237–265, the method currently has signature:

```java
private static Container createSingleModuleContainer(
        EventBus eventBus, ErrorHandler errorHandler, ExecutorService userEventExecutor) throws Exception {
```

Update to:

```java
private static Container createSingleModuleContainer(
        EventBus eventBus,
        ErrorHandler errorHandler,
        ExecutorService userEventExecutor,
        java.time.Duration shutdownTimeout)
        throws Exception {
```

And the reflective constructor lookup at line 257–259:

```java
        Container container = (Container) implClass
                .getDeclaredConstructor(EventBus.class, ErrorHandler.class, ExecutorService.class, boolean.class)
                .newInstance(eventBus, errorHandler, userEventExecutor, /* publishLifecycleEvents */ true);
```

Update to use the 5-arg constructor:

```java
        Container container = (Container) implClass
                .getDeclaredConstructor(
                        EventBus.class,
                        ErrorHandler.class,
                        ExecutorService.class,
                        boolean.class,
                        java.time.Duration.class)
                .newInstance(
                        eventBus,
                        errorHandler,
                        userEventExecutor,
                        /* publishLifecycleEvents */ true,
                        shutdownTimeout);
```

- [ ] **Step 3: Run the full reactor build**

Run: `mvn -pl '!tiko-bom' install`
Expected: BUILD SUCCESS. All modules build, all tests pass. This is the first point where end-to-end wiring is verified.

If a downstream test fails because a 4-arg constructor was the only one tried at a reflective call site, fix that call site (it likely needs to match the new 5-arg protocol). The processor-generated container's 4-arg shim still exists for tests that don't go through `Tiko.create`, so they remain unaffected.

- [ ] **Step 4: Format + commit**

```
mvn -pl '!tiko-bom' spotless:apply
git add tiko-runtime/src/main/java/io/tiko/runtime/Tiko.java
git commit -m "feat(runtime): Tiko.create threads shutdownTimeout to both paths"
```

---

## Task 5: `09_http_javalin` slow async handler + `Main.java` drain demo + Error caveat Javadoc

**Files:**
- Create: `tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/SlowAuditService.java`
- Modify: `tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/Main.java`

- [ ] **Step 1: Create `SlowAuditService.java`**

Create `tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/SlowAuditService.java`:

```java
package io.tiko.examples.http;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deliberately slow async audit handler used to demonstrate the
 * graceful-shutdown drain (#48) end-to-end. Runs on Tiko's framework
 * executor (not the HTTP worker thread), so the HTTP response returns
 * before this handler completes.
 *
 * <p>The {@link CountDownLatch} test hook (mirrors {@link NotificationSender})
 * lets the integration test wait deterministically for completion.
 */
@Component(scope = Scope.SINGLETON)
public class SlowAuditService {

    private static final Duration WORK_DURATION = Duration.ofSeconds(2);

    private final AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(0));

    /** Resets the latch to count down once when the slow handler completes. */
    public CountDownLatch expectOne() {
        var fresh = new CountDownLatch(1);
        latch.set(fresh);
        return fresh;
    }

    @EventHandler(async = true)
    public void onTicketCreated(TicketCreated event) {
        System.out.println("[async] slow audit work starting for ticket " + event.id());
        try {
            Thread.sleep(WORK_DURATION.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }
        System.out.println("[async] slow audit work complete for ticket " + event.id());
        latch.get().countDown();
    }
}
```

- [ ] **Step 2: Rewrite `Main.java` to demonstrate the drain**

Replace the contents of `tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/Main.java`:

```java
package io.tiko.examples.http;

import io.javalin.Javalin;
import io.tiko.Container;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Bootstrap + runnable drain demo. Builds the Tiko container with a 5-second
 * shutdownTimeout, runs Javalin, fires a ticket-creation request that triggers
 * a 2-second async audit handler, then stops Javalin while the audit is still
 * in flight. The container's {@code close()} (via try-with-resources) waits
 * for the in-flight async work to drain before tearing the executor down.
 *
 * <p>Expected console output:
 *
 * <pre>{@code
 * [main] Stopping HTTP server (async work still running)...
 * [async] slow audit work starting for ticket <uuid>
 * [async] slow audit work complete for ticket <uuid>
 * [main] Container closed cleanly.
 * }</pre>
 *
 * <p><strong>Error caveat:</strong> A JVM {@link Error} (e.g. {@code OutOfMemoryError},
 * {@code StackOverflowError}) bypasses this graceful drain — the JVM may tear down
 * threads abruptly when in an unrecoverable state. This is a JVM-level contract,
 * not something Tiko controls. For everything short of a JVM-level fatal, the
 * configured {@code shutdownTimeout} bounds the wait.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        TikoOptions opts =
                TikoOptions.builder().shutdownTimeout(Duration.ofSeconds(5)).build();

        try (Container container = Tiko.create(opts)) {
            TicketHttpRoutes routes = new TicketHttpRoutes(
                    container.get(TicketService.class), container.getEventBus(), container);

            Javalin app = Javalin.create();
            app.post("/tickets", TikoJavalin.scoped(container, routes::handleCreate));
            app.get("/tickets/{id}", TikoJavalin.scoped(container, routes::handleGet));

            int port = portFromEnv();
            app.start(port);

            // Trigger a real ticket-creation request. SlowAuditService receives the
            // resulting event asynchronously and sleeps ~2s.
            fireCreateTicketRequest(port);

            // Stop the HTTP layer while async audit is still in flight.
            System.out.println("[main] Stopping HTTP server (async work still running)...");
            app.stop();
        } // container.close() drains the in-flight slow handler before returning.

        System.out.println("[main] Container closed cleanly.");
    }

    private static void fireCreateTicketRequest(int port) throws Exception {
        // Manual JSON literal — jackson-databind is test-scoped on this module, so the
        // production main can't use ObjectMapper. The shape mirrors CreateTicketRequest(String title).
        String body = "{\"title\":\"drain demo\"}";
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/tickets"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201) {
            throw new IllegalStateException("Unexpected status " + response.statusCode() + ": " + response.body());
        }
    }

    private static int portFromEnv() {
        String value = System.getenv("TIKO_HTTP_PORT");
        if (value == null || value.isBlank()) return 8080;
        return Integer.parseInt(value.trim());
    }
}
```

Note: `jackson-databind` is `<scope>test</scope>` in this module's `pom.xml`, so `Main.java` cannot use `ObjectMapper`. The manual JSON literal sidesteps that — the body's shape is `{"title": "<value>"}` mirroring `CreateTicketRequest(String title)`. Confirm `CreateTicketRequest.java`'s canonical constructor; if it gained additional required fields since this plan was written, extend the literal accordingly.

- [ ] **Step 3: Compile to verify**

Run: `mvn -pl tiko-examples/09_http_javalin compile`
Expected: BUILD SUCCESS — no new dependency needed since `Main.java` uses only `java.net.http` + a manual JSON string literal.

- [ ] **Step 4: Run the example manually to verify expected output**

Run: `mvn -pl tiko-examples/09_http_javalin compile exec:java -Dexec.mainClass=io.tiko.examples.http.Main`

(If the example's `pom.xml` doesn't configure `exec:java`, use a direct invocation: build the jar and run `java -cp ...`. The implementer can choose the most ergonomic path.)

Expected console output (interleaved with Javalin's own startup logs):

```
[main] Stopping HTTP server (async work still running)...
[async] slow audit work starting for ticket <uuid>
[async] slow audit work complete for ticket <uuid>
[main] Container closed cleanly.
```

The ordering of the middle two lines AND the final `[main] Container closed cleanly.` line is the load-bearing observation: the executor was not torn down when Javalin was; the container waited for the slow handler within the 5-second budget.

- [ ] **Step 5: Commit**

```
mvn -pl '!tiko-bom' spotless:apply
git add tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/SlowAuditService.java tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/Main.java
git commit -m "feat(examples): 09_http_javalin demonstrates graceful shutdown drain"
```

---

## Task 6: `HttpAsyncDrainTest` end-to-end with `CountDownLatch`

**Files:**
- Create: `tiko-examples/09_http_javalin/src/test/java/io/tiko/examples/http/HttpAsyncDrainTest.java`

- [ ] **Step 1: Write the failing test**

Create `tiko-examples/09_http_javalin/src/test/java/io/tiko/examples/http/HttpAsyncDrainTest.java`:

```java
package io.tiko.examples.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.tiko.Container;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * End-to-end verification of #48: the framework's event executor is NOT
 * torn down when the HTTP server stops. In-flight async event handlers are
 * allowed to complete within the configured {@code shutdownTimeout} before
 * {@code container.close()} returns.
 */
class HttpAsyncDrainTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void slowAsyncHandlerCompletesBeforeContainerCloseReturns() throws Exception {
        TikoOptions opts =
                TikoOptions.builder().shutdownTimeout(Duration.ofSeconds(5)).build();
        Container container = Tiko.create(opts);
        SlowAuditService slowAudit = container.get(SlowAuditService.class);
        CountDownLatch latch = slowAudit.expectOne();

        TicketHttpRoutes routes = new TicketHttpRoutes(
                container.get(TicketService.class), container.getEventBus(), container);

        Javalin app = Javalin.create();
        app.post("/tickets", TikoJavalin.scoped(container, routes::handleCreate));
        app.start(0); // ephemeral port; honest concurrent-test hygiene
        int port = app.port();

        // Fire the request — server publishes TicketCreated which routes to the slow async handler.
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/tickets"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(new CreateTicketRequest("drain")))) 
                .build();
        HttpResponse<String> response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);

        // Stop HTTP layer immediately — async work still in flight at this point.
        app.stop();

        // The load-bearing assertion: close MUST drain the in-flight async handler
        // before returning. We do not await the latch here — container.close() is
        // the bound.
        long startNanos = System.nanoTime();
        container.close();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        assertThat(latch.getCount())
                .as("slow async handler must have completed during close()")
                .isZero();
        assertThat(elapsed)
                .as("close() must not exceed the configured shutdownTimeout window")
                .isLessThan(Duration.ofSeconds(5).plusMillis(500));
    }

    @Test
    void shortTimeoutForcesAsyncHandlerInterruption() throws Exception {
        // Tight timeout: 200ms vs the slow handler's 2s sleep. Close() returns within ~500ms,
        // and the handler did NOT complete (latch still has count 1).
        TikoOptions opts =
                TikoOptions.builder().shutdownTimeout(Duration.ofMillis(200)).build();
        Container container = Tiko.create(opts);
        SlowAuditService slowAudit = container.get(SlowAuditService.class);
        CountDownLatch latch = slowAudit.expectOne();

        TicketHttpRoutes routes = new TicketHttpRoutes(
                container.get(TicketService.class), container.getEventBus(), container);

        Javalin app = Javalin.create();
        app.post("/tickets", TikoJavalin.scoped(container, routes::handleCreate));
        app.start(0);
        int port = app.port();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/tickets"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(new CreateTicketRequest("force"))))
                .build();
        HttpResponse<String> response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);

        app.stop();

        long startNanos = System.nanoTime();
        container.close();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        // The slow handler sleeps 2s; the 200ms timeout forces an interrupt.
        assertThat(elapsed)
                .as("close() should force termination well before the slow handler finishes")
                .isLessThan(Duration.ofMillis(800));

        // Handler interrupted by Thread.sleep — latch did NOT count down (return path skipped).
        assertThat(latch.await(50, TimeUnit.MILLISECONDS)).isFalse();
    }
}
```

Note: `app.port()` returns the ephemeral port chosen by `start(0)`. If Javalin's API exposes this under a different method name in the version this project pins, adapt — the intent is "bind to any free port and read it back."

- [ ] **Step 2: Run, expect pass**

Run: `mvn -pl tiko-examples/09_http_javalin test -Dtest=HttpAsyncDrainTest`

Expected: `Tests run: 2, Failures: 0, Errors: 0`.

If the second test (forced interruption) flakes on slow CI runners, raise the upper-bound tolerance (`Duration.ofMillis(800)` → `Duration.ofSeconds(1)`). Do NOT loosen the latch-not-counted-down assertion — that's the proof the handler was actually interrupted.

If the first test fails because `close()` returns before the async handler runs (race between `bus.publish` and `app.stop()`), insert a tiny barrier: after `response = client.send(...)`, briefly poll until the executor reports a non-zero active task count, then proceed. This is sometimes needed in CI; flag if observed.

- [ ] **Step 3: Format + commit**

```
mvn -pl '!tiko-bom' spotless:apply
git add tiko-examples/09_http_javalin/src/test/java/io/tiko/examples/http/HttpAsyncDrainTest.java
git commit -m "test(examples): HttpAsyncDrainTest pins graceful-shutdown drain behaviour"
```

---

## Task 7: `docs/events.md` graceful drain subsection

**Files:**
- Modify: `docs/events.md`

- [ ] **Step 1: Pick the insertion point**

`docs/events.md` covers the event bus, async executor, lifecycle events, and `@EventTrigger` chains. Find the section that introduces the async executor (search for "async" or "executor"). The drain subsection sits naturally near the end of that block, before lifecycle events.

If no obvious "executor / async behaviour" section exists, add the subsection at the end of the file with an explicit `## Graceful shutdown drain` heading.

- [ ] **Step 2: Insert the subsection**

Add this content:

```markdown
## Graceful shutdown drain

When `Container.shutdown()` runs, in-flight async event handlers are allowed to
finish within `TikoOptions.shutdownTimeout(Duration)` (default 10 seconds) before
the framework-owned executor is forced via `shutdownNow()`. This means a server
shutdown signal does not abruptly cancel async side-effects already queued on
the executor — they drain cleanly within the configured budget.

```java
TikoOptions opts = TikoOptions.builder()
        .shutdownTimeout(Duration.ofSeconds(30))   // long-running batch handlers
        .build();
```

`Duration.ZERO` skips the graceful wait and calls `shutdownNow()` immediately —
useful for test harnesses where you don't want to wait on a wedged handler. The
knob has no effect when you supply your own executor via `TikoOptions.eventExecutor(...)`
(you own that executor's lifecycle).

See [`tiko-examples/09_http_javalin`](../tiko-examples/09_http_javalin) for a
runnable demo: `Main.java` triggers a slow async handler, stops the HTTP server,
and shows that `container.close()` waits for the handler to complete.

**Caveat:** a JVM `Error` (`OutOfMemoryError`, `StackOverflowError`) bypasses
this graceful drain — the JVM may tear down threads abruptly when in an
unrecoverable state. For everything short of a JVM-level fatal, `shutdownTimeout`
is the bound.
```

- [ ] **Step 3: Commit**

```
git add docs/events.md
git commit -m "docs(events): graceful shutdown drain subsection (TikoOptions.shutdownTimeout)"
```

---

## Task 8: Roadmap entry + full reactor build + push + PR

**Files:**
- Modify: `docs/roadmap.md`

- [ ] **Step 1: Update the roadmap**

In `docs/roadmap.md`, the **"What ships today"** block has a tail of `✅` entries (most recent ones closing #19, #63). Append a new bullet:

```markdown
- ✅ `TikoOptions.shutdownTimeout(Duration)` — graceful drain window for the framework-owned event executor; default 10s, `Duration.ZERO` skips the wait. `09_http_javalin` example demonstrates the behaviour end-to-end: stopping the HTTP server does not interrupt in-flight async event handlers; they finish within the configured budget before `container.close()` returns. (Closes #48.)
```

In the **Phase 2** section, find the bullet that references #48:

```markdown
- **Event system:** configurable executor shutdown timeout ([#48](https://github.com/tomas-samek/coverage/tiko-di/issues/48)); `ErrorContext` permits for lifecycle/config/scope errors ([#52](https://github.com/tomas-samek/tiko-di/issues/52)).
```

Remove the `#48` clause; if `#52` was the other item, leave it. If `#48` was the only item on that bullet, delete the bullet entirely. Read the actual current state of `docs/roadmap.md` Phase 2 block to confirm — the line wording may have drifted.

- [ ] **Step 2: Run the full reactor build**

Run: `mvn -pl '!tiko-bom' install`
Expected: BUILD SUCCESS. All modules, all tests pass.

- [ ] **Step 3: Confirm clean working tree**

Run: `git status`
Expected: only `docs/roadmap.md` modified.

- [ ] **Step 4: Commit roadmap**

```
git add docs/roadmap.md
git commit -m "docs(roadmap): shutdownTimeout knob shipped"
```

- [ ] **Step 5: Push**

```
git push -u origin feat/event-executor-shutdown-timeout
```

- [ ] **Step 6: Open the PR**

```bash
"C:/Program Files/GitHub CLI/gh.exe" pr create \
    --title "feat(runtime): TikoOptions.shutdownTimeout for event executor drain (#48)" \
    --body "$(cat <<'EOF'
## Summary

Closes #48. `TikoOptions.shutdownTimeout(Duration)` lets users override the hardcoded 10-second `awaitTermination` window used when shutting down the framework-owned event executor. Default stays 10s. `Duration.ZERO` skips the graceful wait and calls `shutdownNow()` immediately. Has no effect when the user supplies their own executor via `TikoOptions.eventExecutor(...)`.

The `09_http_javalin` example is rewritten to demonstrate the drain end-to-end: stopping the HTTP server does not interrupt in-flight async event handlers — they finish within the configured budget before `container.close()` returns. A new `HttpAsyncDrainTest` pins this behaviour.

Spec at `docs/superpowers/specs/2026-05-16-event-executor-shutdown-timeout-design.md`. Plan at `docs/superpowers/plans/2026-05-16-event-executor-shutdown-timeout.md`.

### Key pieces

- **`TikoOptions.shutdownTimeout(Duration)`** — additive builder method; default `Duration.ofSeconds(10)`; rejects negative durations; accepts `Duration.ZERO`.
- **`ContainerGenerator`** — emits a new 5-arg constructor on `TikoContainerImpl_<hash>` taking `Duration shutdownTimeout`; the existing 4-arg constructor stays as a delegating shim with the 10s default. Generated `shutdown()` uses `awaitTermination(this.shutdownTimeout.toNanos(), NANOSECONDS)` instead of the hardcoded `10, SECONDS`.
- **`AggregatingContainer`** — new 4-arg canonical constructor `(EventBus, ErrorHandler, ExecutorService, Duration)`; switches reflective per-module construction to the 5-arg protocol; uses the timeout in its own shutdown block.
- **`Tiko.create(...)`** — threads `options.shutdownTimeout()` to both the multi-module aggregator path and the single-module reflective path.
- **`09_http_javalin`** — new `SlowAuditService` (slow async handler with latch test hook); `Main.java` rewritten as a runnable drain demo; class Javadoc documents the JVM `Error` caveat.
- **`HttpAsyncDrainTest`** — end-to-end CountDownLatch assertion: handler completes during `container.close()` with default-ish timeout, AND a tight 200ms timeout forces an interrupt (latch does NOT count down).
- **`docs/events.md`** — new "Graceful shutdown drain" subsection.

### Test plan

- [x] `TikoOptionsTest` — round-trip, default 10s, negative rejection, null rejection (4 new tests; 11/11 pass).
- [x] `ContainerGeneratorShutdownTimeoutTest` — asserts the generated container has the 5-arg constructor, the 4-arg delegating shim with the 10s default, and the field-driven shutdown logic.
- [x] `AggregatingContainerShutdownTimeoutTest` — forced-path (50ms timeout vs 500ms task) AND graceful-path (10s default with 50ms task).
- [x] `HttpAsyncDrainTest` — slow handler completes during 5s budget; tight 200ms budget forces interrupt.
- [x] Full reactor `mvn -pl '!tiko-bom' install` green; Spotless clean.

### Backwards compatibility

Pure addition. Default behaviour unchanged (10s graceful wait) for every existing `Tiko.create(...)` call. The 4-arg generated `TikoContainerImpl` constructor still exists as a delegating shim, so any external code reflectively constructing it via the old protocol continues to work with the default timeout.

### Out of scope (mentioned to set boundary)

- Separate timeouts for `@PreDestroy` and `AutoCloseable.close()` — filed under Phase 6 (Resiliency layer) as #106.
- Per-event-handler shutdown overrides.
- Multiple executor pools (the pool management knobs are #110).
- Configuring `shutdownTimeout` from YAML — `TikoOptions` is programmatic-only today.
- JVM `Error` is documented as a caveat, not mitigated.
EOF
)"
```

- [ ] **Step 7: Watch CI**

```
"C:/Program Files/GitHub CLI/gh.exe" pr checks --watch
```

Expected: all checks pass. If Spotless fails, run `mvn -pl '!tiko-bom' spotless:apply` locally, commit, push.

- [ ] **Step 8: Hand off for manual merge**

Per project policy (branch protection), the user merges in the GitHub UI. After confirmation:

```
git checkout main
git pull --ff-only
git branch -d feat/event-executor-shutdown-timeout
git fetch --prune origin
```

---

## Done

`TikoOptions.shutdownTimeout(Duration)` ships. The `09_http_javalin` example now visibly demonstrates the graceful drain behaviour the knob controls. Issue #48 closes. Phase 2 milestone has one open issue remaining: #74 (java.lang.System.Logger refactor).
