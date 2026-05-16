# `Set<X>` support in `@Configuration` records — design

**Issue:** [#63](https://github.com/tomas-samek/tiko-di/issues/63) — Phase 2 — Configuration & distributed events.

## Goal

`@Configuration` records accept `Set<X>` fields where `X` is any type already supported in `List<X>` / `Map<String,X>` / `Optional<X>` (scalar, enum, nested record, or any composition thereof). YAML lists are coerced into an immutable `Set` view of a `LinkedHashSet` — duplicates are deduped silently with a JUL warning per occurrence; insertion order is preserved.

Today, declaring a field as `Set<X>` fails the annotation-processor's type-set check with `"unsupported config type 'Set'"`. After this change, the same field binds without further action by the user.

## Architecture

The implementation parallels `List<X>` exactly — same three files, plus a fourth doc-only edit:

1. **`tiko-config/.../coercers/CompositeCoercers.java`** — new `public static <X> TypeCoercer<Set<X>> set(TypeCoercer<X> elementCoercer)` method, mirrored on the existing `list(...)` factory at lines 15–25.
2. **`tiko-processor/.../config/ConfigSupportedTypes.java`** — `"java.util.Set"` added to the FQN check at line 39 (alongside `Optional`/`List`/`Map`); `"Set<X>"` added to `bundledTypeNames()`.
3. **`tiko-processor/.../config/ConfigBinderGenerator.java`** — `coercerExpr` (line 382) handles `Set` with the same shape as `List` (single type-arg, value-arg index 0). `emitNestedCoercersFor` (line 195) walks into `Set`'s type argument so `Set<NestedRecord>` triggers nested-coercer generation.
4. **Docs:** README's `@Configuration` supported-types example list gains `Set<X>`. `docs/roadmap.md` "What ships today" closes #63.

No changes to `BindContext`, `ConfigurationValidator` (validation defers to `ConfigSupportedTypes.isSupported`), `ConfigSource`, `Tiko`, or the `tiko-api` surface. Pre-1.0 keeps the diff small.

## Behaviour detail

### Happy path

```yaml
app:
  hosts: [alpha, beta, gamma]
```

```java
@Configuration("app")
public record AppConfig(Set<String> hosts) {}
```

→ `hosts = {alpha, beta, gamma}` (immutable `Set.copyOf` over a `LinkedHashSet`; iteration order matches YAML order).

### Dedupe + warning

```yaml
app:
  hosts: [alpha, beta, alpha, gamma]
```

→ `hosts = {alpha, beta, gamma}` (size 3). One JUL `WARNING` line is logged for `alpha`:

```
@Configuration Set<X> field: duplicate value 'alpha' deduped
```

The log goes through the existing `io.tiko.config` JUL namespace, using the same `LoggerHolder` lazy-init pattern as `DefaultErrorHandler`. Cold-start cost stays zero unless duplicates actually appear.

The warning is intentionally **path-agnostic** — `TypeCoercer<T>` does not receive the binding context's dot-path, and threading it through would expand API surface across every coercer for a single observability concern. The surrounding bootstrap logs already identify which `@Configuration` record is being bound; combined with the duplicate value, the warning is enough for the user to find the offending YAML line. A future change can promote this to a structured `ConfigurationWarning` `ErrorContext` permit if the JUL line proves insufficient.

### Empty list, missing key, non-list input

- `hosts: []` → empty set (size 0). No warning.
- Missing `hosts` key on a required `Set<X>` field → existing `MISSING_KEY` flow via `BindContext.requireScalar` (anchored to the parent section via #19's plumbing). No `Set`-specific behaviour.
- `hosts: notAList` (scalar where list expected) → `CoercionException("expected list, got String")` — same wording as the existing `list` coercer, surfaced as `INVALID_VALUE` anchored to the value's line.

### Nesting

- `Set<EnumX>` — composes via `Coercers.enumCoercer(X.class)`.
- `Set<NestedRecord>` — composes via the existing nested-coercer generation in `emitNestedCoercersFor`. The processor must recurse into `Set`'s type argument exactly as it does for `List` today, otherwise the nested coercer for the inner record never gets emitted.
- `Set<Set<X>>` and other unusual compositions — `coercerExpr` recurses naturally if the inner type is supported, but no test coverage is planned. Documented as out of scope.
- `Optional<Set<X>>` — works via the existing `unwrapOptional` step in `coercerExpr`.

### Defaults

`@Default` on `Set` fields is **not supported**, matching the existing rule for `List`/`Map`/`Optional`. `@Default` is a single-scalar-default mechanism; collections default to empty via `cardinality()` semantics in `ConfigFieldModel`. No validator change needed — the existing `@Default + non-scalar` interaction already handles this through the `validateDefault` path which fails for non-scalar effective types.

### Source-anchored errors

The dedupe warning is path-agnostic, but **coercion failures inside the set** (e.g., a malformed element value) propagate as `CoercionException` and are reported by `BindContext.requireScalar` at the set field's location — same as `List<X>` today. No new source-anchoring work.

## Test coverage

Three layers, matching how `List<X>` is covered today:

1. **`CompositeCoercersTest`** (unit, in `tiko-config`) — happy path; dedupe with order preservation; non-list rejection; empty-list edge; element coercion via composition (`set(intCoercer)` over `[1, 2, 3]`); **warning emission** — install a JUL `Handler` over the `io.tiko.config` logger in the test, bind `[a, b, a]`, assert one `WARNING` record observed with the expected message.
2. **`ConfigBinderGeneratorIT`** (compile-testing, in `tiko-processor`) — a `@Configuration` record with `Set<String>`, `Set<MyEnum>`, and `Set<NestedRecord>` fields compiles cleanly and produces a binder that uses `CompositeCoercers.set(...)`. Verify the generated source contains the expected `set(...)` call shape.
3. **`02_config` end-to-end** — a config record with a `Set<String>` field bound from real YAML; assert dedupe + order preservation through the full `Tiko.create(...)` path. One additional test method on an existing `02_config` test class, or a new test class if the shape doesn't fit cleanly.

## Out of scope

- `TreeSet` / sorted-set semantics — separate design.
- `Set` as the element type of another collection (`List<Set<X>>`, `Map<String, Set<X>>`) — recurses naturally but no test coverage planned.
- Per-element source anchors inside a set (e.g., `"alpha is the duplicate at line 7"`). Lists have the same gap today.
- Promotion of the dedupe warning from JUL to a structured `ConfigurationWarning` `ErrorContext` permit. Reversible later if needed.
- Non-String map keys (existing gap, separate concern).

## Compatibility

Pure addition. Every existing `@Configuration` field declaration keeps its current behaviour. The new `CompositeCoercers.set(...)` method is additive on the `CompositeCoercers` final class. `ConfigSupportedTypes.bundledTypeNames()` gains a new entry — any test asserting on the exact contents of that list would need updating, but the list is documentation surface only and unlikely to have exact-match assertions.

## Acceptance

- [ ] `CompositeCoercers.set(elementCoercer)` returns a `TypeCoercer<Set<X>>` that accepts YAML lists, dedupes via `LinkedHashSet`, emits one JUL `WARNING` per duplicate, and returns an immutable `Set.copyOf(...)` view.
- [ ] `ConfigSupportedTypes.isSupported` accepts `Set<X>` field types; `bundledTypeNames()` lists `"Set<X>"`.
- [ ] `ConfigBinderGenerator.coercerExpr` emits `CompositeCoercers.set(...)` for `Set<X>` fields.
- [ ] `ConfigBinderGenerator.emitNestedCoercersFor` recurses into `Set`'s type argument so `Set<NestedRecord>` produces a nested coercer.
- [ ] `CompositeCoercersTest` covers happy path, dedupe + warning, empty list, non-list rejection, nested element coercion.
- [ ] Codegen test compiles a `@Configuration` with `Set<String>`, `Set<MyEnum>`, `Set<NestedRecord>` fields and asserts the generated binder calls `CompositeCoercers.set(...)`.
- [ ] End-to-end test in `02_config` binds a `Set<String>` field from real YAML and verifies dedupe + order preservation.
- [ ] README "@Configuration" supported-types example mentions `Set<X>`.
- [ ] `docs/roadmap.md` "What ships today" entry closes #63.
- [ ] Full reactor `mvn -pl '!tiko-bom' install` green; Spotless clean.
