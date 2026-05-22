# Testing

`tiko-test` is a small JUnit 5 extension that boots a Tiko `Container` around a `@Test` method (or a whole class), resolves test-method parameters out of that container, and gives you a spy `EventBus` for asserting on what was published. It also ships two scope-helper annotations for tests that need to run inside `runInRequestScope` / `runInEventScope`.

For a runnable example, see [`tiko-examples/12_testing`](../tiko-examples/12_testing).

## Dependency

`tiko-test` is a test-scope dependency. The processor runs at `test-compile` so a separate `TestTikoContainerImpl_<hash>` is emitted under `target/test-classes/`; the runtime picks it up automatically when `META-INF/tiko/test-container.properties` is on the classpath.

```xml
<dependency>
    <groupId>io.tiko</groupId>
    <artifactId>tiko-test</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

## `@TikoTest` — boot a container around each test

Annotate a test class with `@TikoTest`. By default a fresh container is created before each `@Test` method and closed after it runs (`Lifecycle.PER_METHOD`). The extension is registered via `@ExtendWith` on the annotation itself — no manual registration.

```java
@TikoTest
class OrderServiceTest {
    @Test
    void createsOrderAndPublishesEvent(EventBus bus, RecordingEventBus rec) {
        bus.publish(new CreateOrderCommand("alice", 4200L));

        rec.assertPublished(OrderCreatedEvent.class)
                .withPayload((OrderCreatedEvent e) -> e.customerId().equals("alice"));
    }
}
```

### Parameter resolution (no field injection)

The extension is a JUnit 5 `ParameterResolver`. Declare what you need on the test method's parameter list:

| Parameter type        | Resolved from                                                                |
|-----------------------|------------------------------------------------------------------------------|
| `Container`           | the per-test container instance                                              |
| `EventBus`            | the `RecordingEventBus` decorator wrapping the in-memory bus                 |
| `RecordingEventBus`   | the same decorator, typed as the recorder                                    |
| any other type        | `container.get(type)` — annotate with `@Named("...")` to disambiguate        |

There is no `@Inject` field injection in tests. JUnit 5 already owns the lifecycle of the test instance, so dependencies arrive as method parameters where the extension is allowed to participate.

### Per-class lifecycle

Switch to one container per class with `@TikoTest(lifecycle = PER_CLASS)`. The container is booted in `@BeforeAll` and closed in `@AfterAll`, and every `@Test` method on the class shares it.

```java
@TikoTest(lifecycle = TikoTest.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PerClassLifecycleTest {
    private static Container firstContainer;

    @Test @Order(1)
    void firstTestRemembersContainerIdentity(Container c) { firstContainer = c; }

    @Test @Order(2)
    void secondTestSeesSameContainer(Container c) {
        assertThat(c).isSameAs(firstContainer);
    }
}
```

Use `PER_CLASS` when boot cost matters (e.g. configuration parsing, schema work) and when test methods are not allowed to mutate singleton state.

## `RecordingEventBus` — spy for event assertions

`@TikoTest` installs `RecordingEventBus` as a transparent decorator over the in-memory bus via `TikoOptions.eventBusDecorator`. Every `publish` is captured locally and then forwarded, so production handlers still observe every event.

```java
@Test
void publishesEvent(EventBus bus, RecordingEventBus rec) {
    bus.publish(new OrderCreatedEvent("txn-1", "alice", 4200L, Instant.now()));

    rec.assertPublished(OrderCreatedEvent.class)
            .withPayload((OrderCreatedEvent e) -> e.amountCents() == 4200L);
}
```

| Method                                       | Asserts                                                       |
|----------------------------------------------|---------------------------------------------------------------|
| `assertPublished(Class)`                     | at least one event of the type (or a subtype) was captured   |
| `assertPublishedExactly(int n, Class)`       | exactly `n` events of the type were captured                 |
| `assertNoneOf(Class)`                        | no event of the type was captured                            |
| `events()`, `events(Class)`                  | defensive copies of captured events, in publish order        |
| `clear()`                                    | discard captured events (delegate bus and executor untouched)|

`assertPublished` returns a `PublishedEventAssertion` for chaining payload predicates:

```java
rec.assertPublished(OrderCreatedEvent.class)
        .withPayload((OrderCreatedEvent e) -> e.customerId().equals("alice"))
        .withPayload((OrderCreatedEvent e) -> e.amountCents() > 0);
```

### `awaitAsyncDispatch(Duration)` for `async = true` handlers

`@EventHandler(async = true)` runs on the framework's bounded executor. To assert side-effects after the publisher returns, block on the executor draining:

```java
@Test
void awaitAsyncDispatchBlocksUntilHandlersDrain(EventBus bus, RecordingEventBus rec)
        throws TimeoutException {
    int before = AsyncListener.received.get();
    bus.publish(new OrderCreatedEvent("txn-1", "alice", 1L, Instant.EPOCH));
    rec.awaitAsyncDispatch(Duration.ofSeconds(2));
    assertThat(AsyncListener.received.get()).isGreaterThan(before);
}
```

The extension wires the container's executor into the recorder during boot, so this works without any setup. If the executor does not drain inside the timeout a `TimeoutException` is thrown with the active count and queue size — much more informative than a bare `Thread.sleep` that races and flakes.

## `@RequestScopeTest` / `@EventScopeTest` — scope helpers

When a test needs a `REQUEST`- or `EVENT`-scoped bean to be resolvable in the test body, annotate the `@Test` method:

```java
@TikoTest
class RequestScopedRepoTest {
    @Test
    @RequestScopeTest
    void requestScopedRepoResolvableInsideScopeWrapper(AccountRepository repo) {
        assertThat(repo.findCustomerName("alice")).isEqualTo("Customer-alice");
    }
}
```

The extension wraps the invocation in `container.runInRequestScope(...)` (or `runInEventScope`, or both nested with `@RequestScopeTest` + `@EventScopeTest` together). Any throwable from the test body is rethrown unchanged after the scope unwinds — `AssertionError` still fails the test cleanly.

Parameter resolution happens *before* the scope is entered. A REQUEST-scoped bean cannot be a method parameter on a `@RequestScopeTest` method directly; resolve it inside the test body via `container.get(Type.class)`, or use a proxied interface that already crosses the scope boundary.

## `@TestComponent` — compile-time overrides

`@TestComponent` is a test-classpath marker. The annotation processor processes it during `test-compile` and emits a separate `TestTikoContainerImpl_<hash>` into `target/test-classes/`. At runtime, `Tiko.create(...)` prefers the test container when `META-INF/tiko/test-container.properties` is on the classpath.

```java
@TestComponent(scope = Scope.SINGLETON)
public class FixedClock implements Clock {
    @Override public Instant now() { return Instant.parse("2026-01-01T00:00:00Z"); }
}
```

The intent is that the test container resolves `Clock` to `FixedClock` instead of the production `SystemClock`. See [Known limitations](#known-limitations) for the caveats today.

## `TikoOptions.override(...)` — runtime overrides

For per-test substitutions without writing a new `@TestComponent`, hand a supplier to `TikoOptions.override`:

```java
TikoOptions opts = TikoOptions.builder()
        .override(SystemClock.class, () -> new FixedClock(Instant.EPOCH))
        .override(MetricsCollector.class, "primary", FakeMetricsCollector::new)
        .build();
try (Container container = Tiko.create(opts)) {
    // every container.get(SystemClock.class) returns the FixedClock instance
}
```

The override is consulted before the generated factory, at every scope (`SINGLETON`, `REQUEST`, `EVENT`) and for `@Named` lookups via the two-arg form. See [Known limitations](#known-limitations) for the caveat about which type the override key must match.

## Known limitations

Three real gaps surfaced during the first end-to-end example. They are tracked as Phase 3 follow-ups; the `@TikoTest` extension, parameter resolution, `RecordingEventBus`, and the scope helpers are unaffected.

### `@TestComponent` shadow detection currently requires a shared interface

A test-classpath `@TestComponent` is recognised as shadowing a main `@Component` only when both register under a shared routable type — a common interface, or an explicit `expose = {...}` entry. A test subclass that `extends` a main concrete `@Component` is *not* picked up as a shadow today; the test container exposes it as a brand-new addition alongside the production bean instead of replacing it. Track via [#127](https://github.com/tomas-samek/tiko-di/issues/127).

### `TikoOptions.override` key must match the `@Component`'s concrete class

The override map is keyed by the concrete class the `@Component` was generated for. Injecting through an interface and calling `override(MyInterface.class, ...)` does not currently route, because the generated getter only consults the override map at the concrete-class entry point. The workaround is to override the concrete `@Component` class directly; the proper fix is to consult the override map at every routable type. Track via [#128](https://github.com/tomas-samek/tiko-di/issues/128).

### Test-compile processor round only sees test sources

During `test-compile` the annotation processor receives the test sources only — it does not re-collect the main `@Component`s already processed in the earlier `compile` round. The generated test container therefore omits every production bean, and because the runtime prefers the test descriptor it cannot resolve them. The current workaround is to keep every `@Component` used by tests under `src/test/java/` (the layout the `tiko-examples/12_testing` example uses). A more conventional layout — production beans under `src/main/java/`, tests under `src/test/java/` — needs the processor to merge the two sets at `test-compile` time. Track via [#129](https://github.com/tomas-samek/tiko-di/issues/129).
