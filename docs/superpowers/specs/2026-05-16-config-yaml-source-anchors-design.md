# `@Configuration` validation errors anchored to YAML file:line:col — design

Status: draft (2026-05-16). Closes Issue #19. Phase 2 issue —
"Configuration & distributed events" milestone.

## Context

Today, when `@Configuration` binding fails, the user sees descriptions
like `"db.password is required but missing"` or `"app.server.port
expected an integer, got 'eighty'"`. The key path locates the field in
the *record* but says nothing about *where in the YAML* the offending
value (or the section that should have contained it) lives. For a
config file of any real size, the user has to grep their way to the
problematic line.

The infrastructure for anchored errors already exists in
`tiko-config`:

- `ConfigError.at(code, source, line, column, message)` creates an
  anchored error.
- `BindContext.reportAt(code, line, column, message)` accumulates
  anchored errors against the context's known source label.
- `BindContext.issues()` formats anchored errors with the
  `source:line:column message` prefix — `ConfigIssue.description`'s
  Javadoc explicitly anticipates this format.

But every read method in `BindContext` (`requireSection`,
`requireScalar`, `scalarOrDefault`, `optionalScalar`,
`checkUnknownKeys`) calls `report(...)` (unanchored). The reason is
mechanical: the `Map<String, Object>` they receive doesn't carry any
source-location information. `YamlLoader` uses `Yaml.load(in)` which
discards SnakeYAML's `Node` tree and its associated `Mark`s.

This spec wires source locations through the loader, the `ConfigSource`
interface, and `BindContext` so the existing `reportAt(...)` plumbing
can fire on real errors.

## Reference: what the user sees today vs after this change

**Before** (current behaviour):

```
ConfigValidationException: configuration binding failed in config.yaml:
  - INVALID_VALUE: app.server.port expected an integer, got 'eighty'
  - MISSING_KEY: db.password is required but missing
```

**After**:

```
ConfigValidationException: configuration binding failed in config.yaml:
  - INVALID_VALUE: config.yaml:12:9 app.server.port expected an integer, got 'eighty'
  - MISSING_KEY: config.yaml:3:1 db.password is required but missing
```

The first anchor points at the scalar value `eighty` on line 12. The
second points at the `db:` section header on line 3 — the user can see
the section exists and notice the absent key.

## Design principles

1. **Wire Marks to existing infrastructure, don't redesign it.**
   `reportAt(...)` and the `source:line:column message` format already
   exist. Goal is to populate them with real Marks rather than build
   new error-formatting machinery.
2. **Backward-compatible `ConfigSource` API.** `ConfigSource` is public
   API. We add an additive default method, we don't break the existing
   `load()` contract.
3. **Keep tiko-api SnakeYAML-free.** `tiko-api` is the lightweight
   public surface; it must not transitively depend on SnakeYAML. The
   public location type is a plain record (`SourceLocation`) defined in
   tiko-api; the SnakeYAML `Mark` → `SourceLocation` conversion happens
   inside `tiko-config`.
4. **Best-effort anchoring, never required.** A missing Mark (default
   value, programmatic source, synthetic interpolation result) does
   not block error reporting — it just falls back to today's
   unanchored shape. Users always see *at least* what they see today.

## Goals

- A `ConfigValidationException` raised from `Tiko.create(...)` with a
  YAML-backed `ConfigSource` produces `ConfigIssue` descriptions whose
  text is prefixed with `<source>:<line>:<column>` whenever the loader
  knows the location of the offending key or value.
