# Local events stabilization — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement issues #43 and #44 across two sequential PRs: (PR 1) sync handler-exception isolation in `LocalEventBus` with a configurable `ErrorHandler`; (PR 2) honoured `@EventHandler(async = true)` running on a bounded, configurable executor with the same error hook.

**Architecture:** New public types in `tiko-api` (`TikoOptions`, `ErrorHandler`, sealed `ErrorContext`, `EventHandlerError`, `EventHandlerInfo`). `LocalEventBus.publish()` gains per-callback try/catch with slf4j WARN for programmatic subscribers. Generated dispatchers in `EventRegistryGenerator` catch handler throws and route to `container.getErrorHandler()` with rich `EventHandlerInfo`. PR 2 adds a container-owned bounded `ThreadPoolExecutor` (default) or user-supplied `ExecutorService`, retires the `EventChainContext.ASYNC_EXECUTOR` static, and routes `@EventHandler(async)` and `@EventTrigger(async)` failures via `CompletableFuture.whenComplete` — no silent swallow.

**Tech Stack:** Java 17+, Maven multi-module, JavaPoet for codegen, JUnit 5 + AssertJ + Mockito for tests, `com.google.testing.compile` for processor tests, slf4j 2.x for logging.

**Spec:** `docs/superpowers/specs/2026-05-07-local-events-stabilization-design.md`

**Prerequisite:** #47 (`Container.shutdown()` idempotency) must merge to `main` before PR 1 starts. PR 1 tests for `ApplicationEndingEvent`-handler-exception tolerance depend on #47's hardening.

**Maven invocation:** `mvn` lives at `W:\tools\apache-maven\bin\mvn.cmd` (not on PATH in spawned shells). Either prepend the full path or ensure PATH is set before running tasks. All `mvn` references below assume the binary is callable.

---

## Phase 1 — PR 1 (#44): Sync error isolation

### Task 1: Add `EventHandlerInfo` record

**Files:**
- Create: `tiko-api/src/main/java/io/tiko/EventHandlerInfo.java`

- [ ] **Step 1: Write the file**

```java
package io.tiko;

/**
 * Identifies an {@code @EventHandler} method for diagnostic purposes — used inside
 * {@link EventHandlerError} to tell error-handling code which handler threw, without
 * exposing reflection types.
 *
 * <p>Populated at compile time by the annotation processor; framework code does not
 * read these fields, so they remain accurate even if reflection is later disabled.
 *
 * @param declaringClass class declaring the {@code @EventHandler} method
 * @param methodName     simple method name (no descriptor, no class prefix)
 * @param eventType      class of the event the handler subscribes to
 * @param async          whether the handler was declared {@code @EventHandler(async = true)}
 */
public record EventHandlerInfo(
    Class<?> declaringClass,
    String methodName,
    Class<?> eventType,
    boolean async
) {}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn -pl tiko-api compile -q`
Expected: BUILD SUCCESS, no warnings.

- [ ] **Step 3: Commit**

```
git add tiko-api/src/main/java/io/tiko/EventHandlerInfo.java
git commit -m "feat(api): add EventHandlerInfo record"
```

---

### Task 2: Add `ErrorContext` sealed interface and `EventHandlerError` record

**Files:**
- Create: `tiko-api/src/main/java/io/tiko/ErrorContext.java`
- Create: `tiko-api/src/main/java/io/tiko/EventHandlerError.java`

- [ ] **Step 1: Create `ErrorContext`**

```java
package io.tiko;

/**
 * Sealed root of all error categories surfaced through {@link ErrorHandler}.
 * Pattern-match on the concrete subtype to handle each category structurally:
 *
 * <pre>{@code
 * public void onError(ErrorContext ctx) {
 *     switch (ctx) {
 *         case EventHandlerError e -> metrics.eventHandlerError(e.handler());
 *     }
 * }
 * }</pre>
 *
 * <p>Only {@link EventHandlerError} is permitted in this release. Future framework-error
 * categories (lifecycle, configuration, scope) will add new permits in follow-up PRs.
 * Adding a permit is intentionally a compile-time-loud breaking change for users with
 * exhaustive {@code switch} expressions — when a new category appears, callers are told
 * to handle it.
 */
public sealed interface ErrorContext permits EventHandlerError {

    /**
     * The throwable that caused this error context to be raised.
     */
    Throwable cause();
}
```

- [ ] **Step 2: Create `EventHandlerError`**

```java
package io.tiko;

/**
 * Error context raised when an {@code @EventHandler} method throws — sync or async.
 *
 * @param handler identifies which handler method threw
 * @param event   the event instance the handler was processing
 * @param cause   the throwable thrown by the handler ({@code CompletionException}
 *                already unwrapped to the user's original throwable)
 */
public record EventHandlerError(
    EventHandlerInfo handler,
    Object event,
    Throwable cause
) implements ErrorContext {}
```

- [ ] **Step 3: Verify**

Run: `mvn -pl tiko-api compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```
git add tiko-api/src/main/java/io/tiko/ErrorContext.java tiko-api/src/main/java/io/tiko/EventHandlerError.java
git commit -m "feat(api): add sealed ErrorContext with EventHandlerError"
```

---

### Task 3: Add `ErrorHandler` interface

**Files:**
- Create: `tiko-api/src/main/java/io/tiko/ErrorHandler.java`

- [ ] **Step 1: Create the file**

```java
package io.tiko;

/**
 * Hook for observing exceptions raised inside the framework — handler throws,
 * async dispatch failures, and (future) lifecycle / config / scope errors.
 *
 * <p><strong>Contract:</strong>
 * <ul>
 *   <li>Invoked synchronously on whichever thread surfaced the error (publisher thread
 *       for sync handlers; executor thread for async handlers). The framework does not
 *       hop threads to invoke this hook.</li>
 *   <li>The return type is {@code void} on purpose — implementations cannot influence
 *       dispatch flow. This hook is for logs, metrics, and alerts. To branch on
 *       handler outcomes, use {@code @EventTrigger} with an {@code EventTriggerGuard};
 *       do not throw exceptions from event handlers as a control-flow signal.</li>
 *   <li>Implementations should be fast and non-throwing. An exception thrown <em>from</em>
 *       {@code onError} is caught by the framework, logged at ERROR via slf4j, and
 *       suppressed — preventing handler-of-handler recursion.</li>
 * </ul>
 *
 * <p>The default implementation logs at WARN via slf4j. Override via
 * {@code TikoOptions.errorHandler(...)}.
 */
@FunctionalInterface
public interface ErrorHandler {
    void onError(ErrorContext context);
}
```

- [ ] **Step 2: Verify**

Run: `mvn -pl tiko-api compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```
git add tiko-api/src/main/java/io/tiko/ErrorHandler.java
git commit -m "feat(api): add ErrorHandler interface"
```

---

### Task 4: Add `TikoOptions` (PR 1 surface only — `configSource` + `errorHandler`)

**Files:**
- Create: `tiko-api/src/main/java/io/tiko/TikoOptions.java`
- Create: `tiko-api/src/test/java/io/tiko/TikoOptionsTest.java`

- [ ] **Step 1: Add test dependencies to `tiko-api/pom.xml`**

`tiko-api/pom.xml` currently has no test dependencies. Add JUnit Jupiter and AssertJ. After the closing `</dependencies>` of any existing block, ensure there is a `<dependencies>` section that contains:

```xml
<dependencies>
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.assertj</groupId>
        <artifactId>assertj-core</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

If `tiko-api/pom.xml` already has a `<dependencies>` block, append these two entries inside it instead.

- [ ] **Step 2: Write the failing test**

`tiko-api/src/test/java/io/tiko/TikoOptionsTest.java`:

```java
package io.tiko;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class TikoOptionsTest {

    @Test
    void builder_default_has_no_config_source_and_no_error_handler() {
        TikoOptions options = TikoOptions.builder().build();

        assertThat(options.configSource()).isNull();
        assertThat(options.errorHandler()).isNull();
    }

    @Test
    void builder_round_trips_error_handler() {
        ErrorHandler handler = ctx -> {};

        TikoOptions options = TikoOptions.builder()
            .errorHandler(handler)
            .build();

        assertThat(options.errorHandler()).isSameAs(handler);
    }

    @Test
    void builder_rejects_null_error_handler() {
        TikoOptions.Builder b = TikoOptions.builder();
        assertThatNullPointerException().isThrownBy(() -> b.errorHandler(null));
    }

    @Test
    void builder_rejects_null_config_source() {
        TikoOptions.Builder b = TikoOptions.builder();
        assertThatNullPointerException().isThrownBy(() -> b.configSource(null));
    }
}
```

- [ ] **Step 3: Run the test to verify it fails to compile**

Run: `mvn -pl tiko-api test -q`
Expected: COMPILATION ERROR — `TikoOptions` does not exist.

- [ ] **Step 4: Implement `TikoOptions`**

`tiko-api/src/main/java/io/tiko/TikoOptions.java`:

```java
package io.tiko;

import java.util.Objects;

/**
 * Configuration knobs for {@link Tiko#create(TikoOptions)}.
 *
 * <p>Use {@link #builder()} to construct an instance. The result is immutable.
 *
 * <pre>{@code
 * TikoOptions opts = TikoOptions.builder()
 *     .configSource(ConfigSources.classpath("config.yaml"))
 *     .errorHandler(ctx -> myMetrics.recordErrorContext(ctx))
 *     .build();
 * try (Container container = Tiko.create(opts)) { ... }
 * }</pre>
 *
 * <p>All knobs are optional. When omitted, the framework supplies sensible defaults:
 * configuration binding is skipped (and fails fast at startup if any
 * {@code @Configuration} record is declared); the default error handler logs at WARN
 * via slf4j.
 */
public final class TikoOptions {

    private final ConfigSource configSource;
    private final ErrorHandler errorHandler;

    private TikoOptions(Builder b) {
        this.configSource = b.configSource;
        this.errorHandler = b.errorHandler;
    }

    /**
     * @return the configured {@link ConfigSource}, or {@code null} if none was set
     */
    public ConfigSource configSource() {
        return configSource;
    }

    /**
     * @return the configured {@link ErrorHandler}, or {@code null} to use the framework default
     */
    public ErrorHandler errorHandler() {
        return errorHandler;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private ConfigSource configSource;
        private ErrorHandler errorHandler;

        private Builder() {}

        public Builder configSource(ConfigSource source) {
            this.configSource = Objects.requireNonNull(source, "configSource");
            return this;
        }

        public Builder errorHandler(ErrorHandler handler) {
            this.errorHandler = Objects.requireNonNull(handler, "errorHandler");
            return this;
        }

        public TikoOptions build() {
            return new TikoOptions(this);
        }
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `mvn -pl tiko-api test -q`
Expected: BUILD SUCCESS, 4 tests passed.

- [ ] **Step 6: Commit**

```
git add tiko-api/pom.xml tiko-api/src/main/java/io/tiko/TikoOptions.java tiko-api/src/test/java/io/tiko/TikoOptionsTest.java
git commit -m "feat(api): add TikoOptions with builder"
```

---

### Task 5: Add slf4j-simple test dependency to `tiko-event-local`

The bus's defense-in-depth WARN logs need to be observable in tests. `tiko-event-local` has slf4j-api as `provided` and no test binding. Add `slf4j-simple` at test scope and configure the simple logger to write to stderr (its default).

**Files:**
- Modify: `tiko-event-local/pom.xml`
- Create: `tiko-event-local/src/test/resources/simplelogger.properties`

- [ ] **Step 1: Add test dependency**

In `tiko-event-local/pom.xml`, inside the `<dependencies>` block, after the existing `<scope>test</scope>` JUnit Jupiter entry, append:

```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-simple</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.assertj</groupId>
    <artifactId>assertj-core</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Configure simplelogger to log WARN+ to stderr**

`tiko-event-local/src/test/resources/simplelogger.properties`:

```properties
org.slf4j.simpleLogger.defaultLogLevel=warn
org.slf4j.simpleLogger.logFile=System.err
org.slf4j.simpleLogger.showDateTime=false
org.slf4j.simpleLogger.showThreadName=false
org.slf4j.simpleLogger.showLogName=true
```

- [ ] **Step 3: Smoke-compile**

Run: `mvn -pl tiko-event-local test-compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```
git add tiko-event-local/pom.xml tiko-event-local/src/test/resources/simplelogger.properties
git commit -m "build(event-local): add slf4j-simple at test scope for log assertions"
```

---

### Task 6: Add `Slf4jWarnErrorHandler` (default) and unit-test it

**Files:**
- Create: `tiko-event-local/src/main/java/io/tiko/event/local/Slf4jWarnErrorHandler.java`
- Create: `tiko-event-local/src/test/java/io/tiko/event/local/Slf4jWarnErrorHandlerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.tiko.event.local;

import io.tiko.ErrorContext;
import io.tiko.ErrorHandler;
import io.tiko.EventHandlerError;
import io.tiko.EventHandlerInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

class Slf4jWarnErrorHandlerTest {

    private final ByteArrayOutputStream errCapture = new ByteArrayOutputStream();
    private PrintStream originalErr;

    @BeforeEach
    void redirectStderr() {
        originalErr = System.err;
        System.setErr(new PrintStream(errCapture, true));
    }

    @AfterEach
    void restoreStderr() {
        System.setErr(originalErr);
    }

    @Test
    void logs_warn_with_class_method_event_type_and_message() {
        ErrorHandler handler = new Slf4jWarnErrorHandler();
        EventHandlerInfo info = new EventHandlerInfo(
            FakeService.class, "onSomething", FakeEvent.class, false);
        ErrorContext ctx = new EventHandlerError(info, new FakeEvent(), new IllegalStateException("boom"));

        handler.onError(ctx);

        String output = errCapture.toString();
        assertThat(output).contains("WARN");
        assertThat(output).contains("FakeService");
        assertThat(output).contains("onSomething");
        assertThat(output).contains("FakeEvent");
        assertThat(output).contains("boom");
    }

    static class FakeService {}
    record FakeEvent() {}
}
```

- [ ] **Step 2: Run test to verify it fails to compile**

Run: `mvn -pl tiko-event-local test -q`
Expected: COMPILATION ERROR — `Slf4jWarnErrorHandler` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package io.tiko.event.local;

import io.tiko.ErrorContext;
import io.tiko.ErrorHandler;
import io.tiko.EventHandlerError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link ErrorHandler} implementation. Logs each {@link ErrorContext} at WARN
 * via slf4j. Used by {@code Tiko.create(...)} when the user did not supply a custom
 * handler via {@code TikoOptions.errorHandler(...)}.
 */
final class Slf4jWarnErrorHandler implements ErrorHandler {

