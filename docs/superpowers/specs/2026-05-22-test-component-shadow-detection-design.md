# `@TestComponent` shadow detection — extend beyond interface collisions

**Status:** Design approved 2026-05-22. Implementation plan to follow.
**Tracker:** [#127](https://github.com/tomas-samek/tiko-di/issues/127)
**Milestone:** Phase 3 — Onboarding & tooling
**Predecessors:**
- T9 (`@TestComponent` annotation) — extending its attribute surface
- T10 (processor collects `@TestComponent`) — extending its ComponentModel build path
- T11 ([[AmbiguityValidator]] carve-out) — extending the registration step it groups on
- T12 (test-classpath container emission) — unchanged; this design only enriches the input to T11's carve-out, not the carve-out itself

## Goal

Make `@TestComponent class FakeX extends X` (where `X` is a production
`@Component`) shadow `X` in the test container. Today this canonical
"fake extends production class" pattern silently fails to shadow — the
test exposes `FakeX` as a brand-new addition because `AmbiguityValidator`
groups providers by self-FQN + direct interfaces and never walks the
superclass chain.

Surfaced during the `tiko-test` example module work (T16): the
implementer was forced to drop `FixedClock extends Clock` from
`tiko-examples/12_testing/` because the shadow path didn't fire.

## Non-goals

- Touching the `TikoOptions.override(...)` runtime path (that's [#128](https://github.com/tomas-samek/tiko-di/issues/128)).
- Reading main-classpath components during `test-compile` (that's [#129](https://github.com/tomas-samek/tiko-di/issues/129)).
- Re-thinking the shadow mechanism itself — the T11 carve-out is correct;
  this design just enriches what gets registered before T11 runs.
- Changing `@TestComponent`'s retention (stays `SOURCE`).
- Supporting `@Produces` factory-method shadowing (out of scope, same
  rationale as T6/T7).

## Design decisions

Four foundational calls made during brainstorming:

1. **Hybrid declaration model.** Explicit `@TestComponent(value = X.class)`
   when the user names the target; implicit superclass-chain walk when
   `value` is unset. Explicit always wins over implicit.
2. **Unified "extra routable keys" mechanism.** Both explicit and
   implicit paths produce the same intermediate output — a set of
   additional type keys the `@TestComponent` is registered under. The
   existing T11 carve-out in `AmbiguityValidator` then fires unchanged.
3. **Scope mismatch is a compile error.** When the test component's
   `scope()` differs from the shadowed production component's `scope()`,
   fail the build with a clear diagnostic. Forces a conscious decision
   and matches Tiko's compile-time-safety philosophy. Reversible — if
   real users complain, we can relax to a warning later.
4. **Named shadowing is name-matched.** `@TestComponent(name = "primary")`
   shadows `@Component(name = "primary")`. Unnamed test shadows unnamed
   production. No cross-name shadowing.

## Architecture

### Touch points

```
tiko-test/src/main/java/io/tiko/test/TestComponent.java          ← add `value()` attribute
tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java
                                                                 ← compute extra keys when building test ComponentModel
tiko-processor/src/main/java/io/tiko/processor/model/ComponentModel.java
                                                                 ← new field `testExtraKeys: Set<String>` carrying the computed shadow targets from explicit value() or implicit walk
tiko-processor/src/main/java/io/tiko/processor/validation/AmbiguityValidator.java
                                                                 ← register test components under their extra keys too
                                                                 ← scope-mismatch check inside the existing carve-out
```

### Data flow

```
User writes @TestComponent class FakeX [(value = T.class)] extends Y implements Z

  ↓ processor's buildTestComponentModel:

  if @TestComponent.value() != Void.class:
      extraKeys = { value() }
      compile-time check: FakeX must be assignable to value()
  else:
      ancestor = walk FakeX.getSuperclass() until @Component-annotated or Object
      if found:
          extraKeys = routableTypes(ancestor)   // ancestor's FQN + its declared interfaces
      else:
          extraKeys = {}                         // pure addition, no shadow

  ComponentModel.testExtraKeys = extraKeys

  ↓ AmbiguityValidator.validate:

  for each @TestComponent providerInfo:
      register under (existing) self-FQN, declared interfaces
      additionally register under each extraKey

  ↓ existing T11 carve-out:

  for each grouped key with collision:
      if 1 test + ≥1 main:
          if test.scope() != main.scope(): ERROR
          else: markShadowedByTestOverride(main, test)
```

## `@TestComponent` API change

```java
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface TestComponent {

    /**
     * Explicit shadow target. When set, the test component shadows the production
     * {@code @Component} that resolves to this type. The annotated class MUST be
     * assignable to {@code value()} (compile-time check).
     *
     * <p>When unset (default {@link Void Void.class}), the processor walks the
     * test class's superclass chain and shadows the first {@code @Component}-annotated
     * ancestor (if any). If no annotated ancestor exists and no {@code value()} is set,
     * the test component is a pure addition with no shadowing behaviour.
     */
    Class<?> value() default Void.class;

    Scope scope() default Scope.SINGLETON;

    String name() default "";
}
```

`Void.class` is the "unset" sentinel — annotation attributes can't be
genuinely optional, so this is the standard Java idiom for "user didn't
specify a target."

## Implicit walk algorithm (when `value() == Void.class`)

```
TypeElement ancestor = testClass.getSuperclass()
while ancestor != null && ancestor.qualifiedName != "java.lang.Object":
    if ancestor has @Component annotation:
        return routableTypesOf(ancestor)
    ancestor = ancestor.getSuperclass()
return empty set
```

- **First `@Component` ancestor wins.** If both `B` and `A` are
  `@Component` and `FakeC extends B extends A`, shadow `B` only (the
  nearer ancestor is the intended substitution target).
- **Interfaces are NOT walked implicitly.** A `@TestComponent class FakeX
  implements I` where production has `@Component class X implements I` is
  *already* handled by the existing interface-collision mechanism (both
  register under `I`'s key). No new code needed for that case.
- **`routableTypesOf(ancestor)`** returns the ancestor's own FQN plus its
  directly declared interfaces — the same set the validator already
  registers production components under. This means a `FakeC extends B
  implements Cloneable`-shadowing-a-`@Component B implements I` causes
  `FakeC` to register under `B` and `I` — wherever `B` was reachable,
  `FakeC` substitutes.

## Explicit `value()` semantics

When `@TestComponent(value = T.class)` is set:

1. **Compile-time assignability check.** If the annotated class is not
   assignable to `T`, emit a compile error pointing at the `value`
   attribute. Same diagnostic style as other tiko-processor compile-time
   errors.
2. **`extraKeys = { T }`.** Only the explicit target — no implicit walk
   in addition. The user named the target; don't second-guess.
3. **Works for interfaces and classes.** If `T` is an interface, the
   test impl must `implements T`. If `T` is a class, the test impl must
   `extends T` (or `T` itself, though that's degenerate).
4. **`value()` overrides implicit walk.** If both `value()` is set AND
   there's a `@Component` ancestor, only `value()` is used. Explicit
   beats implicit always.

## Scope-mismatch policy

Inside the T11 carve-out, before calling
`context.markShadowedByTestOverride(...)`:

```java
ComponentModel testModel = testProviders.get(0).componentModel;
for (ProviderInfo main : mainProviders) {
    if (main.componentModel.getScope() != testModel.getScope()) {
        reporter.error(testModel.getTypeElement(),
            "Scope mismatch: @TestComponent %s declares scope %s but shadows "
            + "@Component %s declared with scope %s. Either match the scope or "
            + "use TikoOptions.override(...) for different lifecycle.",
            testModel.getQualifiedName(), testModel.getScope(),
            main.componentModel.getQualifiedName(), main.componentModel.getScope());
        continue;  // don't record the shadow; build fails
    }
    if (main.componentKey != null) {
        context.markShadowedByTestOverride(main.componentKey, testModel);
    }
}
```

Error message includes both scopes and points the user at the runtime
escape hatch.

## Edge cases

| Scenario | Behavior |
|----------|----------|
| `@TestComponent class FakeX extends X` where `@Component X` | Implicit shadow of X. Test impl registers under {FakeX, X, X's interfaces}. |
| `@TestComponent class FakeX implements I` where `@Component X implements I` | Existing interface-collision mechanism — no new code. |
| `@TestComponent(value = I.class) class FakeX implements I` (multi-prod-impls of I exist) | Explicit shadow of I — replaces every prod impl of I at the I key. Production may have its own ambiguity error if it had unnamed multi-impls without `@Named`. |
| `@TestComponent class FakeX extends B extends A` where both A and B are `@Component` | Shadow B (closer ancestor). |
| `@TestComponent class FakeX extends Object` (no `@Component` ancestor) and no `value()` | Pure addition. No shadow. Behaves as a test-only component. |
| `@TestComponent class FakeX` and `@TestComponent class FakeY` both with `value = X.class` | Collision under X's key with 2 test components → existing T11 rule: error. |
| `@TestComponent(name = "primary") class FakeX extends X` shadows `@Component(name = "primary") X` | Yes — name match. `@TestComponent class FakeY extends X` shadows unnamed `@Component X`. Different name slots. |
| `@TestComponent class FakeX extends X` with `@TestComponent.scope = SINGLETON` and `@Component X` with `scope = REQUEST` | Scope-mismatch compile error. |
| `@TestComponent(value = String.class) class FakeX` (FakeX not assignable to String) | Compile error: "value type must be assignable from annotated class." |
| `@TestComponent class FakeX` (no `value`, no `@Component` ancestor, but FakeX implements an interface a production component also implements) | Existing interface-collision mechanism fires. T11 carve-out shadows the production component on the interface key. |

## Validation rules summary

Compile errors (build fails):

1. `value()` set, test class not assignable to `value()`.
2. Shadow detected with scope mismatch between test and production.
3. Two `@TestComponent`s shadow the same key (extends T11 ambiguity rule
   to the new shadow paths).

No new warnings. No silent behaviors.

## Testing

In `tiko-processor/src/test/java/io/tiko/processor/`:

- `TestComponentShadowDetectionTest` — covers each row of the edge-cases
  table, one test method per row. Uses Google compile-testing to assert
  on generated container source + diagnostic messages.
- `TestComponentExplicitValueTest` — focused on the `value()` attribute:
  assignability check, explicit overrides implicit, value-is-interface
  variant.
- `TestComponentScopeMismatchTest` — error messages include both scopes
  and point to `TikoOptions.override(...)`.

In `tiko-examples/12_testing/`:

- Add back `FixedClock extends Clock` + `FixedClockTest` (currently
  dropped, see [`tiko-examples/12_testing/README.md`](../../../tiko-examples/12_testing/README.md)).
- Update the README to remove the workaround note for #127.

## Documentation updates

- `docs/testing.md` — replace the "Known limitations" entry for #127
  with the documented shadow-detection mechanism. Add a small subsection
  on the implicit-vs-explicit choice with examples.
- `docs/roadmap.md` — mark #127 as shipped under Phase 3.

## References

- `tiko-processor/src/main/java/io/tiko/processor/validation/AmbiguityValidator.java:49-92`
  — current registration + T11 carve-out
- `tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java:230-241`
  — current `buildTestComponentModel`
- `tiko-test/src/main/java/io/tiko/test/TestComponent.java` — annotation
  being extended
- `tiko-processor/src/main/java/io/tiko/processor/model/ComponentModel.java:141-143`
  — `getComponentKey()` shape (unchanged)
- [#127](https://github.com/tomas-samek/tiko-di/issues/127) — tracker
- [#128](https://github.com/tomas-samek/tiko-di/issues/128), [#129](https://github.com/tomas-samek/tiko-di/issues/129) — sibling Phase 3 follow-ups
