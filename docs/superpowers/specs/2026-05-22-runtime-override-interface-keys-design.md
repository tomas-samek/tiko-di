# Runtime override — match every injection-site type, not just the concrete `@Component`

**Status:** Design approved 2026-05-22. Implementation plan to follow.
**Tracker:** [#128](https://github.com/tomas-samek/tiko-di/issues/128)
**Milestone:** Phase 3 — Onboarding & tooling
**Predecessors:**
- T6 (SINGLETON getter override consultation)
- T7 (REQUEST/EVENT getter via shared `emitScopedGetOrCreate` helper)
- T8 (named-qualifier dispatcher fan-out per routable type)

## Goal

Make `TikoOptions.override(Interface.class, () -> mock)` apply at
constructor-injection sites that depend on `Interface`. Today the
override silently does nothing because the generated factory wires
`new A(getHttpPaymentGateway())` directly and `getHttpPaymentGateway`
only consults `options.hasOverride(HttpPaymentGateway.class)` — the
concrete `@Component` class.

Surfaced during the `tiko-test` example module work (T16 of #122) where
`MockedPaymentTest` had to be dropped because the natural
`mock(PaymentGateway.class)` + `override(PaymentGateway.class, mock)`
pattern silently produced the production gateway.

## Non-goals

- Touching `@TestComponent` shadow detection (that's #127 — already shipped).
- Reading main-classpath components during `test-compile` (that's #129).
- Applying overrides to `getAll(Class)` (different semantic: multi-instance vs
  single override — defer until a real use case appears).
- Compile-time validation that override keys correspond to real wiring graph
  edges (override is a dynamic mechanism; this is out of scope by nature).
- Allowing `@Produces` factory-method outputs to be overridden (consistent
  with the existing T6/T7 carve-out).

## Design decisions (with rationale)

Three foundational calls made during brainstorming:

1. **Per-call-site fan-out — not per-component-getter.** The override
   check lives at the *injection site* using the parameter's declared
   type. `new A(...)` becomes `new A(<override-aware resolution>(...))`.
   The cast is to the parameter's declared type (interface or concrete),
   not the concrete `@Component` class. This sidesteps the "final
   concrete class with `mockito-core`" friction entirely — `mock(I.class)`
   succeeds because `(I) mock` is a no-op cast at the call site.

2. **Per-component getter becomes override-free.** Today's
   `options.hasOverride(componentType)` check inside `getSingleton_X` /
   `emitScopedGetOrCreate` is *removed*. All entry points consult
   overrides at their natural type key; the per-component getter
   becomes a pure factory cache. Generated code shrinks.

3. **Test ergonomics target interfaces.** Docs, example tests, and
   diagnostics all lead with the interface as the override key. The
   project memory `[[test-against-interfaces-not-impls]]` makes this
   project-wide policy: impl-using-impl is an anti-pattern; users should
   write `override(Interface.class, mock(Interface.class))`.

## Architecture

### Touch points

```
tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java
  ← remove override consultation from getSingleton_X / emitScopedGetOrCreate
  ← add override check at the head of get(Class) dispatcher
  ← add override check at the head of get(Class, String) dispatcher
  ← wrap each constructor parameter resolution in factory emission with
    override-aware resolution using the parameter's declared type
  ← wrap each Provider<T> lambda's get() body with same

tiko-examples/12_testing/src/test/java/io/tiko/examples/testing/payment/MockedPaymentTest.java
  ← restore (deleted in T16 of #122); now passes
```

No changes to:
- `TikoOptions` (the `override(Class, Supplier)` and `override(Class, String, Supplier)` builder methods stay as-is)
- `@TestComponent` annotation
- `AmbiguityValidator`
- `ComponentModel`
- Named-qualifier dispatcher emission in `createGetWithNameMethod` (already does the right thing)

### Data flow

**Before (current):**

```
User: TikoOptions.builder().override(PaymentGateway.class, () -> mock).build()

User: container.get(PaymentGateway.class)
   → dispatcher matches PaymentGateway.class
   → delegates to getHttpPaymentGateway()
   → getHttpPaymentGateway checks hasOverride(HttpPaymentGateway.class) ← MISS
   → returns production HttpPaymentGateway

A's factory:
   new A(getHttpPaymentGateway())
   → getHttpPaymentGateway checks hasOverride(HttpPaymentGateway.class) ← MISS
   → returns production HttpPaymentGateway
```

**After:**

```
User: TikoOptions.builder().override(PaymentGateway.class, () -> mock).build()

User: container.get(PaymentGateway.class)
   → dispatcher: hasOverride(PaymentGateway.class) ← HIT
   → returns (PaymentGateway) mock  ✓

A's factory (generator emits):
   PaymentGateway gw = options.hasOverride(PaymentGateway.class)
       ? (PaymentGateway) options.getOverride(PaymentGateway.class).get()
       : getHttpPaymentGateway();
   new A(gw)
   → hasOverride(PaymentGateway.class) ← HIT
   → cast (PaymentGateway) mock succeeds (mock IS-A PaymentGateway by design)  ✓
```

## Generator changes

### 1. Per-component getter — remove override check

The current SINGLETON emission (around `ContainerGenerator.java:1073`):

```java
"return ($1T) singletons.computeIfAbsent($2S, k -> options.hasOverride($3T.class) ? options.getOverride($3T.class).get() : $4L.create())"
```

becomes:

```java
"return ($1T) singletons.computeIfAbsent($2S, k -> $4L.create())"
```

`emitScopedGetOrCreate` (used by REQUEST/EVENT, around line 1184-1200):

```java
method.addStatement(
        "__existing = options.hasOverride($1T.class) ? ($2T) options.getOverride($1T.class).get() : $3L",
        componentType, returnType, createExpr);
```

becomes:

```java
method.addStatement("__existing = $L", createExpr);
```

`@Produces`-targeted `emitScopedGetOrCreateNoOverride` stays unchanged
(it never consulted overrides).

### 2. Dispatcher — add override check at the head

`get(Class<T>)` (around `createGetMethod`):

```java
@Override
public <T> T get(Class<T> type) {
    if (options.hasOverride(type)) {
        return (T) options.getOverride(type).get();
    }
    // ... existing type-arm dispatch ...
}
```

`get(Class<T>, String)` (around `createGetWithNameMethod`):

```java
@Override
public <T> T get(Class<T> type, String name) {
    if (options.hasOverride(type, name)) {
        return (T) options.getOverride(type, name).get();
    }
    // ... existing type+name fan-out arms (which still consult overrides per-arm — leave intact) ...
}
```

The per-arm override check inside `createGetWithNameMethod` *stays* —
T8's fan-out already correctly fires per routable-type. The new
top-of-dispatcher check is a fast-path for the user's lookup key, but
the per-arm checks remain so the named-qualifier path keeps working
when the user's lookup goes through a different type than they
overrode under. (Edge case: user looks up `Impl.class` with name X but
override was registered under `Interface.class` with name X. The
top-of-dispatcher check misses on `Impl.class`, but the per-arm fan-out
catches it via `effectiveRoutableTypes`. Symmetry preserved.)

### 3. Factory emission — wrap each constructor parameter

For each component-factory's constructor-call emission, transform:

```java
return new A(getHttpPaymentGateway(), getClock());
```

into:

```java
PaymentGateway _p0 = options.hasOverride(PaymentGateway.class)
        ? (PaymentGateway) options.getOverride(PaymentGateway.class).get()
        : getHttpPaymentGateway();
Clock _p1 = options.hasOverride(Clock.class)
        ? (Clock) options.getOverride(Clock.class).get()
        : getClock();
return new A(_p0, _p1);
```

The override key for each parameter is the parameter's *declared type*
(unwrapping `Provider<T>`, honoring `@Pick(T.class)`, applying `@Named`
when present). The cast is to that same declared type — interfaces
work naturally, no final-class friction.

For `Provider<T>` parameters, the wrapping happens inside the Provider's
lambda so the override is consulted at `Provider.get()` time:

```java
Provider<PaymentGateway> _p0 = () -> {
    if (options.hasOverride(PaymentGateway.class)) {
        return (PaymentGateway) options.getOverride(PaymentGateway.class).get();
    }
    return getHttpPaymentGateway();
};
```

For `@Named("primary")` params, the named-key form
`options.hasOverride(Type.class, "primary")` is used.

### 4. Cross-scope proxy delegation

Cross-scope proxies (generated by `ProxyGenerator`) delegate per call
to `getCurrent*()` for the actual lookup. Those `getCurrent*` getters
share the `emitScopedGetOrCreate` helper, so removing the override
check there also removes it from the proxy path. Override consultation
for proxied beans is handled at the *call site* (where the proxy itself
is wired into a constructor), same as any other parameter. No proxy-
specific code changes required.

## Edge cases

| Scenario | Behavior |
|----------|----------|
| `override(Interface.class, mock)`, A injects `Interface` | Mock returned (factory's per-param wrap fires). |
| `override(Interface.class, mock)`, A injects concrete `Impl` | Production Impl returned (factory wrap checks `Impl.class`, miss). User must override under `Impl.class` for this case — but project policy says inject by interface, so this rarely occurs. |
| `override(Impl.class, mock)`, A injects `Interface` | Production Impl returned (factory wrap checks `Interface.class`, miss). User must override under the interface to match the injection site. |
| `override(Interface.class, mock1)` AND `override(Impl.class, mock2)` | A injecting `Interface` gets mock1; A injecting `Impl` gets mock2. Each injection site is independently keyed. |
| `container.get(Interface.class)` with `override(Interface.class, mock)` | Mock returned (dispatcher top-of-method check). |
| `container.get(Impl.class)` with `override(Interface.class, mock)` | Production Impl. (Override key is `Interface.class`, dispatcher's `hasOverride(Impl.class)` misses.) |
| `getProvider(Interface.class).get()` | Mock returned (Provider's lambda consults override). |
| Mock returns interface-only object (e.g. `mock(PaymentGateway.class)`, `HttpPaymentGateway` is final) | Works — cast at the call site is `(PaymentGateway) mock`, which succeeds because the mock IS-A `PaymentGateway` by Mockito's design. |
| `getAll(Interface.class)` with `override(Interface.class, mock)` | Production list returned, override ignored. Out of scope for v1; documented. |
| `@TestComponent` shadow PLUS `override(...)` for same type | Override wins (resolved at the entry point before the shadow's getter is even called). |
| Supplier returns `null` | Propagates as `null` (no special handling). User sees NPE downstream. Document. |
| Supplier throws | Propagates. Not the framework's job to catch. |

## Validation rules

No new compile-time validation. The override mechanism is dynamic by
design — users register overrides at runtime, the framework can't know
their intent at compile time. Existing validation (`AmbiguityValidator`,
etc.) is unaffected.

## Testing

In `tiko-processor/src/test/java/io/tiko/processor/`:

- `RuntimeOverrideInterfaceKeyTest` — covers the headline case: factory
  emission wraps a `PaymentGateway`-typed parameter with the
  `options.hasOverride(PaymentGateway.class)` check, not
  `HttpPaymentGateway.class`.
- `DispatcherOverrideTest` — covers `get(Class)` and `get(Class, String)`
  honoring overrides at the top of the dispatcher.
- `ProviderOverrideTest` — covers Provider<T> lambda's `get()`
  consulting override at call time.
- `OverrideRemovedFromComponentGetterTest` — locks the cleanup:
  generated `getSingleton_X` / scoped getters no longer contain
  `options.hasOverride(...)` calls.

In `tiko-runtime/src/test/java/io/tiko/runtime/`:

- An end-to-end test using a real generated container that exercises
  `override(Interface.class, mock)` flowing through to a constructor-
  injected interface parameter. (May reuse the existing fixture
  infrastructure if simple enough; otherwise lives in the example
  module.)

In `tiko-examples/12_testing/`:

- Restore `MockedPaymentTest.java` (deleted in T16 of #122). Uses
  Mockito's `mock(PaymentGateway.class)` and
  `override(PaymentGateway.class, () -> mock)`. Asserts the mock's
  `charge(...)` was invoked from `OrderService`.

## Documentation updates

- `docs/testing.md` — replace the "Known limitations" entry for #128
  with the documented behavior. Lead the new content with the
  interface-keyed example. Remove the workaround note that said
  "override under the concrete class". One paragraph max — this is
  natural now, not a feature to teach.
- `docs/roadmap.md` — mark #128 as shipped under Phase 3.
- `tiko-examples/12_testing/README.md` — restore the
  `MockedPaymentTest` row to the demo table.

## References

- `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java:1073`
  — current SINGLETON override emission
- `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java:1184-1200`
  — current `emitScopedGetOrCreate` override emission
- `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java:1789-1792`
  — existing named-qualifier per-arm override emission (no change)
- `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java:1987-2001`
  — `effectiveRoutableTypes(component)` (used by named path; not directly used by the new per-call-site path, which uses the parameter's declared type)
- [#128](https://github.com/tomas-samek/tiko-di/issues/128) — tracker
- [#127](https://github.com/tomas-samek/tiko-di/issues/127), [#129](https://github.com/tomas-samek/tiko-di/issues/129) — sibling Phase 3 follow-ups
