# Migrate internal logging to `java.lang.System.Logger` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `java.util.logging` with `java.lang.System.Logger` across framework + tiko-api fallback + tiko-config + examples + codegen, introduce a package-private `TikoLog` helper for parameterised messages in tiko-runtime, migrate log-capture tests to a test-scoped `CapturingLoggerFinder`, and add a new `tiko-examples/11_custom_logger` module demonstrating slf4j routing end-to-end.

**Architecture:** Single `TikoLog` package-private helper in `io.tiko.runtime` (used by 4 framework files with parameterised messages). One-call-site files (FallbackErrorHandler, CompositeCoercers, all 4 example handlers) use raw `System.Logger` with the supplier form (`LOG.log(level, () -> "...")`). Test capture uses per-module `CapturingLoggerFinder` instances registered via `META-INF/services/java.lang.System$LoggerFinder` — tests assert on a static records list cleared in `@BeforeEach`. Codegen emits raw `System.Logger` calls (no `TikoLog` import in generated source).

**Tech Stack:** Java 21, JUnit 5, AssertJ, JavaPoet codegen, slf4j 2.0+ (for the new 11_custom_logger example).

**Spec:** `docs/superpowers/specs/2026-05-17-system-logger-migration-design.md` (committed at `8711406` on `feat/system-logger-migration`).

---

## File structure

```
tiko-runtime/src/main/java/io/tiko/runtime/
├── TikoLog.java                            (create — package-private helper)
├── DefaultErrorHandler.java                (modify — switch to TikoLog)
├── LocalEventBus.java                      (modify — switch to TikoLog)
├── EventChainContext.java                  (modify — switch to TikoLog)
└── AggregatingContainer.java               (modify — switch to TikoLog, 3 sites)

tiko-runtime/src/test/java/io/tiko/runtime/
├── TikoLogTest.java                        (create — package-private test)
├── CapturingLoggerFinder.java              (create — test infra)
└── DefaultErrorHandlerTest.java            (rewrite assertion strategy)

tiko-runtime/src/test/resources/META-INF/services/
└── java.lang.System$LoggerFinder           (create — single line: FQN of CapturingLoggerFinder)

tiko-api/src/main/java/io/tiko/
└── FallbackErrorHandler.java               (modify — raw System.Logger, 1 site)

tiko-config/src/main/java/io/tiko/config/internal/coercers/
└── CompositeCoercers.java                  (modify — raw System.Logger supplier form)

tiko-config/src/test/java/io/tiko/config/
└── CapturingLoggerFinder.java              (create — test infra)

tiko-config/src/test/resources/META-INF/services/
└── java.lang.System$LoggerFinder           (create — single line: FQN of CapturingLoggerFinder)

tiko-config/src/test/java/io/tiko/config/internal/coercers/
└── CompositeCoercersTest.java              (modify — dedupe-warning test uses CapturingLoggerFinder)

tiko-processor/src/main/java/io/tiko/processor/generator/
└── ContainerGenerator.java                 (modify — emit System.Logger calls, 5 sites)

tiko-processor/src/test/java/io/tiko/processor/
└── (existing codegen tests)                 (modify — substring assertions updated)

tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/
├── AuditLogger.java                        (modify — raw System.Logger supplier form)
├── NotificationSender.java                 (modify — raw System.Logger supplier form)
├── RequestTimer.java                       (modify — raw System.Logger supplier form)
└── SlowAuditService.java                   (modify — raw System.Logger println-keeps-format-loud)

tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/batch/
└── BatchAuditLogger.java                   (modify — raw System.Logger supplier form)

tiko-examples/11_custom_logger/             (create — new module)
├── pom.xml
├── README.md
└── src/main/
    ├── java/io/tiko/examples/logger/
    │   ├── Main.java
    │   └── FailingComponent.java
    └── resources/
        └── logback.xml

pom.xml                                     (modify — add 11_custom_logger to <modules>)
tiko-examples/pom.xml                       (modify — add 11_custom_logger to <modules>)

README.md                                   (modify — add Logging section)
docs/events.md                              (modify — JUL→System.Logger references)
docs/roadmap.md                             (modify — "What ships today" closes #74)
```

Two `CapturingLoggerFinder` files (one per module) — ~30 LOC each, package-private duplication is acceptable for v1 per the spec's "no shared test-support module" decision.

---

## Task 1: `TikoLog` package-private helper + `TikoLogTest` (TDD)

**Files:**
- Create: `tiko-runtime/src/main/java/io/tiko/runtime/TikoLog.java`
- Create: `tiko-runtime/src/test/java/io/tiko/runtime/TikoLogTest.java`

- [ ] **Step 1: Write the failing test `TikoLogTest.java`**

Create `tiko-runtime/src/test/java/io/tiko/runtime/TikoLogTest.java`:

```java
package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.System.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests for the package-private {@link TikoLog} helper. Lives in the same package so it
 * can see the package-private API directly without exposing the helper publicly.
 */
class TikoLogTest {

    @Test
    void short_circuit_when_level_filtered_does_not_format() {
        AtomicInteger toStringCalls = new AtomicInteger();
        Object expensiveArg = new Object() {
            @Override
            public String toString() {
                toStringCalls.incrementAndGet();
                return "expensive";
            }
        };
        RecordingLogger logger = new RecordingLogger(/* loggable= */ false);

        TikoLog.log(logger, Logger.Level.WARNING, "value is {0}", expensiveArg);

        assertThat(toStringCalls).hasValue(0);
        assertThat(logger.entries).isEmpty();
    }

    @Test
    void format_path_substitutes_parameters() {
        RecordingLogger logger = new RecordingLogger(/* loggable= */ true);

        TikoLog.log(logger, Logger.Level.WARNING, "value is {0}, count {1}", "x", 7);

        assertThat(logger.entries).hasSize(1);
        Entry entry = logger.entries.get(0);
        assertThat(entry.level).isEqualTo(Logger.Level.WARNING);
        assertThat(entry.message).isEqualTo("value is x, count 7");
        assertThat(entry.thrown).isNull();
    }

    @Test
    void throwable_variant_attaches_the_throwable() {
        RecordingLogger logger = new RecordingLogger(/* loggable= */ true);
        IllegalStateException boom = new IllegalStateException("kaboom");

        TikoLog.log(logger, Logger.Level.ERROR, boom, "failed to {0}", "X");

        assertThat(logger.entries).hasSize(1);
        Entry entry = logger.entries.get(0);
        assertThat(entry.level).isEqualTo(Logger.Level.ERROR);
        assertThat(entry.message).isEqualTo("failed to X");
        assertThat(entry.thrown).isSameAs(boom);
    }

    @Test
    void no_args_path_skips_format_step() {
        RecordingLogger logger = new RecordingLogger(/* loggable= */ true);

        TikoLog.log(logger, Logger.Level.INFO, "static message with no placeholders");

        assertThat(logger.entries).hasSize(1);
        // Important: MessageFormat would barf on stray single quotes etc. The no-args branch
        // passes the pattern through verbatim. A pattern with unescaped quotes would prove
        // this — but the assertion above already confirms substring equality without mangling.
        assertThat(logger.entries.get(0).message).isEqualTo("static message with no placeholders");
    }

    private record Entry(Logger.Level level, String message, Throwable thrown) {}

    /** Captures log calls for inspection. */
    private static final class RecordingLogger implements Logger {
        private final boolean loggable;
        final List<Entry> entries = new ArrayList<>();

        RecordingLogger(boolean loggable) {
            this.loggable = loggable;
        }

        @Override
        public String getName() {
            return "test";
        }

        @Override
        public boolean isLoggable(Level level) {
            return loggable;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
            entries.add(new Entry(level, msg, thrown));
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            // Our helper always calls the pre-formatted overload — this should never fire.
            throw new AssertionError("TikoLog should call the no-params overload, not this one");
        }
    }
}
```

