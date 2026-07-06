# Testing

`tiko-test` is a small JUnit 5 extension that boots a Tiko `Container` around a `@Test` method (or a whole class), resolves test-method parameters out of that container, and gives you a spy `EventBus` for asserting on what was published. It also ships two scope-helper annotations for tests that need to run inside `runInEventScope` / `runInEventScope`.

For a runnable example, see [`tiko-examples/12_testing`](../tiko-examples/12_testing).

## Dependency

`tiko-test` is a test-scope dependency. The processor runs at `test-compile` so a separate `TestContainerImpl_<hash>` is emitted under `target/test-classes/`; the runtime picks it up automatically when `META-INF/tiko/test-container.properties` is on the classpath.

```xml
<dependency>
    <groupId>io.github.tomas-samek</groupId>
    <artifactId>tiko-test</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

## Classpath layout

Tiko's processor runs in two Maven phases:

- **`compile`** — sees `src/main/java/` sources; generates the main `TikoContainerImpl` + `META-INF/tiko/container.properties` in `target/classes/`.
- **`test-compile`** — sees `src/test/java/` sources only (Maven's behaviour); generates a standalone `TestContainerImpl` + `META-INF/tiko/test-container.properties` + (if any `@TestComponent` shadows exist) `META-INF/tiko/test-shadows.properties` in `target/test-classes/`.

At runtime, `Tiko.create(...)` detects the test descriptors and uses `AggregatingContainer` to federate both containers. Shadow declarations register as runtime overrides on the shared `TikoOptions` — `@TestComponent FakeClock extends Clock` causes every `Clock` injection across both containers to resolve to `FakeClock`.

Production components live in `src/main/java/`, test fixtures (mocks, `@TestComponent`s, helpers) live in `src/test/java/` — the natural Maven layout.

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

## `@EventScopeTest` / `@EventScopeTest` — scope helpers

When a test needs a `REQUEST`- or `EVENT`-scoped bean to be resolvable in the test body, annotate the `@Test` method:

```java
@TikoTest
class RequestScopedRepoTest {
    @Test
    @EventScopeTest
    void requestScopedRepoResolvableInsideScopeWrapper(Container container) {
        var repo = container.get(AccountRepository.class);
        assertThat(repo.findCustomerName("alice")).isEqualTo("Customer-alice");
    }
}
```

The extension wraps the invocation in `container.runInEventScope(...)`. Any throwable from the test body is rethrown unchanged after the scope unwinds — `AssertionError` still fails the test cleanly.

Parameter resolution happens *before* the scope is entered. An EVENT-scoped bean cannot be a method parameter on a `@EventScopeTest` method directly — the resolution attempt throws `NoActiveEventScopeException` because no unit of work is open yet. Resolve it inside the test body via `container.get(Type.class)`, or use a proxied interface that already crosses the scope boundary.

## `@TestComponent` — compile-time overrides

`@TestComponent` is a test-classpath marker. The annotation processor processes it during `test-compile` and emits a separate `TestContainerImpl_<hash>` into `target/test-classes/`. At runtime, `Tiko.create(...)` federates the main and test containers via `AggregatingContainer`; `@TestComponent` shadow declarations register as runtime overrides so production injections of the shadowed type resolve to the test class.

```java
@TestComponent(scope = Scope.SINGLETON)
public class FixedClock implements Clock {
    @Override public Instant now() { return Instant.parse("2026-01-01T00:00:00Z"); }
}
```

The test container resolves `Clock` to `FixedClock` instead of the production `SystemClock`.

## Shadow detection

`@TestComponent` discovers its shadow target two ways:

**Implicit (default)** — the processor walks the test class's superclass chain
looking for a `@Component`-annotated ancestor. If found, the test class shadows
that ancestor:

```java
@Component(scope = Scope.SINGLETON)
public class Clock { /* prod impl */ }

