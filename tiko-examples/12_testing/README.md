# Example 12 — Testing

Runnable demo of the `tiko-test` JUnit 5 extension. Each test file targets one feature:

| File | Demonstrates |
|------|--------------|
| `async/AsyncHandlerTest.java` (+ `AsyncListener.java`) | `awaitAsyncDispatch` for `@EventHandler(async = true)` |
| `clock/FixedClockTest.java` (+ `FixedClock.java`) | `@TestComponent extends @Component` — superclass-walk shadow detection |
| `lifecycle/PerClassLifecycleTest.java` | `@TikoTest(lifecycle = PER_CLASS)` |
| `order/OrderServiceTest.java` | Parameter resolution + `RecordingEventBus` assertions + `@EventTrigger` chain |
| `repo/RequestScopedRepoTest.java` | `@RequestScopeTest` scope helper |

Run with:

```
mvn -pl tiko-examples/12_testing -am test
```

## Source layout

Every `@Component` lives in `src/test/java/`. This matches the layout that `tiko-test` itself
uses for its own fixtures and is the configuration the current annotation-processor design
is exercised under end-to-end.

A more conventional layout (production components in `src/main/java/`, tests under
`src/test/java/`) surfaces one known gap that needs a follow-up issue before the example can
add the runtime-`override` demo:

- **Test-compile processor round does not see main `@Component`s.** During `test-compile`
  the annotation processor only receives the test sources, so the generated test container
  is missing every main component. The dual-emission descriptor (`META-INF/tiko/test-container.properties`)
  then routes the runtime at a partial container that cannot resolve production beans.

That gap would let `MockedPaymentTest` (runtime `override(PaymentGateway.class, …)` against
an interface injection point) ship as an additional demo. It is intentionally omitted here
so the example only exercises code paths that work today.

See [docs/testing.md](../../docs/testing.md) for the full guide.