- [ ] **Step 2: Run, expect compile failure**

Run: `mvn -pl tiko-runtime test -Dtest=TikoLogTest`
Expected: compile failure — `TikoLog` does not exist.

- [ ] **Step 3: Create `TikoLog.java`**

Create `tiko-runtime/src/main/java/io/tiko/runtime/TikoLog.java`:

```java
package io.tiko.runtime;

import java.text.MessageFormat;

/**
 * Internal logging helper for tiko-runtime framework code. Pre-formats messages via
 * {@link MessageFormat} only when the level is loggable, avoiding allocation for
 * filtered-out levels.
 *
 * <p>Two shapes:
 *
 * <ul>
 *   <li>Parameterised — {@link #log(System.Logger, System.Logger.Level, String, Object...)}</li>
 *   <li>Parameterised + throwable — {@link #log(System.Logger, System.Logger.Level, Throwable, String, Object...)}</li>
 * </ul>
 *
 * <p>For expensive-to-format messages without parameters, prefer the SDK's supplier
 * form directly: {@code LOG.log(level, () -> "computed " + expensive())}.
 *
 * <p><strong>MessageFormat gotcha:</strong> single quotes are special in patterns.
 * Escape by doubling — {@code "deduped ''{0}''"} renders as {@code "deduped 'value'"}.
 * For messages without parameters, the helper bypasses {@code MessageFormat} entirely
 * (no-args branch) so stray quotes are passed through verbatim.
 */
final class TikoLog {

    private TikoLog() {}

    static void log(System.Logger logger, System.Logger.Level level, String pattern, Object... args) {
        if (!logger.isLoggable(level)) return;
        logger.log(level, args.length == 0 ? pattern : MessageFormat.format(pattern, args));
    }

    static void log(
            System.Logger logger,
            System.Logger.Level level,
            Throwable thrown,
            String pattern,
            Object... args) {
        if (!logger.isLoggable(level)) return;
        logger.log(level, args.length == 0 ? pattern : MessageFormat.format(pattern, args), thrown);
    }
}
```

- [ ] **Step 4: Run, expect pass**

Run: `mvn -pl tiko-runtime test -Dtest=TikoLogTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`.

- [ ] **Step 5: Format + commit**

```
mvn -pl '!tiko-bom' spotless:apply
git add tiko-runtime/src/main/java/io/tiko/runtime/TikoLog.java tiko-runtime/src/test/java/io/tiko/runtime/TikoLogTest.java
git commit -m "feat(runtime): TikoLog package-private helper with isLoggable short-circuit"
```

---

## Task 2: `CapturingLoggerFinder` test infrastructure (tiko-runtime + tiko-config)

**Files:**
- Create: `tiko-runtime/src/test/java/io/tiko/runtime/CapturingLoggerFinder.java`
- Create: `tiko-runtime/src/test/resources/META-INF/services/java.lang.System$LoggerFinder`
- Create: `tiko-config/src/test/java/io/tiko/config/CapturingLoggerFinder.java`
- Create: `tiko-config/src/test/resources/META-INF/services/java.lang.System$LoggerFinder`

This task adds the test infrastructure. No production code changes yet; subsequent tasks switch the actual tests to use these capturers.

- [ ] **Step 1: Create tiko-runtime `CapturingLoggerFinder.java`**

Create `tiko-runtime/src/test/java/io/tiko/runtime/CapturingLoggerFinder.java`:

```java
package io.tiko.runtime;

import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-scoped {@link System.LoggerFinder} installed via
 * {@code META-INF/services/java.lang.System$LoggerFinder} on the tiko-runtime test
 * classpath. Captures every {@code System.Logger} call into {@link #RECORDS} so tests
 * can assert on log output without poking at JUL handlers.
 *
 * <p>{@code LoggerFinder} is JVM-wide: once the JDK resolves this finder, every
 * {@code System.getLogger(name)} returns a {@link RecordingLogger} for the remainder
 * of the JVM. Tests that need clean assertions call {@link #clear()} in
 * {@code @BeforeEach}; tests that don't assert just ignore {@code RECORDS}.
 */
public final class CapturingLoggerFinder extends System.LoggerFinder {

    public static final List<LogEntry> RECORDS = new CopyOnWriteArrayList<>();

    public static void clear() {
        RECORDS.clear();
    }

    @Override
    public System.Logger getLogger(String name, Module module) {
        return new RecordingLogger(name);
    }

    public record LogEntry(String loggerName, System.Logger.Level level, String message, Throwable thrown) {}

    private static final class RecordingLogger implements System.Logger {
        private final String name;

        RecordingLogger(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isLoggable(Level level) {
            // Capture everything — tests filter by level after the fact.
            return true;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
            RECORDS.add(new LogEntry(name, level, msg, thrown));
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            // Pre-format with MessageFormat to match what the framework helper produces.
            String msg = (params == null || params.length == 0) ? format : MessageFormat.format(format, params);
            RECORDS.add(new LogEntry(name, level, msg, null));
        }
    }
}
```

- [ ] **Step 2: Register the tiko-runtime finder via ServiceLoader**

Create `tiko-runtime/src/test/resources/META-INF/services/java.lang.System$LoggerFinder` with a single line (no trailing newline mandatory but conventional):

```
io.tiko.runtime.CapturingLoggerFinder
```

- [ ] **Step 3: Create tiko-config `CapturingLoggerFinder.java`**

Create `tiko-config/src/test/java/io/tiko/config/CapturingLoggerFinder.java`:

```java
package io.tiko.config;

import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-scoped {@link System.LoggerFinder} for tiko-config tests. Same shape as the
 * tiko-runtime equivalent — package-private duplication is acceptable for v1 (no shared
 * test-support module).
 */
public final class CapturingLoggerFinder extends System.LoggerFinder {

    public static final List<LogEntry> RECORDS = new CopyOnWriteArrayList<>();

    public static void clear() {
        RECORDS.clear();
    }

    @Override
    public System.Logger getLogger(String name, Module module) {
        return new RecordingLogger(name);
    }

    public record LogEntry(String loggerName, System.Logger.Level level, String message, Throwable thrown) {}

    private static final class RecordingLogger implements System.Logger {
        private final String name;

        RecordingLogger(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
            RECORDS.add(new LogEntry(name, level, msg, thrown));
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            String msg = (params == null || params.length == 0) ? format : MessageFormat.format(format, params);
            RECORDS.add(new LogEntry(name, level, msg, null));
        }
    }
}
```

- [ ] **Step 4: Register the tiko-config finder**

Create `tiko-config/src/test/resources/META-INF/services/java.lang.System$LoggerFinder`:

```
io.tiko.config.CapturingLoggerFinder
```

- [ ] **Step 5: Verify both modules still compile (no test using the finder yet)**

