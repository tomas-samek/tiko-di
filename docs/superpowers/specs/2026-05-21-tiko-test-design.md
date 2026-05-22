# tiko-test — JUnit 5 extension + module for compile-time-safe test wiring

**Status:** Design approved 2026-05-21. Implementation plan to follow.
**Tracker:** [#122](https://github.com/tomas-samek/tiko-di/issues/122)
**Milestone:** Phase 3 — Onboarding & tooling
**Predecessors:** none (greenfield module). Composes with the existing
`tiko-processor`, `tiko-runtime`, and `AggregatingContainer` discovery.

## Goal

Ship a first-party testing module that makes the loop "boot a container,
override one component, assert on event publishes" a three-line operation.
Closes the most common adoption-blocker for users coming from
`@SpringBootTest` / `@QuarkusTest` / Avaje's `@InjectTest`. Tiko's
compile-time-safety pitch leaks without a compile-time-safe testing story;
this design closes that gap.

## Non-goals

- Mockito / AssertJ integration helpers. Users wire those themselves; Tiko
  takes no stance.
- `@SpringBootTest`-style "slice" tests (`@WebMvcTest` equivalents). Defer
  until a user surfaces a concrete shape.
- Per-test DB-transaction rollback hooks. Persistence-cookbook concern (see
  `tiko-examples/10_persistence_jdbc`), not framework.
- A custom JUnit `TestEngine`. We use the public `Extension` SPI.

## Design decisions (with rationale)

The four foundational calls made during brainstorming:

1. **Both override mechanisms.** `@TestComponent` (compile-time, validated
   by `tiko-processor`) for real test impls and fakes;
   `TikoOptions.override(Class, Supplier)` (runtime) for Mockito mocks and
   late-binding overrides. The two mechanisms compose: runtime override
   beats `@TestComponent` beats production `@Component`.
2. **Per-method container lifecycle by default.** Boot + close one container
   per `@Test`. ~20 ms boot cost is acceptable; clean state every test is
   the safe default. Opt-in to per-class via `@TikoTest(lifecycle =
   PER_CLASS)`.
3. **Spy `RecordingEventBus`.** Always installed under `@TikoTest`. Wraps
   the real `LocalEventBus`: every `publish(...)` is captured *and*
   forwarded so `@EventHandler` methods still fire. Tests that don't care
   never reference it; tests that do take it as a parameter.
4. **Parameter resolution only — no field injection.** JUnit 5's
   `ParameterResolver` extension point populates `@Test` / `@BeforeEach` /
   `@AfterEach` / constructor parameters via `container.get(...)`. Zero
   field reflection. Honours Tiko's constructor-only stance.

## Architecture

```
tiko-test/                                  ← new Maven module
  src/main/java/io/tiko/test/
    TikoTest.java                           ← @TikoTest (class-level)
    TikoTestExtension.java                  ← JUnit 5 Extension impl
    TestComponent.java                      ← @TestComponent (SOURCE retention)
    RequestScopeTest.java                   ← @RequestScopeTest (method-level)
    EventScopeTest.java                     ← @EventScopeTest (method-level)
    RecordingEventBus.java                  ← spy wrapping LocalEventBus
    PublishedEventAssertion.java            ← fluent assertion API
```

Cross-module touch points:

- `tiko-processor` — collect `@TestComponent` in addition to `@Component`;
  emit a *test-scoped* `TikoContainerImpl_<hash>` under
  `target/test-classes/io/tiko/generated/`; `AmbiguityValidator` learns
  one new rule (test-component shadowing main is not ambiguous).
- `tiko-runtime` — `TikoOptions.Builder` gains `override(...)` overloads;
  the generated container's getter prologue checks the override map.
- Root `pom.xml`, `tiko-bom/pom.xml` — register the new module + version.

## Override mechanisms — implementation

### Compile-time: `@TestComponent`

```java
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface TestComponent {
    Scope scope() default Scope.SINGLETON;
    String name() default "";
}
```

Processor behaviour during `test-compile`:

1. `ComponentDiscoverer` (`tiko-processor`) collects `@TestComponent`-annotated
   classes alongside `@Component`. They share the same internal
   `ComponentDescriptor` shape.
2. `AmbiguityValidator` (`tiko-processor/validation/AmbiguityValidator.java`)
   gains a `TestComponentOverrides` carve-out: when descriptors with the
   same target type include exactly one `@TestComponent` and one
   `@Component`, the `@TestComponent` shadows. Two `@TestComponent`s on
   the same type with no qualifier remain ambiguous (compile error).
3. Code generation emits a `TikoContainerImpl_<hash>` to
   `target/test-classes/io/tiko/generated/`. The hash differs from the
   main container's hash so both can coexist on the test classpath. The
   test container includes main components + test overrides.
4. `META-INF/tiko/container.properties` in `test-classes/` points at the
   test container. Because `test-classes/` precedes `classes/` on the
   test classpath, `AggregatingContainer`'s existing scan finds the test
   container first and uses it.

### Runtime: `TikoOptions.override(...)`

```java
public final class TikoOptions {
    // ... existing fields ...
    private final Map<OverrideKey, Supplier<?>> overrides;

    public static final class Builder {
        // ... existing setters ...
        public <T> Builder override(Class<T> type, Supplier<? extends T> supplier);
        public <T> Builder override(Class<T> type, String name, Supplier<? extends T> supplier);
    }

    boolean hasOverride(Class<?> type);
    boolean hasOverride(Class<?> type, String name);
    Supplier<?> getOverride(Class<?> type);
    Supplier<?> getOverride(Class<?> type, String name);
}
```

Generator (`SingletonGetterGenerator`, `RequestScopedGetterGenerator`,
`EventScopedGetterGenerator`) prepends one branch to every type-keyed
getter:

```java
private OrderService getSingleton_OrderService() {
    if (options.hasOverride(OrderService.class)) {
        return (OrderService) options.getOverride(OrderService.class).get();
    }
    // existing DCL + factory call
}
```

Qualified getters (`getSingleton_OrderService_primary`) check the
qualified override key.

**Override caching semantics — match the scope.** The override `Supplier`
is treated identically to the production factory for that scope:

- `SINGLETON`: supplier called once at first `container.get(...)`; result
  cached in the container's `singletons` map. Subsequent gets return the
  same instance. This is what test users intuit when they write
  `override(X.class, () -> mock)` — the same mock across the test.
- `REQUEST` / `EVENT`: supplier called once per scope entry; cached in
  the per-scope map; cleared on scope exit. Same as production.
- `PROTOTYPE`: supplier called every `container.get(...)`. Same as
  production.

Implementation: the override branch in the generated getter sits *inside*
the scope-level caching, not before it. Roughly:

```java
private OrderService getSingleton_OrderService() {
    return (OrderService) singletons.computeIfAbsent(
        OrderService.class,
        k -> options.hasOverride(OrderService.class)
            ? options.getOverride(OrderService.class).get()
            : produceOrderService());
}
```

## `@TikoTest` extension

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(TikoTestExtension.class)
public @interface TikoTest {
    Lifecycle lifecycle() default Lifecycle.PER_METHOD;
    enum Lifecycle { PER_METHOD, PER_CLASS }
}
```

`TikoTestExtension implements BeforeEachCallback, AfterEachCallback,
BeforeAllCallback, AfterAllCallback, ParameterResolver`.

### Lifecycle flow (per-method)

1. `beforeEach`: build `TikoOptions` from class-level config and any
   method-level annotations (see [EventBus decoration](#eventbus-decoration)
   below for how the recording bus gets installed); `Tiko.create(options)`;
   stash the container and the `RecordingEventBus` reference in the
   extension store keyed by the test instance.
2. Test method runs. `ParameterResolver` populates each parameter:
   - `Container`, `EventBus`, `RecordingEventBus` → extension store
   - Anything else → `container.get(paramType)` (with `@Named` honoured)
3. `afterEach`: `container.close()`; clear the extension store.

### Per-class flow

`beforeAll` and `afterAll` do the same as `beforeEach`/`afterEach`.
`beforeEach`/`afterEach` are no-ops in per-class mode (the recording bus
is *not* reset between methods — users opt into per-class because they
want shared state). A `RecordingEventBus.clear()` method is available
for tests that want to reset the capture buffer mid-class.

### Container source

The extension calls `Tiko.create(TikoOptions)`. It does not know — or
care — whether the test classpath contains a `tiko-processor`-generated
`TikoContainerImpl_<hash>` (single-module case) or multiple module
containers aggregated via `AggregatingContainer` (multi-module case).
This is the same path production code uses.

### EventBus decoration

The container constructs `LocalEventBus` internally and exposes it via
`container.getEventBus()`. To install `RecordingEventBus` as a spy, the
extension needs to influence the bus *before* `Tiko.create(...)` wires
subscribers — otherwise `@EventHandler` subscriptions register against
the raw `LocalEventBus` instead of the spy.

`TikoOptions.Builder` gains one new method:

```java
public Builder eventBusDecorator(UnaryOperator<EventBus> wrap);
```

`Tiko.createInternal()` constructs `LocalEventBus`, then — if a decorator
is set — replaces the bus reference with `wrap.apply(rawBus)` before
passing it into the generated container's constructor. Both publishers
and subscribers see the decorated bus.

`@TikoTest` always installs this decorator with `RecordingEventBus::new`.
Production code never sets it. The mechanism is general enough that
non-test users can later wrap the bus for tracing, metrics, etc., without
breaking changes — but that's not a goal here.

## `RecordingEventBus`

```java
public final class RecordingEventBus implements EventBus {
    private final EventBus delegate;
    private final List<Object> captured = new CopyOnWriteArrayList<>();

    @Override
    public <T> void publish(T event) {
        captured.add(event);
        delegate.publish(event);
    }

    @Override
    public <T> Subscription subscribe(Class<T> type, EventCallback<T> cb) {
        return delegate.subscribe(type, cb);
    }

    public PublishedEventAssertion assertPublished(Class<?> type);
    public PublishedEventAssertion assertPublishedExactly(int n, Class<?> type);
    public void assertNoneOf(Class<?> type);
    public List<Object> events();
    public <T> List<T> events(Class<T> type);
    public void clear();
    public void awaitAsyncDispatch(Duration timeout);
}
```

### Assertion API

```java
recordingBus.assertPublished(OrderCreatedEvent.class);
recordingBus.assertPublished(OrderCreatedEvent.class)
            .withPayload(e -> e.orderId().equals("ORD-123"));
recordingBus.assertPublishedExactly(2, OrderCreatedEvent.class);
recordingBus.assertNoneOf(OrderCanceledEvent.class);
```

`PublishedEventAssertion` is a small fluent builder; no AssertJ
extension required. Failure messages include the captured event list for
diagnosability.

### Async dispatch helper

```java
recordingBus.awaitAsyncDispatch(Duration.ofSeconds(2));
```

Implementation: cast the `Container`'s `eventExecutor` to
`ThreadPoolExecutor`; spin-wait (with `Awaitility`-style polling — 10 ms
intervals) until `getActiveCount() == 0 && getQueue().isEmpty()`. Throws
on timeout with a diagnostic dumping queue contents + active task count.

The cast is safe because Tiko's bounded executor is always a
`ThreadPoolExecutor`. If a user supplies a custom executor via
`TikoOptions.eventExecutor(...)`, `awaitAsyncDispatch` falls back to a
`fixed-duration sleep + warning log` (documented in javadoc). Tests
covering Tiko's defaults are unaffected.

## Scope helpers

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequestScopeTest {}

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface EventScopeTest {}
```

Implementation: the extension intercepts the test invocation via
`InvocationInterceptor.interceptTestMethod`, wraps it in
`container.runInRequestScope(() -> invocation.proceed())` (or `Event`).
Composes with `ParameterResolver` — request-scoped beans declared as
parameters resolve cleanly because the scope is active when JUnit calls
back.

Stacking `@RequestScopeTest` + `@EventScopeTest` on the same method is
allowed; REQUEST is opened first, EVENT nested inside (per Tiko's scope
hierarchy).

## Example module — `tiko-examples/12_testing/`

Runnable demo covering every feature exactly once:

| File | Demonstrates |
|------|--------------|
| `domain/Clock.java` (production `@Component`) + `test/FixedClockTest.java` with `@TestComponent FixedClock` | Compile-time override via `@TestComponent` |
| `service/PaymentGateway.java` + test using `TikoOptions.override(PaymentGateway.class, () -> mock)` | Runtime override with Mockito |
| `OrderServiceTest.java` with `@Test void createsOrder(OrderService s, RecordingEventBus bus)` | Parameter resolution + recording bus |
| `RequestScopedRepoTest.java` with `@RequestScopeTest` | Scope helper |
| `AsyncHandlerTest.java` calling `bus.awaitAsyncDispatch(...)` | Async dispatch helper |
| `BatchProcessingTest.java` with `@TikoTest(lifecycle = PER_CLASS)` | Per-class lifecycle |

The example builds and tests in CI; its README is one paragraph plus the
above table.

## Acceptance

- [ ] `tiko-test/` module compiles and publishes alongside the others.
- [ ] `@TikoTest` boots a container, runs tests, tears it down — no manual
      `Tiko.create()` / `close()` in the test class.
- [ ] `@TestComponent` on a test-classpath class overrides the production
      `@Component` of the same type; verified by spot-check that the
      generated test container points its factory at the test class.
- [ ] `TikoOptions.override(UserService.class, () -> mock)` works for a
      Mockito mock without recompiling production code.
- [ ] `RecordingEventBus` asserts on sync and async publishes;
      `awaitAsyncDispatch(...)` covers the async case deterministically.
- [ ] `@RequestScopeTest` / `@EventScopeTest` wrap the test in the
      appropriate scope.
- [ ] `tiko-examples/12_testing/` compiles in CI and demonstrates each
      feature.
- [ ] README "Documentation" table and `tiko-examples/README.md` gain a
      testing row.
- [ ] `spotless:check` passes on the new module.

## Open questions

- **Custom `eventExecutor` and `awaitAsyncDispatch`:** the cast-to-
  `ThreadPoolExecutor` strategy assumes the default executor. The spec
  documents the fallback (sleep + warning log); a follow-up could expose
  a `ExecutorDrainStrategy` SPI if a user surfaces a concrete need.
- **Test-container hash collision:** if a user pins the generator's hash
  scheme and a test container happens to hash to the same value as the
  main container, the test classpath loads only one of them. Mitigation:
  include a `test-` prefix in the test container's class name (e.g.
  `TestTikoContainerImpl_<hash>`). Will confirm during implementation.

## References

- `tiko-runtime/src/main/java/io/tiko/runtime/Tiko.java` — entry point
- `tiko-runtime/src/main/java/io/tiko/runtime/TikoOptions.java` — options
  builder being extended
- `tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java`
  — multi-module discovery the test container plugs into
- `tiko-processor/src/main/java/io/tiko/processor/validation/AmbiguityValidator.java`
  — validator gaining the test-component carve-out
- `tiko-runtime/src/main/java/io/tiko/runtime/LocalEventBus.java` — the
  bus `RecordingEventBus` decorates
- [#122](https://github.com/tomas-samek/tiko-di/issues/122) — tracker
