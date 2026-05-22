# Example 12 — Testing

Runnable demo of the `tiko-test` JUnit 5 extension. Each test file targets one feature:

| File | Demonstrates |
|------|--------------|
| `async/AsyncHandlerTest.java` (+ `AsyncListener.java`) | `awaitAsyncDispatch` for `@EventHandler(async = true)` |
| `clock/FixedClockTest.java` (+ `FixedClock.java`) | `@TestComponent extends @Component` — superclass-walk shadow detection |
| `lifecycle/PerClassLifecycleTest.java` | `@TikoTest(lifecycle = PER_CLASS)` |
| `order/OrderServiceTest.java` | Parameter resolution + `RecordingEventBus` assertions + `@EventTrigger` chain |
| `payment/MockedPaymentTest.java` | Runtime `override(PaymentGateway.class, …)` swaps a Mockito mock in by interface |
| `repo/RequestScopedRepoTest.java` | `@RequestScopeTest` scope helper |

Run with:

```
mvn -pl tiko-examples/12_testing -am test
```

## Source layout

This example follows the natural Maven convention:

- **`src/main/java/`** — production components (`Clock`, `OrderService`, `PaymentGateway`,
  `HttpPaymentGateway`, `AccountRepository`, plus the `CreateOrderCommand` and
  `OrderCreatedEvent` records).
- **`src/test/java/`** — `@TestComponent` fakes (e.g. `FixedClock`), test-only components
  (e.g. `AsyncListener`), and the tests themselves.

At test-compile the processor sees only the test sources but finds the existing
`META-INF/tiko/container.properties` from `target/classes/` on the compile classpath. It
emits a standalone `TestContainerImpl_<hash>` plus a `META-INF/tiko/test-shadows.properties`
declaration instead of regenerating the main container. At runtime `AggregatingContainer`
federates the two: each shadow declaration registers as a runtime override on the shared
`TikoOptions`, so a `@TestComponent` like `FixedClock` (extending `Clock`) becomes the
`Clock` injected into every consumer.

See [docs/testing.md](../../docs/testing.md) for the full guide.