Run: `mvn -pl tiko-runtime,tiko-config test`
Expected: BUILD SUCCESS. The new test classes compile; ServiceLoader is wired but no test consumes `RECORDS` yet so nothing changes behaviourally.

- [ ] **Step 6: Format + commit**

```
mvn -pl '!tiko-bom' spotless:apply
git add tiko-runtime/src/test/java/io/tiko/runtime/CapturingLoggerFinder.java tiko-runtime/src/test/resources/META-INF/services/java.lang.System\$LoggerFinder tiko-config/src/test/java/io/tiko/config/CapturingLoggerFinder.java tiko-config/src/test/resources/META-INF/services/java.lang.System\$LoggerFinder
git commit -m "test: CapturingLoggerFinder + ServiceLoader descriptors in tiko-runtime + tiko-config"
```

(Shell-escape the `$` in the service descriptor filename — `\$LoggerFinder` in bash.)

---

## Task 3: Migrate 4 tiko-runtime framework files to `TikoLog` + update `DefaultErrorHandlerTest`

**Files:**
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/DefaultErrorHandler.java`
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/LocalEventBus.java`
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/EventChainContext.java`
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java`
- Modify: `tiko-runtime/src/test/java/io/tiko/runtime/DefaultErrorHandlerTest.java`

- [ ] **Step 1: Migrate `DefaultErrorHandler.java`**

Replace the file's contents with:

```java
package io.tiko.runtime;

import io.tiko.AutoCloseFailure;
import io.tiko.ConfigurationFailure;
import io.tiko.ErrorContext;
import io.tiko.ErrorHandler;
import io.tiko.EventHandlerError;
import io.tiko.PostConstructFailure;
import io.tiko.PreDestroyFailure;
import io.tiko.ProduceFailure;
import io.tiko.TransportError;

/**
 * Default {@link ErrorHandler} implementation. Logs each {@link ErrorContext} at
 * {@link System.Logger.Level#WARNING} via {@link java.lang.System#getLogger}. Used by
 * {@code Tiko.create(...)} when the user did not supply a custom handler via
 * {@code TikoOptions.errorHandler(...)}.
 *
 * <p>Backed by {@code System.Logger} so the framework requires no logging-binding
 * dependency to start. Default routing is JUL; users on slf4j stacks add a
 * {@code System.LoggerFinder} provider (e.g. {@code slf4j-jdk-platform-logging}).
 *
 * <p>Dispatch is an exhaustive {@code switch} on the sealed {@link ErrorContext}. Adding a
 * new top-level permit makes this class fail to compile until a corresponding case is
 * added — the project's intentional compile-time-loud contract.
 */
public final class DefaultErrorHandler implements ErrorHandler {

    // Lazy holder: defers System.LoggerFinder resolution until the first error fires.
    // Most apps never touch this path on startup, so Tiko.create() pays no logging cost.
    private static final class LoggerHolder {
        static final System.Logger LOG = System.getLogger("io.tiko.events");
    }

    @Override
    public void onError(ErrorContext context) {
        switch (context) {
            case EventHandlerError(var handler, var event, var cause) ->
                TikoLog.log(
                        LoggerHolder.LOG,
                        System.Logger.Level.WARNING,
                        cause,
                        "EventHandler {0}#{1} on event {2} threw: {3}",
                        handler.declaringClass().getName(),
                        handler.methodName(),
                        handler.eventType().getName(),
                        cause);
            case PostConstructFailure(var component, var cause) ->
                TikoLog.log(
                        LoggerHolder.LOG,
                        System.Logger.Level.WARNING,
                        cause,
                        "@PostConstruct on {0} threw: {1}",
                        component.getName(),
                        cause);
            case PreDestroyFailure(var component, var cause) ->
                TikoLog.log(
                        LoggerHolder.LOG,
                        System.Logger.Level.WARNING,
                        cause,
                        "@PreDestroy on {0} threw: {1}",
                        component.getName(),
                        cause);
            case AutoCloseFailure(var component, var cause) ->
                TikoLog.log(
                        LoggerHolder.LOG,
                        System.Logger.Level.WARNING,
                        cause,
                        "AutoCloseable.close() on {0} threw: {1}",
                        component.getName(),
                        cause);
            case ConfigurationFailure f -> {
                // One log line per issue at WARNING — keep them grepable / metric-friendly.
                for (var issue : f.issues()) {
                    TikoLog.log(
                            LoggerHolder.LOG,
                            System.Logger.Level.WARNING,
                            "@Configuration [{0}] {1}",
                            issue.code(),
                            issue.description());
                }
            }
            case ProduceFailure(var declaringClass, var methodName, var cause) ->
                TikoLog.log(
                        LoggerHolder.LOG,
                        System.Logger.Level.WARNING,
                        cause,
                        "@Produces {0}#{1} threw: {2}",
                        declaringClass.getName(),
                        methodName,
                        cause);
            case TransportError t ->
                TikoLog.log(
                        LoggerHolder.LOG,
                        System.Logger.Level.WARNING,
                        t.cause(),
                        "Transport {0} error: {1}",
                        t.transport(),
                        t.cause());
        }
    }
}
```

- [ ] **Step 2: Migrate `LocalEventBus.java`**

Find the existing fields + the `publish` method's catch block. Replace the JUL imports and call:

Imports — drop these:
```java
import java.util.logging.Level;
import java.util.logging.Logger;
```

LoggerHolder block — change `Logger` type to `System.Logger`:
```java
    private static final class LoggerHolder {
        static final System.Logger LOG = System.getLogger(LocalEventBus.class.getName());
    }
```

In `publish(...)`'s `catch (Exception e)` block, replace:
```java
                LoggerHolder.LOG.log(
                        Level.WARNING,
                        String.format("Programmatic event callback threw on event %s: %s", eventType.getName(), e),
                        e);
```

With:
```java
                TikoLog.log(
                        LoggerHolder.LOG,
                        System.Logger.Level.WARNING,
                        e,
                        "Programmatic event callback threw on event {0}: {1}",
                        eventType.getName(),
                        e);
```

Also update the Javadoc on `LocalEventBus` (line ~25-26 currently says "at WARNING via {@link java.util.logging}") — change to "at WARNING via {@link java.lang.System.Logger}".

- [ ] **Step 3: Migrate `EventChainContext.java`**

Drop the imports:
```java
import java.util.logging.Level;
import java.util.logging.Logger;
```

Replace the `logErrorHandlerFailure` method:

```java
    public static void logErrorHandlerFailure(Throwable inner) {
        System.getLogger("io.tiko.events").log(System.Logger.Level.ERROR, "ErrorHandler.onError threw", inner);
    }
```

