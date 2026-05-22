# Example 12 — Testing

Runnable demo of the `tiko-test` JUnit 5 extension. Each test file targets one feature:

| File | Demonstrates |
|------|--------------|
| `async/AsyncHandlerTest.java` (+ `AsyncListener.java`) | `awaitAsyncDispatch` for `@EventHandler(async = true)` |
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
`src/test/java/`) surfaces two known gaps that need follow-up issues before the example can
add the `@TestComponent` and runtime-`override` demos:

- **`@TestComponent` shadow detection is interface-only.** A test-classpath `@TestComponent`
  is currently recognised as shadowing a main `@Component` only when both register under a
  shared routable type (a common interface or `expose = {...}` entry). A test subclass that
  `extends` a main concrete `@Component` is *not* picked up as a shadow, so the generated
  test container exposes the test bean as a brand-new addition instead of replacing the
  production lookup.
- **Test-compile processor round does not see main `@Component`s.** During `test-compile`
  the annotation processor only receives the test sources, so the generated test container
  is missing every main component. The dual-emission descriptor (`META-INF/tiko/test-container.properties`)
  then routes the runtime at a partial container that cannot resolve production beans.

Both gaps would let `FixedClockTest` (compile-time `@TestComponent` shadow) and
`MockedPaymentTest` (runtime `override(PaymentGateway.class, …)` against an interface
injection point) ship as additional demos. They are intentionally omitted here so the
example only exercises code paths that work today.

See [docs/testing.md](../../docs/testing.md) for the full guide.
