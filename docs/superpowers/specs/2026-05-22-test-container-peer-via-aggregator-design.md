# Test container as a peer of main, federated via `AggregatingContainer`

**Status:** Design approved 2026-05-22. Implementation plan to follow.
**Tracker:** [#129](https://github.com/tomas-samek/tiko-di/issues/129)
**Milestone:** Phase 3 — Onboarding & tooling
**Predecessors:**
- T11 (`AmbiguityValidator` test-component carve-out — still used; the shadow detection itself doesn't change)
- T12 (test-classpath container emission — this design *replaces* the subclass mechanic with a peer mechanic)
- #127 (shadow detection: explicit `value()` + implicit walk — produces the shadow map this design consumes)
- #128 (override at injection sites — independent; unchanged by this work)

## Goal

Make `tiko-examples/12_testing/` (and any user project with the same shape)
work with production `@Component`s in `src/main/java/` and test fixtures
in `src/test/java/` — the natural Maven layout. Currently the test
container generated during `test-compile` ignores main-classpath
components because Maven's `test-compile` phase only presents test
sources to the annotation processor.

Surfaced during the `tiko-test` example module work (T16 of #122): the
implementer was forced to move every `@Component` into `src/test/java/`
to make the example work at all.

## Non-goals

- Re-thinking `@TestComponent` shadow *detection* (the implicit walk and
  explicit `value()` from #127 stay).
- Re-thinking runtime `TikoOptions.override(...)` (the per-call-site
  mechanism from #128 stays).
- Touching `@Produces` semantics.
- Compile-time validation of cross-container wiring (the aggregator
  already does runtime composition; no new compile-time checks).

## Design decisions

Three foundational calls:

1. **Test container is a peer of main, not a subclass.** Both are loaded
   by `AggregatingContainer`. The test container is standalone — it
   does not extend, reference, or inherit anything from the main
   container's generated class. Composition over inheritance, matching
   the `[[interfaces-and-composition-over-impls-and-inheritance]]`
   memory.
2. **Shadow routing lives in the aggregator, not in inherited Java
   method overrides.** A new `META-INF/tiko/test-shadows.properties`
   descriptor declares which routable keys each test container claims.
   `AggregatingContainer` reads it and routes shadowed lookups to the
   declaring test container.
3. **`AggregatingContainer` is always used when any test descriptor is
   present** — single-module test scenarios pay a small init overhead
   but the routing path becomes uniform. The
   `createSingleModuleContainer` direct-instantiation path is unused
   when `test-container.properties` is on the classpath.

This design **replaces** the T12 subclass mechanic. T12's
`generateTestSubclass`, the `extensibleMainContainer` toggle, the
field-visibility relaxation (`scopeStorageModifiers()`), and the test
container's `extends MainContainer` declaration all go away.

## Architecture

### Touch points

```
tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java
  ← generateTestSubclass: replace with standalone test-container emission
  ← scopeStorageModifiers: drop the toggle, always emit private
  ← emit META-INF/tiko/test-shadows.properties when shadow map is non-empty
  ← do NOT emit a fresh main container in test-compile when one exists on classpath
     (skip generateOne for the main container; we keep the test container generation)
  ← the @Produces / test-only-additions logic stays in the test container

tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java
  ← load META-INF/tiko/test-shadows.properties from each test-descriptor's classpath root
  ← build a Map<String, Container> shadowRouting
  ← in get(Class)/get(Class, String)/getAll(Class), consult shadowRouting before existing dispatch

tiko-runtime/src/main/java/io/tiko/runtime/Tiko.java
  ← always instantiate AggregatingContainer when a test descriptor is present
    (drop the single-module short-circuit for the test case)
```

No changes to:
- `TikoOptions` (the override builder API stays as #128 left it)
- `@TestComponent` annotation
- `AmbiguityValidator` (the test-component carve-out from T11 still
  populates `ProcessorContext.shadowedByTestOverride` — this design just
  consumes that map differently downstream)
- `ComponentModel.testExtraKeys` (still used for routable-key
  registration in the validator)

### Data flow

**Main module compiled in `compile` phase:**

```
src/main/java
  └── demo.Clock                @Component(SINGLETON)
  └── demo.OrderService         @Component(SINGLETON), injects Clock

  → emits:
    target/classes/io/tiko/generated/TikoContainerImpl_abc.class
    target/classes/META-INF/tiko/container.properties (impl=...TikoContainerImpl_abc)
    target/classes/META-INF/tiko/components.txt
```

**Test sources compiled in `test-compile` phase:**

```
src/test/java
  └── demo.FixedClock           @TestComponent extends Clock          (shadow)
  └── demo.TestProbe            @Component(SINGLETON)                 (test-only addition)

  → emits:
    target/test-classes/io/tiko/generated/TestContainerImpl_xyz.class    (standalone)
    target/test-classes/META-INF/tiko/test-container.properties (impl=...TestContainerImpl_xyz)
    target/test-classes/META-INF/tiko/test-shadows.properties
        demo.Clock=io.tiko.generated.TestContainerImpl_xyz
        // (demo.TestProbe is NOT in the shadow map — it's an addition, not a shadow)
```

The test container generated here is **standalone**:
- It does NOT extend `TikoContainerImpl_abc`.
- It contains factories ONLY for the test-side components (`FixedClock`,
  `TestProbe`) — not for any main components.
- Its constructor signature matches the existing six-arg form, so the
  aggregator's reflection-based instantiation works.
- It exposes `getFixedClock()` and `getTestProbe()` plus a
  `get(Class)`/`get(Class, String)` dispatcher that handles only its own
  types (returns `null`-equivalent / lets the aggregator dispatch
  elsewhere if the key isn't local).

**At runtime, `AggregatingContainer`:**

```
classpath scan for META-INF/tiko/test-container.properties → 1 hit (xyz)
classpath scan for META-INF/tiko/container.properties      → 1 hit (abc)
classpath scan for META-INF/tiko/test-shadows.properties   → 1 hit (xyz)

load both container classes via reflection (existing per-module dispatch)
build shadowRouting:
    "demo.Clock" → TestContainerImpl_xyz

on container.get(Clock.class):
    if "demo.Clock" in shadowRouting:
        return shadowRouting["demo.Clock"].get(Clock.class)  ← FakeClock
    else:
        existing per-module dispatch
```

For `container.get(OrderService.class)`: no shadow entry, falls through
to existing dispatch → main container's `getOrderService()` → returns
production `OrderService` whose constructor was wired with the
test-routed `Clock` (because at `OrderService` construction time, its
constructor's `Clock`-typed parameter is resolved via
`container.get(Clock.class)` — see #128's per-call-site override which
goes through the aggregator-aware dispatcher).

Hmm wait — let me check that.

Actually the main container's `getOrderService()` factory calls
`container.getClock()` (a direct method call on itself) per T128's
emission for the no-override path. That bypasses the aggregator.

Resolution: see "Cross-container constructor injection" below.

### Cross-container constructor injection

The shadow needs to flow into transitive injection sites. When the main
container's `OrderService` is constructed, its `Clock`-typed parameter
must resolve to the test's `FixedClock`, not the production `Clock`.

T128's per-call-site emission wraps each parameter in:

```java
Clock clk = container.options().hasOverride(Clock.class)
        ? (Clock) container.options().getOverride(Clock.class).get()
        : getClock();    // direct call to THIS container's getter
```

`getClock()` here is the local method on the main container — bypasses
the aggregator. The shadow doesn't apply.

**The fix:** the test descriptor's shadow declarations also populate
`TikoOptions.overrides` at runtime startup. The aggregator translates
each `test-shadows.properties` entry into an `override(<key>, () ->
shadowingContainer.get(<key>))` registration BEFORE per-module
containers are instantiated. Then T128's per-call-site override path
naturally picks up the shadow.

This means: `test-shadows.properties` isn't a separate routing
mechanism — it's a **declarative source** for runtime overrides. The
aggregator reads the file, registers overrides, and existing T128 logic
handles the rest.

Refined data flow:

```
Tiko.create(...)
  → AggregatingContainer constructor
  → discoverShadows(): scan test-shadows.properties files, build
    Map<routableKey, shadowingContainerFQN>
  → for each entry: TikoOptions.Builder.override(routableKey, () ->
    shadowingContainer.get(routableKey))
  → instantiate per-module containers with the augmented TikoOptions
  → main container's factories now see overrides for all shadow keys
  → shadows flow through transitive injection naturally
```

The "shadowing container" needs to exist before the override Supplier
fires. Since per-module instantiation is sequential and the test
container is instantiated alongside the main one, we capture each
per-module container reference as it's created, and the Supplier closes
over it via a final field.

### `AggregatingContainer.get(Class)` dispatch (after this change)

```java
@Override
public <T> T get(Class<T> type) {
    // options-level override fires for shadowed types too (registered above)
    if (options.hasOverride(type)) {
        return (T) options.getOverride(type).get();
    }
    // existing per-module type-arm dispatch
    ...
}
```

No new `shadowRouting` map at the aggregator level — the override map
*is* the shadow map. Cleaner than I first sketched.

### Cross-module test scenarios

Multi-module setups work without special-casing:

```
moduleA/target/classes/META-INF/tiko/container.properties
moduleA/target/test-classes/META-INF/tiko/test-container.properties
moduleA/target/test-classes/META-INF/tiko/test-shadows.properties

moduleB/target/classes/META-INF/tiko/container.properties
// moduleB has no @TestComponent — no test descriptor

moduleC/target/classes/META-INF/tiko/container.properties
moduleC/target/test-classes/META-INF/tiko/test-container.properties
moduleC/target/test-classes/META-INF/tiko/test-shadows.properties
```

When testing module A (its test classpath includes its own
`target/test-classes` and the JARs of B and C):

- Aggregator scans all `container.properties` → finds A's main, B's
  main, C's main.
- Aggregator scans all `test-container.properties` → finds A's test.
  (C's `test-classes/` isn't on A's test classpath — only A's own +
  upstream JARs without their test JARs by default.)
- Aggregator scans all `test-shadows.properties` → A's only.
- A's shadows register as overrides; the multi-module wiring composes
  naturally.

If A depends on a special "tiko-test JAR" published by C, then C's test
sources are on A's test classpath and shadow declarations compose. The
override Supplier dispatches to whichever container claims that key.
First-shadowed-wins; conflict between two shadow declarations for the
same key (rare) is logged as a runtime warning.

## Processor changes

### 1. Drop the fresh main regeneration in test-compile

In `ContainerGenerator.generate()`:

- The current code unconditionally calls `generateOne(mainContainerClassName, ...)` for the main container, then `generateTestSubclass(...)` if test components exist.
- Change: when test components exist AND a main `container.properties` is already on the compile classpath (probed via `Filer.getResource(StandardLocation.CLASS_PATH, "", "META-INF/tiko/container.properties")`), SKIP the main container regeneration.
- The standalone test container (see step 2) is emitted instead.
- When NO main descriptor exists on classpath (`tiko-test`'s own tests, `tiko-examples/12_testing/` with all components in `src/test/`, etc.), keep generating a fresh main container for backwards compatibility. This is the fallback path.

### 2. Replace `generateTestSubclass` with `generateStandaloneTestContainer`

The current `generateTestSubclass` emits a subclass declaration and overrides shadowed getters. Replace with `generateStandaloneTestContainer`:

- Emit a standalone `TestContainerImpl_<hash>` (not extending anything).
- Match the existing six-arg constructor signature so `AggregatingContainer`'s reflective instantiation works.
- Generate factories ONLY for components in `context.getTestSideComponents()` — i.e. `@TestComponent`s and test-only `@Component`s. Skip factories for main components (they live in the main container).
- Generate getters ONLY for those components: `getFakeClock()`, `getTestProbe()`, etc.
- Generate `get(Class)` and `get(Class, String)` dispatchers that handle only the test-side types. For any other type, throw `NoSuchComponentException` (same as existing per-module container behaviour today — single-module containers throw on unknown types, the aggregator catches and tries the next module).

### 3. Drop the `extensibleMainContainer` toggle

- `scopeStorageModifiers()` always returns `{PRIVATE, FINAL}`.
- The four fields (`singletons`, `requestScoped`, `eventScoped`, `options`) stay `private` always.
- Main container's bytecode is byte-identical to pre-T12 in the "no @TestComponent" case AND in the new "@TestComponent present" case. T12's relaxation is gone — generally tighter encapsulation.

### 4. Emit `META-INF/tiko/test-shadows.properties`

When the test container generation runs and `ProcessorContext.shadowedByTestOverride` is non-empty (i.e. at least one `@TestComponent` shadows a main `@Component`), emit:

```properties
# Generated by tiko-processor — test-component shadow declarations
demo.Clock=io.tiko.generated.TestContainerImpl_xyz
demo.AnotherShadowed=io.tiko.generated.TestContainerImpl_xyz
```

One line per shadowed key. Value is the test container's FQN (the
container that claims to shadow this key). Same FQN repeated for every
shadow registered by the same test container.

## Runtime changes

### 1. `Tiko.createInternal()` — always aggregate in test scenarios

Drop the single-module short-circuit when `test-container.properties` is
present on the classpath. Always instantiate `AggregatingContainer` in
that case. Production scenarios (no test descriptor) preserve the
existing single-module fast path.

### 2. `AggregatingContainer` — shadow-registration phase

In `AggregatingContainer.discoverAndInitializeModuleContainers()`:

1. Before instantiating per-module containers, scan
   `META-INF/tiko/test-shadows.properties` resources across the
   classpath. Build a `Map<String, String> shadowDeclarations`
   (key → shadowing container FQN).
2. Instantiate per-module containers (existing logic). As each is
   created, register it in a `Map<String, Container>
   containersByImplName`.
3. After all containers are instantiated, walk
   `shadowDeclarations`. For each entry, look up the shadowing
   container by FQN and call
   `options.override(routableKey, () -> shadowingContainer.get(...))`
   on the shared `TikoOptions`.
4. The override is keyed on the routable type (the `String` from the
   shadow file is the type's qualified name; convert to `Class<?>` via
   `Class.forName(...)`).
5. Conflict detection: if two shadow files claim the same key with
   different container FQNs, log a `WARNING` at `io.tiko.events` and
   first-write-wins.

### 3. `TikoOptions` shape and sequencing

No public API change. The aggregator augments overrides internally
before per-module instantiation. The sequencing is delicate because per-
module containers capture `TikoOptions` in their constructor:

1. **Aggregator constructor receives** the user's `TikoOptions`
   (containing whatever `override(...)` calls they made).
2. **Build a `Map<String, Container> containersByImplName`** (initially
   empty).
3. **Scan classpath** for `META-INF/tiko/test-shadows.properties`. For
   each entry `(routableKey, shadowFqn)`:
   - If user options already contain an override for `routableKey`,
     **skip** (user wins).
   - Else register `options.internalAddOverride(routableKey, () ->
     containersByImplName.get(shadowFqn).get(routableKey))`. The
     `internalAddOverride` is a package-private method on `TikoOptions`
     that mutates the override map *only when called from the aggregator
     during initialisation* — the public API stays builder-only.
4. **Instantiate per-module containers**, passing the now-augmented
   `TikoOptions`. As each is created, store in `containersByImplName`
   by its `impl=` FQN.
5. **First container.get(...)** that resolves a shadowed key triggers
   the Supplier, which looks up the shadow container in the now-
   populated map and delegates.

**User precedence:** user-provided `override(...)` calls win because
step 3's skip rule means the aggregator never overwrites them. This is
the test author's escape hatch — declare `@TestComponent` for the
convenience, override one specific key at runtime via `TikoOptions`
when finer control is needed.

The `internalAddOverride` package-private method is the only TikoOptions
mutation surface; user code cannot reach it. It exists because
`TikoOptions` is otherwise immutable and the aggregator needs to inject
overrides AFTER the user has already called `build()`. Alternative
designs (rebuild TikoOptions after shadow scan, or thread shadows
through a separate path) were considered and rejected for being more
invasive — see "Out of scope" for the rationale.

## Edge cases

| Scenario | Behavior |
|----------|----------|
| Main has `@Component`s, test has `@TestComponent` shadowing one | Standalone test container with FakeX getter; shadow registers as override; main's `getOrderService` factory sees override at its `Clock`-typed param. |
| Main has `@Component`s, test has only test-only `@Component`s (no shadow) | Standalone test container with the test-only getters; no shadow file emitted; aggregator dispatches test-only types via existing per-module type-arm match. |
| Main has `@Component`s, test has shadow + test-only addition | Both — shadow file + test-only getter. |
| Main has nothing, test has `@Component`s | Falls back to generating a fresh main container in test-compile (existing behaviour). Standalone test container would conflict for the @Component classes that aren't shadows. |
| Main has nothing, test has `@TestComponent` | Per #127, `@TestComponent` with no `@Component` ancestor and no explicit `value()` is a pure addition (no shadow). Standalone test container exposes it like any other test-only component. No `test-shadows.properties` entry emitted for it. |
| 2+ shadow files claim same key with different FQNs | WARN log, first-write-wins. Multi-module convention: each module shadows its own; cross-module shadowing is rare and ambiguous. |
| User calls `TikoOptions.override(Clock.class, ...)` while `@TestComponent FakeClock` exists | User override wins (aggregator skips its registration for already-present keys). |
| Multi-module: A has `@TestComponent` shadowing A's `@Component`; B has unrelated `@Component` | A's shadow registers; B's dispatch path unchanged; aggregator's existing federation handles B. |
| `getAll(Class)` for a shadowed type | Out of scope (consistent with #128 — overrides apply to single-instance lookup). Returns the production list. |

## Validation rules

No new compile-time rules. The shadow detection happens upstream (T11's
carve-out, #127's superclass-walk + explicit value). This design
changes *how the shadow is realised at runtime*, not *what counts as a
shadow*.

## Testing

In `tiko-processor/src/test/java/io/tiko/processor/`:

- `TestContainerIsStandaloneTest` — generated test container's source
  does NOT contain `extends ` (no inheritance from main).
- `TestShadowsPropertiesEmittedTest` — when `@TestComponent` shadows
  exist, `test-shadows.properties` is emitted with the right entries.
- `TestContainerHasOnlyTestSideFactoriesTest` — generated test container
  doesn't contain factories for main components.
- `MainContainerNotRegeneratedAtTestCompileTest` — when a main descriptor
  is on the classpath, the test-compile round does NOT emit a fresh
  `TikoContainerImpl_<hash>` (only the test container + shadow file).
- `FreshMainGeneratedWhenNoMainOnClasspathTest` — fallback case: no
  main descriptor on classpath → fresh main container generated
  (existing behaviour preserved).

In `tiko-runtime/src/test/java/io/tiko/runtime/`:

- `AggregatorRegistersShadowOverridesTest` — aggregator reads
  test-shadows.properties on init, registers overrides on the shared
  `TikoOptions`.
- `UserOverrideWinsOverShadowDeclarationTest` — when user already
  overrode a key, the shadow declaration is skipped.
- `ShadowConflictWarnsAndUsesFirstWriteTest` — two shadow files claim
  the same key → WARN log + first-write-wins.

In `tiko-examples/12_testing/`:

- Move `Clock`, `PaymentGateway`, `HttpPaymentGateway`, `OrderService`,
  `AccountRepository`, `OrderCreatedEvent`, etc. from `src/test/java/`
  back to `src/main/java/`.
- Test fixtures (`FixedClock`, mocks, lifecycle test, etc.) stay in
  `src/test/java/`.
- All existing tests should pass: `MockedPaymentTest`, `FixedClockTest`,
  `OrderServiceTest`, `AsyncHandlerTest`, `PerClassLifecycleTest`,
  `RequestScopedRepoTest`.
- The example becomes the canonical "production in main, tests in test"
  layout — what every Maven user expects.

## Documentation updates

- `docs/testing.md` — remove the "Known limitations" entry for #129.
  Replace with a positive description: "Components in `src/main/java/`,
  test fixtures in `src/test/java/` — the natural Maven layout. Tiko's
  aggregator at runtime federates the two and applies `@TestComponent`
  shadow declarations."
- `docs/roadmap.md` — mark #129 shipped; counter `3/6 → 4/6`.
- `tiko-examples/12_testing/README.md` — update the layout description
  now that production components live in `src/main/java/`.

## Out of scope

- `getAll(Class)` override behaviour (still ignored; documented).
- Cross-module shadow conflicts (warn + first-wins; users with real
  conflict needs file follow-up).
- Compile-time check that shadow declarations actually shadow something
  (the upstream validator already enforces this; the file is just
  declarative output).

## References

- `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java:216-269`
  — `generateTestSubclass` being replaced by standalone emission.
- `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java:548-552, 737-739`
  — `scopeStorageModifiers()` toggle being dropped.
- `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java:76-102`
  — `generate()` orchestration getting the "skip main regeneration if descriptor on classpath" branch.
- `tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java:176-213`
  — `discoverAndInitializeModuleContainers` gaining the shadow-registration phase.
- `tiko-runtime/src/main/java/io/tiko/runtime/Tiko.java:102-118`
  — descriptor-preference logic gaining the always-aggregate-in-test branch.
- [#129](https://github.com/tomas-samek/tiko-di/issues/129) — tracker.
- Project memory `[[interfaces-and-composition-over-impls-and-inheritance]]`
  — the principle that pushed us from subclass to peer composition.
