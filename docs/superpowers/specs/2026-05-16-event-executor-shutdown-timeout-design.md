# Event-executor shutdown timeout via `TikoOptions` — design

**Issue:** [#48](https://github.com/tomas-samek/tiko-di/issues/48) — Phase 2 — Configuration & distributed events.

## Goal

`TikoOptions.shutdownTimeout(Duration)` lets users override the hardcoded 10-second `awaitTermination` window the framework uses when shutting down its owned event executor. The current value (`AggregatingContainer.java:380`, `awaitTermination(10, TimeUnit.SECONDS)`) is fine for typical workloads but wrong for two cases:

- Long-running async handlers (batch flushes, slow I/O) where 10s isn't enough.
- Test harnesses where 10s is too long when something is wedged.

The 09_http_javalin example is updated in the same PR to demonstrate the graceful-drain semantics this knob controls — server `stop()` does not interrupt in-flight async events; the container drains them within the configured budget before tearing the executor down.

## Architecture

Three additive surface changes plus an example refresh:

1. **`TikoOptions`** gains a `shutdownTimeout(Duration)` builder method and a **nullable** accessor (matches the existing `configSource()` / `errorHandler()` / `eventExecutor()` shape — null means "not explicitly set"). Reject negative durations; accept `Duration.ZERO` as a "skip the graceful wait, call `shutdownNow()` immediately" signal.
2. **`Tiko.resolveShutdownTimeout(options, classLoader)`** — new package-private static helper that computes the effective `Duration` with this precedence: programmatic `TikoOptions.shutdownTimeout(...)` > YAML `tiko.shutdownTimeout` > default `Duration.ofSeconds(10)`. Loads the YAML via the existing reflective path through `tiko-config` (no new compile dep on tiko-runtime — gracefully no-ops if tiko-config is absent).
3. **Containers receive the resolved Duration.** `Tiko.create(...)` calls the resolver and threads the result into both `AggregatingContainer` (multi-module) and the generated `TikoContainerImpl` (single-module). Both replace `awaitTermination(10, TimeUnit.SECONDS)` with `awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS)` so sub-millisecond precision works for `Duration.ZERO`.
4. **`tiko-examples/09_http_javalin`** gains a deliberately slow `@EventHandler(async = true)` handler. `Main.java` is rewritten so the `5s` drain budget comes from the **YAML config** (demonstrating the configurability story end-to-end), and the example stops Javalin while the handler is still running, then closes the container — the console output shows the async handler completing **before** container.close() returns.

No changes to public sealed types, no new `ErrorContext` permit, no new SPI. Pure addition.

## Behaviour detail

### Default path

Existing code that doesn't touch `shutdownTimeout` sees identical behaviour: 10-second graceful wait, then `shutdownNow()` fallback. No regression risk for current users.

### Custom timeout

```java
TikoOptions opts = TikoOptions.builder()
        .shutdownTimeout(Duration.ofSeconds(30))   // long-running batch handler
        .build();
```

→ `Container.shutdown()` waits up to 30 seconds for the executor to drain before calling `shutdownNow()`.

### `Duration.ZERO`

```java
TikoOptions opts = TikoOptions.builder()
        .shutdownTimeout(Duration.ZERO)
        .build();
```

→ `awaitTermination(0, NANOSECONDS)` returns immediately with `false` if any task is still running, so the framework falls through to `shutdownNow()` on the next line. Useful for test harnesses where something is wedged and you don't want to wait.

### Negative duration

```java
TikoOptions.builder().shutdownTimeout(Duration.ofSeconds(-1)).build();
```

→ `IllegalArgumentException("shutdownTimeout must not be negative")` at builder time, before `build()` returns.

### User-supplied executor

```java
ExecutorService myExec = Executors.newCachedThreadPool();
TikoOptions opts = TikoOptions.builder()
        .eventExecutor(myExec)
        .shutdownTimeout(Duration.ofSeconds(30))   // ← has no effect
        .build();
```

`AggregatingContainer.shutdown()` gates the entire shutdown block on `ownsEventExecutor` (`AggregatingContainer.java:377`). When the user supplies their own executor, the container never calls `shutdown()` on it — and therefore never consults the timeout. The Javadoc on the builder method must make this explicit.

### Error caveat (documented, not enforced)

A JVM `Error` (`OutOfMemoryError`, `StackOverflowError`) bypasses the graceful drain — the JVM may tear down threads abruptly when in an unrecoverable state. This is a JVM-level contract, not something Tiko controls. Documented in the example's `Main.java` class comment and in the builder's Javadoc as a side note.

## API surface

### `TikoOptions`

New nullable field, nullable accessor (matches the existing pattern for other `TikoOptions` fields — `null` means "user did not explicitly set it"):

```java
private final Duration shutdownTimeout;   // null when not set
// ...

/**
 * @return the configured graceful drain window, or {@code null} if not set. When
 *         {@code null}, the effective value is taken from the YAML key
 *         {@code tiko.shutdownTimeout} if present in the layered config sources,
 *         otherwise {@code Duration.ofSeconds(10)}.
 */
public Duration shutdownTimeout() {
    return shutdownTimeout;
}
```

Builder gains:

```java
private Duration shutdownTimeout;   // null = use YAML or default

/**
 * Maximum time {@link Container#shutdown()} waits for the framework's event executor
 * to terminate gracefully before falling back to {@code shutdownNow()}. When unset
 * programmatically, the framework resolves the effective value in this precedence
 * order: programmatic > YAML {@code tiko.shutdownTimeout} > {@code Duration.ofSeconds(10)}.
 *
 * <p>Has <strong>no effect</strong> when {@link #eventExecutor(ExecutorService)} is
 * set — the user owns the executor's lifecycle and the container does not stop it.
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

The existing `eventExecutor(...)` Javadoc gains a short cross-reference: *"See `shutdownTimeout(Duration)` for the related drain window (no effect when this executor is user-supplied)."*

### `Tiko.resolveShutdownTimeout(options, classLoader)`

Package-private static helper that computes the effective `Duration`:

```java
static Duration resolveShutdownTimeout(TikoOptions options, ClassLoader classLoader) {
    // 1. Programmatic wins.
    Duration explicit = options.shutdownTimeout();
    if (explicit != null) return explicit;

    // 2. YAML lookup via reflective tiko-config (gracefully no-ops if absent).
    Duration fromYaml = readYamlShutdownTimeout(options.configSource(), classLoader);
    if (fromYaml != null) return fromYaml;

    // 3. Framework default.
    return Duration.ofSeconds(10);
}
```

The YAML helper layers `META-INF/tiko/defaults.yaml` (every module's baked defaults) with the user's `ConfigSource` (if any), looks up the key path `tiko.shutdownTimeout`, and coerces via `io.tiko.config.internal.coercers.Coercers.durationCoercer()` (also reflective). Negative durations parsed from YAML throw the standard `CoercionException`, surfaced as an early bootstrap failure. If tiko-config is not on the classpath, the helper returns `null` — programmatic + default still work.

## YAML configurability

### Schema

```yaml
tiko:
  shutdownTimeout: 30s   # or "PT30S", "5m", etc. — anything the Coercers.durationCoercer accepts
```

The `tiko:` top-level section is **reserved** for framework-level config knobs. v1 only defines `shutdownTimeout`; Phase 6 (Resiliency) will add sibling keys (`tiko.eventExecutor.coreSize`, `tiko.eventExecutor.queueCapacity`, etc.). The schema is intentionally flat for v1 — nesting under `tiko.eventExecutor` becomes worthwhile when there are multiple knobs targeting the same sub-system.

### Precedence

| Source | Wins over |
|---|---|
| `TikoOptions.shutdownTimeout(Duration)` (programmatic) | YAML + default |
| `tiko.shutdownTimeout` in YAML | default |
| `Duration.ofSeconds(10)` (framework default) | — |

### Limitations (v1)

- **No `${VAR}` interpolation** on `tiko.shutdownTimeout`. The framework reads the value directly from the layered map without running the full `Interpolator`. This keeps the resolver tiny and avoids dragging the whole config-binding pipeline into the bootstrap path. Phase 6 may promote framework knobs to a proper `@Configuration` record when there's enough mass to justify the migration.
- **No `tiko-config` compile dep on `tiko-runtime`.** The YAML lookup is reflective (mirrors the existing `bindConfigs` path at `Tiko.java:202`). If tiko-config is not on the classpath, the YAML path silently falls through to programmatic + default.
- **No source-anchoring on YAML parse failures.** A malformed `tiko.shutdownTimeout` value surfaces via the standard `CoercionException` message but does NOT get the file:line:column treatment from #19 (that's gated through `ConfigBootstrap.bind`, which isn't on this code path).

### `AggregatingContainer`

The constructor that takes the event executor today gains a sibling `Duration shutdownTimeout` parameter (or accepts the whole `TikoOptions` reference if the local convention favours that — pick the smaller diff during implementation). The shutdown block at lines 377–387 replaces the hardcoded `10`:

```java
if (ownsEventExecutor) {
    eventExecutor.shutdown();
    try {
        if (!eventExecutor.awaitTermination(shutdownTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
            eventExecutor.shutdownNow();
        }
    } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        eventExecutor.shutdownNow();
    }
}
```

`Tiko.create(...)` reads the new field from `TikoOptions` and threads it into the constructor — same call path as `eventExecutor`. One additional argument; mechanical.

## 09_http_javalin demo update

The example today shows three subscribers demonstrating sync/async side effects. A fourth handler is added that's deliberately slow, and `Main.java` is rewritten as a deliberate drain demo.

### New / extended component

Either extend an existing async handler (e.g. `AuditService`) with a sleep, or add a sibling `SlowAuditService`. Implementation choice during plan; the spec just requires one `@EventHandler(async = true)` method that sleeps ~2s and prints unambiguous start/end markers.

```java
@EventHandler(async = true)
public void onOrderPlaced(OrderPlacedEvent event) {
    System.out.println("[async] slow audit work starting...");
    try {
        Thread.sleep(Duration.ofSeconds(2).toMillis());
    } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return;
    }
    System.out.println("[async] slow audit work complete");
}
```

### `Main.java` flow

Replace the current main with a flow that publishes a slow async event, stops Javalin **immediately**, then closes the container (via try-with-resources). The `5s` budget comes from **YAML config** (`tiko.shutdownTimeout: 5s` in the example's `config.yaml`) — `TikoOptions` is not used at all for the timeout, demonstrating the YAML configurability story:

```java
public static void main(String[] args) throws Exception {
    // No programmatic shutdownTimeout — the value comes from the YAML config
    // (tiko.shutdownTimeout: 5s in config.yaml). Demonstrates the framework-level
    // YAML configurability introduced by #48.
    TikoOptions opts = TikoOptions.builder()
            .configSource(ConfigSources.classpath("config.yaml"))
            .build();
    try (Container container = Tiko.create(opts)) {
        var app = container.get(JavalinApp.class);
        app.start(7070);

        // Trigger a slow async handler via a real HTTP request.
        try (var conn = new java.net.URI("http://localhost:7070/orders/place").toURL().openStream()) {
            conn.readAllBytes();
        }

        // Stop the HTTP layer while async work is still in flight.
        System.out.println("[main] Stopping HTTP server (async work still running)...");
        app.stop();
    } // ← container.close() drains the in-flight slow handler before returning.
    System.out.println("[main] Container closed cleanly.");
}
```

Expected output ordering when run:

```
[main] Stopping HTTP server (async work still running)...
[async] slow audit work starting...
[async] slow audit work complete
[main] Container closed cleanly.
```

The ordering of the middle two lines and the final `Container closed cleanly` line is the load-bearing assertion: the executor was not torn down when Javalin was, and `container.close()` honoured the in-flight work within the 5-second budget.

A class-level Javadoc on `Main` documents the Error caveat (OOM/StackOverflow bypass the graceful drain).

### Endpoint / event wiring

If `/orders/place` doesn't exist in the current example, either reuse an existing endpoint or add a minimal one that publishes a single event. The exact route name is not load-bearing — what matters is that a request triggers the slow async handler exactly once.

## Test coverage

Four test files touched:

1. **`TikoOptionsTest`** (existing) — gains four cases:
   - `shutdownTimeout` round-trip (`builder().shutdownTimeout(Duration.ofSeconds(2)).build().shutdownTimeout()` equals `Duration.ofSeconds(2)`).
   - **Default accessor value is `null`** (not 10s — the 10s default lives in the resolver, not on the field).
   - Negative duration → `IllegalArgumentException`.
   - Null duration → `NullPointerException`.

2. **`TikoResolveShutdownTimeoutTest`** (new under `tiko-runtime`) — covers the resolver in isolation:
   - **Programmatic wins**: explicit `shutdownTimeout(3s)` + a YAML source with `tiko.shutdownTimeout: 7s` → resolves to `3s`.
   - **YAML fallback**: no programmatic, YAML source with `tiko.shutdownTimeout: 7s` → resolves to `7s`.
   - **Default fallback**: no programmatic, no YAML source / no `tiko` key → resolves to `Duration.ofSeconds(10)`.
   - **Negative in YAML** → `CoercionException` surfaced at bootstrap.

3. **`AggregatingContainerShutdownTimeoutTest`** (new under `tiko-runtime`) — pure runtime, no HTTP:
   - **Forced path**: submit a `sleep(500ms)` task to a container with `shutdownTimeout(Duration.ofMillis(50))`. Call `shutdown()`. Assert `eventExecutor.isTerminated()` is true and elapsed wall time is < ~300ms (loose upper bound for CI jitter). This proves the executor was forced via `shutdownNow()`.
   - **Graceful path**: submit a `sleep(50ms)` task with default 10s timeout. Call `shutdown()`. Assert elapsed < ~300ms (well under the 10s window). This guards against a regression where shutdown accidentally always waits the full timeout.

4. **`HttpAsyncDrainTest`** (new under `tiko-examples/09_http_javalin`):
   - Bootstrap container with `shutdownTimeout(Duration.ofSeconds(5))` (the test uses the **programmatic** path for isolation from the example's main YAML — the test is allowed to be opinionated about its own timeout).
   - Use a `CountDownLatch` injected into the slow handler so the test can deterministically wait for completion.
   - Trigger the endpoint, then `app.stop()`, then `container.close()`.
   - Assert: `latch.getCount() == 0` **before** `container.close()` returns (i.e. the slow handler completed during the drain, not after).
   - Assert: overall close time is well under 5s for a healthy handler.
   - **Plus** a second test method that uses a short timeout (200ms) and asserts the handler is interrupted (latch does NOT count down).

## Documentation

- **`docs/events.md`** gains a short subsection covering BOTH the programmatic API and the YAML key: "When `Container.shutdown()` runs, in-flight async event handlers drain within `TikoOptions.shutdownTimeout(Duration)` (programmatic) or `tiko.shutdownTimeout` (YAML) — default 10s. See `tiko-examples/09_http_javalin` for a YAML-config demo."
- **`docs/configuration.md`** — short note that `tiko.*` is the reserved framework-config namespace (and currently exposes only `shutdownTimeout`). Cross-link to `docs/events.md` for the runtime story.
- **`docs/roadmap.md`** — new "What ships today" entry closes #48 and explicitly mentions the YAML key.
- **`README.md`** — no change. The README doesn't enumerate `TikoOptions` knobs; it links to `docs/events.md` which now covers this.

## Out of scope

- Separate timeouts for `@PreDestroy` and `AutoCloseable.close()`. Filed as #106 under Phase 6 (Resiliency layer).
- Per-event-type or per-handler shutdown overrides.
- Multiple executor pools (the executor pool management knobs are #110).
- Special handling of JVM `Error` — documented as a caveat, not mitigated.
- `${VAR}` interpolation on `tiko.shutdownTimeout` — documented as a v1 limitation.
- Promoting the YAML lookup to a full `@Configuration(prefix = "tiko")` record — deferred to Phase 6 when more framework knobs justify the migration. The flat `tiko:` schema established here forward-extends naturally.

## Compatibility

Pure addition. Every existing `Tiko.create(...)` call sees the same 10-second timeout it does today. New `TikoOptions.shutdownTimeout(...)` builder method is the only new public API surface. No changes to sealed types, no behavioural change for users who never touch the knob.

## Acceptance

- [ ] `TikoOptions.Builder.shutdownTimeout(Duration)` exists; **accessor returns `null` when not set**; round-trips a set value through `build()`.
- [ ] Negative `Duration` rejected with `IllegalArgumentException`; null rejected with `NullPointerException`.
- [ ] `Duration.ZERO` accepted and causes `awaitTermination` to return immediately, forcing `shutdownNow()`.
- [ ] `Tiko.resolveShutdownTimeout(...)` returns programmatic > YAML > 10s with the correct precedence.
- [ ] `AggregatingContainer.shutdown()` consults the resolved timeout instead of the hardcoded `10`.
- [ ] Generated `TikoContainerImpl.shutdown()` consults the resolved timeout instead of the hardcoded `10`.
- [ ] `TikoOptionsTest` covers the 4 new builder cases.
- [ ] `TikoResolveShutdownTimeoutTest` covers programmatic-wins, YAML-fallback, default-fallback, negative-YAML-rejected.
- [ ] `AggregatingContainerShutdownTimeoutTest` covers forced-path and graceful-path scenarios.
- [ ] `tiko-examples/09_http_javalin` `Main.java` demonstrates the drain with the timeout sourced from YAML (`config.yaml` has `tiko.shutdownTimeout: 5s`); class Javadoc notes the JVM `Error` caveat.
- [ ] `HttpAsyncDrainTest` pins the drain behaviour end-to-end via a `CountDownLatch`.
- [ ] `docs/events.md` mentions BOTH the programmatic API and the YAML key.
- [ ] `docs/configuration.md` documents the `tiko:` reserved framework-config namespace.
- [ ] `docs/roadmap.md` "What ships today" closes #48.
- [ ] Full reactor `mvn -pl '!tiko-bom' install` green; Spotless clean.
