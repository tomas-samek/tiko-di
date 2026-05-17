# Migrate internal logging from `java.util.logging` to `java.lang.System.Logger`

**Issue:** [#74](https://github.com/tomas-samek/tiko-di/issues/74) — Phase 2 — Configuration & distributed events.

## Goal

Switch all tiko log sites from `java.util.logging.Logger` to `java.lang.System.Logger` (JEP 264, available since Java 9). Consumers route framework logs to their preferred stack by adding a `java.lang.System.LoggerFinder` provider on the classpath — no tiko-side adapter module, no `TikoOptions` wiring, no new SPI.

This PR also covers the full repo (not just framework): example handlers migrate too, and a new `tiko-examples/11_custom_logger` demonstrates routing through slf4j end-to-end.

## Architecture

Five concerns:

1. **Pre-format ourselves with `MessageFormat`.** Don't rely on any `LoggerFinder` bridge's interpretation of `{0}` placeholders. Pass an already-formatted `String` (plus optional `Throwable`) to `System.Logger.log(...)`.
2. **One internal `TikoLog` helper**, package-private in `io.tiko.runtime`, used only by the four tiko-runtime framework files with parameterised messages. Two static methods (`log(...)` and `log(..., Throwable, ...)`) with an internal `isLoggable` short-circuit so MessageFormat doesn't run when the level is filtered.
3. **Tests capture via `CapturingLoggerFinder`**, installed per-module via `META-INF/services/java.lang.System$LoggerFinder` on the test classpath. Tests assert on a static records list cleared in `@BeforeEach`.
4. **Examples migrate too** — handlers in `09_http_javalin` and `10_persistence_jdbc` switch to `System.Logger`. They use the SDK's supplier form (`LOG.log(level, () -> "...")`) directly — TikoLog is internal and unavailable to user code, which actively demonstrates what real user logging looks like.
5. **New `tiko-examples/11_custom_logger`** demonstrates slf4j routing via `slf4j-jdk-platform-logging` + a backend. README documents the recipe; example proves it works under CI.

No new public API surface. No new module. No new `TikoOptions` knob.

## Migration map

| File | Strategy |
|---|---|
| `tiko-runtime/.../DefaultErrorHandler.java` | `TikoLog.log(LOG, level, …, pattern, args)` |
| `tiko-runtime/.../LocalEventBus.java` | `TikoLog.log(...)` |
| `tiko-runtime/.../EventChainContext.java` | `TikoLog.log(...)` |
| `tiko-runtime/.../AggregatingContainer.java` | `TikoLog.log(...)` |
| `tiko-api/.../FallbackErrorHandler.java` | raw `LOG.log(WARNING, msg, throwable)` — one site, no params |
| `tiko-config/.../CompositeCoercers.java` | raw `LOG.log(WARNING, () -> "...")` — supplier form, one site, single param |
| `tiko-examples/09_http_javalin/.../AuditLogger.java` | raw `LOG.log(level, () -> "...")` |
| `tiko-examples/09_http_javalin/.../NotificationSender.java` | raw `LOG.log(level, () -> "...")` |
| `tiko-examples/09_http_javalin/.../RequestTimer.java` | raw `LOG.log(level, () -> "...")` |
| `tiko-examples/10_persistence_jdbc/.../BatchAuditLogger.java` | raw `LOG.log(level, () -> "...")` |
| `tiko-processor/.../ContainerGenerator.java` (codegen) | emits raw `System.Logger` calls into generated `TikoContainerImpl` |

`LoggerHolder` lazy-init pattern preserved across all sites — `System.getLogger(name)` is still potentially expensive on cold start.

## `TikoLog` helper

**File:** `tiko-runtime/src/main/java/io/tiko/runtime/TikoLog.java`

**Visibility:** package-private (no `public` on the class). Visible only within `io.tiko.runtime` — the four framework files using it all live in that package.

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

**Call-site shape comparison:**

```java
// Before (JUL with String.formatted)
LOG.log(Level.WARNING,
        "EventHandler %s#%s on event %s threw: %s".formatted(
                handler.declaringClass().getName(),
                handler.methodName(),
                handler.eventType().getName(),
                cause),
        cause);

// After (TikoLog with MessageFormat)
TikoLog.log(LOG, WARNING, cause,
        "EventHandler {0}#{1} on event {2} threw: {3}",
        handler.declaringClass().getName(),
        handler.methodName(),
        handler.eventType().getName(),
        cause);
```

Throwable parameter comes BEFORE the pattern in the 5+ arg overload so it's positionally explicit and unambiguous.

## Test capture: `CapturingLoggerFinder`

Tests that previously installed JUL handlers (`DefaultErrorHandlerTest` in tiko-runtime, the dedupe-warning case in `CompositeCoercersTest` in tiko-config) install a `CapturingLoggerFinder` instead.

**Per-module duplication, ~30 LOC each, package-private:**

- `tiko-runtime/src/test/java/io/tiko/runtime/CapturingLoggerFinder.java`
- `tiko-config/src/test/java/io/tiko/config/CapturingLoggerFinder.java`

Each module also adds a service descriptor on the test classpath:

- `tiko-runtime/src/test/resources/META-INF/services/java.lang.System$LoggerFinder`
- `tiko-config/src/test/resources/META-INF/services/java.lang.System$LoggerFinder`

Each file contains the FQN of that module's `CapturingLoggerFinder` (single line).

**Sketch (identical shape in both modules):**

```java
final class CapturingLoggerFinder extends System.LoggerFinder {

    static final List<LogEntry> RECORDS = new CopyOnWriteArrayList<>();

    static void clear() {
        RECORDS.clear();
    }

    @Override
    public System.Logger getLogger(String name, Module module) {
        return new RecordingLogger(name);
    }

    record LogEntry(String loggerName, System.Logger.Level level, String message, Throwable thrown) {}

    private static final class RecordingLogger implements System.Logger {
        private final String name;
        RecordingLogger(String name) { this.name = name; }
        @Override public String getName() { return name; }
        @Override public boolean isLoggable(Level level) { return true; }
        @Override public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
            RECORDS.add(new LogEntry(name, level, msg, thrown));
        }
        @Override public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            RECORDS.add(new LogEntry(name, level, MessageFormat.format(format, params), null));
        }
    }
}
```

`isLoggable` returns `true` so every level passes through — test-mode default. Tests that need to assert on filtering can instrument differently.

**Test pattern:**

```java
@BeforeEach
void clearLogs() {
    CapturingLoggerFinder.clear();
}

@Test
void someTestThatExpectsAWarning() {
    triggerTheThing();

    assertThat(CapturingLoggerFinder.RECORDS)
            .filteredOn(r -> r.level() == System.Logger.Level.WARNING)
            .extracting(CapturingLoggerFinder.LogEntry::message)
            .containsExactly("expected message");
}
```

**JVM-wide caveat:** `LoggerFinder` is resolved once per JVM. The test finder applies to ALL tests in that module's test JVM, including ones that don't assert on logs — they just don't read `RECORDS`. The `clear()` per-`@BeforeEach` keeps test isolation tight.

## Examples migration

**Existing example handlers** (4 files across 2 modules) switch their JUL imports and call sites to `System.Logger` with the supplier form:

```java
// Before
private static final Logger LOG = Logger.getLogger("io.tiko.examples.http.audit");

@EventHandler
public void onTicketCreated(TicketCreated event) {
    LOG.info(() -> "[AUDIT req=" + event.requestId() + "] ticket " + event.id() + " created");
}

// After
private static final System.Logger LOG = System.getLogger("io.tiko.examples.http.audit");

@EventHandler
public void onTicketCreated(TicketCreated event) {
    LOG.log(System.Logger.Level.INFO,
            () -> "[AUDIT req=" + event.requestId() + "] ticket " + event.id() + " created");
}
```

The supplier form means examples don't depend on TikoLog and demonstrate the user-facing best practice: deferred formatting via lambda when the level is filtered.

## New example: `tiko-examples/11_custom_logger`

Demonstrates slf4j routing — the production-grade recipe.

```
11_custom_logger/
├── pom.xml                            # tiko + slf4j-jdk-platform-logging + logback-classic
├── README.md                          # the dep + config recipe
├── src/main/java/io/tiko/examples/logger/
│   ├── Main.java                      # bootstrap that triggers framework + user logs
│   └── FailingComponent.java          # a @PreDestroy that throws to exercise DefaultErrorHandler
└── src/main/resources/
    └── logback.xml                    # a recognizable pattern like `%level [%logger{30}] %msg%n`
```

**`FailingComponent.java`** — a `@Component(SINGLETON)` whose `@PreDestroy` throws an exception. This exercises the `DefaultErrorHandler` log path, producing a framework warning that flows through slf4j → logback.

**`Main.java`** — bootstraps tiko (no `TikoOptions`-level config needed), retrieves the component to force instantiation, and closes. Closing triggers `@PreDestroy`, which throws, which produces a framework log. logback formats it.

**Expected console output:**

```
WARN  [io.tiko.events] PreDestroyFailure: io.tiko.examples.logger.FailingComponent threw
java.lang.IllegalStateException: simulated teardown failure
    at io.tiko.examples.logger.FailingComponent.cleanup(FailingComponent.java:18)
    ...
```

The `WARN [io.tiko.events]` prefix proves logback owns the formatting — JUL's default format is recognizably different.

**`pom.xml` deps** beyond the standard tiko trio:

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

`slf4j-jdk-platform-logging` ships a `System.LoggerFinder` implementation that forwards everything to slf4j. `logback-classic` is the backend that actually writes the output. No tiko-side wiring needed.

**`README.md`** explains:

1. Drop `slf4j-jdk-platform-logging` + your slf4j backend of choice into your pom.
2. Configure the backend (here, `logback.xml`).
3. Done — every framework log goes through slf4j.

Versions managed by the BOM where possible; if these libs aren't already in the BOM, add them.

## `README.md` Logging section

Add a new top-level section:

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

## Cold-start regression check

Per memory `feedback_coldstart_comparison_methodology`: A/B must be back-to-back on the same warm machine.

**Methodology:**

1. Before any code change, run `comparisons/run-all.ps1` on a clean checkout of `main`. Capture wall-clock + per-phase CSV output.
2. Branch + execute the migration tasks.
3. Re-run the same harness invocation on the same machine, same JVM, same JDK.
4. Compare CSV outputs. Anything within noise → ship. Measurable regression → diagnose before merge.
5. Capture both runs' summary numbers in the PR description for reviewer reference.

The expected outcome is "no measurable regression" — `System.Logger`'s default backend IS JUL, so cold-start cost should match the current JUL baseline within noise.

## Codegen changes (`ContainerGenerator`)

Two emitted log sites:

- `ApplicationEndingEvent` publish failure (try/catch around `eventBus.publish(...)`)
- Shutdown drain timeout (after `awaitTermination` returns false with `inFlightGets > 0`)

**Update the codegen** to emit `System.getLogger(...)` and `System.Logger.Level.*` instead of `Logger.getLogger(...)` + JUL `Level`. Emit direct `LOG.log(...)` calls — generated code does NOT use `TikoLog` (one fewer import in generated source; auto-generated readability matters less than human-written code).

**Existing codegen-assertion tests** (`ContainerGeneratorEventExecutorTest`, `ContainerGeneratorShutdownTimeoutTest`, `ContainerGeneratorShutdownIdempotencyTest` if it exists) have `assertThat(content).contains("Logger.getLogger")` or similar substring checks. Update those substrings to the new shape. Do NOT loosen test intent — verify each assertion still pins the generated behaviour it was originally checking.

## Test coverage

**New / updated tests:**

1. **`TikoLogTest`** (new, tiko-runtime, same package as TikoLog for package-private access) — 4 cases:
   - Short-circuit when level is filtered (use a custom `System.Logger` whose `isLoggable` returns false; pass an `Object` whose `toString()` increments a counter as an arg; assert counter stays at 0 — proves MessageFormat never ran).
   - Format path with args substitutes parameters correctly.
   - Throwable variant attaches the throwable.
   - No-args path skips the format step (uses pattern verbatim).

2. **`DefaultErrorHandlerTest`** (updated) — assertion strategy changes from JUL handler capture to `CapturingLoggerFinder.RECORDS`. Test bodies stay structurally similar; just the assertion-target switches.

3. **`CompositeCoercersTest`** dedupe-warning case (updated, in tiko-config) — same migration as above, using tiko-config's `CapturingLoggerFinder`.

4. **`ContainerGeneratorShutdownTimeoutTest`** + any other codegen-assertion tests — assertion substrings updated for the new `System.Logger` shape.

## Documentation

- **`README.md`** — new top-level "Logging" section (content above).
- **`docs/events.md`** — if it mentions JUL anywhere (it does, per the earlier grep), update those references.
- **`docs/roadmap.md`** — "What ships today" entry closes #74.

## Out of scope

- Structured logging / MDC / volume controls — users get those via whatever backend their `LoggerFinder` provider routes to.
- Changes to `ErrorHandler` semantics — different layer (handler-exception isolation), unchanged.
- Per-level convenience methods on `TikoLog` (`warn`, `error`, etc.) — 2 generic methods keep the surface minimal; can promote if maintenance burden warrants.
- A shared test-support module for `CapturingLoggerFinder` reuse — ~30 LOC duplication twice is fine for v1.
- log4j2 / JBoss Logging runnable example modules — README documents the recipe; only slf4j gets a dedicated example because it's the most-asked route.

## Acceptance

- [ ] All handwritten production sites switched from `java.util.logging.Logger` to `System.Logger`. JUL imports removed from those files.
- [ ] Four tiko-runtime framework files use `TikoLog.log(...)`. `FallbackErrorHandler`, `CompositeCoercers`, and the four example handlers use raw `System.Logger` (supplier form for parameterised messages).
- [ ] `TikoLog` is package-private in `io.tiko.runtime` with two static methods + private ctor + final class.
- [ ] `TikoLogTest` covers short-circuit, format substitution, throwable attachment, no-args path.
- [ ] `CapturingLoggerFinder` exists in tiko-runtime and tiko-config test sources, each with its own `META-INF/services/java.lang.System$LoggerFinder` descriptor.
- [ ] `DefaultErrorHandlerTest` + `CompositeCoercersTest` migrated to assert on the captured records list.
- [ ] `ContainerGenerator` emits `System.Logger` shape; affected codegen-assertion tests updated.
- [ ] `tiko-examples/11_custom_logger` exists with pom + Main + FailingComponent + logback.xml + README, builds via the reactor, and `mvn exec:java` (or equivalent) shows the framework warning formatted by logback (not JUL).
- [ ] README has the new "Logging" section.
- [ ] Cold-start harness re-run captured in PR description; no measurable regression vs baseline.
- [ ] Full reactor `mvn -pl '!tiko-bom' install` green; Spotless clean.