@TestComponent
public class FixedClock extends Clock { /* test impl */ }
// FixedClock shadows Clock in the test container.
```

**Explicit** — when the test class doesn't extend the production class (e.g.
faking an interface), name the shadow target via `value`:

```java
@TestComponent(value = PaymentGateway.class)
public class StubPaymentGateway implements PaymentGateway { /* ... */ }
```

The annotated class must be assignable to `value` — the processor enforces this
at compile time. Explicit `value` always wins over the implicit walk.

**Scope match required.** The `@TestComponent.scope` must match the shadowed
`@Component.scope`, or the build fails with a clear diagnostic. Use
`TikoOptions.override(...)` if you need different lifecycle semantics.

**Named shadow is not currently supported.** A `@TestComponent(name = "primary")`
does not shadow a `@Component(name = "primary")` — the validator's shadow path
processes unnamed components only. For named test doubles, use the runtime
`TikoOptions.override(Class, "name", Supplier)` hook instead.

## `TikoOptions.override(...)` — runtime overrides

For per-test substitutions without writing a new `@TestComponent`, hand a supplier to `TikoOptions.override`. Key the override by the type the consumer depends on — typically an interface:

```java
PaymentGateway mock = mock(PaymentGateway.class);
try (Container container = Tiko.create(TikoOptions.builder()
        .override(PaymentGateway.class, () -> mock)
        .build())) {
    // Any @Component that injects PaymentGateway gets `mock`,
    // regardless of which concrete @Component implements it.
}
```

The override applies at every injection site declared as `PaymentGateway`, at `container.get(PaymentGateway.class)`, and at `getProvider(PaymentGateway.class).get()`. It is consulted before the generated factory and at every scope (`SINGLETON`, `REQUEST`, `EVENT`).

For qualified injection (`@Named("primary") PaymentGateway`), use the named form:

```java
TikoOptions opts = TikoOptions.builder()
        .override(PaymentGateway.class, "primary", () -> mock)
        .build();
```

Override keys are matched by the *declared* type at the lookup site, not the concrete `@Component` class. Code that wants to override should mock the same type consumers depend on — usually the interface.

## Faking the Kafka transport — `replaceTransport` + `FakeKafkaTransport`

To integration-test a `@KafkaSource` / `@KafkaSink` app without a broker, replace the
generated Kafka transport with one backed by the in-memory `FakeKafkaBroker`:

```java
FakeKafkaBroker broker = new FakeKafkaBroker();
try (Container c = Tiko.create(TikoOptions.builder()
        .configSource(ConfigSources.classpath("application.yaml"))
        .replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker))
        .build())) {

    // Outbound: publish the local event; assert the sink produced a record.
    c.getEventBus().publish(new OrderPlaced("o-42", amount, Instant.now()));
    assertThat(broker.produced("orders")).hasSize(1);

    // Inbound: produce onto the fake broker; the @KafkaSource bridge consumes it.
    broker.produce("orders", new JsonKafkaSerializer().serialize(order));
    // consumption is async (a background poll thread) — use Awaitility, not Thread.sleep
}
```

- `replaceTransport` is a **test affordance** in the `override(...)` family: class-keyed,
  applied between ServiceLoader discovery and transport start. The container owns the
  fake's lifecycle — no separate resource to close.
- Returning `null` from the decorator drops the transport instead
  (`.replaceTransport(TransportBootstrap.class, t -> null)` disables all transports).
- A key that matches no discovered transport fails fast at `Tiko.create(...)` — if you hit
  that in a unit-test module, the generated transport isn't on the test classpath.
- `configSource(...)` is still required if the app declares any `@Configuration` (including
  `tiko-kafka`'s own config) — set it exactly like the app's `Main` does, or `Tiko.create`
  fails config validation before the transport substitution ever runs.
- If the module packages a shaded/fat runnable jar, point `maven-failsafe-plugin` at
  `<classesDirectory>${project.build.outputDirectory}</classesDirectory>` — otherwise
  failsafe defaults to running ITs against the packaged jar, which puts bundled
  dependency classes on the classpath twice and fails container boot with
  `duplicate @Configuration prefix 'tiko.kafka'`.

Runnable reference: `tiko-examples/08_kafka_order_warehouse` —
`FakeBrokerOrderPublishIT` (outbound) and `FakeBrokerWarehouseConsumeIT` (inbound), both
Docker-free. Both ITs demonstrate the `configSource` requirement above, and both modules'
poms carry the `maven-failsafe-plugin` `classesDirectory` override.