- Public API change is strictly additive on `ConfigSource`. Programmatic
  sources (`ConfigSources.empty()`, `ConfigSources.map(...)`, test
  fixtures, user customisations) keep working unchanged and produce
  unanchored issues (today's behaviour).
- A new public record `io.tiko.SourceLocation(String source, int line,
  int column)` exposes locations to handlers that want richer access
  than the formatted description string.
- The persistence cookbook example (`02_config`) gets an integration
  test demonstrating an anchored error in the `ConfigurationFailure`
  ErrorContext payload.

## Non-goals

- Layered sources / per-key source attribution. Today's
  `BindContext(String source)` assumes one source label per binding
  call; if a future layered configuration system needs per-key source
  tracking, it gets its own issue.
- Reformatting the description string. The existing
  `source:line:column message` shape stays — issues just gain real
  numbers in place of the previous (unanchored) message.
- Surfacing locations through any new public method on `ConfigIssue`
  (the description string already carries them in text form, and
  changing `ConfigIssue`'s public shape is more invasive than the
  problem warrants). The new `SourceLocation` record is reachable via
  `ConfigSource.locations()` if a user explicitly wants structured
  access.
- Mark info for non-YAML config sources. Programmatic sources have no
  location concept and stay unanchored.

## Components

### 1. `tiko-api/src/main/java/io/tiko/SourceLocation.java` (new)

```java
package io.tiko;

/**
 * Source location of a configuration value, exposed via
 * {@link ConfigSource#locations()}. Best-effort: a missing or unknown
 * location is represented by the absence of an entry in the map, not
 * by a sentinel value.
 *
 * @param source  the source identifier (typically a file name like
 *     {@code "config.yaml"}, or whatever label
 *     {@code ConfigSources.classpath(name)} chose)
 * @param line    1-based line number of the value (or the closest
 *     enclosing structural marker — e.g., the section header for a
 *     missing required key inside that section)
 * @param column  1-based column number, same anchoring rule
 */
public record SourceLocation(String source, int line, int column) {}
```

### 2. `tiko-api/src/main/java/io/tiko/ConfigSource.java` (modify)

Add an additive default method:

```java
import java.util.Map;

public interface ConfigSource {
    Map<String, Object> load();

    /**
     * Returns a best-effort map of dot-path → source location for the
     * values produced by {@link #load()}. Returns an empty map by
     * default; YAML-backed sources override to expose Marks. Consumers
     * (typically the framework's binding pipeline) treat the absence
     * of an entry as "no location known" — they do not interpret an
     * empty return as "everything is at the origin".
     *
     * <p>Keys are dot-paths matching the YAML structure: top-level keys
     * like {@code "db"} and nested-record keys like {@code "app.server.host"}
     * appear as their fully-qualified path strings. Intermediate sections
     * also appear in the index (so binding can anchor a missing-key error
     * to the section header).
     */
    default Map<String, SourceLocation> locations() {
        return Map.of();
    }
}
```

### 3. `tiko-config/src/main/java/io/tiko/config/internal/YamlLoader.java` (modify)

Switch the SnakeYAML invocation from `yaml.load(input)` to
`yaml.compose(input)`. `compose()` returns a `Node` tree with
`Mark` objects intact. Walk it twice (or once with two outputs) to
produce:

- The existing `Map<String, Object>` shape (unchanged from today).
- A new `Map<String, SourceLocation>` keyed by dot-path.

Both leaf scalars and intermediate `MappingNode`s contribute entries.
The Map is `LinkedHashMap` to preserve insertion (= YAML) order, useful
for debugging.

The internal API of `YamlLoader` changes from
`load(InputStream): Map<String, Object>` to a small carrier:

```java
// internal record, package-private
record LoadedYaml(Map<String, Object> data, Map<String, SourceLocation> locations) {}

public static LoadedYaml load(InputStream input, String sourceLabel) { ... }
```

The `sourceLabel` is needed to fill `SourceLocation.source(...)`.
SnakeYAML's `Mark` doesn't know what to call the input stream.

### 4. `tiko-config/src/main/java/io/tiko/config/ConfigSources.java` (modify)

Each YAML-backed factory (`classpath(name)`, `file(path)`, etc.)
returns a `ConfigSource` whose `load()` returns the data map and whose
new `locations()` returns the dot-path → `SourceLocation` map produced
by `YamlLoader`. The internal anonymous-class / lambda implementation
captures both maps.

`ConfigSources.empty()` and the in-memory `map(...)` factory return
`Map.of()` for `locations()` — they have no source location concept.

### 5. `tiko-config/src/main/java/io/tiko/config/internal/Interpolator.java` (modify)

The interpolator walks the data map and substitutes `${ENV}`
placeholders. It does NOT need to know about locations — interpolation
operates on string values in place. The location index is built from
the *pre-interpolation* tree and remains valid because:

- The data map's structure (key paths) is unchanged by interpolation.
- The Mark on a scalar points at the YAML text where the user wrote
  `${ENV:default}`, which is exactly where the user looks when an env
  var is missing.

`Interpolator` does not need a behaviour change for §1–6 to work; its
output map keeps the same structure as its input, so the dot-path
keys in the location index stay valid post-interpolation.

If `Interpolator` reports its own issues today (e.g., unresolved
required env var with no default), those issues *should* also be
anchored — the plan stage will confirm `Interpolator`'s current
reporting behaviour and, if it calls `BindContext.report(...)`,
switch those sites to `reportAtPath(..., currentPath, ...)`. This is
a small in-scope adjustment, not a separate component.

### 6. `tiko-config/src/main/java/io/tiko/config/BindContext.java` (modify)

Constructor signature changes from:

```java
public BindContext(String source) { ... }
```

to:

```java
public BindContext(String source, Map<String, SourceLocation> locations) { ... }
```

A second constructor `BindContext(String source)` delegates to the
above with `Map.of()` for backwards-compatibility with any existing
direct caller (there is one — `ConfigBootstrap`, which this spec
updates anyway).

New private helper:

```java
private void reportAtPath(ConfigIssueCode code, String dotPath, String message) {
    SourceLocation loc = locations.get(dotPath);
    if (loc != null) {
        reportAt(code, loc.line(), loc.column(), message);
    } else {
        report(code, message);
    }
}
```

Every existing read method (`requireSection`, `requireScalar`,
`scalarOrDefault`, `optionalScalar`, `checkUnknownKeys`) replaces its
`report(...)` calls with `reportAtPath(..., fullPath, ...)`. The
existing `fullPath` parameter (already passed for `requireScalar` etc.)
becomes the lookup key. `requireSection` uses the section key as the
path. `checkUnknownKeys` uses `sectionPath + "." + k` for each leftover
key — same path the description already names.

For missing-key errors specifically, the path passed to
`reportAtPath` is the **parent section's** path, not the missing
leaf's (the leaf doesn't exist in the YAML, so it has no Mark — but
the parent section does). The read methods know both paths.

