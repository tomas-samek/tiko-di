# Configuration Injection — v1 Design

**Status:** Design approved, ready for implementation plan.
**Date:** 2026-05-04
**Phase:** Phase 1 — Alpha completion (promoted from Phase 2 per 2026-04-27 priority decision).

---

## Context

Tiko's existing core DI is functional end-to-end but offers no first-class way to inject configuration values from external files. Users today either pass settings programmatically through a hand-written `ConfigProvider` `@Component` (illustrative only — no Tiko machinery behind it) or read system properties / env vars directly inside their services. Both routes leak configuration plumbing into business logic and have no compile-time or startup validation.

This design adds typed configuration injection with three guarantees:

1. The configuration **schema is a Java type** — a record annotated `@Configuration`. Tiko knows it completely at compile time.
2. The runtime configuration data lives in a YAML file shipped with the application and may change without recompiling. Editing values does not trigger annotation-processor work.
3. Validation is split: compile-time errors catch what the type system can prove (unsupported types, malformed defaults, duplicate prefixes); startup errors catch everything else (missing keys, wrong types in YAML, unknown keys) before any consumer's `@PostConstruct` runs.

This preserves VISION.md's "if it builds, it runs" claim at the *code* level while letting deployment-time YAML evolve freely.

## Goals

- Inject typed configuration records into `@Component` constructors as ordinary singletons.
- Generate per-record binder code at compile time. **No reflection at runtime.**
- Validate the YAML at container startup against the schema derived from records.
- Accumulate validation errors, report all problems in one exception.
- Support the comprehensive set of JDK types people put in config files.
- Keep the runtime dependency on YAML parsing isolated to a new module so users without `@Configuration` records pay nothing.
- Establish internal seams so a future public `TypeCoercer<T>` SPI is a backwards-compatible addition.

## Non-goals (deferred or excluded)

- **Profile-specific YAML overlays** (`config-prod.yaml` overlaying `config.yaml`). Deferred to a follow-up phase. Users needing per-profile config in v1 can supply different `ConfigSource`s programmatically.
- **`ConfigSources.env()` as a layered source.** Env vars are handled exclusively via `${VAR}` interpolation in YAML scalars. Sidesteps the env-var-name-to-YAML-path mapping bikeshed.
- **JSON Schema artifact generation.** Recognised as a future deliverable for editor tooling, but not part of v1. The compile-time manifest of `@Configuration` records is written to support it later.
- **Customizers / post-processors / interceptors** on the loading pipeline. The pipeline is *load → interpolate → validate → bind* with no transformation hook in between for v1.
- **Constraint annotations** (`@Min`, `@Max`, `@Pattern`, `@Email`). Schema is the record's type system — full stop. v1 ships no extra validation vocabulary.
- **User-supplied schema files.** The schema comes only from records.
- **Runtime re-validation / hot reload.** YAML is read and validated once at startup. Per VISION.md, Tiko has no hot-reload story.
- **Public `TypeCoercer<T>` SPI.** Internal registry only in v1. Designed to be exposed later additively.

---

## Architecture

### Module layout

| Module | Role | New deps |
|---|---|---|
| `tiko-api` | Three new annotations: `@Configuration`, `@Default`, `@Key`. One new public interface: `ConfigSource`. | None. |
| `tiko-processor` | Extended (not split). Validates `@Configuration` records, generates per-record binder classes, generates a per-module `ConfigBinderRegistry` class, writes manifest entries to `META-INF/tiko/configs.txt`. | `tiko-config` at **processor scope only**, for shared coercer logic. SnakeYAML is on the processor classpath but never executed at compile time (lazy-loaded by `YamlLoader`, which the processor does not call). Does not leak onto user runtime classpaths via Maven `<annotationProcessorPaths>` — that scope is isolated from compile/runtime. |
| **`tiko-config` (new)** | All runtime helpers: `ConfigSources` factory class, YAML loader, `${VAR}` interpolation pass, internal `TypeCoercerRegistry`, `BindContext`, `ConfigBinder<T>` interface, `ConfigValidationException`. **Sole module that depends on SnakeYAML.** | SnakeYAML (pinned, not shaded). |
| `tiko-runtime` | One small change: `Tiko.create(ConfigSource)` overload added; the no-arg `Tiko.create()` keeps working and fails fast at startup if `@Configuration` records exist but no source was provided. | **None.** `tiko-runtime` does not depend on `tiko-config` or SnakeYAML — they are required only when the user actually has a `@Configuration` record. |

