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

Every `@Component` lives in `src/test/java/`. This matches the layout that `tiko-test` itself
uses for its own fixtures and is the configuration the current annotation-processor design
is exercised under end-to-end.

See [docs/testing.md](../../docs/testing.md) for the full guide.