(Direct `System.Logger` call here — no parameters, single site, no need to route through `TikoLog`. Also: was `Level.SEVERE` in JUL → `System.Logger.Level.ERROR` in the new SPI per the spec's level mapping.)

Update the Javadoc on `logErrorHandlerFailure` (line ~39-43) — change "stays free of {@code java.util.logging}" to "stays free of any logging framework imports".

- [ ] **Step 4: Migrate `AggregatingContainer.java`** — 3 sites

Drop the imports:
```java
import java.util.logging.Level;
import java.util.logging.Logger;
```

Site 1 — in `start()` around line 364:
```java
        // Before
        Logger.getLogger("io.tiko.events").log(Level.WARNING, "ApplicationStartedEvent publish threw", t);
        // After
        System.getLogger("io.tiko.events").log(System.Logger.Level.WARNING, "ApplicationStartedEvent publish threw", t);
```

Site 2 — in `shutdown()` around line 384:
```java
        // Before
        Logger.getLogger("io.tiko.events").log(Level.WARNING, "ApplicationEndingEvent publish threw", t);
        // After
        System.getLogger("io.tiko.events").log(System.Logger.Level.WARNING, "ApplicationEndingEvent publish threw", t);
```

Site 3 — in `shutdown()` around line 394:
```java
        // Before
        Logger.getLogger("io.tiko.events").log(Level.WARNING, "Error shutting down module container", e);
        // After
        System.getLogger("io.tiko.events").log(System.Logger.Level.WARNING, "Error shutting down module container", e);
```

These three sites are all single-message-no-parameters; raw `System.Logger` is appropriate (no need for `TikoLog`). Match the existing inline style.

- [ ] **Step 5: Rewrite `DefaultErrorHandlerTest.java`** to use `CapturingLoggerFinder`

Replace the entire file with:

```java
package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.ErrorContext;
import io.tiko.ErrorHandler;
import io.tiko.EventHandlerError;
import io.tiko.EventHandlerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultErrorHandlerTest {

    @BeforeEach
    void clearCapturedRecords() {
        CapturingLoggerFinder.clear();
    }

    @Test
    void logs_warning_with_class_method_event_type_and_message() {
        ErrorHandler handler = new DefaultErrorHandler();
        EventHandlerInfo info = new EventHandlerInfo(FakeService.class, "onSomething", FakeEvent.class, false);
        IllegalStateException cause = new IllegalStateException("boom");
        ErrorContext ctx = new EventHandlerError(info, new FakeEvent(), cause);

        handler.onError(ctx);

        assertThat(CapturingLoggerFinder.RECORDS)
                .filteredOn(r -> "io.tiko.events".equals(r.loggerName()))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.level()).isEqualTo(System.Logger.Level.WARNING);
                    assertThat(record.thrown()).isSameAs(cause);
                    assertThat(record.message()).contains(FakeService.class.getName());
                    assertThat(record.message()).contains("onSomething");
                    assertThat(record.message()).contains(FakeEvent.class.getName());
                    assertThat(record.message()).contains("boom");
                });
    }

    static class FakeService {}

    record FakeEvent() {}
}
```

- [ ] **Step 6: Run the full tiko-runtime test suite**

Run: `mvn -pl tiko-runtime test`
Expected: BUILD SUCCESS. All existing tests pass; `DefaultErrorHandlerTest` passes via the new capture strategy; the new `TikoLog` tests still pass.

- [ ] **Step 7: Format + commit**

```
mvn -pl '!tiko-bom' spotless:apply
git add tiko-runtime/src/main/java/io/tiko/runtime/DefaultErrorHandler.java tiko-runtime/src/main/java/io/tiko/runtime/LocalEventBus.java tiko-runtime/src/main/java/io/tiko/runtime/EventChainContext.java tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java tiko-runtime/src/test/java/io/tiko/runtime/DefaultErrorHandlerTest.java
git commit -m "feat(runtime): migrate framework logging from JUL to System.Logger via TikoLog"
```

---

## Task 4: Migrate `FallbackErrorHandler.java` (tiko-api)

**Files:**
- Modify: `tiko-api/src/main/java/io/tiko/FallbackErrorHandler.java`

- [ ] **Step 1: Replace the file's contents**

Replace `tiko-api/src/main/java/io/tiko/FallbackErrorHandler.java` with:

```java
package io.tiko;

/**
 * Package-private fallback returned by {@link Container#getErrorHandler()} when a user-supplied
 * {@code Container} implementation does not override the default. The runtime module ships a
 * richer {@code DefaultErrorHandler} via {@code TikoOptions}; this fallback exists so non-runtime
 * {@code Container} implementations remain functional without a binary-incompatible API change.
 */
final class FallbackErrorHandler implements ErrorHandler {

    static final FallbackErrorHandler INSTANCE = new FallbackErrorHandler();

    // Lazy holder: defers System.LoggerFinder resolution until the first error fires.
    private static final class LoggerHolder {
        static final System.Logger LOG = System.getLogger("io.tiko");
    }

    private FallbackErrorHandler() {}

    @Override
    public void onError(ErrorContext context) {
        LoggerHolder.LOG.log(
                System.Logger.Level.WARNING,
                context.getClass().getSimpleName() + ": " + context.cause(),
                context.cause());
    }
}
```

Single log site, simple message + throwable concatenation, no parameters — raw `System.Logger` is appropriate (no `TikoLog` import; tiko-api can't see it anyway since `TikoLog` is package-private in `io.tiko.runtime`).

- [ ] **Step 2: Verify compile + tests**

Run: `mvn -pl tiko-api test`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Format + commit**

```
mvn -pl '!tiko-bom' spotless:apply
git add tiko-api/src/main/java/io/tiko/FallbackErrorHandler.java
git commit -m "feat(api): FallbackErrorHandler uses System.Logger"
```

---

## Task 5: Migrate `CompositeCoercers.java` + update `CompositeCoercersTest` dedupe-warning assertion

**Files:**
- Modify: `tiko-config/src/main/java/io/tiko/config/internal/coercers/CompositeCoercers.java`
- Modify: `tiko-config/src/test/java/io/tiko/config/internal/coercers/CompositeCoercersTest.java`

- [ ] **Step 1: Migrate `CompositeCoercers.java`**

Drop the import:
```java
import java.util.logging.Logger;
```

Update the `LoggerHolder` to use `System.Logger`:

```java
    // Lazy holder — defers System.LoggerFinder resolution until the first
    // duplicate actually fires. Matches the pattern used by DefaultErrorHandler.
    private static final class LoggerHolder {
        static final System.Logger LOG = System.getLogger("io.tiko.config");
    }
```

Update the `set(...)` coercer's warning call from `LoggerHolder.LOG.warning(...)` to the supplier form (single call site, single parameter, deferred via lambda — no `TikoLog` available in tiko-config):

```java
                if (!out.add(coerced)) {
                    LoggerHolder.LOG.log(
                            System.Logger.Level.WARNING,
                            () -> "@Configuration Set<X> field: duplicate value '" + coerced + "' deduped");
                }
```

The lambda body is identical to the old string-concat expression. The supplier form means MessageFormat is never invoked AND the concatenation only runs when the level is loggable.

- [ ] **Step 2: Rewrite the `CompositeCoercersTest.set_coercer_emits_jul_warning_per_duplicate` test**

In `tiko-config/src/test/java/io/tiko/config/internal/coercers/CompositeCoercersTest.java`:

Drop the JUL-handler imports:
```java
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
```

Add the new import for the capture:
```java
import io.tiko.config.CapturingLoggerFinder;
import org.junit.jupiter.api.BeforeEach;
```

Add a `@BeforeEach` method to clear the captured records before each test:
```java
    @BeforeEach
    void clearCapturedLogs() {
        CapturingLoggerFinder.clear();
    }
```

Replace the existing `set_coercer_emits_jul_warning_per_duplicate` test (rename to drop the `_jul_` reference) with:

```java
    @Test
    void set_coercer_emits_warning_per_duplicate() {
        TypeCoercer<Set<String>> c = CompositeCoercers.set(Coercers.stringCoercer());
        c.coerce(List.of("a", "b", "a", "c", "b"));

        assertThat(CapturingLoggerFinder.RECORDS)
                .filteredOn(r -> r.level() == System.Logger.Level.WARNING)
                .filteredOn(r -> "io.tiko.config".equals(r.loggerName()))
                .extracting(CapturingLoggerFinder.LogEntry::message)
                .containsExactly(
                        "@Configuration Set<X> field: duplicate value 'a' deduped",
                        "@Configuration Set<X> field: duplicate value 'b' deduped");
    }
```

- [ ] **Step 3: Run the full tiko-config test suite**

Run: `mvn -pl tiko-config test`
Expected: BUILD SUCCESS — all CompositeCoercersTest cases pass, including the rewritten dedupe-warning test.

- [ ] **Step 4: Format + commit**

```
mvn -pl '!tiko-bom' spotless:apply
git add tiko-config/src/main/java/io/tiko/config/internal/coercers/CompositeCoercers.java tiko-config/src/test/java/io/tiko/config/internal/coercers/CompositeCoercersTest.java
git commit -m "feat(config): CompositeCoercers uses System.Logger supplier form; test via CapturingLoggerFinder"
```

---

## Task 6: Migrate `ContainerGenerator.java` codegen + update affected codegen-assertion tests

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java`
- Modify: any existing codegen-assertion tests that reference the old `Logger.getLogger(` / `Level.WARNING` substrings.

There are FIVE emission sites in `ContainerGenerator` that emit JUL calls. All five use the same pattern:

```java
ClassName logger = ClassName.get("java.util.logging", "Logger");
ClassName level = ClassName.get("java.util.logging", "Level");
// ...
method.addStatement(
        "$T.getLogger($S).log($T.WARNING, $S, __t)",
        logger,
        "io.tiko.events",
        level,
        "some message");
```

Migration target: emit `System.getLogger(...)` directly as a literal string (no import needed since `java.lang.System` is auto-imported), and use `ClassName.get("java.lang", "System", "Logger", "Level")` for the level enum.

- [ ] **Step 1: Find every emission site**

Search the file:
```
grep -n 'java.util.logging' tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java
```

Expected: 4 occurrences of `ClassName.get("java.util.logging", ...)` (two pairs of `Logger` + `Level` locals). Plus 5 `addStatement` blocks that use them.

The two locations (read the file to confirm exact line numbers — they will shift slightly between commits):
- `createScopeDestroyMethod` — around line 872 (1 Logger + 1 Level pair, used in 2 addStatement sites: per-component @PreDestroy and AutoCloseable failure logging).
- `createShutdownMethod` — around line 1000 (1 Logger + 1 Level pair, used in 3 addStatement sites: ApplicationEndingEvent publish failure, drain-timeout warning, per-singleton @PreDestroy failure).

- [ ] **Step 2: Update each emission site**

For each `Logger` + `Level` local declaration pair, replace:

```java
ClassName logger = ClassName.get("java.util.logging", "Logger");
ClassName level = ClassName.get("java.util.logging", "Level");
```

with:

```java
ClassName loggerLevel = ClassName.get("java.lang", "System", "Logger", "Level");
```

(Drop the `logger` local entirely — we'll emit `System.getLogger(...)` as a string literal.)

For each `addStatement` of shape:

```java
method.addStatement(
        "$T.getLogger($S).log($T.WARNING, $S, __t)",
        logger,
        "io.tiko.events",
        level,
        "...message...");
```

Replace with:

```java
method.addStatement(
        "$T.getLogger($S).log($T.WARNING, $S, __t)",
        ClassName.get("java.lang", "System"),
        "io.tiko.events",
        loggerLevel,
        "...message...");
```

The first `$T` becomes `java.lang.System` (auto-imported, no JavaPoet import added). The third `$T` is the level enum class.

For the drain-timeout site that doesn't take a throwable:

```java
method.addStatement(
        "$T.getLogger($S).log($T.WARNING, $S + inFlightGets.get())",
        ClassName.get("java.lang", "System"),
        "io.tiko.events",
        loggerLevel,
        "Container shutdown drain timed out with in-flight get() calls: ");
```

Same shape, no `__t` arg.

Apply this transformation to all 5 emission sites.

- [ ] **Step 3: Run the processor tests, expect some failures**

Run: `mvn -pl tiko-processor test`

Expected: existing codegen-assertion tests (e.g. `ContainerGeneratorShutdownTimeoutTest` from #48) may fail on substring assertions that pin `"awaitTermination(...)"` patterns — those are unrelated and should still pass. But tests that assert on `"Logger.getLogger"` or `"java.util.logging"` substrings will fail.

Identify failing tests:
```
mvn -pl tiko-processor test 2>&1 | grep -E '(FAIL|expected|actual)' | head -30
```

- [ ] **Step 4: Update failing test substrings**

For each failing assertion, change the expected substring from the JUL shape to the System.Logger shape:

| Old substring | New substring |
|---|---|
| `Logger.getLogger(` | `System.getLogger(` |
| `java.util.logging.Logger` | `System.Logger` (if asserting on the type) |
| `Level.WARNING` | `Logger.Level.WARNING` (with JavaPoet's qualified output) or `System.Logger.Level.WARNING` |
| `java.util.logging.Level.WARNING` | `java.lang.System.Logger.Level.WARNING` |

**Note on JavaPoet's import behavior**: `ClassName.get("java.lang", "System", "Logger", "Level")` will likely render as `System.Logger.Level.WARNING` in the generated source (no import needed since `java.lang.System` is auto-imported). Verify by reading the generated source after the codegen change.

- [ ] **Step 5: Re-run tests, expect pass**

Run: `mvn -pl tiko-processor test`
Expected: BUILD SUCCESS. All codegen tests pass with the updated substrings.

- [ ] **Step 6: Format + commit**

```
mvn -pl '!tiko-bom' spotless:apply
git add tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java tiko-processor/src/test/java/io/tiko/processor/
git commit -m "feat(processor): generated container uses System.Logger; codegen tests updated"
```

---

## Task 7: Migrate 4 example handlers to raw `System.Logger` supplier form

**Files:**
- Modify: `tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/AuditLogger.java`
- Modify: `tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/NotificationSender.java`
- Modify: `tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/RequestTimer.java`
- Modify: `tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/batch/BatchAuditLogger.java`

Mechanical migration: drop JUL imports, change `Logger` → `System.Logger`, change `Logger.getLogger(...)` → `System.getLogger(...)`, change `LOG.info(() -> ...)` → `LOG.log(System.Logger.Level.INFO, () -> ...)`.

- [ ] **Step 1: Migrate `AuditLogger.java`**

Replace the file:

```java
package io.tiko.examples.http;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;

/** Synchronous audit handler. Runs inline before {@code EventBus.publish} returns. */
@Component(scope = Scope.SINGLETON)
public class AuditLogger {

    private static final System.Logger LOG = System.getLogger("io.tiko.examples.http.audit");

    @EventHandler
    public void onTicketCreated(TicketCreated event) {
        LOG.log(System.Logger.Level.INFO,
                () -> "[AUDIT req=" + event.requestId() + "] ticket " + event.id() + " '" + event.title()
                        + "' created at " + event.createdAt());
    }
}
```

- [ ] **Step 2: Migrate `NotificationSender.java`**

Change the `Logger` import and field/call:

Drop:
```java
import java.util.logging.Logger;
```

Replace the field:
```java
    private static final System.Logger LOG = System.getLogger("io.tiko.examples.http.notify");
```

Replace the handler body:
```java
    @EventHandler(async = true)
    public void onTicketCreated(TicketCreated event) {
        LOG.log(System.Logger.Level.INFO,
                () -> "[NOTIFY req=" + event.requestId() + "] would email about ticket " + event.id());
        latch.get().countDown();
    }
```

- [ ] **Step 3: Migrate `RequestTimer.java`**

Drop:
```java
import java.util.logging.Logger;
```

Replace the field:
```java
    private static final System.Logger LOG = System.getLogger("io.tiko.examples.http.timer");
```

Replace the two handler bodies:
```java
    @EventHandler
    public void onRequestStarted(RequestStartedEvent event) {
        startedCount.incrementAndGet();
        LOG.log(System.Logger.Level.INFO,
                () -> "[REQ " + event.requestId() + "] started at " + event.timestamp());
    }

    @EventHandler
    public void onRequestEnding(RequestEndingEvent event) {
        endedCount.incrementAndGet();
        LOG.log(System.Logger.Level.INFO,
                () -> "[REQ " + event.requestId() + "] completed in " + event.duration());
    }
```

- [ ] **Step 4: Migrate `BatchAuditLogger.java`**

Drop:
```java
import java.util.logging.Logger;
```

Replace the field:
```java
    private static final System.Logger LOG = System.getLogger("io.tiko.examples.persistence.batch");
```

Replace the handler body:
```java
    @EventHandler
    public void onEventEnding(EventEndingEvent event) {
        UUID id = current.orderId();
        if (id != null) {
            seen.add(id);
            LOG.log(System.Logger.Level.INFO, () -> "[batch-audit] processing order " + id);
        }
    }
```

- [ ] **Step 5: Check `SlowAuditService.java` — does it use JUL?**

Read `tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/SlowAuditService.java`. From #48 it uses `System.out.println` directly (not a logger), so no migration needed. Confirm and skip if true.

- [ ] **Step 6: Run example modules' tests**

Run: `mvn -pl tiko-examples/09_http_javalin,tiko-examples/10_persistence_jdbc test`
Expected: BUILD SUCCESS — all existing tests pass with the new logger shape (the supplier form is functionally equivalent to the old JUL `LOG.info(() -> ...)`).

- [ ] **Step 7: Format + commit**

```
mvn -pl '!tiko-bom' spotless:apply
git add tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/AuditLogger.java tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/NotificationSender.java tiko-examples/09_http_javalin/src/main/java/io/tiko/examples/http/RequestTimer.java tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/batch/BatchAuditLogger.java
git commit -m "feat(examples): example handlers use System.Logger supplier form"
```

---

## Task 8: Create `tiko-examples/11_custom_logger` module demonstrating slf4j routing

**Files:**
- Modify: `pom.xml` (root) — add `tiko-examples/11_custom_logger` to `<modules>` (probably indirectly via `tiko-examples/pom.xml`)
- Modify: `tiko-examples/pom.xml` — add `<module>11_custom_logger</module>`
- Create: `tiko-examples/11_custom_logger/pom.xml`
- Create: `tiko-examples/11_custom_logger/README.md`
- Create: `tiko-examples/11_custom_logger/src/main/java/io/tiko/examples/logger/Main.java`
- Create: `tiko-examples/11_custom_logger/src/main/java/io/tiko/examples/logger/FailingComponent.java`
- Create: `tiko-examples/11_custom_logger/src/main/resources/logback.xml`

- [ ] **Step 1: Add the module to the reactor**

First check if `tiko-examples/pom.xml` exists as a parent aggregator (read it if so). Add `<module>11_custom_logger</module>` to its `<modules>` list in numerical order (between `<module>10_persistence_jdbc</module>` and whatever closes the list).

If the root `pom.xml` directly lists individual example modules instead of aggregating through `tiko-examples/pom.xml`, add the entry there. Read both poms to determine the actual layout before editing.

- [ ] **Step 2: Create `tiko-examples/11_custom_logger/pom.xml`**

Mirror the structure of `tiko-examples/09_http_javalin/pom.xml` minus the Javalin/Jackson bits and plus slf4j-jdk-platform-logging + logback:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.tiko.examples</groupId>
        <artifactId>tiko-examples</artifactId>
        <version>0.1.0</version>
    </parent>

    <artifactId>11_custom_logger</artifactId>
    <packaging>jar</packaging>
    <name>11 - Custom Logger Routing Example</name>
    <description>Routes tiko's framework logs through slf4j + logback via the System.LoggerFinder SPI.</description>

    <dependencies>
        <dependency>
            <groupId>io.tiko</groupId>
            <artifactId>tiko-api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>io.tiko</groupId>
            <artifactId>tiko-processor</artifactId>
            <version>${project.version}</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>io.tiko</groupId>
            <artifactId>tiko-runtime</artifactId>
            <version>${project.version}</version>
        </dependency>

        <!--
            slf4j-jdk-platform-logging is the System.LoggerFinder bridge from
            java.lang.System.Logger to slf4j. Combined with a backend (logback below),
            every framework log goes through slf4j with no Tiko-side code.
        -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-jdk-platform-logging</artifactId>
        </dependency>
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
        </dependency>

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

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>${maven-shade.version}</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals>
                            <goal>shade</goal>
                        </goals>
                        <configuration>
                            <transformers>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <mainClass>io.tiko.examples.logger.Main</mainClass>
                                </transformer>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                            </transformers>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

If `slf4j-jdk-platform-logging` and `logback-classic` versions aren't already in the parent BOM, add them to `tiko-bom/pom.xml` `<dependencyManagement>` first. Latest versions at the time of writing: slf4j 2.0.16, logback 1.5.12 — verify on Maven Central before committing.

- [ ] **Step 3: Create `FailingComponent.java`**

```java
package io.tiko.examples.logger;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PreDestroy;

/**
 * Has a {@code @PreDestroy} that throws — exercises {@code DefaultErrorHandler}'s
 * log path during container teardown, producing a framework WARNING that flows
 * through slf4j → logback.
 */
@Component(scope = Scope.SINGLETON)
public class FailingComponent {

    @PreDestroy
    public void cleanup() {
        throw new IllegalStateException("simulated teardown failure");
    }
}
```

- [ ] **Step 4: Create `Main.java`**

```java
package io.tiko.examples.logger;

import io.tiko.Container;
import io.tiko.runtime.Tiko;

/**
 * Bootstrap that demonstrates routing tiko's framework logs through slf4j + logback.
 *
 * <p>Drops two deps into {@code pom.xml}:
 *
 * <ul>
 *   <li>{@code slf4j-jdk-platform-logging} — the {@code System.LoggerFinder} bridge.</li>
 *   <li>{@code logback-classic} — the slf4j backend that owns the output format.</li>
 * </ul>
 *
 * <p>Plus a {@code logback.xml} on the classpath with a recognizable pattern. That's it —
 * no tiko-side code needed, no {@code TikoOptions} wiring.
 *
 * <p>This main forces an instantiation of {@link FailingComponent} (whose {@code @PreDestroy}
 * throws), then closes the container. The thrown exception is routed through
 * {@code DefaultErrorHandler}, which logs a WARNING. Logback formats and prints it.
 *
 * <p>Look for {@code WARN [io.tiko.events]} in the output — that prefix proves logback owns
 * the formatting (JUL's default format is recognizably different).
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        try (Container container = Tiko.create()) {
            // Force instantiation so @PreDestroy will fire on close.
            container.get(FailingComponent.class);
        }
        System.out.println("[main] container closed cleanly");
    }
}
```

- [ ] **Step 5: Create `logback.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <!-- Distinctive format so the example's expected output is unambiguous: framework
                 logs that flow through this stack will look like:
                 WARN  [io.tiko.events] @PreDestroy on io.tiko.examples.logger.FailingComponent threw -->
            <pattern>%-5level [%logger{30}] %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="STDOUT" />
    </root>
</configuration>
```

- [ ] **Step 6: Create `README.md`**

Create `tiko-examples/11_custom_logger/README.md`:

```markdown
# 11 — Custom logger routing (slf4j + logback)

Demonstrates how to route tiko's framework logs through slf4j + a logback backend by
adding two dependencies to your `pom.xml`. No tiko-side code needed.

## What it does

`Main` boots tiko, forces instantiation of a `FailingComponent` whose `@PreDestroy`
throws, then closes the container. The thrown exception is routed through
`DefaultErrorHandler`, which logs a WARNING via `java.lang.System.Logger`. Because
`slf4j-jdk-platform-logging` is on the classpath, that log call flows through slf4j
into logback, which formats and prints it using the pattern in `logback.xml`.

## The recipe

Add two deps to your `pom.xml`:

```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-jdk-platform-logging</artifactId>
</dependency>
<dependency>
    <groupId>ch.qos.logback</groupId>
    <artifactId>logback-classic</artifactId>
</dependency>
```

Add a `logback.xml` on your classpath (this example's is in `src/main/resources/`).

That's it. Every framework log goes through slf4j → logback.

## Expected output when you run `Main`

```
WARN  [io.tiko.events] @PreDestroy on io.tiko.examples.logger.FailingComponent threw: java.lang.IllegalStateException: simulated teardown failure
[main] container closed cleanly
```

The `WARN [io.tiko.events]` prefix matches logback's configured pattern — JUL's
default format is recognizably different (`Aug 17, 2026 ... WARNING:`), so seeing this
shape proves the routing works.

## Other backends

Same pattern works for **log4j2** (`log4j-jpl` + `log4j-core`) and any other
`System.LoggerFinder` provider. See the project's main README under "Logging" for the
brief recipe per backend.
```

- [ ] **Step 7: Build the module + run manually to verify**

Run: `mvn -pl tiko-examples/11_custom_logger -am package -DskipTests`
Expected: BUILD SUCCESS — shaded jar produced.

Run the shaded jar manually (path will be something like `tiko-examples/11_custom_logger/target/11_custom_logger-0.1.0.jar`):
```
java -jar tiko-examples/11_custom_logger/target/11_custom_logger-0.1.0.jar
```

Expected console output:
```
WARN  [io.tiko.events] @PreDestroy on io.tiko.examples.logger.FailingComponent threw: ...
[main] container closed cleanly
```

The `WARN [...]` prefix MUST be there — that's logback's format, not JUL's default. If you see something like `Aug 17, 2026 ... WARNING:`, the slf4j bridge isn't on the classpath; fix the pom.

- [ ] **Step 8: Format + commit**

```
mvn -pl '!tiko-bom' spotless:apply
git add tiko-examples/11_custom_logger/ tiko-examples/pom.xml pom.xml tiko-bom/pom.xml
git commit -m "feat(examples): 11_custom_logger demonstrates slf4j+logback routing via System.LoggerFinder"
```

(Stage only the actual files touched — if you didn't edit `tiko-bom/pom.xml` or the root `pom.xml`, skip those.)

---

## Task 9: README "Logging" section + `docs/events.md` JUL references + roadmap entry

**Files:**
- Modify: `README.md`
- Modify: `docs/events.md`
- Modify: `docs/roadmap.md`

- [ ] **Step 1: Add the Logging section to `README.md`**

In `README.md`, find a sensible insertion point. The existing sections (in order) include `## Annotations at a glance`, `## Runnable examples`, `## Measured cold-start`, `## Modules`. The Logging section fits naturally between `## Annotations at a glance` and `## Runnable examples` (it's another user-facing affordance) OR between `## Modules` and `## Documentation`. Pick one based on what reads cleanly.

Insert this section:

```markdown
## Logging

Tiko logs through `java.lang.System.Logger` — the JDK-standard SPI introduced in
Java 9. There is no tiko-side configuration knob, no SPI to implement, no
adapter module.

**Default:** routes through `java.util.logging`. Nothing to configure for "just works."

**Routing to slf4j:** add `slf4j-jdk-platform-logging` + your slf4j backend
(logback, log4j-slf4j2-impl, slf4j-simple, etc.). See
[`tiko-examples/11_custom_logger`](./tiko-examples/11_custom_logger) for a
runnable example.

**Routing to log4j2:** add `log4j-jpl` + the log4j2 core. Same mechanism, different bridge.

**Routing to JBoss Logging:** JBoss Logging uses `JulLogManager` rather than a
`LoggerFinder` — set `-Djava.util.logging.manager=org.jboss.logmanager.LogManager`.

The single tiko-side knob remains `TikoOptions.errorHandler(...)` for handler-exception
policy — a different layer than framework logging.
```

If the existing Runnable examples table lists numbered modules, add row 11:
```
| 11 | [`11_custom_logger`](./tiko-examples/11_custom_logger)                | Routing framework logs through slf4j + logback via `System.LoggerFinder`                                |
```

- [ ] **Step 2: Update `docs/events.md`**

Find the Error handling section (around line 59). The current text says:

```
If an `@EventHandler` method throws, the exception is routed to the configured `ErrorHandler` (default: logs at `WARNING` via `java.util.logging`). It does not propagate to the publisher and does not prevent other handlers from running.

The framework itself has zero logging-binding dependencies — JUL is in the JDK, so `Tiko.create()` works without adding any logging artifact to your classpath.
```

Replace with:

```
If an `@EventHandler` method throws, the exception is routed to the configured `ErrorHandler` (default: logs at `WARNING` via `java.lang.System.Logger`). It does not propagate to the publisher and does not prevent other handlers from running.

The framework itself has zero logging-binding dependencies — `System.Logger` is in the JDK, so `Tiko.create()` works without adding any logging artifact to your classpath. Its default routing is JUL; route through slf4j or log4j2 by adding their `LoggerFinder` bridge to your classpath. See the [Logging section](../README.md#logging) in the main README.
```

If there are any other `java.util.logging` or `JUL` references in `docs/events.md`, update them similarly. Run a final grep to confirm:
```
grep -n 'java.util.logging\|JUL' docs/events.md
```

- [ ] **Step 3: Update `docs/roadmap.md`**

In the **"What ships today"** block (after the last `✅` entry), append:

```markdown
- ✅ Internal logging via `java.lang.System.Logger` — framework, codegen, and example handlers now use the JDK-standard `System.Logger` SPI. Default routing stays JUL (zero-config "just works"); users plug in slf4j or log4j2 by adding the appropriate `LoggerFinder` bridge to their classpath. New `tiko-examples/11_custom_logger` demonstrates the slf4j recipe end-to-end. (Closes #74.)
```

In the **Phase 2** section, find the bullet that references #74 and remove it. If #74 is the only item left on its bullet, delete the bullet entirely.

- [ ] **Step 4: Commit**

```
git add README.md docs/events.md docs/roadmap.md
git commit -m "docs: Logging section in README; events.md updated for System.Logger; roadmap closes #74"
```

---

## Task 10: Cold-start regression check + final reactor build + push + PR

- [ ] **Step 1: Run the cold-start harness on the feature branch**

Per memory `feedback_coldstart_comparison_methodology`: A/B must be back-to-back on the same warm machine. Since we can't easily flip between branches as the implementer, we'll run the harness on the feature branch and compare against the historical baseline.

```
cd comparisons
pwsh ./run-all.ps1 -Only tiko
```

Expected: produces wall-time numbers for tiko's cold-start. The known JUL baseline from memory `project_slf4j_cold_start_cost` is roughly 202ms wall (+16ms over dagger).

Capture the resulting numbers from `comparisons/results/`. The migration's expected impact is **no measurable regression** — `System.Logger`'s default backend IS JUL, so cold-start cost should match the prior baseline within noise.

- [ ] **Step 2: Verify the numbers**

If the new run's wall time is within ~10% of the historical 202ms baseline → ship.

If it's significantly higher (e.g. +50ms), the migration introduced an unexpected cost. Diagnose before pushing — common culprits would be eager `System.Logger` initialization or accidentally pulling slf4j into the wrong module.

Report the numbers in the PR body (Task 10 Step 5 below).

- [ ] **Step 3: Run the full reactor build**

```
mvn -pl '!tiko-bom' install
```

Expected: BUILD SUCCESS. All modules build, all tests pass.

- [ ] **Step 4: Push**

```
git push -u origin feat/system-logger-migration
```

- [ ] **Step 5: Open the PR**

```bash
"C:/Program Files/GitHub CLI/gh.exe" pr create \
    --title "feat: migrate internal logging to java.lang.System.Logger (#74)" \
    --body "$(cat <<'EOF'
## Summary

Closes #74. Replaces `java.util.logging` with `java.lang.System.Logger` across:

- **Framework code** — 4 files in tiko-runtime use a new package-private `TikoLog` helper that pre-formats via `MessageFormat` only when the level is loggable.
- **Single-call-site files** — `FallbackErrorHandler` (tiko-api) and `CompositeCoercers` (tiko-config) use raw `System.Logger` directly; the latter uses the SDK's supplier form (`LOG.log(level, () -> "...")`) for deferred formatting.
- **Example handlers** — 4 files across `09_http_javalin` + `10_persistence_jdbc` switch to `System.Logger` with the supplier form, demonstrating the user-facing best practice.
- **Codegen** — `ContainerGenerator` emits `System.getLogger(...)` + `System.Logger.Level.*` calls instead of JUL.
- **Tests** — `DefaultErrorHandlerTest` (tiko-runtime) and `CompositeCoercersTest` dedupe-warning case (tiko-config) capture log records via a test-scoped `System.LoggerFinder` registered through `META-INF/services/`.

Plus a new `tiko-examples/11_custom_logger` module demonstrating slf4j+logback routing end-to-end — two pom deps + a `logback.xml`, no tiko-side code needed.

Spec at `docs/superpowers/specs/2026-05-17-system-logger-migration-design.md`. Plan at `docs/superpowers/plans/2026-05-17-system-logger-migration.md`.

### Key pieces

- **`TikoLog`** — package-private in `io.tiko.runtime`, 2 static methods (`log(...)` and `log(..., Throwable, ...)`), internal `isLoggable` short-circuit so MessageFormat doesn't run when the level is filtered. Used only by the 4 tiko-runtime framework files with parameterised messages.
- **`CapturingLoggerFinder`** — per-module duplication in tiko-runtime + tiko-config test sources, ~30 LOC each. Captures all `System.Logger` calls into a static records list cleared in `@BeforeEach`.
- **`tiko-examples/11_custom_logger`** — runnable demo: `FailingComponent.@PreDestroy` throws → `DefaultErrorHandler` logs WARNING → slf4j → logback formats as `WARN [io.tiko.events] ...`.
- **Public API surface unchanged.** `TikoLog` is package-private. No new `TikoOptions` knob. No new module dependencies pulled into tiko-runtime / tiko-config / tiko-api.

### Test plan

- [x] `TikoLogTest` — 4 cases: short-circuit when level filtered, format-path substitutes params, throwable variant attaches throwable, no-args path skips MessageFormat.
- [x] `DefaultErrorHandlerTest` — rewritten to assert via `CapturingLoggerFinder.RECORDS`.
- [x] `CompositeCoercersTest` dedupe-warning case — rewritten similarly for tiko-config's `CapturingLoggerFinder`.
- [x] Affected codegen-assertion tests updated for new `System.getLogger(...)` substring pattern.
- [x] `11_custom_logger` builds and runs manually — output shows `WARN [io.tiko.events]` (logback format), proving the slf4j bridge is wired.
- [x] Full reactor `mvn -pl '!tiko-bom' install` green; Spotless clean.

### Cold-start regression check

Per memory's methodology, ran `comparisons/run-all.ps1 -Only tiko` on the feature branch and compared against the prior JUL baseline (~202ms wall per `project_slf4j_cold_start_cost`):

- **Feature branch wall time:** _<fill in from Step 1 results>_
- **Within noise of baseline** (~202ms): _<yes/no>_

`System.Logger`'s default backend IS JUL, so cold-start cost should match the baseline within noise. If meaningfully higher, diagnose before merging.

### Backwards compatibility

Pure refactor of internal logging. No public API change. Default routing remains JUL — every existing user gets the same log output. Adding a `LoggerFinder` provider is opt-in and additive.

### Out of scope

- Structured logging / MDC / volume controls — users get those via whatever backend their `LoggerFinder` provider routes to.
- `ErrorHandler` semantics — different layer, unchanged.
- log4j2 / JBoss Logging dedicated example modules — README documents the recipe; only slf4j gets a runnable example.
- Per-level convenience methods on `TikoLog` (`warn`, `error`, etc.) — 2 generic methods keep the surface minimal.

EOF
)"
```

- [ ] **Step 6: Watch CI**

```
"C:/Program Files/GitHub CLI/gh.exe" pr checks --watch
```

Expected: all checks pass. If Spotless fails, `mvn -pl '!tiko-bom' spotless:apply` locally, commit, push.

- [ ] **Step 7: Hand off for manual merge**

Per project policy (branch protection), the user merges in the GitHub UI. After confirmation:

```
git checkout main
git pull --ff-only
git branch -d feat/system-logger-migration
git fetch --prune origin
```

---

## Done

Internal logging migrated from JUL to `java.lang.System.Logger` across framework + codegen + tiko-api + tiko-config + 4 example handlers. New `11_custom_logger` example demonstrates slf4j routing. Issue #74 closes; **Phase 2 milestone now has zero open issues**.