**Why a separate `tiko-config` module:** keeps `tiko-runtime`'s "~100KB, zero deps beyond `tiko-api`" pitch intact.

**Why extend `tiko-processor` instead of forking a new processor:** validation rules (cross-record naming, type-set membership, prefix uniqueness) participate in the same compile-time round as `@Component` validation. The processor already owns the element-discovery and error-reporting infrastructure.

### Build-time vs runtime split

| Phase | Action |
|---|---|
| Compile (in `tiko-processor`) | Scan records, validate type set / record shape / prefix uniqueness / `@Default` parseability, generate `<RecordName>ConfigBinder.java` and a per-module `ConfigBinderRegistry.java` into `target/generated-sources/annotations/io/tiko/generated/config/`, write manifest line `<fqn>=<prefix>` to `META-INF/tiko/configs.txt`. |
| Runtime (in `tiko-config`, called from `tiko-runtime`'s startup path) | `ConfigSource.load()` → `Map<String,Object>` → `${VAR}` interpolation → top-level prefix check → per-record `bind(...)` → accumulate errors → throw `ConfigValidationException` if any → register bound records as singletons → continue normal startup. |

### Cross-module aggregation

Reuses Tiko's existing `META-INF/tiko/` aggregation pattern. `AggregatingContainer` collects manifest entries from every linked module, merges the per-module `ConfigBinderRegistry` lists into one combined registry. Same plumbing already used for component aggregation.

---

## Components

### Annotations (`io.tiko.annotations`)

All `@Retention(SOURCE)` to match `@Component`.

```java
@Target(TYPE)        public @interface Configuration { String prefix(); }
@Target(PARAMETER)   public @interface Default      { String value(); }
@Target(PARAMETER)   public @interface Key          { String value(); }
```

`@Default` and `@Key` apply to record components (which are constructor parameters in record syntax). `@Key` is the escape hatch for users who want kebab-case or other naming styles per field; verbatim camelCase remains the default.

### `ConfigSource` API

```java
// io.tiko.ConfigSource — in tiko-api
public interface ConfigSource {
    Map<String, Object> load();   // returns YAML-shaped tree
}
```

Anyone can implement `ConfigSource` without depending on `tiko-config`.

```java
// io.tiko.config.ConfigSources — in tiko-config
public final class ConfigSources {
    public static ConfigSource classpath(String resourcePath);
    public static ConfigSource file(Path path);
    public static ConfigSource fromMap(Map<String, Object> map);
    public static ConfigSource layered(ConfigSource... sources);
}
```

Factory methods live on the utility class because `classpath` and `file` need SnakeYAML — keeping them off the interface preserves `tiko-api`'s dependency-free contract.

### Generated artifacts (per compilation unit)

For a record `io.example.app.DbConfig`:

```java
// generated: io.tiko.generated.config.DbConfigBinder
public final class DbConfigBinder implements ConfigBinder<DbConfig> {
    @Override public Class<DbConfig> type() { return DbConfig.class; }
    @Override public String prefix()        { return "db"; }
    @Override public DbConfig bind(Map<String,Object> root, BindContext ctx) {
        Map<String,Object> node = ctx.requireSection(root, "db");
        String url = ctx.requireString(node, "url", "db.url");
        int maxConnections = ctx.intOrDefault(node, "maxConnections", "db.maxConnections", 10);
        Optional<Duration> connectTimeout =
            ctx.optionalDuration(node, "connectTimeout", "db.connectTimeout");
        ctx.checkUnknownKeys(node, "db", Set.of("url", "maxConnections", "connectTimeout"));
        return new DbConfig(url, maxConnections, connectTimeout);
    }
}
```

Plain method body, explicit field reads, dispatches coercion through the `BindContext`. Steppable in a debugger. No reflection.

```java
// generated: io.tiko.generated.config.ConfigBinderRegistry — one per module
public final class ConfigBinderRegistry {
    public static List<ConfigBinder<?>> all() {
        return List.of(new DbConfigBinder(), new KafkaConfigBinder());
    }
}
```

Discovered statically by the runtime; no `Class.forName(...)` at startup.

```
# META-INF/tiko/configs.txt (per module, written by the processor)
io.example.app.DbConfig=db
io.example.app.KafkaConfig=kafka
```

Used by `AggregatingContainer` for cross-module aggregation. Also the input the JSON Schema generator (follow-up phase) will read.

### Runtime helpers (`tiko-config`)

- **`ConfigBinder<T>`** — public interface. Generated binders implement it.
- **`BindContext`** — holds the coercer registry, accumulates errors, formats messages with YAML line/column from SnakeYAML's `Mark` objects. The contract surface that generated binders call.
- **`TypeCoercerRegistry`** — package-private for v1. Bundles coercers for the comprehensive type set (see *Supported types* below). Shape designed so making it public (and making `TypeCoercer<T>` a public SPI) is purely additive.
- **`YamlLoader`** — wraps SnakeYAML, runs the `${VAR}` interpolation pass, returns the `Map<String,Object>` tree.
- **`ConfigValidationException`** — thrown once at end of validation, message contains the entire numbered report.

### Supported types (bundled in v1)

- All primitives + boxed: `int/Integer`, `long/Long`, `boolean/Boolean`, `double/Double`, `float/Float`, `short/Short`, `byte/Byte`, `char/Character`.
- `String`.
- `Duration`, `Instant`, `LocalDate`, `LocalDateTime`, `ZoneId` (ISO-8601-style strings).
- `UUID`, `URI`, `Path` (string-parsed).
- `BigDecimal`, `Pattern`, `Charset`.
- Enums (case-sensitive name match against the YAML scalar).
- `List<X>` where `X` is any leaf type (primitives/boxed, `String`, time/UUID/URI/Path/etc., enums). v1 does **not** support `List<Record>` — declare nested record sections as separate `@Configuration` records.
- `Map<String,X>` where `X` is any leaf type. v1 does **not** support `Map<String,Record>` — same workaround as List.
- **Nested records** as direct field types are recognised by validation but **not** supported by v1 codegen — the processor emits a clear compile-time error directing users to declare them as separate top-level `@Configuration` records. Nested-record codegen is a deferred enhancement.
- `Optional<X>` wrapping any of the above leaf types (not `Optional<Record>` or `Optional<List<Record>>`).

`@Configuration` is the **top-level marker**: it tells the processor "this record gets its own root section in the YAML, identified by `prefix`."

No public `TypeCoercer<T>` SPI in v1. Users needing other types declare a `String` field and parse in their service. The internal registry is shaped for additive SPI exposure later.

### Required / optional / defaulted

Field-level semantics, encoded by type and annotation:

| Declaration | Semantics |
|---|---|
| `X` (any non-Optional type) | Required. Binder errors if absent. |
| `Optional<X>` | Optional. Binder substitutes `Optional.empty()` if absent. |
| `@Default("...") X` | Required-with-default. Binder substitutes the parsed default value if absent. |

`@Default` on `Optional<X>` is a compile-time error (semantic conflict — pick one).

### Strict mode (only mode in v1)

- Per-section unknown-key check: at the end of binding, any keys remaining in the section's map that no field consumed are errors.
- Top-level unknown-prefix check: any top-level YAML key not in the set of registered prefixes is an error, with a Levenshtein-based "did you mean...?" suggestion.

No lenient mode in v1.

### `${VAR}` interpolation

- Syntax: `${NAME}` and `${NAME:default}`. Multiple per scalar supported.
- Applied to YAML scalar **values** only — not keys.
- Performed after YAML parse, before binding. Coercion sees the resolved string.
- Source: `System.getenv()`.
- Missing non-defaulted `${VAR}` is a validation error (accumulated, with file:line:col), not a hard throw.
- Escape syntax: none in v1. `$` in YAML scalar values is treated as a literal unless followed by `{`. If real-world need surfaces, the implementation may add `$${literal}` and update this spec; until then, leave unspec'd rather than half-built.

---

## Data flow

Inside `Tiko.create(ConfigSource source)`:

1. **Discovery.** Read `ConfigBinderRegistry.all()` (and aggregated entries from sibling modules). If the list is non-empty and `source == null`, throw immediately:
   > *"You declared @Configuration records (DbConfig, KafkaConfig) but called Tiko.create() without a ConfigSource. Use Tiko.create(ConfigSources.classpath(\"config.yaml\")) or similar."*
2. **Source load.** `source.load()` returns a `Map<String,Object>`. For a layered source, this is the deep-merged result with last-source-wins semantics: maps merge recursively; lists are atomic (replaced, not appended); scalars overwrite.
3. **`${VAR}` interpolation.** Walk scalars, substitute. Accumulate errors for missing non-defaulted vars.
4. **Top-level prefix check.** Compute claimed-prefixes set from the registry. Any top-level YAML key not in that set is an error with did-you-mean suggestion.
5. **Per-record binding.** For each `ConfigBinder<T>` in the registry: walk to its prefix section, call `binder.bind(map, ctx)`. The generated body does explicit field reads, dispatches coercion through the registry, runs the per-section unknown-key check at the end, and returns the constructed record. Errors accumulate into `ctx`.
6. **Error reporting.** If the accumulator has any entries, throw a single `ConfigValidationException` whose message *is* the entire numbered report.
7. **Registration.** On clean validation, each bound record instance is registered as a `SINGLETON`-scoped bean in the container's bean registry, indexed by record class.
8. **Continue normal startup.** All other singletons initialise. Any `@Component` that `@Inject`s a `@Configuration` record sees a fully-bound, validated instance — config beans exist before any consumer's `@PostConstruct` runs.

**Edge case — section absent:** if a record's entire section is absent (`db:` missing from the YAML), the binder treats it as an empty map. Required fields error individually ("db.url is required, but section 'db' is missing entirely"); fully optional/defaulted records construct cleanly.

---

## Error handling

### Compile-time errors

Emitted via the existing `Messager` infrastructure in `tiko-processor`. Format follows CLAUDE.md's convention: location, what's wrong, suggested fix.

| Trigger | Example |
|---|---|
| `@Configuration` on a non-record class | "DbConfig.java:5 — @Configuration must be applied to a record. Suggested fix: change `class` to `record`." |
| Field type not in supported coercion set | "DbConfig.java:8 — Field 'maxConnections' uses unsupported config type 'BigInteger'. See *Components → Supported types* for the bundled set. Suggested fixes: 1) use BigDecimal; 2) declare as String and parse in your service." |
| Duplicate prefix across two records | "DbConfig.java:3, KafkaDbConfig.java:3 — Both records declare prefix 'db'. Each prefix must be unique. Suggested fix: rename one of the prefixes." |
| `@Default` on `Optional<X>` | "DbConfig.java:8 — @Default cannot be combined with Optional<X>. They mean different things. Suggested fix: drop the Optional wrapper, or remove @Default." |
| `@Default` value not parseable for declared type | "DbConfig.java:8 — @Default('abc') on int field 'maxConnections' is not a valid integer." |
| Recursive record reference (`A` contains `A`) | Standard recursion error with the cycle path. |

### Compile-time / runtime parity

The processor's compile-time `@Default` validation **must** use exactly the same coercer logic as the runtime binder, otherwise drift creates "build accepts, runtime rejects" failures. Spec-mandated: the coercer table is shared code in `tiko-config`, called from both the processor (`tiko-processor` depends on `tiko-config` at processor scope only) and the runtime.

### Runtime errors

Accumulated in `BindContext` during the validation phases (interpolation, top-level check, per-record bind), then thrown as a single `ConfigValidationException`. Numbered, anchored, snippet-included where helpful, suggestion-attached where useful.

```
ConfigValidationException: 3 problem(s) in config.yaml:

  1. config.yaml:5:7   db.url is required but missing
       (section 'db' has no 'url' key)

  2. config.yaml:6:18  db.maxConnections expected integer, got string "ten"
         maxConnections: ten
                         ^^^

  3. config.yaml:11:1  unknown top-level section 'kafkkka'
       Did you mean 'kafka'?
```

`${VAR}` failures follow the same shape: *"config.yaml:5:14 — ${DB_URL} is not set and has no default."*

**Why accumulate-then-throw rather than fail-fast:** misconfigured YAML often has multiple problems; one-error-per-rerun is hostile UX.

---

## Testing strategy

### 1. Annotation processor tests (`tiko-processor/src/test`)

- One test per compile-time error case above. Each test compiles a small fixture, asserts expected error message and source location.
- Positive tests: compile a valid `@Configuration` record, verify the generated binder source has the expected shape (parameter resolution, coercer dispatches, unknown-key check call).

### 2. Runtime helper tests (`tiko-config/src/test`)

- Per-coercer unit tests for every type in the bundled set: round-trip parse, error cases (wrong type, malformed string), boundary conditions.
- `BindContext` error-accumulation tests: feed a malformed map, assert all errors collected.
- `${VAR}` interpolation tests: present, missing+default, missing+no-default, multiple per scalar.
- Layered `ConfigSource` deep-merge tests: nested map merge, list replacement, scalar override, multiple-layer composition.
- `ConfigSources.fromMap`, `classpath`, `file` round-trip.
- Strict-mode unknown-key check: per-section and top-level (with "did you mean...?" suggestions).

### 3. Integration / examples (`tiko-examples/03_config` — new module)

- End-to-end: a `@Configuration` record, a YAML fixture, a `@Component` that injects it, a `Main` that runs.
- Demonstrates `${VAR}` env-var override.
- Demonstrates `ConfigSources.fromMap(...)` for tests (proves out the test-friendliness claim).
- Cross-module aggregation: a sibling sub-module with its own `@Configuration` record, validated under `AggregatingContainer`.
- The example's `Main` doubles as a smoke test in the build (existing `tiko-examples` pattern).

### Out of scope for v1's test surface

- JSON Schema artifact generation — separate test surface in the follow-up phase.
- Profile-specific overlays — deferred.
- `ConfigSources.env()` — not shipped in v1.
- Customizers / post-processors — deferred.

---

## Open items the implementation plan must resolve

- **`${VAR}` escape syntax.** Spec defaults to "no escape" with a doc note. Implementation should pick one if a clear case appears.
- **Pinned SnakeYAML version.** Pick the latest stable that lines up with current Jackson YAML's transitive dep, document in `tiko-config/README.md`.
- **`tiko-processor → tiko-config` dependency at processor scope.** Required for shared coercer logic. Implementation must verify this does not leak SnakeYAML onto user runtime classpaths through annotation-processor-path resolution.
- **Manifest format extensions.** v1 writes `<fqn>=<prefix>`. The JSON Schema follow-up will likely need additional metadata (field types, defaults). Format should be forward-compatible — line-based, key=value, ignore unknown lines on read.

---

## Roadmap impact

- **README.md:** move "Configuration injection (`@Value`)" entry from Phase 2 to Phase 1, retitle to "Configuration injection (`@Configuration`)" since the annotation name is now decided.
- **VISION.md:** no change needed; configuration was implicitly in scope.
- **GitHub issue:** a tracking issue under the *Phase 1 — Alpha completion* milestone, linking to this spec.