    private static final Logger LOG = LoggerFactory.getLogger("io.tiko.events");

    @Override
    public void onError(ErrorContext context) {
        if (context instanceof EventHandlerError e) {
            LOG.warn("EventHandler {}#{} on event {} threw: {}",
                e.handler().declaringClass().getName(),
                e.handler().methodName(),
                e.handler().eventType().getName(),
                e.cause().toString(),
                e.cause());
        } else {
            // Forward-compatible: future ErrorContext subtypes log a generic message
            // until this default handler is updated to match the new permits.
            LOG.warn("Framework error: {}: {}", context.getClass().getSimpleName(), context.cause().toString(), context.cause());
        }
    }
}
```

- [ ] **Step 4: Run the test**

Run: `mvn -pl tiko-event-local test -Dtest=Slf4jWarnErrorHandlerTest -q`
Expected: BUILD SUCCESS, 1 test passed.

- [ ] **Step 5: Commit**

```
git add tiko-event-local/src/main/java/io/tiko/event/local/Slf4jWarnErrorHandler.java tiko-event-local/src/test/java/io/tiko/event/local/Slf4jWarnErrorHandlerTest.java
git commit -m "feat(event-local): add Slf4jWarnErrorHandler default"
```

---

### Task 7: Isolate handler exceptions in `LocalEventBus.publish()`

**Files:**
- Modify: `tiko-event-local/src/main/java/io/tiko/event/local/LocalEventBus.java`
- Create: `tiko-event-local/src/test/java/io/tiko/event/local/LocalEventBusErrorIsolationTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.tiko.event.local;

import io.tiko.EventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class LocalEventBusErrorIsolationTest {

    private final ByteArrayOutputStream errCapture = new ByteArrayOutputStream();
    private PrintStream originalErr;

    @BeforeEach
    void redirectStderr() {
        originalErr = System.err;
        System.setErr(new PrintStream(errCapture, true));
    }

    @AfterEach
    void restoreStderr() {
        System.setErr(originalErr);
    }

    @Test
    void throwing_handler_does_not_abort_subsequent_handlers() {
        EventBus bus = new LocalEventBus();
        AtomicInteger secondHandlerInvocations = new AtomicInteger();

        bus.subscribe(String.class, e -> { throw new IllegalStateException("first"); });
        bus.subscribe(String.class, e -> secondHandlerInvocations.incrementAndGet());

        bus.publish("hello");

        assertThat(secondHandlerInvocations).hasValue(1);
    }

    @Test
    void throwing_handler_does_not_propagate_to_publisher() {
        EventBus bus = new LocalEventBus();
        bus.subscribe(String.class, e -> { throw new IllegalStateException("kaboom"); });

        assertThatCode(() -> bus.publish("hello")).doesNotThrowAnyException();
    }

    @Test
    void throwing_programmatic_subscriber_logs_warn_with_event_type() {
        EventBus bus = new LocalEventBus();
        bus.subscribe(String.class, e -> { throw new IllegalStateException("logged"); });

        bus.publish("hello");

        String output = errCapture.toString();
        assertThat(output).contains("WARN");
        assertThat(output).contains("Programmatic event callback threw");
        assertThat(output).contains("java.lang.String");
        assertThat(output).contains("logged");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl tiko-event-local test -Dtest=LocalEventBusErrorIsolationTest -q`
Expected: TWO ASSERTION FAILURES — `throwing_handler_does_not_abort_subsequent_handlers` (second handler not invoked because the loop aborts) and `throwing_handler_does_not_propagate_to_publisher` (exception propagates). The third may pass-by-accident if logging happens to write somewhere visible — that's fine.

- [ ] **Step 3: Modify `LocalEventBus.publish()`**

Replace `tiko-event-local/src/main/java/io/tiko/event/local/LocalEventBus.java` lines 1-72 with:

```java
package io.tiko.event.local;

import io.tiko.EventBus;
import io.tiko.EventCallback;
import io.tiko.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simple in-memory event bus implementation.
 * <p>
 * Thread-safe and synchronous. Per-callback exceptions are isolated: a throw from one
 * subscriber does not prevent subsequent subscribers from running, and does not propagate
 * to the publisher.
 *
 * <p>Subscribers registered via {@code @EventHandler} (i.e. through the generated
 * {@code EventRegistry}) have their exceptions reported through the configured
 * {@code ErrorHandler} with a rich {@code EventHandlerError}. Subscribers registered
 * programmatically (via {@link #subscribe(Class, EventCallback)} from user code) are
 * not associated with any compile-time identity, so the bus logs their exceptions at
 * WARN via slf4j as a defense-in-depth net.
 */
public final class LocalEventBus implements EventBus {

    private static final Logger LOG = LoggerFactory.getLogger(LocalEventBus.class);

    private final Map<Class<?>, List<EventCallback<?>>> handlers = new ConcurrentHashMap<>();

    @Override
    public <T> void publish(T event) {
        if (event == null) {
            return;
        }

        Class<?> eventType = event.getClass();
        List<EventCallback<?>> callbacks = handlers.get(eventType);

        if (callbacks == null) {
            return;
        }

        for (EventCallback<?> callback : callbacks) {
            @SuppressWarnings("unchecked")
            EventCallback<T> typedCallback = (EventCallback<T>) callback;
            try {
                typedCallback.handle(event);
            } catch (Exception e) {
                // Defense-in-depth: the generated dispatcher already catches and reports
                // its own throws via the ErrorHandler with a rich EventHandlerInfo. This
                // branch fires only for programmatic EventCallback subscribers (no
                // @EventHandler, no compile-time identity). Log at WARN — Errors (OOM,
                // StackOverflow) are deliberately not caught here; those mean the JVM
                // is sick and surfacing them is the right move.
                LOG.warn("Programmatic event callback threw on event {}: {}",
                    eventType.getName(), e.toString(), e);
            }
        }
    }

    @Override
    public <T> Subscription subscribe(Class<T> eventType, EventCallback<T> callback) {
        List<EventCallback<?>> callbacks = handlers.computeIfAbsent(
            eventType,
            k -> new CopyOnWriteArrayList<>()
        );
        callbacks.add(callback);

        return new LocalSubscription<>(callbacks, callback);
    }

    private static final class LocalSubscription<T> implements Subscription {
        private final List<EventCallback<?>> callbacks;
        private final EventCallback<T> callback;
        private final AtomicBoolean active = new AtomicBoolean(true);

        LocalSubscription(List<EventCallback<?>> callbacks, EventCallback<T> callback) {
            this.callbacks = callbacks;
            this.callback = callback;
        }

        @Override
        public void unsubscribe() {
            if (active.compareAndSet(true, false)) {
                callbacks.remove(callback);
            }
        }

        @Override
        public boolean isActive() {
            return active.get();
        }
    }
}
```

- [ ] **Step 4: Run all `tiko-event-local` tests**

Run: `mvn -pl tiko-event-local test -q`
Expected: BUILD SUCCESS, all tests pass (including any pre-existing tests).

- [ ] **Step 5: Commit**

```
git add tiko-event-local/src/main/java/io/tiko/event/local/LocalEventBus.java tiko-event-local/src/test/java/io/tiko/event/local/LocalEventBusErrorIsolationTest.java
git commit -m "fix(event-local): isolate handler exceptions in publish (#44)"
```

---

### Task 8: Generate `getErrorHandler()` accessor in `ContainerGenerator` (no error-handler wiring yet)

The container needs an `ErrorHandler` field and accessor for the generated dispatcher to read. Wire it through the constructor: existing constructor takes `EventBus`; new constructor takes `EventBus, ErrorHandler`. The single-arg constructor is removed (call sites are updated in Task 11). The accessor is package-private — internal API.

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java`
- Modify: `tiko-processor/src/test/java/io/tiko/processor/...` (new test, see below)
- Create: `tiko-processor/src/test/java/io/tiko/processor/ContainerGeneratorErrorHandlerTest.java`

- [ ] **Step 1: Read the current `ContainerGenerator` to find the constructor-generation method**

Run: `grep -n "createConstructor\|MethodSpec.constructorBuilder\|EventBus.class.*eventBus" tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java | head -40`
Expected: shows the method (around the existing `createConstructor` or similar) that builds the constructor with `EventBus eventBus`. Also shows the field declaration for `eventBus`.

Note the line numbers — you'll insert similar `errorHandler` field+param.

- [ ] **Step 2: Write the failing test**

```java
package io.tiko.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

class ContainerGeneratorErrorHandlerTest {

    @Test
    void generated_container_has_error_handler_field_and_accessor() throws IOException {
        JavaFileObject src = JavaFileObjects.forSourceLines(
            "io.example.MyService",
            "package io.example;",
            "import io.tiko.annotations.Component;",
            "import io.tiko.annotations.Scope;",
            "@Component(scope = Scope.SINGLETON)",
            "public class MyService { public MyService() {} }"
        );
        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        JavaFileObject container = c.generatedSourceFiles().stream()
            .filter(f -> f.getName().contains("TikoContainerImpl"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("TikoContainerImpl not generated"));

        String content = new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content).contains("private final io.tiko.ErrorHandler errorHandler");
        assertThat(content).contains("io.tiko.ErrorHandler getErrorHandler()");
        assertThat(content).contains("public TikoContainerImpl(io.tiko.EventBus eventBus, io.tiko.ErrorHandler errorHandler)");
        assertThat(content).contains("this.errorHandler = errorHandler");
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -pl tiko-processor test -Dtest=ContainerGeneratorErrorHandlerTest -q`
Expected: ASSERTION FAILURE — generated container does not contain `errorHandler` field/accessor/constructor param.

- [ ] **Step 4: Modify `ContainerGenerator` to add the field, accessor, and constructor parameter**

In `ContainerGenerator.java`:

1. Locate the existing `eventBus` field declaration (look for `FieldSpec.builder(...EventBus.class..., "eventBus"...)` or similar). Immediately after, add an analogous field for `errorHandler`:

```java
FieldSpec errorHandlerField = FieldSpec.builder(
        ClassName.get("io.tiko", "ErrorHandler"),
        "errorHandler",
        Modifier.PRIVATE, Modifier.FINAL)
    .build();
```

Add `errorHandlerField` to the type spec wherever `eventBus` field is added (look for `.addField(...)` calls referencing eventBus).

2. Locate the constructor builder (search for `MethodSpec.constructorBuilder()` and the addition of `eventBus` parameter). Modify it to also add an `errorHandler` parameter and assignment:

```java
constructorBuilder.addParameter(ClassName.get(EventBus.class), "eventBus");
constructorBuilder.addParameter(ClassName.get("io.tiko", "ErrorHandler"), "errorHandler");
// ... existing assignment for this.eventBus ...
constructorBuilder.addStatement("this.errorHandler = errorHandler");
```

3. Add a getter method (placed alongside any existing `getEventBus()`):

```java
MethodSpec getErrorHandlerMethod = MethodSpec.methodBuilder("getErrorHandler")
    .addModifiers(Modifier.PUBLIC)
    .returns(ClassName.get("io.tiko", "ErrorHandler"))
    .addStatement("return this.errorHandler")
    .build();
```

Add `getErrorHandlerMethod` to the type spec. (Public for now — generated dispatcher accessibility is simpler if it's public; the `Container` interface itself does not expose this method, so users don't see it through the interface.)

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -pl tiko-processor test -Dtest=ContainerGeneratorErrorHandlerTest -q`
Expected: BUILD SUCCESS, 1 test passed.

- [ ] **Step 6: Verify other processor tests still compile (the new constructor signature breaks call sites)**

Run: `mvn -pl tiko-processor test -q`
Expected: SOME PRE-EXISTING TESTS FAIL — any test that compiles a generated container and instantiates it via reflection with the old `(EventBus)` constructor will fail. Note these failures; they will be fixed in Task 11 once `Tiko.create()` passes the error handler through.

If pre-existing tests instantiate the container directly in their assertions, you may need a temporary stub: pass `null` for `errorHandler` until Task 11 wires it. Look for compilation/instantiation patterns in failing tests and adjust.

- [ ] **Step 7: Commit**

```
git add tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java tiko-processor/src/test/java/io/tiko/processor/ContainerGeneratorErrorHandlerTest.java
git commit -m "feat(processor): generate ErrorHandler field and accessor on container"
```

---

### Task 9: Emit `HANDLER_INFO_<n>` constants and try/catch dispatcher in `EventRegistryGenerator`

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/EventRegistryGenerator.java`
- Create: `tiko-processor/src/test/java/io/tiko/processor/EventRegistryDispatcherTryCatchTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.tiko.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

class EventRegistryDispatcherTryCatchTest {

    @Test
    void generated_dispatcher_wraps_handler_in_try_catch_routing_to_error_handler() throws IOException {
        JavaFileObject component = JavaFileObjects.forSourceLines(
            "io.example.MyHandler",
            "package io.example;",
            "import io.tiko.annotations.Component;",
            "import io.tiko.annotations.EventHandler;",
            "import io.tiko.annotations.Scope;",
            "@Component(scope = Scope.SINGLETON)",
            "public class MyHandler {",
            "    @EventHandler",
            "    public void onPing(Ping event) {}",
            "}"
        );
        JavaFileObject event = JavaFileObjects.forSourceLines(
            "io.example.Ping",
            "package io.example;",
            "public record Ping() {}"
        );

        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(component, event);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        JavaFileObject registry = c.generatedSourceFiles().stream()
            .filter(f -> f.getName().contains("EventRegistry"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("EventRegistry not generated"));

        String content = new String(registry.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // HANDLER_INFO constant
        assertThat(content).contains("private static final io.tiko.EventHandlerInfo HANDLER_INFO_0");
        assertThat(content).contains("new io.tiko.EventHandlerInfo(io.example.MyHandler.class, \"onPing\", io.example.Ping.class, false)");

        // Try/catch routing to ErrorHandler
        assertThat(content).contains("__handler.onPing(event)");
        assertThat(content).contains("} catch (java.lang.Exception ");
        assertThat(content).contains("container.getErrorHandler()");
        assertThat(content).contains("new io.tiko.EventHandlerError(HANDLER_INFO_0, event,");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl tiko-processor test -Dtest=EventRegistryDispatcherTryCatchTest -q`
Expected: ASSERTION FAILURE — none of the new strings present in generated `EventRegistry`.

- [ ] **Step 3: Modify `EventRegistryGenerator` — add HANDLER_INFO field generation**

In `EventRegistryGenerator.generate()`, before the `for` loop that emits dispatcher methods (currently around line 59), add a loop that emits the static constants. Replace the existing emit-dispatcher loop with this expanded version:

```java
ClassName eventHandlerInfo = ClassName.get("io.tiko", "EventHandlerInfo");

// Emit one HANDLER_INFO_<n> static constant per handler
for (int i = 0; i < eventHandlers.size(); i++) {
    EventHandlerModel handler = eventHandlers.get(i);
    ClassName declaring = ClassName.bestGuess(handler.getDeclaringClass().getQualifiedName().toString());
    ClassName eventClass = ClassName.bestGuess(handler.getEventTypeName());
    boolean async = handler.isAsync();  // false for now in PR 1; PR 2 surfaces real value

    FieldSpec info = FieldSpec.builder(eventHandlerInfo, "HANDLER_INFO_" + i,
            Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
        .initializer("new $T($T.class, $S, $T.class, $L)", eventHandlerInfo, declaring,
            handler.getMethodName(), eventClass, async)
        .build();
    registry.addField(info);
}

// Emit one private helper per handler (existing behaviour)
for (int i = 0; i < eventHandlers.size(); i++) {
    registry.addMethod(createDispatcherMethod(eventHandlers.get(i), i));
}
```

If `EventHandlerModel` does not currently expose `isAsync()` as a public method, add a getter that returns `this.async`. Find the model class at `tiko-processor/src/main/java/io/tiko/processor/model/EventHandlerModel.java` and verify; it does carry the `async` field per the spec context. If the getter is missing, add it.

- [ ] **Step 4: Modify `EventRegistryGenerator.createDispatcherMethod` to wrap handler invocation in try/catch**

Find the existing method (around line 97-150). Inside the outer `try { ... }` (the chain-context bookkeeping block), the handler invocation `__handler.onPing(event)` (or whatever it was) is currently a single statement. Wrap it in an inner try/catch that routes to the ErrorHandler.

Replace the section that currently looks like:

```java
String invocation = handler.hasEventWrapper()
        ? "__handler." + handler.getMethodName() + "(event, __wrapper)"
        : "__handler." + handler.getMethodName() + "(event)";

if (captureResult) {
    method.addStatement("$T __result = $L", TypeName.get(returnType), invocation);
} else {
    method.addStatement(invocation);
}

if (hasTriggers && !returnsValue) {
    context.getMessager().printMessage(
            Diagnostic.Kind.WARNING,
            "@EventTrigger on a void-returning @EventHandler has no payload to publish — ignored",
            handler.getMethodElement());
}

if (captureResult) {
    for (EventTriggerModel trigger : handler.getEventTriggers()) {
        emitTrigger(method, trigger);
    }
}
```

with:

```java
String invocation = handler.hasEventWrapper()
        ? "__handler." + handler.getMethodName() + "(event, __wrapper)"
        : "__handler." + handler.getMethodName() + "(event)";

if (hasTriggers && !returnsValue) {
    context.getMessager().printMessage(
            Diagnostic.Kind.WARNING,
            "@EventTrigger on a void-returning @EventHandler has no payload to publish — ignored",
            handler.getMethodElement());
}

ClassName errorHandler = ClassName.get("io.tiko", "ErrorHandler");
ClassName eventHandlerError = ClassName.get("io.tiko", "EventHandlerError");
ClassName loggerFactory = ClassName.get("org.slf4j", "LoggerFactory");

method.beginControlFlow("try");
if (captureResult) {
    method.addStatement("$T __result = $L", TypeName.get(returnType), invocation);
} else {
    method.addStatement(invocation);
}

if (captureResult) {
    for (EventTriggerModel trigger : handler.getEventTriggers()) {
        emitTrigger(method, trigger);
    }
}

method.nextControlFlow("catch ($T __t)", Exception.class);
method.addStatement("$T __err = container.getErrorHandler()", errorHandler);
method.beginControlFlow("try");
method.addStatement("__err.onError(new $T(HANDLER_INFO_$L, event, __t))", eventHandlerError, index);
method.nextControlFlow("catch ($T __inner)", Exception.class);
method.addStatement("$T.getLogger($S).error($S, __inner)",
    loggerFactory, "io.tiko.events", "ErrorHandler.onError threw");
method.endControlFlow();
method.endControlFlow();
```

The `index` parameter was already in `createDispatcherMethod`'s signature.

- [ ] **Step 5: Run the test**

Run: `mvn -pl tiko-processor test -Dtest=EventRegistryDispatcherTryCatchTest -q`
Expected: BUILD SUCCESS, 1 test passed.

- [ ] **Step 6: Run full `tiko-processor` tests**

Run: `mvn -pl tiko-processor test -q`
Expected: BUILD SUCCESS. Pre-existing tests should pass — the dispatcher's outward behaviour (chain-context bookkeeping + handler call + trigger emit) is unchanged when no exception is thrown.

- [ ] **Step 7: Commit**

```
git add tiko-processor/src/main/java/io/tiko/processor/generator/EventRegistryGenerator.java tiko-processor/src/main/java/io/tiko/processor/model/EventHandlerModel.java tiko-processor/src/test/java/io/tiko/processor/EventRegistryDispatcherTryCatchTest.java
git commit -m "feat(processor): emit HANDLER_INFO and try/catch in dispatcher (#44)"
```

(If `EventHandlerModel.java` was not modified, drop it from the `git add` line.)

---

### Task 10: Wire `ErrorHandler` through `Tiko.create(TikoOptions)`

**Files:**
- Modify: `tiko-api/src/main/java/io/tiko/Tiko.java`

- [ ] **Step 1: Read the current `Tiko.java` `createSingleModuleContainer` and `createInternal` methods**

Run: `grep -n "createInternal\|createSingleModuleContainer\|registerEventHandlers\|getDeclaredConstructor" tiko-api/src/main/java/io/tiko/Tiko.java`
Expected: shows the current reflective construction pattern.

- [ ] **Step 2: Modify `Tiko.java`**

The existing public methods are `Tiko.create()` and `Tiko.create(ConfigSource)`. Add `Tiko.create(TikoOptions)` and refactor the existing ones to delegate.

Replace the existing `create()` and `create(ConfigSource)` methods with:

```java
/**
 * Creates a container with all-default options.
 *
 * <p>Equivalent to {@code Tiko.create(TikoOptions.builder().build())}.
 */
public static Container create() {
    return create(TikoOptions.builder().build());
}

/**
 * Creates a container with the given configuration source. Equivalent to
 * {@code Tiko.create(TikoOptions.builder().configSource(source).build())}.
 *
 * @param source the configuration source, never {@code null}
 */
public static Container create(ConfigSource source) {
    return create(TikoOptions.builder()
        .configSource(java.util.Objects.requireNonNull(source, "source"))
        .build());
}

/**
 * Creates a container with the supplied options.
 *
 * @param options framework knobs (config source, error handler, ...). Never {@code null}.
 */
public static Container create(TikoOptions options) {
    java.util.Objects.requireNonNull(options, "options");
    if (options.configSource() == null) {
        failIfConfigsMissingSource();
    }
    return createInternal(options);
}
```

Modify `createInternal` to take `TikoOptions` instead of `ConfigSource`:

```java
private static Container createInternal(TikoOptions options) {
    try {
        // 1. Resolve the ErrorHandler — user-supplied or the default Slf4jWarnErrorHandler.
        ErrorHandler errorHandler = options.errorHandler();
        if (errorHandler == null) {
            errorHandler = resolveDefaultErrorHandler();
        }

        // 2. Create EventBus instance (still no-arg; the bus does not take ErrorHandler).
        Class<?> eventBusClass = Class.forName("io.tiko.event.local.LocalEventBus");
        EventBus eventBus = (EventBus) eventBusClass.getDeclaredConstructor().newInstance();

        // 3. Detect single vs multi-module scenario
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) classLoader = Tiko.class.getClassLoader();

        var resources = classLoader.getResources("META-INF/tiko/container.properties");
        int moduleCount = 0;
        while (resources.hasMoreElements()) { resources.nextElement(); moduleCount++; }

        Container container;
        if (moduleCount > 1) {
            // Multi-module: AggregatingContainer — its constructor will need the ErrorHandler too;
            // for now pass eventBus and errorHandler reflectively if its signature was updated,
            // else fall back to the legacy 1-arg constructor.
            Class<?> aggregatingClass = Class.forName("io.tiko.runtime.AggregatingContainer");
            try {
                container = (Container) aggregatingClass
                    .getDeclaredConstructor(EventBus.class, ErrorHandler.class)
                    .newInstance(eventBus, errorHandler);
            } catch (NoSuchMethodException nsm) {
                container = (Container) aggregatingClass
                    .getDeclaredConstructor(EventBus.class)
                    .newInstance(eventBus);
            }
        } else {
            // Single module: Direct instantiation (does NOT call start yet)
            container = createSingleModuleContainer(eventBus, errorHandler);
        }

        // 4. Inject config singletons before start(), so @PostConstruct can use them
        if (options.configSource() != null) {
            java.util.Map<Class<?>, Object> bound = bindConfigs(options.configSource(), classLoader);
            container.getClass().getMethod("injectConfigs", java.util.Map.class).invoke(container, bound);
        }

        // 5. Start the container (initialize all SINGLETON components)
        if (moduleCount <= 1) {
            container.getClass().getMethod("start").invoke(container);
        }

        return container;
    } catch (RuntimeException e) {
        throw e;
    } catch (ClassNotFoundException e) {
        throw new IllegalStateException(
            "Tiko container implementation not found. Did you include tiko-processor in your annotation processor path?", e);
    } catch (Exception e) {
        throw new IllegalStateException("Failed to create container instance", e);
    }
}

/**
 * Reflectively builds an {@code Slf4jWarnErrorHandler} from {@code tiko-event-local}.
 * Kept reflective so {@code tiko-api} stays free of the slf4j dependency.
 */
private static ErrorHandler resolveDefaultErrorHandler() {
    try {
        Class<?> defaultClass = Class.forName("io.tiko.event.local.Slf4jWarnErrorHandler");
        java.lang.reflect.Constructor<?> ctor = defaultClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        return (ErrorHandler) ctor.newInstance();
    } catch (ClassNotFoundException e) {
        // Bus implementation not on the classpath — return a minimal no-op so we do not
        // crash users who have somehow excluded tiko-event-local. This will also be
        // surfaced when EventBus construction fails downstream.
        return ctx -> {};
    } catch (Exception e) {
        throw new IllegalStateException("Failed to construct default ErrorHandler", e);
    }
}
```

Modify `createSingleModuleContainer` to take and pass through `ErrorHandler`:

```java
private static Container createSingleModuleContainer(EventBus eventBus, ErrorHandler errorHandler) throws Exception {
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    if (classLoader == null) classLoader = Tiko.class.getClassLoader();

    var resources = classLoader.getResources("META-INF/tiko/container.properties");
    Class<?> implClass;
    if (resources.hasMoreElements()) {
        var props = new java.util.Properties();
        try (var input = resources.nextElement().openStream()) {
            props.load(input);
        }
        String implClassName = props.getProperty("impl");
        implClass = Class.forName(implClassName);
    } else {
        implClass = Class.forName("io.tiko.generated.TikoContainerImpl");
    }

    Container container = (Container) implClass
        .getDeclaredConstructor(EventBus.class, ErrorHandler.class)
        .newInstance(eventBus, errorHandler);

    registerEventHandlers(eventBus, container, implClass);
    return container;
}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn -pl tiko-api compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Run the full module tests up to and including a representative example**

Run: `mvn -pl tiko-api,tiko-processor,tiko-runtime,tiko-event-local,tiko-examples/01_basic_di test -q`

If `tiko-examples/01_basic_di` is not a directly-buildable submodule path, instead run the example modules' tests via reactor: `mvn -pl tiko-examples -am test -q`.

Expected: BUILD SUCCESS — `Tiko.create()` and `Tiko.create(ConfigSource)` continue to work; new generated containers are instantiated via the 2-arg constructor.

If a multi-module example fails because `AggregatingContainer` does not yet have the 2-arg constructor and the reflective fallback path catches the missing constructor, no further work is needed — that fallback path returns a container that ignores the ErrorHandler for multi-module setups. (Multi-module ErrorHandler wiring lands in a follow-up PR; out of scope here.)

- [ ] **Step 5: Commit**

```
git add tiko-api/src/main/java/io/tiko/Tiko.java
git commit -m "feat(api): add Tiko.create(TikoOptions) wiring ErrorHandler (#44)"
```

---

### Task 11: Integration test — full path with throwing handler routes to custom `ErrorHandler`

This is the round-trip test: declare an `@EventHandler` that throws, install a recording `ErrorHandler` via `TikoOptions`, publish, assert the handler captured the right `EventHandlerError`. Lives in `tiko-examples` since the example modules already pull together api + processor + runtime + event-local at the right scopes.

**Files:**
- Create: `tiko-examples/01_basic_di/src/test/java/io/tiko/examples/basic/EventErrorIsolationIntegrationTest.java` (assuming this example exists)

If `tiko-examples/01_basic_di` does not exist or does not currently host an integration test setup, create the test under whichever example does (the `CoreDiIntegrationTest` parent path I saw earlier was `tiko-examples/01_basic_di/src/test/java/io/tiko/examples/basic/`). If multiple examples are equivalent, prefer the simplest one.

- [ ] **Step 1: Write the test**

```java
package io.tiko.examples.basic;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.ErrorHandler;
import io.tiko.EventHandlerError;
import io.tiko.Tiko;
import io.tiko.TikoOptions;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.Scope;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class EventErrorIsolationIntegrationTest {

    record Ping() {}

    @Component(scope = Scope.SINGLETON)
    public static class ThrowingHandler {
        @EventHandler
        public void onPing(Ping event) {
            throw new IllegalStateException("integration boom");
        }
    }

    @Test
    void throwing_handler_routes_to_custom_error_handler() {
        AtomicReference<ErrorContext> captured = new AtomicReference<>();
        ErrorHandler recording = captured::set;

        TikoOptions opts = TikoOptions.builder().errorHandler(recording).build();
        try (Container container = Tiko.create(opts)) {
            assertThatCode(() -> container.getEventBus().publish(new Ping()))
                .doesNotThrowAnyException();
        }

        assertThat(captured.get()).isInstanceOf(EventHandlerError.class);
        EventHandlerError err = (EventHandlerError) captured.get();
        assertThat(err.handler().declaringClass()).isEqualTo(ThrowingHandler.class);
        assertThat(err.handler().methodName()).isEqualTo("onPing");
        assertThat(err.handler().eventType()).isEqualTo(Ping.class);
        assertThat(err.handler().async()).isFalse();
        assertThat(err.event()).isInstanceOf(Ping.class);
        assertThat(err.cause()).isInstanceOf(IllegalStateException.class);
        assertThat(err.cause()).hasMessage("integration boom");
    }
}
```

- [ ] **Step 2: Run the test**

Run (substitute the actual example path): `mvn -pl tiko-examples/01_basic_di test -Dtest=EventErrorIsolationIntegrationTest -q`
Expected: BUILD SUCCESS, 1 test passed.

If the example needs `mvn install` of the parents first, do `mvn -am install -DskipTests` for the parents, then re-run.

- [ ] **Step 3: Commit**

```
git add tiko-examples/01_basic_di/src/test/java/io/tiko/examples/basic/EventErrorIsolationIntegrationTest.java
git commit -m "test(examples): integration test for handler-error isolation"
```

---

### Task 12: Update `@EventHandler` Javadoc and add README error-handling section

**Files:**
- Modify: `tiko-api/src/main/java/io/tiko/annotations/EventHandler.java`
- Modify: `README.md`

- [ ] **Step 1: Replace the misleading Javadoc paragraph**

In `tiko-api/src/main/java/io/tiko/annotations/EventHandler.java`, find the paragraph at lines 66-69:

```java
 * <p><strong>Error Handling:</strong> If an event handler throws an exception,
 * it does not prevent other handlers from executing. The exception is logged
 * and can be accessed via error handling hooks.</p>
```

Replace with:

```java
 * <p><strong>Error handling:</strong> If a handler throws, the exception is routed
 * to the configured {@link io.tiko.ErrorHandler} (default: slf4j WARN). It does not
 * propagate to the publisher and does not prevent other handlers from running.
 * Async handler exceptions are routed identically.
 *
 * <p>The hook is for observability — do not throw from a handler to signal business
 * state. Use {@link EventTrigger} together with {@link io.tiko.EventTriggerGuard}
 * to branch on outcomes; throwing is an error path, not a control-flow primitive.</p>
```

- [ ] **Step 2: Add an "Error handling" subsection to README**

In `README.md`, locate the events-related section. After whatever subsection introduces `@EventHandler`, add:

```markdown
### Error handling

If an `@EventHandler` method throws, the exception is routed to the configured
`ErrorHandler` (default: logs at WARN via slf4j). It does not propagate to the
publisher and does not prevent other handlers from running.

Override the default to wire metrics, alerts, or custom logging:

```java
TikoOptions opts = TikoOptions.builder()
    .errorHandler(ctx -> {
        switch (ctx) {
            case EventHandlerError e -> metrics.eventHandlerError(e.handler());
        }
    })
    .build();
try (Container container = Tiko.create(opts)) { ... }
```

The hook is for observability — exceptions are an error path, not a control-flow
primitive. To branch on handler outcomes, return a typed result from your
`@EventHandler` and chain the next event with `@EventTrigger` (optionally guarded
by an `EventTriggerGuard`).
```

(Use the existing README markdown style — heading level, code-fence language tags — that you find in surrounding sections. The block above assumes h3; adjust if siblings use h2/h4.)

- [ ] **Step 3: Verify Javadoc renders cleanly**

Run: `mvn -pl tiko-api javadoc:javadoc -q`
Expected: BUILD SUCCESS, no Javadoc errors. (Warnings about other classes are pre-existing — only fail if NEW warnings appear about `EventHandler.java`.)

- [ ] **Step 4: Commit**

```
git add tiko-api/src/main/java/io/tiko/annotations/EventHandler.java README.md
git commit -m "docs: rewrite @EventHandler error-handling contract"
```

---

### Task 13: PR 1 final verification

- [ ] **Step 1: Clean build**

Run: `mvn clean install -q`
Expected: BUILD SUCCESS — every module builds, every test passes.

- [ ] **Step 2: Inspect generated `EventRegistry` for one of the examples to eyeball the dispatcher**

Run: `find tiko-examples -path '*generated-sources*EventRegistry*'`
Open one of the matching files and confirm:
- `private static final io.tiko.EventHandlerInfo HANDLER_INFO_<n>` constants are present
- The dispatcher methods have `try { ... handler call ... } catch (Exception ...) { container.getErrorHandler().onError(new EventHandlerError(...)); }` structure

If it doesn't, re-run `mvn -pl tiko-examples clean compile` and try again.

- [ ] **Step 3: Confirm no PR-2 surface leaked into PR 1**

Run: `grep -r "eventExecutor\|getEventExecutor\|DefaultEventExecutorFactory" tiko-api/src tiko-event-local/src tiko-runtime/src tiko-processor/src` — should be empty.

If results appear, those changes belong in PR 2; back them out.

PR 1 is now ready to push and submit. Plan the PR description from the spec's "PR 1 (#44)" subsection.

---

## Phase 2 — PR 2 (#43): Async dispatch + bounded executor

### Task 14: Add `eventExecutor` to `TikoOptions`

**Files:**
- Modify: `tiko-api/src/main/java/io/tiko/TikoOptions.java`
- Modify: `tiko-api/src/test/java/io/tiko/TikoOptionsTest.java`

- [ ] **Step 1: Extend the test**

Append to `TikoOptionsTest.java`:

```java
    @Test
    void builder_round_trips_event_executor() {
        java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            TikoOptions options = TikoOptions.builder()
                .eventExecutor(executor)
                .build();

            assertThat(options.eventExecutor()).isSameAs(executor);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void builder_rejects_null_event_executor() {
        TikoOptions.Builder b = TikoOptions.builder();
        assertThatNullPointerException().isThrownBy(() -> b.eventExecutor(null));
    }

    @Test
    void builder_event_executor_default_null() {
        TikoOptions options = TikoOptions.builder().build();
        assertThat(options.eventExecutor()).isNull();
    }
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `mvn -pl tiko-api test -Dtest=TikoOptionsTest -q`
Expected: COMPILATION ERROR — `eventExecutor()` and builder method don't exist.

- [ ] **Step 3: Add the field and builder method**

Modify `TikoOptions.java` — inside the class, after the `errorHandler` field/accessor:

```java
    private final java.util.concurrent.ExecutorService eventExecutor;
```

Update the constructor:

```java
    private TikoOptions(Builder b) {
        this.configSource = b.configSource;
        this.errorHandler = b.errorHandler;
        this.eventExecutor = b.eventExecutor;
    }
```

Add accessor:

```java
    /**
     * @return the user-supplied event executor, or {@code null} to use the framework default
     *         (a bounded {@link java.util.concurrent.ThreadPoolExecutor}). When user-supplied,
     *         the user owns the executor's lifecycle — {@code Container.shutdown()} does not
     *         stop it.
     */
    public java.util.concurrent.ExecutorService eventExecutor() {
        return eventExecutor;
    }
```

Inside the `Builder` class:

```java
        private java.util.concurrent.ExecutorService eventExecutor;

        public Builder eventExecutor(java.util.concurrent.ExecutorService executor) {
            this.eventExecutor = Objects.requireNonNull(executor, "eventExecutor");
            return this;
        }
```

- [ ] **Step 4: Run the tests**

Run: `mvn -pl tiko-api test -Dtest=TikoOptionsTest -q`
Expected: BUILD SUCCESS, all 7 tests pass.

- [ ] **Step 5: Commit**

```
git add tiko-api/src/main/java/io/tiko/TikoOptions.java tiko-api/src/test/java/io/tiko/TikoOptionsTest.java
git commit -m "feat(api): add eventExecutor knob to TikoOptions"
```

---

### Task 15: Add `getEventExecutor()` to `Container` interface

**Files:**
- Modify: `tiko-api/src/main/java/io/tiko/Container.java`

- [ ] **Step 1: Modify the interface**

Add this method to the `Container` interface (place it near `getEventBus()`):

```java
    /**
     * Returns the executor used for asynchronous event dispatch
     * ({@code @EventHandler(async = true)} and {@code @EventTrigger(async = true)}).
     *
     * <p>This is either the user-supplied {@link java.util.concurrent.ExecutorService}
     * passed via {@code TikoOptions.eventExecutor(...)}, or the framework's default
     * bounded {@link java.util.concurrent.ThreadPoolExecutor}. The returned executor
     * is alive for the lifetime of the container.
     *
     * <p>You may submit your own tasks to this executor if you want to share thread
     * resources with the framework. Mutating its state (e.g. calling
     * {@code shutdown()}) is your responsibility, but doing so on the framework's
     * default executor will cause undefined behaviour during subsequent event
     * dispatch — prefer supplying your own via {@code TikoOptions} if you need
     * lifecycle control.
     *
     * @return the event executor (never {@code null})
     */
    java.util.concurrent.ExecutorService getEventExecutor();
```

- [ ] **Step 2: Verify compilation**

Run: `mvn -pl tiko-api compile -q`
Expected: BUILD SUCCESS.

Other modules' tests will fail to compile until generated containers add the method (Task 18). That's expected — proceed.

- [ ] **Step 3: Commit**

```
git add tiko-api/src/main/java/io/tiko/Container.java
git commit -m "feat(api): add Container.getEventExecutor() (#43)"
```

---

### Task 16: Add `DefaultEventExecutorFactory` in `tiko-runtime`

**Files:**
- Create: `tiko-runtime/src/main/java/io/tiko/runtime/DefaultEventExecutorFactory.java`
- Create: `tiko-runtime/src/test/java/io/tiko/runtime/DefaultEventExecutorFactoryTest.java`

- [ ] **Step 1: Add test deps to `tiko-runtime/pom.xml`**

If `tiko-runtime/pom.xml` does not already declare junit-jupiter and assertj at test scope, add them. Read the current file first:

Run: `cat tiko-runtime/pom.xml`
Expected: see the existing dependencies block.

If junit-jupiter isn't already at test scope, add (alongside existing deps):

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.assertj</groupId>
    <artifactId>assertj-core</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Write the failing test**

```java
package io.tiko.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultEventExecutorFactoryTest {

    @Test
    void produces_threadpool_with_documented_settings() {
        ExecutorService es = DefaultEventExecutorFactory.create();
        try {
            assertThat(es).isInstanceOf(ThreadPoolExecutor.class);
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) es;

            int cores = Runtime.getRuntime().availableProcessors();
            assertThat(tpe.getCorePoolSize()).isEqualTo(Math.max(2, cores / 2));
            assertThat(tpe.getMaximumPoolSize()).isEqualTo(cores * 4);
            assertThat(tpe.getKeepAliveTime(TimeUnit.SECONDS)).isEqualTo(60);
            assertThat(tpe.getQueue()).isInstanceOf(LinkedBlockingQueue.class);
            assertThat(tpe.getQueue().remainingCapacity()).isEqualTo(1024);
            assertThat(tpe.getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        } finally {
            es.shutdownNow();
        }
    }

    @Test
    void threads_are_daemon_and_named_tiko_event_async() throws Exception {
        ExecutorService es = DefaultEventExecutorFactory.create();
        try {
            AtomicReference<Thread> captured = new AtomicReference<>();
            es.submit(() -> captured.set(Thread.currentThread())).get();

            Thread t = captured.get();
            assertThat(t.isDaemon()).isTrue();
            assertThat(t.getName()).startsWith("tiko-event-async-");
        } finally {
            es.shutdownNow();
        }
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `mvn -pl tiko-runtime test -Dtest=DefaultEventExecutorFactoryTest -q`
Expected: COMPILATION ERROR — `DefaultEventExecutorFactory` does not exist.

- [ ] **Step 4: Implement the factory**

```java
package io.tiko.runtime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the bounded {@link ThreadPoolExecutor} used for asynchronous event dispatch
 * when the user has not supplied a custom executor via {@code TikoOptions.eventExecutor(...)}.
 *
 * <p>Configuration:
 * <ul>
 *   <li>Core pool: {@code Math.max(2, availableProcessors() / 2)}</li>
 *   <li>Max pool: {@code availableProcessors() * 4}</li>
 *   <li>Keep-alive: 60 seconds</li>
 *   <li>Queue: bounded {@link LinkedBlockingQueue} with capacity 1024</li>
 *   <li>Rejection policy: {@link ThreadPoolExecutor.CallerRunsPolicy} — under sustained
 *       overload the publisher thread runs the rejected task itself, providing
 *       backpressure rather than dropping events.</li>
 *   <li>Threads: daemon, named {@code tiko-event-async-{n}}.</li>
 * </ul>
 *
 * <p>Sized for typical small-to-medium services. Workloads with extreme throughput
 * or latency requirements should supply their own executor.
 */
public final class DefaultEventExecutorFactory {

    private DefaultEventExecutorFactory() {}

    public static ExecutorService create() {
        int cores = Runtime.getRuntime().availableProcessors();
        int corePoolSize = Math.max(2, cores / 2);
        int maxPoolSize = cores * 4;

        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "tiko-event-async-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };

        return new ThreadPoolExecutor(
            corePoolSize,
            maxPoolSize,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1024),
            threadFactory,
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
```

Note: the class is `public` so generated container code in user-side packages can reference it. The class itself is in `io.tiko.runtime`; that package is already on the runtime classpath of any tiko-using application.

- [ ] **Step 5: Run the tests**

Run: `mvn -pl tiko-runtime test -Dtest=DefaultEventExecutorFactoryTest -q`
Expected: BUILD SUCCESS, 2 tests passed.

- [ ] **Step 6: Commit**

```
git add tiko-runtime/pom.xml tiko-runtime/src/main/java/io/tiko/runtime/DefaultEventExecutorFactory.java tiko-runtime/src/test/java/io/tiko/runtime/DefaultEventExecutorFactoryTest.java
git commit -m "feat(runtime): add DefaultEventExecutorFactory with bounded threadpool"
```

---

### Task 17: Refactor `EventChainContext` — retire static, accept executor + error-handler params

**Files:**
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/EventChainContext.java`
- Create: `tiko-runtime/src/test/java/io/tiko/runtime/EventChainContextAsyncTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.tiko.runtime;

import io.tiko.ErrorHandler;
import io.tiko.Event;
import io.tiko.EventBus;
import io.tiko.EventCallback;
import io.tiko.EventHandlerError;
import io.tiko.EventHandlerInfo;
import io.tiko.Subscription;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class EventChainContextAsyncTest {

    @Test
    void publishAsync_routes_handler_failures_to_error_handler() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<EventHandlerError> captured = new AtomicReference<>();
        ErrorHandler eh = ctx -> {
            if (ctx instanceof EventHandlerError e) captured.set(e);
        };
        EventBus bus = new InMemoryBus();
        bus.subscribe(String.class, e -> { throw new IllegalStateException("trigger boom"); });
        EventHandlerInfo info = new EventHandlerInfo(getClass(), "test", String.class, true);

        EventChainContext.publishAsync(bus, "hello", null, executor, eh, info).get();

        executor.shutdown();
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().handler()).isEqualTo(info);
        assertThat(captured.get().event()).isEqualTo("hello");
        assertThat(captured.get().cause()).hasMessage("trigger boom");
    }

    /** Minimal in-memory bus for the test; identical semantics to LocalEventBus modulo error-handler. */
    private static final class InMemoryBus implements EventBus {
        private final Map<Class<?>, List<EventCallback<?>>> handlers = new ConcurrentHashMap<>();

        @Override
        public <T> void publish(T event) {
            List<EventCallback<?>> cs = handlers.get(event.getClass());
            if (cs == null) return;
            for (EventCallback<?> c : cs) {
                @SuppressWarnings("unchecked")
                EventCallback<T> tc = (EventCallback<T>) c;
                tc.handle(event);
            }
        }

        @Override
        public <T> Subscription subscribe(Class<T> type, EventCallback<T> cb) {
            handlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(cb);
            return new Subscription() {
                @Override public void unsubscribe() {}
                @Override public boolean isActive() { return true; }
            };
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl tiko-runtime test -Dtest=EventChainContextAsyncTest -q`
Expected: COMPILATION ERROR — `publishAsync` does not have the new 6-arg signature.

- [ ] **Step 3: Replace `EventChainContext.java`**

```java
package io.tiko.runtime;

import io.tiko.ErrorHandler;
import io.tiko.Event;
import io.tiko.EventBus;
import io.tiko.EventHandlerError;
import io.tiko.EventHandlerInfo;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

/**
 * Tracks the currently-executing event wrapper across a thread of event delivery, so that
 * events triggered by an {@code @EventTrigger} handler can chain their origin back to the
 * event that caused the handler to fire.
 *
 * <p>Used by generated {@code EventRegistry} code; not part of the public API.
 *
 * <p>Async helpers ({@link #publishAsync}, {@link #publishSpreadAsync}) take the
 * container's {@link ExecutorService} and {@link ErrorHandler} as parameters — there is
 * no longer a global static executor. Exceptional completions of submitted tasks are
 * routed to the supplied error handler with the originating handler's
 * {@link EventHandlerInfo}, ensuring no async failure is silently swallowed even when
 * the returned future is discarded.
 */
public final class EventChainContext {

    private static final ThreadLocal<Event<?>> CURRENT = new ThreadLocal<>();

    private EventChainContext() {}

    public static <T> Event<T> wrap(T payload) {
        return new Event<>(payload, CURRENT.get());
    }

    public static void runWith(Event<?> wrapper, Runnable body) {
        Event<?> previous = enter(wrapper);
        try {
            body.run();
        } finally {
            exit(previous);
        }
    }

    public static Event<?> enter(Event<?> wrapper) {
        Event<?> previous = CURRENT.get();
        CURRENT.set(wrapper);
        return previous;
    }

    public static void exit(Event<?> previous) {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }

    public static void publishWithOrigin(EventBus bus, Object payload, Event<?> origin) {
        if (payload == null) return;
        runWith(origin, () -> bus.publish(payload));
    }

    public static void publishSpreadWithOrigin(EventBus bus, Object payload, Event<?> origin) {
        if (payload == null) return;
        if (payload instanceof Collection<?> collection) {
            runWith(origin, () -> {
                for (Object item : collection) if (item != null) bus.publish(item);
            });
        } else if (payload.getClass().isArray()) {
            runWith(origin, () -> {
                int len = Array.getLength(payload);
                for (int i = 0; i < len; i++) {
                    Object item = Array.get(payload, i);
                    if (item != null) bus.publish(item);
                }
            });
        } else if (payload instanceof Iterable<?> iterable) {
            runWith(origin, () -> {
                for (Object item : iterable) if (item != null) bus.publish(item);
            });
        } else {
            publishWithOrigin(bus, payload, origin);
        }
    }

    public static CompletableFuture<Void> publishAsync(
            EventBus bus, Object payload, Event<?> origin,
            ExecutorService executor, ErrorHandler errorHandler, EventHandlerInfo info) {
        if (payload == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture
            .runAsync(() -> publishWithOrigin(bus, payload, origin), executor)
            .whenComplete((__, throwable) -> reportIfFailed(throwable, payload, errorHandler, info));
    }

    public static CompletableFuture<Void> publishSpreadAsync(
            EventBus bus, Object payload, Event<?> origin,
            ExecutorService executor, ErrorHandler errorHandler, EventHandlerInfo info) {
        if (payload == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture
            .runAsync(() -> publishSpreadWithOrigin(bus, payload, origin), executor)
            .whenComplete((__, throwable) -> reportIfFailed(throwable, payload, errorHandler, info));
    }

    private static void reportIfFailed(Throwable throwable, Object payload,
                                        ErrorHandler errorHandler, EventHandlerInfo info) {
        if (throwable == null) return;
        Throwable cause = (throwable instanceof CompletionException && throwable.getCause() != null)
            ? throwable.getCause() : throwable;
        try {
            errorHandler.onError(new EventHandlerError(info, payload, cause));
        } catch (Exception inner) {
            LoggerFactory.getLogger("io.tiko.events").error("ErrorHandler.onError threw", inner);
        }
    }
}
```

Note: `tiko-runtime/pom.xml` may need an slf4j-api dependency if it doesn't already have one. Check with:

Run: `grep slf4j tiko-runtime/pom.xml`
If empty, add:

```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <scope>provided</scope>
</dependency>
```

- [ ] **Step 4: Run the test**

Run: `mvn -pl tiko-runtime test -Dtest=EventChainContextAsyncTest -q`
Expected: BUILD SUCCESS, 1 test passed.

- [ ] **Step 5: Commit**

```
git add tiko-runtime/pom.xml tiko-runtime/src/main/java/io/tiko/runtime/EventChainContext.java tiko-runtime/src/test/java/io/tiko/runtime/EventChainContextAsyncTest.java
git commit -m "refactor(runtime): retire EventChainContext.ASYNC_EXECUTOR static (#43)"
```

---

### Task 18: Generate `eventExecutor` field, accessor, and shutdown wiring in `ContainerGenerator`

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java`
- Create: `tiko-processor/src/test/java/io/tiko/processor/ContainerGeneratorEventExecutorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.tiko.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

class ContainerGeneratorEventExecutorTest {

    @Test
    void generated_container_has_event_executor_field_constructor_and_accessor() throws IOException {
        JavaFileObject src = JavaFileObjects.forSourceLines(
            "io.example.MyService",
            "package io.example;",
            "import io.tiko.annotations.Component;",
            "import io.tiko.annotations.Scope;",
            "@Component(scope = Scope.SINGLETON)",
            "public class MyService { public MyService() {} }"
        );
        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        JavaFileObject container = c.generatedSourceFiles().stream()
            .filter(f -> f.getName().contains("TikoContainerImpl"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("TikoContainerImpl not generated"));

        String content = new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content).contains("private final java.util.concurrent.ExecutorService eventExecutor");
        assertThat(content).contains("private final boolean ownsEventExecutor");
        assertThat(content).contains("public java.util.concurrent.ExecutorService getEventExecutor()");
        assertThat(content).contains(
            "public TikoContainerImpl(io.tiko.EventBus eventBus, io.tiko.ErrorHandler errorHandler, "
            + "java.util.concurrent.ExecutorService userEventExecutor)");
        assertThat(content).contains(
            "this.eventExecutor = userEventExecutor != null ? userEventExecutor : "
            + "io.tiko.runtime.DefaultEventExecutorFactory.create()");
        assertThat(content).contains("this.ownsEventExecutor = (userEventExecutor == null)");

        // Shutdown handles the default executor (PR 2 contribution to shutdown())
        assertThat(content).contains("if (this.ownsEventExecutor)");
        assertThat(content).contains("this.eventExecutor.shutdown()");
        assertThat(content).contains("this.eventExecutor.awaitTermination(10");
        assertThat(content).contains("this.eventExecutor.shutdownNow()");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl tiko-processor test -Dtest=ContainerGeneratorEventExecutorTest -q`
Expected: ASSERTION FAILURES.

- [ ] **Step 3: Modify `ContainerGenerator`**

In `ContainerGenerator.java`:

1. Add the field declarations alongside `eventBus` and `errorHandler`:

```java
ClassName executorService = ClassName.get("java.util.concurrent", "ExecutorService");

FieldSpec eventExecutorField = FieldSpec.builder(executorService, "eventExecutor",
        Modifier.PRIVATE, Modifier.FINAL).build();
FieldSpec ownsExecutorField = FieldSpec.builder(TypeName.BOOLEAN, "ownsEventExecutor",
        Modifier.PRIVATE, Modifier.FINAL).build();
```

Add both to the type spec.

2. Modify the constructor to take a third parameter and initialise both fields. Find the existing constructor builder and append:

```java
constructorBuilder.addParameter(executorService, "userEventExecutor");
constructorBuilder.addStatement(
    "this.eventExecutor = userEventExecutor != null ? userEventExecutor : "
    + "io.tiko.runtime.DefaultEventExecutorFactory.create()");
constructorBuilder.addStatement("this.ownsEventExecutor = (userEventExecutor == null)");
```

3. Add the public accessor:

```java
MethodSpec getExec = MethodSpec.methodBuilder("getEventExecutor")
    .addAnnotation(Override.class)
    .addModifiers(Modifier.PUBLIC)
    .returns(executorService)
    .addStatement("return this.eventExecutor")
    .build();
```

Add to the type spec.

4. Modify `createShutdownMethod` (around line 634-675) to add the executor-shutdown sequence at the end of the method, after the existing `@PreDestroy` loop:

Insert before the method's closing `return method.build()`:

```java
method.addComment("Shut down framework-owned event executor (#43); user-supplied executors are not touched");
method.beginControlFlow("if (this.ownsEventExecutor)");
method.addStatement("this.eventExecutor.shutdown()");
method.beginControlFlow("try");
method.beginControlFlow("if (!this.eventExecutor.awaitTermination(10, $T.SECONDS))",
    ClassName.get("java.util.concurrent", "TimeUnit"));
method.addStatement("this.eventExecutor.shutdownNow()");
method.endControlFlow();
method.nextControlFlow("catch ($T __ie)", InterruptedException.class);
method.addStatement("$T.currentThread().interrupt()", Thread.class);
method.addStatement("this.eventExecutor.shutdownNow()");
method.endControlFlow();
method.endControlFlow();
```

- [ ] **Step 4: Run the test**

Run: `mvn -pl tiko-processor test -Dtest=ContainerGeneratorEventExecutorTest -q`
Expected: BUILD SUCCESS, 1 test passed.

- [ ] **Step 5: Run all processor tests; expect breakage in tests that instantiate generated containers**

Run: `mvn -pl tiko-processor test -q`
Expected: previously-passing tests for the existing 2-arg `(EventBus, ErrorHandler)` constructor will now fail because the constructor signature changed to 3-arg. They will be fixed in Task 19 by updating `Tiko.create()`'s reflective invocation.

- [ ] **Step 6: Commit**

```
git add tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java tiko-processor/src/test/java/io/tiko/processor/ContainerGeneratorEventExecutorTest.java
git commit -m "feat(processor): generate eventExecutor field/lifecycle on container (#43)"
```

---

### Task 19: Pass executor through `Tiko.create()` to the generated container constructor

**Files:**
- Modify: `tiko-api/src/main/java/io/tiko/Tiko.java`

- [ ] **Step 1: Modify `createSingleModuleContainer`**

Replace its body to invoke the new 3-arg constructor:

```java
private static Container createSingleModuleContainer(
        EventBus eventBus, ErrorHandler errorHandler,
        java.util.concurrent.ExecutorService userEventExecutor) throws Exception {
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    if (classLoader == null) classLoader = Tiko.class.getClassLoader();

    var resources = classLoader.getResources("META-INF/tiko/container.properties");
    Class<?> implClass;
    if (resources.hasMoreElements()) {
        var props = new java.util.Properties();
        try (var input = resources.nextElement().openStream()) {
            props.load(input);
        }
        String implClassName = props.getProperty("impl");
        implClass = Class.forName(implClassName);
    } else {
        implClass = Class.forName("io.tiko.generated.TikoContainerImpl");
    }

    Container container = (Container) implClass
        .getDeclaredConstructor(EventBus.class, ErrorHandler.class, java.util.concurrent.ExecutorService.class)
        .newInstance(eventBus, errorHandler, userEventExecutor);

    registerEventHandlers(eventBus, container, implClass);
    return container;
}
```

- [ ] **Step 2: Modify `createInternal` to pass `options.eventExecutor()` through**

Replace the call site that previously was `createSingleModuleContainer(eventBus, errorHandler)` with:

```java
container = createSingleModuleContainer(eventBus, errorHandler, options.eventExecutor());
```

For the multi-module branch, also extend the reflective lookup to attempt 3-arg:

```java
if (moduleCount > 1) {
    Class<?> aggregatingClass = Class.forName("io.tiko.runtime.AggregatingContainer");
    try {
        container = (Container) aggregatingClass
            .getDeclaredConstructor(EventBus.class, ErrorHandler.class, java.util.concurrent.ExecutorService.class)
            .newInstance(eventBus, errorHandler, options.eventExecutor());
    } catch (NoSuchMethodException nsm3) {
        try {
            container = (Container) aggregatingClass
                .getDeclaredConstructor(EventBus.class, ErrorHandler.class)
                .newInstance(eventBus, errorHandler);
        } catch (NoSuchMethodException nsm2) {
            container = (Container) aggregatingClass
                .getDeclaredConstructor(EventBus.class)
                .newInstance(eventBus);
        }
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn -pl tiko-api compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Run reactor build to verify processor tests pass again**

Run: `mvn -am test -pl tiko-processor -q`
Expected: BUILD SUCCESS, all `tiko-processor` tests pass (the new 3-arg constructor is consistent across `ContainerGenerator` and `Tiko`).

- [ ] **Step 5: Commit**

```
git add tiko-api/src/main/java/io/tiko/Tiko.java
git commit -m "feat(api): wire eventExecutor through Tiko.create()"
```

---

### Task 20: Update `EventRegistryGenerator` for async dispatch path

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/EventRegistryGenerator.java`
- Create: `tiko-processor/src/test/java/io/tiko/processor/EventRegistryAsyncDispatchTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.tiko.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

class EventRegistryAsyncDispatchTest {

    @Test
    void async_handler_generates_completable_future_dispatch_with_when_complete() throws IOException {
        JavaFileObject component = JavaFileObjects.forSourceLines(
            "io.example.AsyncHandler",
            "package io.example;",
            "import io.tiko.annotations.Component;",
            "import io.tiko.annotations.EventHandler;",
            "import io.tiko.annotations.Scope;",
            "@Component(scope = Scope.SINGLETON)",
            "public class AsyncHandler {",
            "    @EventHandler(async = true)",
            "    public void onPing(Ping event) {}",
            "}"
        );
        JavaFileObject event = JavaFileObjects.forSourceLines(
            "io.example.Ping",
            "package io.example;",
            "public record Ping() {}"
        );

        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(component, event);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        JavaFileObject registry = c.generatedSourceFiles().stream()
            .filter(f -> f.getName().contains("EventRegistry"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("EventRegistry not generated"));

        String content = new String(registry.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // HANDLER_INFO with async=true
        assertThat(content).contains(
            "new io.tiko.EventHandlerInfo(io.example.AsyncHandler.class, \"onPing\", io.example.Ping.class, true)");

        // CompletableFuture.runAsync with container.getEventExecutor()
        assertThat(content).contains("java.util.concurrent.CompletableFuture.runAsync(");
        assertThat(content).contains("container.getEventExecutor()");

        // Re-enter chain context inside async task
        assertThat(content).contains("io.tiko.runtime.EventChainContext.enter(__wrapper)");

        // whenComplete routes to ErrorHandler
        assertThat(content).contains(".whenComplete(");
        assertThat(content).contains("container.getErrorHandler().onError(");
        assertThat(content).contains("new io.tiko.EventHandlerError(HANDLER_INFO_0, event,");

        // CompletionException unwrap
        assertThat(content).contains("CompletionException");
    }

    @Test
    void sync_handler_generates_unchanged_inline_dispatch() throws IOException {
        JavaFileObject component = JavaFileObjects.forSourceLines(
            "io.example.SyncHandler",
            "package io.example;",
            "import io.tiko.annotations.Component;",
            "import io.tiko.annotations.EventHandler;",
            "import io.tiko.annotations.Scope;",
            "@Component(scope = Scope.SINGLETON)",
            "public class SyncHandler {",
            "    @EventHandler",
            "    public void onPing(Ping event) {}",
            "}"
        );
        JavaFileObject event = JavaFileObjects.forSourceLines(
            "io.example.Ping",
            "package io.example;",
            "public record Ping() {}"
        );

        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(component, event);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        JavaFileObject registry = c.generatedSourceFiles().stream()
            .filter(f -> f.getName().contains("EventRegistry"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("EventRegistry not generated"));

        String content = new String(registry.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // No CompletableFuture for sync handlers
        assertThat(content).doesNotContain("CompletableFuture.runAsync");
        // Sync invocation still emitted as before
        assertThat(content).contains("__handler.onPing(event)");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl tiko-processor test -Dtest=EventRegistryAsyncDispatchTest -q`
Expected: ASSERTION FAILURES — async handlers don't yet generate the CompletableFuture path.

- [ ] **Step 3: Modify `EventRegistryGenerator.createDispatcherMethod` to branch on async**

The current method (post-Task 9) has try/catch around the handler call. Now restructure to:
- For sync handlers: keep the existing try/catch.
- For async handlers: replace the handler invocation block with a `CompletableFuture.runAsync(...).whenComplete(...)` block; do NOT use the inline try/catch since the future routes errors itself.

Locate `createDispatcherMethod` in `EventRegistryGenerator.java`. Replace its body with:

```java
private MethodSpec createDispatcherMethod(EventHandlerModel handler, int index) {
    ClassName containerClass = ClassName.get(GENERATED_PACKAGE, context.getContainerClassName());
    ClassName eventClass = ClassName.bestGuess(handler.getEventTypeName());
    ClassName declaringClass = ClassName.bestGuess(handler.getDeclaringClass().getQualifiedName().toString());
    String getterName = "get" + handler.getDeclaringClass().getSimpleName().toString();

    ClassName errorHandler = ClassName.get("io.tiko", "ErrorHandler");
    ClassName eventHandlerError = ClassName.get("io.tiko", "EventHandlerError");
    ClassName loggerFactory = ClassName.get("org.slf4j", "LoggerFactory");
    ClassName completableFuture = ClassName.get("java.util.concurrent", "CompletableFuture");
    ClassName completionException = ClassName.get("java.util.concurrent", "CompletionException");

    MethodSpec.Builder method = MethodSpec.methodBuilder(dispatcherName(handler, index))
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(ClassName.get(EventBus.class), "eventBus")
            .addParameter(containerClass, "container")
            .addParameter(eventClass, "event");

    method.addStatement("$T<$T> __wrapper = $T.wrap(event)", Event.class, eventClass, CHAIN_CONTEXT);
    method.addStatement("$T<?> __previous = $T.enter(__wrapper)", Event.class, CHAIN_CONTEXT);
    method.beginControlFlow("try");
    method.addStatement("$T __handler = container.$L()", declaringClass, getterName);

    TypeMirror returnType = handler.getMethodElement().getReturnType();
    boolean hasTriggers = !handler.getEventTriggers().isEmpty();
    boolean returnsValue = returnType.getKind() != TypeKind.VOID;
    boolean captureResult = hasTriggers && returnsValue;

    if (hasTriggers && !returnsValue) {
        context.getMessager().printMessage(
                Diagnostic.Kind.WARNING,
                "@EventTrigger on a void-returning @EventHandler has no payload to publish — ignored",
                handler.getMethodElement());
    }

    String invocation = handler.hasEventWrapper()
            ? "__handler." + handler.getMethodName() + "(event, __wrapper)"
            : "__handler." + handler.getMethodName() + "(event)";

    if (handler.isAsync()) {
        // Async path: submit to executor, route exceptional completions to ErrorHandler.
        // Note: triggers in async handlers run inside the async task body too.
        method.addStatement("$T __exec = container.getEventExecutor()",
            ClassName.get("java.util.concurrent", "ExecutorService"));
        method.addStatement("$T __err = container.getErrorHandler()", errorHandler);
        method.addStatement("$T<?> __asyncWrapper = __wrapper", Event.class);
        method.beginControlFlow("$T.runAsync(() -> ", completableFuture);
        method.addStatement("$T<?> __asyncPrev = $T.enter(__asyncWrapper)", Event.class, CHAIN_CONTEXT);
        method.beginControlFlow("try");
        if (captureResult) {
            method.addStatement("$T __result = $L", TypeName.get(returnType), invocation);
            for (EventTriggerModel trigger : handler.getEventTriggers()) {
                emitTrigger(method, trigger, index);
            }
        } else {
            method.addStatement(invocation);
        }
        method.nextControlFlow("finally");
        method.addStatement("$T.exit(__asyncPrev)", CHAIN_CONTEXT);
        method.endControlFlow(); // try/finally
        method.endControlFlow(", __exec).whenComplete(($$1, __t) -> "); // close runAsync, open whenComplete

        // ^ The above is awkward in JavaPoet — practical implementation: build the lambda and chain via addStatement
        // Replace the previous two endControlFlow() lines with the cleaner sequence below:
    }
    // ...
}
```

The above sketch shows the structure. JavaPoet's lambda-with-multistatement-body is awkward; the practical implementation uses `CodeBlock.builder()` to compose the runAsync lambda body and the whenComplete lambda. Here is the production-ready version:

```java
private MethodSpec createDispatcherMethod(EventHandlerModel handler, int index) {
    ClassName containerClass = ClassName.get(GENERATED_PACKAGE, context.getContainerClassName());
    ClassName eventClass = ClassName.bestGuess(handler.getEventTypeName());
    ClassName declaringClass = ClassName.bestGuess(handler.getDeclaringClass().getQualifiedName().toString());
    String getterName = "get" + handler.getDeclaringClass().getSimpleName().toString();

    ClassName errorHandler = ClassName.get("io.tiko", "ErrorHandler");
    ClassName eventHandlerError = ClassName.get("io.tiko", "EventHandlerError");
    ClassName loggerFactory = ClassName.get("org.slf4j", "LoggerFactory");
    ClassName completableFuture = ClassName.get("java.util.concurrent", "CompletableFuture");
    ClassName completionException = ClassName.get("java.util.concurrent", "CompletionException");
    ClassName executorService = ClassName.get("java.util.concurrent", "ExecutorService");

    MethodSpec.Builder method = MethodSpec.methodBuilder(dispatcherName(handler, index))
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(ClassName.get(EventBus.class), "eventBus")
            .addParameter(containerClass, "container")
            .addParameter(eventClass, "event");

    method.addStatement("$T<$T> __wrapper = $T.wrap(event)", Event.class, eventClass, CHAIN_CONTEXT);
    method.addStatement("$T<?> __previous = $T.enter(__wrapper)", Event.class, CHAIN_CONTEXT);
    method.beginControlFlow("try");
    method.addStatement("$T __handler = container.$L()", declaringClass, getterName);

    TypeMirror returnType = handler.getMethodElement().getReturnType();
    boolean hasTriggers = !handler.getEventTriggers().isEmpty();
    boolean returnsValue = returnType.getKind() != TypeKind.VOID;
    boolean captureResult = hasTriggers && returnsValue;

    if (hasTriggers && !returnsValue) {
        context.getMessager().printMessage(
                Diagnostic.Kind.WARNING,
                "@EventTrigger on a void-returning @EventHandler has no payload to publish — ignored",
                handler.getMethodElement());
    }

    String invocation = handler.hasEventWrapper()
            ? "__handler." + handler.getMethodName() + "(event, __wrapper)"
            : "__handler." + handler.getMethodName() + "(event)";

    if (handler.isAsync()) {
        // Async dispatch
        method.addStatement("$T __exec = container.getEventExecutor()", executorService);
        method.addStatement("$T __err = container.getErrorHandler()", errorHandler);
        method.addStatement("final $T<?> __asyncWrapper = __wrapper", Event.class);

        // Build the runAsync body
        CodeBlock.Builder runBody = CodeBlock.builder();
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

        // whenComplete body
        CodeBlock.Builder wcBody = CodeBlock.builder();
        wcBody.beginControlFlow("if (__t != null)");
        wcBody.addStatement(
            "$T __cause = (__t instanceof $T && __t.getCause() != null) ? __t.getCause() : __t",
            Throwable.class, completionException);
        wcBody.beginControlFlow("try");
        wcBody.addStatement("__err.onError(new $T(HANDLER_INFO_$L, event, __cause))",
            eventHandlerError, index);
        wcBody.nextControlFlow("catch ($T __inner)", Exception.class);
        wcBody.addStatement("$T.getLogger($S).error($S, __inner)",
            loggerFactory, "io.tiko.events", "ErrorHandler.onError threw");
        wcBody.endControlFlow();
        wcBody.endControlFlow();

        method.addCode(CodeBlock.builder()
            .add("$T.runAsync(() -> {\n$L}, __exec).whenComplete((__r, __t) -> {\n$L});\n",
                completableFuture, runBody.build(), wcBody.build())
            .build());

    } else {
        // Sync dispatch (Task 9 behaviour)
        method.beginControlFlow("try");
        if (captureResult) {
            method.addStatement("$T __result = $L", TypeName.get(returnType), invocation);
            for (EventTriggerModel trigger : handler.getEventTriggers()) {
                emitTrigger(method, trigger, index);
            }
        } else {
            method.addStatement(invocation);
        }
        method.nextControlFlow("catch ($T __t)", Exception.class);
        method.addStatement("$T __err = container.getErrorHandler()", errorHandler);
        method.beginControlFlow("try");
        method.addStatement("__err.onError(new $T(HANDLER_INFO_$L, event, __t))",
            eventHandlerError, index);
        method.nextControlFlow("catch ($T __inner)", Exception.class);
        method.addStatement("$T.getLogger($S).error($S, __inner)",
            loggerFactory, "io.tiko.events", "ErrorHandler.onError threw");
        method.endControlFlow();
        method.endControlFlow();
    }

    method.nextControlFlow("finally");
    method.addStatement("$T.exit(__previous)", CHAIN_CONTEXT);
    method.endControlFlow();

    return method.build();
}
```

Note that `emitTrigger` and `emitTriggerInto` need to take the `index` parameter so they can pass it to the new `EventChainContext.publishAsync(...)` signature. See Task 21.

- [ ] **Step 4: Run the test**

Run: `mvn -pl tiko-processor test -Dtest=EventRegistryAsyncDispatchTest -q`
Expected: BUILD SUCCESS, 2 tests passed.

- [ ] **Step 5: Commit (defer trigger codegen update to next task)**

```
git add tiko-processor/src/main/java/io/tiko/processor/generator/EventRegistryGenerator.java tiko-processor/src/test/java/io/tiko/processor/EventRegistryAsyncDispatchTest.java
git commit -m "feat(processor): generate async dispatcher path for @EventHandler(async) (#43)"
```

---

### Task 21: Update `emitTrigger` for new `EventChainContext.publishAsync` signature

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/EventRegistryGenerator.java`
- Modify or create: `tiko-processor/src/test/java/io/tiko/processor/EventRegistryAsyncTriggerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.tiko.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

class EventRegistryAsyncTriggerTest {

    @Test
    void async_event_trigger_passes_executor_and_error_handler() throws IOException {
        JavaFileObject component = JavaFileObjects.forSourceLines(
            "io.example.Triggering",
            "package io.example;",
            "import io.tiko.annotations.Component;",
            "import io.tiko.annotations.EventHandler;",
            "import io.tiko.annotations.EventTrigger;",
            "import io.tiko.annotations.Scope;",
            "@Component(scope = Scope.SINGLETON)",
            "public class Triggering {",
            "    @EventHandler",
            "    @EventTrigger(eventName = \"io.example.Pong\", async = true)",
            "    public Pong onPing(Ping event) { return new Pong(); }",
            "}"
        );
        JavaFileObject ping = JavaFileObjects.forSourceLines(
            "io.example.Ping",
            "package io.example;",
            "public record Ping() {}"
        );
        JavaFileObject pong = JavaFileObjects.forSourceLines(
            "io.example.Pong",
            "package io.example;",
            "public record Pong() {}"
        );

        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(component, ping, pong);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        JavaFileObject registry = c.generatedSourceFiles().stream()
            .filter(f -> f.getName().contains("EventRegistry"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("EventRegistry not generated"));

        String content = new String(registry.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // The publishAsync call should pass the new 6-arg form
        assertThat(content).contains("io.tiko.runtime.EventChainContext.publishAsync(eventBus, __result, __wrapper, container.getEventExecutor(), container.getErrorHandler(), HANDLER_INFO_0)");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -pl tiko-processor test -Dtest=EventRegistryAsyncTriggerTest -q`
Expected: ASSERTION FAILURE — old 3-arg form is still in the generated source.

- [ ] **Step 3: Modify `emitTrigger` to take `index` and pass new args**

Change `emitTrigger`'s signature to take `int index`, then update the publish-helper invocation. The current method (around line 156-181) generates statements like:

```
$T.$L(eventBus, __result, __wrapper)
```

Modify it to differentiate sync vs. async helper signatures. New version:

```java
private void emitTrigger(MethodSpec.Builder method, EventTriggerModel trigger, int index) {
    ClassName executorService = ClassName.get("java.util.concurrent", "ExecutorService");
    ClassName errorHandlerCls = ClassName.get("io.tiko", "ErrorHandler");

    String publishHelper;
    boolean isAsync = trigger.isAsync();
    if (isAsync) {
        publishHelper = trigger.isSpread() ? "publishSpreadAsync" : "publishAsync";
    } else {
        publishHelper = trigger.isSpread() ? "publishSpreadWithOrigin" : "publishWithOrigin";
    }

    String publishCall = isAsync
        ? "$T.$L(eventBus, __result, __wrapper, container.getEventExecutor(), container.getErrorHandler(), HANDLER_INFO_" + index + ")"
        : "$T.$L(eventBus, __result, __wrapper)";

    if (!trigger.hasGuard()) {
        method.addStatement(publishCall, CHAIN_CONTEXT, publishHelper);
        return;
    }

    StringBuilder condition = new StringBuilder();
    Object[] args = new Object[trigger.getGuardClasses().size()];
    for (int i = 0; i < trigger.getGuardClasses().size(); i++) {
        if (i > 0) condition.append(" && ");
        condition.append("new $T().shouldTrigger(__result, event)");
        args[i] = ClassName.get(trigger.getGuardClasses().get(i));
    }

    method.beginControlFlow("if (" + condition + ")", args);
    method.addStatement(publishCall, CHAIN_CONTEXT, publishHelper);
    method.endControlFlow();
}

// New variant for async dispatcher's CodeBlock.Builder context
private void emitTriggerInto(CodeBlock.Builder body, EventTriggerModel trigger, int index) {
    String publishHelper;
    boolean isAsync = trigger.isAsync();
    if (isAsync) {
        publishHelper = trigger.isSpread() ? "publishSpreadAsync" : "publishAsync";
    } else {
        publishHelper = trigger.isSpread() ? "publishSpreadWithOrigin" : "publishWithOrigin";
    }

    String publishCall = isAsync
        ? "$T.$L(eventBus, __result, __asyncWrapper, container.getEventExecutor(), container.getErrorHandler(), HANDLER_INFO_" + index + ")"
        : "$T.$L(eventBus, __result, __asyncWrapper)";

    if (!trigger.hasGuard()) {
        body.addStatement(publishCall, CHAIN_CONTEXT, publishHelper);
        return;
    }

    StringBuilder condition = new StringBuilder();
    Object[] args = new Object[trigger.getGuardClasses().size()];
    for (int i = 0; i < trigger.getGuardClasses().size(); i++) {
        if (i > 0) condition.append(" && ");
        condition.append("new $T().shouldTrigger(__result, event)");
        args[i] = ClassName.get(trigger.getGuardClasses().get(i));
    }

    body.beginControlFlow("if (" + condition + ")", args);
    body.addStatement(publishCall, CHAIN_CONTEXT, publishHelper);
    body.endControlFlow();
}
```

Update the existing call sites in `createDispatcherMethod` (Task 9 added `emitTrigger(method, trigger);` — change to `emitTrigger(method, trigger, index);`).

- [ ] **Step 4: Run the test**

Run: `mvn -pl tiko-processor test -Dtest=EventRegistryAsyncTriggerTest -q`
Expected: BUILD SUCCESS, 1 test passed.

- [ ] **Step 5: Run all processor tests**

Run: `mvn -pl tiko-processor test -q`
Expected: BUILD SUCCESS — earlier test from Task 9 still passes since the sync helper signature is unchanged.

- [ ] **Step 6: Commit**

```
git add tiko-processor/src/main/java/io/tiko/processor/generator/EventRegistryGenerator.java tiko-processor/src/test/java/io/tiko/processor/EventRegistryAsyncTriggerTest.java
git commit -m "feat(processor): pass executor and error-handler to async @EventTrigger publish (#43)"
```

---

### Task 22: Integration tests — async dispatch end-to-end

**Files:**
- Create: `tiko-examples/01_basic_di/src/test/java/io/tiko/examples/basic/AsyncEventIntegrationTest.java`

(Adjust path to whichever existing example module hosts integration tests.)

- [ ] **Step 1: Write the test**

```java
package io.tiko.examples.basic;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.ErrorHandler;
import io.tiko.EventHandlerError;
import io.tiko.Tiko;
import io.tiko.TikoOptions;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.Scope;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncEventIntegrationTest {

    record Ping() {}

    @Component(scope = Scope.SINGLETON)
    public static class AsyncRecorder {
        static final AtomicReference<String> threadName = new AtomicReference<>();
        static final CountDownLatch latch = new CountDownLatch(1);

        @EventHandler(async = true)
        public void onPing(Ping event) {
            threadName.set(Thread.currentThread().getName());
            latch.countDown();
        }
    }

    @Component(scope = Scope.SINGLETON)
    public static class AsyncThrower {
        @EventHandler(async = true)
        public void onPing(Ping event) {
            throw new IllegalStateException("async boom");
        }
    }

    @Test
    void async_handler_runs_off_publisher_thread() throws Exception {
        try (Container container = Tiko.create()) {
            String publisherThread = Thread.currentThread().getName();
            container.getEventBus().publish(new Ping());

            assertThat(AsyncRecorder.latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(AsyncRecorder.threadName.get())
                .isNotEqualTo(publisherThread)
                .startsWith("tiko-event-async-");
        }
    }

    @Test
    void async_handler_error_routes_to_error_handler_even_when_future_discarded() throws Exception {
        AtomicReference<ErrorContext> captured = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        ErrorHandler recording = ctx -> {
            captured.set(ctx);
            latch.countDown();
        };

        TikoOptions opts = TikoOptions.builder().errorHandler(recording).build();
        try (Container container = Tiko.create(opts)) {
            container.getEventBus().publish(new Ping());

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        }

        EventHandlerError err = (EventHandlerError) captured.get();
        assertThat(err).isNotNull();
        assertThat(err.handler().async()).isTrue();
        assertThat(err.cause()).hasMessage("async boom");
    }

    @Test
    void custom_event_executor_is_used() throws Exception {
        ExecutorService delegate = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "custom-executor-thread");
            t.setDaemon(true);
            return t;
        });
        AtomicInteger submissions = new AtomicInteger();
        ExecutorService recording = new java.util.concurrent.AbstractExecutorService() {
            @Override public void shutdown() { delegate.shutdown(); }
            @Override public java.util.List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
            @Override public boolean isShutdown() { return delegate.isShutdown(); }
            @Override public boolean isTerminated() { return delegate.isTerminated(); }
            @Override public boolean awaitTermination(long t, TimeUnit u) throws InterruptedException {
                return delegate.awaitTermination(t, u);
            }
            @Override public void execute(Runnable command) {
                submissions.incrementAndGet();
                delegate.execute(command);
            }
        };

        TikoOptions opts = TikoOptions.builder().eventExecutor(recording).build();
        try (Container container = Tiko.create(opts)) {
            assertThat(container.getEventExecutor()).isSameAs(recording);
            container.getEventBus().publish(new Ping());

            assertThat(AsyncRecorder.latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(submissions.get()).isPositive();
        } finally {
            recording.shutdown();
        }
    }

    @Test
    void user_supplied_executor_is_not_shut_down_by_container() {
        ExecutorService user = Executors.newSingleThreadExecutor();
        TikoOptions opts = TikoOptions.builder().eventExecutor(user).build();
        try (Container container = Tiko.create(opts)) {
            // empty body — close at end
        }
        assertThat(user.isShutdown()).isFalse();
        user.shutdown();
    }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn -pl tiko-examples/01_basic_di test -Dtest=AsyncEventIntegrationTest -q`

If reactor build is needed first: `mvn -am install -DskipTests -q && mvn -pl tiko-examples/01_basic_di test -Dtest=AsyncEventIntegrationTest -q`
Expected: BUILD SUCCESS, 4 tests passed.

Note: tests share `AsyncRecorder.latch` static — JUnit 5 runs tests in unspecified order by default. If flakes appear, isolate `latch` per-test by using a fresh `CountDownLatch` field on each test method, or annotate tests with `@org.junit.jupiter.api.MethodOrderer` to enforce ordering.

- [ ] **Step 3: Commit**

```
git add tiko-examples/01_basic_di/src/test/java/io/tiko/examples/basic/AsyncEventIntegrationTest.java
git commit -m "test(examples): integration tests for async event dispatch"
```

---

### Task 23: Update `@EventHandler(async)` Javadoc and README async section

**Files:**
- Modify: `tiko-api/src/main/java/io/tiko/annotations/EventHandler.java`
- Modify: `README.md`

- [ ] **Step 1: Update `async()` Javadoc**

In `EventHandler.java`, find the `async()` accessor (around line 79-91). Replace its Javadoc:

```java
    /**
     * Whether this handler should be invoked asynchronously.
     *
     * <p>When {@code true}, the handler runs on the container's event executor —
     * a bounded {@link java.util.concurrent.ThreadPoolExecutor} by default
     * (see the framework defaults documented on
     * {@code io.tiko.runtime.DefaultEventExecutorFactory}), or the user-supplied
     * {@link java.util.concurrent.ExecutorService} passed via
     * {@code TikoOptions.eventExecutor(...)}.
     *
     * <p>The publisher does not wait for an async handler to complete. Async handler
     * exceptions are routed to the configured {@link io.tiko.ErrorHandler}, identical
     * to sync handler errors.
     *
     * <p>Default: {@code false} (synchronous).
     *
     * @return true for async execution, false for sync
     */
```

- [ ] **Step 2: Add a README "Async events" subsection**

Locate the existing events section in README. After the "Error handling" subsection added in Task 12, append:

```markdown
### Async events

Mark a handler `@EventHandler(async = true)` to run it off the publisher thread:

```java
@EventHandler(async = true)
public void onSlowOperation(SlowEvent event) {
    // ... I/O, network, batch flush ...
}
```

The framework dispatches via a bounded `ThreadPoolExecutor` sized for typical
small-to-medium services:

| Knob              | Value                                              |
|-------------------|----------------------------------------------------|
| Core pool size    | `max(2, cores / 2)`                                |
| Max pool size     | `cores * 4`                                        |
| Keep-alive        | 60 seconds                                         |
| Queue             | bounded `LinkedBlockingQueue` capacity 1024        |
| Rejection policy  | `CallerRunsPolicy` — slows publisher under overload|
| Thread name       | `tiko-event-async-{n}` (daemon)                    |

Workloads with extreme throughput or latency requirements can supply their own:

```java
ExecutorService myExecutor = ...;
TikoOptions opts = TikoOptions.builder()
    .eventExecutor(myExecutor)
    .build();
try (Container container = Tiko.create(opts)) { ... }
```

When you supply your own executor, you own its lifecycle — `Container.shutdown()`
does not stop it. Async handler exceptions still route to the configured
`ErrorHandler` regardless of which executor is in use.
```

- [ ] **Step 3: Verify Javadoc**

Run: `mvn -pl tiko-api javadoc:javadoc -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```
git add tiko-api/src/main/java/io/tiko/annotations/EventHandler.java README.md
git commit -m "docs: document @EventHandler(async) executor and overrides"
```

---

### Task 24: PR 2 final verification

- [ ] **Step 1: Clean reactor build**

Run: `mvn clean install -q`
Expected: BUILD SUCCESS — every module compiles, every test passes.

- [ ] **Step 2: Inspect a generated `EventRegistry` for an example with an async handler**

Run: `find tiko-examples -path '*generated-sources*EventRegistry*'`

For any example that declares `@EventHandler(async = true)`, confirm:
- `HANDLER_INFO_<n>` constant has `async=true` literal
- The dispatcher uses `CompletableFuture.runAsync(..., container.getEventExecutor()).whenComplete(...)`
- The whenComplete block calls `container.getErrorHandler().onError(new EventHandlerError(...))` after unwrapping `CompletionException`

If no example currently uses `@EventHandler(async)`, skip this step or add an annotation temporarily and re-compile.

- [ ] **Step 3: Verify default-executor is bounded under load (smoke check)**

Optional sanity check — run `AsyncEventIntegrationTest` repeatedly:

Run: `mvn -pl tiko-examples/01_basic_di test -Dtest=AsyncEventIntegrationTest -q`
Expected: each run BUILD SUCCESS, no thread exhaustion warnings in logs.

- [ ] **Step 4: Confirm no spec-out-of-scope items leaked into PR 2**

The spec lists `LifecycleError`, `ConfigError`, `ScopeError`, configurable shutdown timeout (#48), Kafka, framework-on-its-own-thread as out-of-scope. Run:

```
grep -rn "LifecycleError\|ConfigError\|ScopeError\|TikoOptions.*shutdownTimeout" tiko-api/src tiko-event-local/src tiko-runtime/src tiko-processor/src
```

Expected: empty.

PR 2 is now ready to push and submit.

---

## Self-review checklist

- [ ] **Spec coverage:** Each requirement in `docs/superpowers/specs/2026-05-07-local-events-stabilization-design.md` maps to at least one task above. The PR breakdown table in the spec aligns with Tasks 1-13 (PR 1) and Tasks 14-24 (PR 2). The internal-design code blocks in the spec are reproduced in the corresponding tasks' implementation steps.
- [ ] **Placeholder scan:** No "TBD", "TODO", "fill in details", or hand-wavy "add appropriate error handling" instructions. Every step has either complete code or an exact command with expected output.
- [ ] **Type consistency:** `EventHandlerInfo` is referenced consistently as `(Class<?>, String, Class<?>, boolean)` across Tasks 1, 6, 8, 9, 16, 20. `EventHandlerError` is `(EventHandlerInfo, Object, Throwable)` across all tasks. `ErrorHandler.onError(ErrorContext)` is consistent. `TikoOptions.Builder` method names (`configSource`, `errorHandler`, `eventExecutor`) match between Tasks 4, 14, integration tests, and Tiko.java wiring.
- [ ] **Sequencing:** Each task's failing-test step references types/methods that earlier tasks have already created. Task 11's integration test depends on Tasks 1-10. Task 22's integration test depends on Tasks 14-21.
- [ ] **Commit frequency:** Each task ends with a commit. PR 1 yields ~13 commits; PR 2 yields ~11.
- [ ] **Spec alignment:** "No silent swallow" contract (spec section 4) is enforced at the dispatcher (Task 9 sync, Task 20 async), at `LocalEventBus.publish` defense-in-depth (Task 7), and at `EventChainContext.publishAsync` whenComplete (Task 17). Sealed `ErrorContext` permits only `EventHandlerError` (Task 2 plus the spec note in Open decisions).