### 7. `tiko-config/src/main/java/io/tiko/config/runtime/ConfigBootstrap.java` (modify)

`ConfigBootstrap.bind(...)` reads `source.locations()` after
`source.load()` and threads it into the `BindContext` constructor:

```java
Map<String, Object> raw = source.load();
Map<String, SourceLocation> locations = source.locations();
BindContext ctx = new BindContext(sourceLabel, locations);
```

The two top-level `ctx.report(...)` sites (`DUPLICATE_PREFIX` and
`UNKNOWN_SECTION`) also switch to `reportAtPath(...)`:

- `UNKNOWN_SECTION` (line 80): anchors to the unknown top-level key's
  location.
- `DUPLICATE_PREFIX` (line 66): no obvious anchor — the duplicate is a
  *processor-time* configuration error, not a YAML problem. Leave
  unanchored. (Out of scope detail: the issue surfaces independent of
  YAML content.)

### 8. `tiko-examples/02_config/src/test/java/...` (test)

New integration test asserting a malformed YAML produces a
`ConfigurationFailure` whose first issue's description contains
`config.yaml:N:M` for a known invalid value.

## Data flow

```
ConfigSource (classpath)
  └─ load() ──────────► Map<String, Object>  ──┐
  └─ locations() ─────► Map<String, SourceLocation> ──┐
                                                       │
        Interpolator.interpolate() ◄──────┘            │
                                                       │
        BindContext(sourceLabel, locations) ◄──────────┘
                ▼
        binder.bind(interpolated, ctx)
           ├─ ctx.requireScalar("password", "db.password", ...)
           │      └─ on miss: reportAtPath("db", "db.password is required...")
           │                       └─ locations.get("db") → SourceLocation("config.yaml", 3, 1)
           │                            → reportAt(code, 3, 1, message)
           ▼
        ctx.issues() → ConfigurationFailure / ConfigValidationException
```

## Testing

### Unit (tiko-config)

- `YamlLoaderTest` — load a small YAML, assert the location index
  contains expected dot-paths with line/column matching the source.
  Cover: top-level scalar, nested-section header, nested scalar, list
  element.
- `BindContextLocationTest` — given a small `Map<String, Object>` and
  a matching `Map<String, SourceLocation>`, call a `requireScalar`
  with a missing-key path; assert the resulting `ConfigIssue`'s
  description starts with `source:line:column`.

### Integration (02_config example)

- `ConfigurationAnchoredErrorsTest` — load a deliberately-malformed
  YAML through `Tiko.create(ConfigSources.classpath("bad.yaml"))`,
  assert the thrown `ConfigValidationException`'s message contains a
  `config.yaml:N:M` anchor, AND assert a custom `ErrorHandler`
  received a `ConfigurationFailure` whose first `issues().get(0)`
  contains the same anchor in its `description()`.

### Regression

- All existing `ConfigBinderGeneratorTest`,
  `ConfigurationValidatorTest`, etc. tests continue to pass. Issue
  descriptions are *richer* (now anchored) — any existing test that
  asserts the FULL description verbatim will need its expectation
  updated to include the source prefix.

## Acceptance

- [ ] `SourceLocation` record exists at `io.tiko.SourceLocation`.
- [ ] `ConfigSource.locations()` default method exists, returns
      `Map.of()`.
- [ ] `YamlLoader` produces a `(data, locations)` pair via
      `yaml.compose(...)`.
- [ ] `ConfigSources.classpath(...)` (and other YAML-backed factories)
      populate `locations()` from the loader.
- [ ] `BindContext` accepts an optional `Map<String, SourceLocation>`,
      uses it for anchored error reporting via `reportAtPath(...)`.
- [ ] Existing read methods (`requireSection`, `requireScalar`,
      `scalarOrDefault`, `optionalScalar`, `checkUnknownKeys`) emit
      anchored errors when locations are available.
- [ ] `ConfigBootstrap.bind(...)` threads locations from source to
      context.
- [ ] New `ConfigurationAnchoredErrorsTest` in `02_config` verifies
      the end-to-end anchored output.
- [ ] All existing `tiko-config` tests pass (updated where they
      asserted exact unanchored descriptions).
- [ ] Programmatic sources keep working — verified by an explicit
      test using `ConfigSources.empty()` (or a `Map.of(...)`-backed
      source) that asserts unanchored output.
- [ ] Full reactor `mvn -pl '!tiko-bom' install` green.
- [ ] Spotless gate clean.

## Risks

- **Existing tests asserting exact descriptions.** Anchored output
  changes the description string. Most tests likely assert
  `code` and a substring of `description`; any that compare the full
  string verbatim will need updating. Mitigation: grep for tests that
  compare full `ConfigIssue` descriptions during implementation; touch
  them as part of the change.
- **`yaml.compose()` API surface.** The compose path returns a `Node`
  rather than the directly-usable `Map<String, Object>`. Walking the
  Node tree is straightforward but more code than today's `yaml.load()`
  one-liner. Tradeoff: ~50 lines added to `YamlLoader` for the path
  index; acceptable.
- **Map key strategy for lists / list elements.** Dot-paths handle
  scalar keys cleanly. List elements need a convention — e.g.,
  `"servers[0].port"`. Decision: do NOT index inside lists for v1.
  Anchor an "invalid element" error to the LIST itself (the parent
  key's Mark). If list-element-level anchoring becomes important, it's
  a follow-up. Documented in spec, not in code.
- **`@Default`-value coercion failures.** When `@Default("not-a-number")
  int x` fails to coerce, the value is synthetic — no YAML Mark.
  Today's behaviour (unanchored) is preserved. Acceptable.

## Out of scope

- Layered / merged config sources with per-key source attribution. The
  `BindContext(source, locations)` shape carries one source label and
  one location map; if a future feature needs per-key source
  attribution, it gets its own issue.
- Structured access to `SourceLocation` via `ConfigIssue`. Today the
  location is folded into the description string. Adding a structured
  field to `ConfigIssue` is a public-API surface change worth its own
  discussion; out of scope here.
- Anchoring inside list elements. Lists get anchored to the parent
  list key, not per-element. Refinement for a follow-up issue if
  needed.
- Changing the description string format (`source:line:column message`).
  This stays — only the values change.

## References

- Issue #19 — the GitHub-tracked issue this spec closes.
- `tiko-api/src/main/java/io/tiko/ConfigSource.java` — interface
  receiving the additive `locations()` method.
- `tiko-api/src/main/java/io/tiko/ConfigIssue.java` — its Javadoc
  already documents the `source:line:column message` description
  format we're about to start populating.
- `tiko-config/src/main/java/io/tiko/config/internal/YamlLoader.java`
  — primary loader change site.
- `tiko-config/src/main/java/io/tiko/config/BindContext.java` —
  accumulator that already exposes `reportAt(...)` but never gets
  called.
- `tiko-config/src/main/java/io/tiko/config/runtime/ConfigBootstrap.java`
  — wiring layer.
