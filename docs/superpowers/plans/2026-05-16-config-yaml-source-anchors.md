# `@Configuration` validation errors anchored to YAML file:line:col Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Thread SnakeYAML `Mark` source locations through the config-binding pipeline so `ConfigurationFailure` issues display `config.yaml:line:column` anchors next to descriptions, dramatically improving the error UX for users with large YAML files.

**Architecture:** New `SourceLocation` record in `tiko-api` keeps the public surface SnakeYAML-free. `ConfigSource` gains an additive default `locations()` method returning `Map<String, SourceLocation>` keyed by dot-paths. `YamlLoader` switches from `yaml.load()` to `yaml.compose()` so it can walk the `Node` tree and produce both the data map AND the location index. `BindContext` accepts the location map at construction and uses an internal `reportAtPath(...)` helper to anchor errors when a Mark is known, falling back to unanchored output otherwise.

**Tech Stack:** Java 21, SnakeYAML 2.x (`compose()` API), JUnit 5, AssertJ.

---

## File structure

```
tiko-api/src/main/java/io/tiko/
├── SourceLocation.java                     (create — new public record)
└── ConfigSource.java                       (modify — add default locations() method)

tiko-config/src/main/java/io/tiko/config/
├── BindContext.java                        (modify — 2-arg ctor + reportAtPath + read-method updates)
└── ConfigSources.java                      (modify — wire locations from YamlLoader)
tiko-config/src/main/java/io/tiko/config/internal/
├── YamlLoader.java                         (rewrite — switch to compose(), produce LoadedYaml)
└── Interpolator.java                       (modify — thread path, anchor INTERPOLATION_UNRESOLVED)
tiko-config/src/main/java/io/tiko/config/runtime/
└── ConfigBootstrap.java                    (modify — thread locations, anchor UNKNOWN_SECTION)
tiko-config/src/test/java/io/tiko/config/internal/
└── YamlLoaderTest.java                     (create — test location index population)
tiko-config/src/test/java/io/tiko/config/
└── BindContextLocationTest.java            (create — test anchored error output)

tiko-examples/02_config/src/test/java/io/tiko/examples/config/
└── ConfigurationAnchoredErrorsTest.java    (create — e2e anchored output)
tiko-examples/02_config/src/test/resources/
└── bad-config.yaml                         (create — deliberately malformed fixture)

docs/roadmap.md                             (modify — add "What ships today" entry)
```

---

## Task 1: `SourceLocation` record

**Files:**
- Create: `tiko-api/src/main/java/io/tiko/SourceLocation.java`

- [ ] **Step 1: Create `SourceLocation.java`**

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

- [ ] **Step 2: Verify compile**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-api compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add tiko-api/src/main/java/io/tiko/SourceLocation.java
git commit -m "feat(api): SourceLocation record for config error anchoring"
```

---

## Task 2: `ConfigSource.locations()` additive default

**Files:**
- Modify: `tiko-api/src/main/java/io/tiko/ConfigSource.java`

- [ ] **Step 1: Find the existing `ConfigSource` interface**

Read `tiko-api/src/main/java/io/tiko/ConfigSource.java`. It declares `Map<String, Object> load()`. Add a sibling default method.

- [ ] **Step 2: Modify `ConfigSource.java` to add the default method**

Add this method after the existing `load()` declaration (and the matching import `java.util.Map` is already there):

```java
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
```

- [ ] **Step 3: Verify compile**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-api compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add tiko-api/src/main/java/io/tiko/ConfigSource.java
git commit -m "feat(api): ConfigSource.locations() additive default returning empty map"
```

---

## Task 3: `YamlLoader` — switch to `compose()` + build location index (TDD)

**Files:**
- Create: `tiko-config/src/test/java/io/tiko/config/internal/YamlLoaderTest.java`
- Modify: `tiko-config/src/main/java/io/tiko/config/internal/YamlLoader.java`

- [ ] **Step 1: Write the failing test `YamlLoaderTest.java`**

```java
package io.tiko.config.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.SourceLocation;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link YamlLoader} produces both the data map (today's
 * behaviour) AND a parallel location index keyed by dot-path.
 */
class YamlLoaderTest {

    private static final String YAML =
            "db:\n" + "  url: jdbc:postgres://localhost\n" + "  poolSize: 10\n" + "app:\n" + "  name: example\n";

    @Test
    void loadProducesDataMap() {
        YamlLoader.LoadedYaml loaded = YamlLoader.load(new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8)), "test.yaml");

        assertThat(loaded.data()).containsKeys("db", "app");
        @SuppressWarnings("unchecked")
        var dbMap = (java.util.Map<String, Object>) loaded.data().get("db");
        assertThat(dbMap).containsEntry("url", "jdbc:postgres://localhost").containsEntry("poolSize", 10);
    }

    @Test
    void loadProducesLocationIndexForLeafScalars() {
        YamlLoader.LoadedYaml loaded = YamlLoader.load(new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8)), "test.yaml");

        SourceLocation urlLoc = loaded.locations().get("db.url");
        assertThat(urlLoc).isNotNull();
        assertThat(urlLoc.source()).isEqualTo("test.yaml");
        assertThat(urlLoc.line()).isEqualTo(2); // "  url: ..." is line 2 (1-based)
    }

    @Test
    void loadProducesLocationIndexForSectionHeaders() {
        YamlLoader.LoadedYaml loaded = YamlLoader.load(new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8)), "test.yaml");

        SourceLocation dbLoc = loaded.locations().get("db");
        assertThat(dbLoc).isNotNull();
        assertThat(dbLoc.line()).isEqualTo(1); // "db:" is line 1
    }

    @Test
    void loadEmptyYamlProducesEmptyMaps() {
        YamlLoader.LoadedYaml loaded =
                YamlLoader.load(new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)), "empty.yaml");

        assertThat(loaded.data()).isEmpty();
        assertThat(loaded.locations()).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test — expect failure**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-api install -DskipTests && W:/tools/apache-maven/bin/mvn -pl tiko-config test -Dtest=YamlLoaderTest`
Expected: compile failure (`cannot find symbol: LoadedYaml`).

- [ ] **Step 3: Rewrite `YamlLoader.java`**

Replace the entire file with:

```java
package io.tiko.config.internal;

import io.tiko.SourceLocation;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * SnakeYAML-backed loader that produces a {@link LoadedYaml} carrier with
 * both the data tree and a parallel dot-path → {@link SourceLocation}
 * index. The location index drives anchored error messages in
 * {@code ConfigurationFailure} / {@code ConfigValidationException}.
 */
public final class YamlLoader {

    private YamlLoader() {}

    /**
     * Loaded YAML plus the parallel location index. Both maps use
     * {@link LinkedHashMap} so iteration preserves YAML order.
     */
    public record LoadedYaml(Map<String, Object> data, Map<String, SourceLocation> locations) {}

    public static LoadedYaml load(InputStream input, String sourceLabel) {
        var opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        // SafeConstructor pinned explicitly: we only ever load data (Map/List/scalar), never
        // instantiate arbitrary Java types. SnakeYAML 2.x already defaults to safe behavior,
        // but spelling it out makes the security property a code-level invariant.
        var yaml = new Yaml(new SafeConstructor(opts));

        Node root = yaml.compose(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
        if (root == null) {
            return new LoadedYaml(new LinkedHashMap<>(), new LinkedHashMap<>());
        }
        if (!(root instanceof MappingNode rootMapping)) {
            throw new IllegalArgumentException(
                    "YAML root must be a mapping; got " + root.getClass().getSimpleName());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, SourceLocation> locations = new LinkedHashMap<>();
        walkMapping(rootMapping, "", sourceLabel, data, locations);
        return new LoadedYaml(data, locations);
    }

    private static void walkMapping(
            MappingNode mapping,
            String pathPrefix,
            String sourceLabel,
            Map<String, Object> outData,
            Map<String, SourceLocation> outLocations) {
        for (NodeTuple t : mapping.getValue()) {
            if (!(t.getKeyNode() instanceof ScalarNode keyNode)) {
                continue; // skip non-string keys (defensive — SafeConstructor on a Map already enforces)
            }
            String key = keyNode.getValue();
            String fullPath = pathPrefix.isEmpty() ? key : pathPrefix + "." + key;
            Node valueNode = t.getValueNode();

            outLocations.put(fullPath, locationOf(valueNode, sourceLabel));

            if (valueNode instanceof MappingNode nestedMapping) {
                Map<String, Object> nested = new LinkedHashMap<>();
                outData.put(key, nested);
                walkMapping(nestedMapping, fullPath, sourceLabel, nested, outLocations);
            } else if (valueNode instanceof SequenceNode seq) {
                outData.put(key, walkSequence(seq, sourceLabel));
            } else if (valueNode instanceof ScalarNode scalar) {
                outData.put(key, parseScalar(scalar));
            } else {
                outData.put(key, null);
            }
        }
    }

    private static List<Object> walkSequence(SequenceNode seq, String sourceLabel) {
        List<Object> out = new ArrayList<>(seq.getValue().size());
        for (Node item : seq.getValue()) {
            if (item instanceof MappingNode m) {
                Map<String, Object> nested = new LinkedHashMap<>();
                Map<String, SourceLocation> ignored = new LinkedHashMap<>(); // list elements aren't location-indexed in v1
                walkMapping(m, "", sourceLabel, nested, ignored);
                out.add(nested);
            } else if (item instanceof SequenceNode s) {
                out.add(walkSequence(s, sourceLabel));
            } else if (item instanceof ScalarNode scalar) {
                out.add(parseScalar(scalar));
            } else {
                out.add(null);
            }
        }
        return out;
    }

    /**
     * Parse a scalar through SnakeYAML's default resolver so int/bool/etc. coercion
     * happens at YAML-load time exactly as the previous {@code yaml.load(...)} path
     * delivered them to the binder. Without this, every scalar would arrive as a
     * String and downstream coercers would have to re-parse.
     */
    private static Object parseScalar(ScalarNode scalar) {
        // SnakeYAML's SafeConstructor handles scalar parsing internally when going through
        // load(), but the compose() path returns raw Nodes. Re-run a tiny Yaml.load() on
        // just the scalar's text to reuse the same coercion rules.
        var opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        var yaml = new Yaml(new SafeConstructor(opts));
        return yaml.load(scalar.getValue());
    }

    private static SourceLocation locationOf(Node node, String sourceLabel) {
        Mark m = node.getStartMark();
        if (m == null) return new SourceLocation(sourceLabel, 0, 0);
        return new SourceLocation(sourceLabel, m.getLine() + 1, m.getColumn() + 1);
    }
}
```

Note: SnakeYAML's `Mark.getLine()` / `getColumn()` are 0-based; we shift to 1-based for human readers.

- [ ] **Step 4: Run the test — expect pass**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-api install -DskipTests && W:/tools/apache-maven/bin/mvn -pl tiko-config test -Dtest=YamlLoaderTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add tiko-config/src/main/java/io/tiko/config/internal/YamlLoader.java tiko-config/src/test/java/io/tiko/config/internal/YamlLoaderTest.java
git commit -m "feat(config): YamlLoader produces (data, locations) via SnakeYAML compose"
```

---

## Task 4: `ConfigSources` factories — wire locations from `YamlLoader`

**Files:**
- Modify: `tiko-config/src/main/java/io/tiko/config/ConfigSources.java`

- [ ] **Step 1: Read existing factories**

Read `tiko-config/src/main/java/io/tiko/config/ConfigSources.java`. The `classpath(name)` factory likely returns an anonymous `ConfigSource` whose `load()` calls `YamlLoader.load(in)`. We update it to:
- Call the new `YamlLoader.load(in, name)` form.
- Hold both the data map and the locations map.
- Expose `locations()` returning the held map.

- [ ] **Step 2: Modify each YAML-backed factory**

For `classpath(String resourcePath)`:

```java
public static ConfigSource classpath(String resourcePath) {
    return new ConfigSource() {
        private YamlLoader.LoadedYaml loaded;

        private YamlLoader.LoadedYaml ensureLoaded() {
            if (loaded == null) {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                if (cl == null) cl = ConfigSources.class.getClassLoader();
                try (java.io.InputStream in = cl.getResourceAsStream(resourcePath)) {
                    if (in == null) {
                        throw new IllegalStateException("Classpath resource not found: " + resourcePath);
                    }
                    loaded = YamlLoader.load(in, resourcePath);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("Failed to read " + resourcePath, e);
                }
            }
            return loaded;
        }

        @Override
        public java.util.Map<String, Object> load() {
            return ensureLoaded().data();
        }

        @Override
        public java.util.Map<String, io.tiko.SourceLocation> locations() {
            return ensureLoaded().locations();
        }
    };
}
```

Apply the same pattern (extract `ensureLoaded()`, expose both `load()` and `locations()`) to any other YAML-reading factories in `ConfigSources.java` (e.g. `file(Path)`, `classpathAll(...)` if present — read the file and update each).

For `layered(ConfigSource... sources)`: today this merges multiple sources. The location story for layering is out of scope per the spec — but the implementation should still expose SOME `locations()` map: merge each child source's locations the same way it merges data (last source wins on a given key, preserving the spec's "today's behaviour preserved" promise for unanchored sources).

Specifically:

```java
public static ConfigSource layered(ConfigSource... sources) {
    return new ConfigSource() {
        @Override
        public java.util.Map<String, Object> load() {
            // existing behaviour — merge data maps
            java.util.Map<String, Object> merged = new java.util.LinkedHashMap<>();
            for (ConfigSource s : sources) {
                merged = io.tiko.config.internal.DeepMerge.merge(merged, s.load());
            }
            return merged;
        }

        @Override
        public java.util.Map<String, io.tiko.SourceLocation> locations() {
            java.util.Map<String, io.tiko.SourceLocation> merged = new java.util.LinkedHashMap<>();
            for (ConfigSource s : sources) {
                merged.putAll(s.locations()); // last-source-wins
            }
            return merged;
        }
    };
}
```

- [ ] **Step 3: Verify compile**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-config compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add tiko-config/src/main/java/io/tiko/config/ConfigSources.java
git commit -m "feat(config): ConfigSources factories expose locations() from YamlLoader"
```

---

## Task 5: `BindContext` — 2-arg constructor + `reportAtPath` + read-method updates (TDD)

**Files:**
- Create: `tiko-config/src/test/java/io/tiko/config/BindContextLocationTest.java`
- Modify: `tiko-config/src/main/java/io/tiko/config/BindContext.java`

- [ ] **Step 1: Write the failing test `BindContextLocationTest.java`**

```java
package io.tiko.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.ConfigIssueCode;
import io.tiko.SourceLocation;
import io.tiko.config.internal.coercers.IntCoercer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link BindContext}'s read methods emit anchored issues
 * when a location is known for the failing path, and fall back to
 * unanchored output when not.
 */
class BindContextLocationTest {

    @Test
    void missingRequiredScalarAnchoredToParentSectionLocation() {
        Map<String, SourceLocation> locations = new LinkedHashMap<>();
        locations.put("db", new SourceLocation("test.yaml", 1, 1)); // section header
        // intentionally no "db.password" entry — the key is absent in YAML

        var ctx = new BindContext("test.yaml", locations);
        Map<String, Object> dbSection = new LinkedHashMap<>(); // empty section
        ctx.requireScalar(dbSection, "password", "db.password", new IntCoercer(), 0);

        assertThat(ctx.hasErrors()).isTrue();
        var issue = ctx.issues().get(0);
        assertThat(issue.code()).isEqualTo(ConfigIssueCode.MISSING_KEY);
        assertThat(issue.description()).startsWith("test.yaml:1:1 ");
    }

    @Test
    void unanchoredFallbackWhenLocationsMapIsEmpty() {
        var ctx = new BindContext("test.yaml", Map.of());
        Map<String, Object> dbSection = new LinkedHashMap<>();
        ctx.requireScalar(dbSection, "password", "db.password", new IntCoercer(), 0);

        assertThat(ctx.hasErrors()).isTrue();
        var issue = ctx.issues().get(0);
        assertThat(issue.description()).doesNotContain("test.yaml:");
        assertThat(issue.description()).contains("db.password is required but missing");
    }

    @Test
    void coercionFailureAnchoredToValueLocation() {
        Map<String, SourceLocation> locations = new LinkedHashMap<>();
        locations.put("app.port", new SourceLocation("test.yaml", 5, 9));

        var ctx = new BindContext("test.yaml", locations);
        Map<String, Object> appSection = new LinkedHashMap<>();
        appSection.put("port", "eighty"); // not a number
        ctx.requireScalar(appSection, "port", "app.port", new IntCoercer(), 0);

        assertThat(ctx.hasErrors()).isTrue();
        var issue = ctx.issues().get(0);
        assertThat(issue.code()).isEqualTo(ConfigIssueCode.INVALID_VALUE);
        assertThat(issue.description()).startsWith("test.yaml:5:9 ");
    }
}
```

Note: `IntCoercer` is the existing coercer at `io.tiko.config.internal.coercers.IntCoercer`. If it has a different name in the codebase, check the `coercers` package; the test only needs a coercer that fails on a non-numeric string.

- [ ] **Step 2: Run the test — expect failure**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-config test -Dtest=BindContextLocationTest`
Expected: compile failure (`BindContext` constructor signature doesn't accept the locations map).

- [ ] **Step 3: Modify `BindContext.java` — add 2-arg constructor + `reportAtPath` + update read methods**

In `tiko-config/src/main/java/io/tiko/config/BindContext.java`:

Replace the field block + constructor with:

```java
    private final String source;
    private final Map<String, SourceLocation> locations;
    private final List<ConfigError> errors = new ArrayList<>();

    public BindContext(String source) {
        this(source, Map.of());
    }

    public BindContext(String source, Map<String, SourceLocation> locations) {
        this.source = source;
        this.locations = Map.copyOf(locations);
    }
```

Add the import at the top:

```java
import io.tiko.SourceLocation;
```

Add a new private helper near the existing `report` / `reportAt` methods:

```java
    /**
     * Reports an error against the known location for {@code dotPath} when one is
     * available, else falls back to {@link #report(ConfigIssueCode, String)} unanchored.
     */
    private void reportAtPath(ConfigIssueCode code, String dotPath, String message) {
        SourceLocation loc = locations.get(dotPath);
        if (loc != null) {
            reportAt(code, loc.line(), loc.column(), message);
        } else {
            report(code, message);
        }
    }
```

Update the read methods to use `reportAtPath(...)` with the right path argument:

- `requireSection(root, key)`: missing-section error and wrong-type error → `reportAtPath(code, key, message)`.
- `requireScalar(node, key, fullPath, coercer, fallback)`: missing-key error → `reportAtPath(MISSING_KEY, parentSectionPath(fullPath), message)` where `parentSectionPath(fullPath)` extracts everything before the last dot (e.g. "db.password" → "db"). Coercion-failure error → `reportAtPath(INVALID_VALUE, fullPath, message)`.
- `scalarOrDefault(...)`: coercion-failure error → `reportAtPath(INVALID_VALUE, fullPath, message)`.
- `optionalScalar(...)`: coercion-failure error → `reportAtPath(INVALID_VALUE, fullPath, message)`.
- `checkUnknownKeys(node, sectionPath, known)`: per leftover key `k` → `reportAtPath(UNKNOWN_KEY, sectionPath + "." + k, message)`.

Add a private helper for the parent-section path extraction:

```java
    private static String parentSectionPath(String fullPath) {
        int lastDot = fullPath.lastIndexOf('.');
        return lastDot < 0 ? fullPath : fullPath.substring(0, lastDot);
    }
```

Concretely, for the `requireScalar` missing-key branch:

```java
    public <T> T requireScalar(
            Map<String, Object> node, String key, String fullPath, TypeCoercer<T> coercer, T fallback) {
        if (!node.containsKey(key)) {
            reportAtPath(ConfigIssueCode.MISSING_KEY, parentSectionPath(fullPath), fullPath + " is required but missing");
            return fallback;
        }
        Object raw = node.remove(key);
        try {
            return coercer.coerce(raw);
        } catch (CoercionException e) {
            reportAtPath(ConfigIssueCode.INVALID_VALUE, fullPath, fullPath + " " + e.getMessage());
            return fallback;
        }
    }
```

Apply the analogous replacement (use `reportAtPath` with the right path) to `scalarOrDefault`, `optionalScalar`, `requireSection`, and `checkUnknownKeys`.

- [ ] **Step 4: Run the test — expect pass**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-config test -Dtest=BindContextLocationTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`.

- [ ] **Step 5: Run the full tiko-config test suite**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-config test`
Expected: BUILD SUCCESS. Existing tests still pass. (Issue descriptions now have anchors where locations are known — any test asserting an exact full description will fail here and be fixed in Task 9.)

If existing tests fail at this point, note the failing test names but proceed; Task 9 will sweep them.

- [ ] **Step 6: Commit**

```bash
git add tiko-config/src/main/java/io/tiko/config/BindContext.java tiko-config/src/test/java/io/tiko/config/BindContextLocationTest.java
git commit -m "feat(config): BindContext anchors reads to SourceLocation when known"
```

---

## Task 6: `Interpolator` — thread path + anchor `INTERPOLATION_UNRESOLVED`

**Files:**
- Modify: `tiko-config/src/main/java/io/tiko/config/internal/Interpolator.java`

- [ ] **Step 1: Read the existing `Interpolator.java`**

Verify the current shape: `interpolate(Object node, Function<String,String> env, BindContext ctx)` recursively walks Maps, Lists, and Strings. `interpolateScalar(String s, env, ctx)` calls `ctx.report(INTERPOLATION_UNRESOLVED, ...)` on missing-var-no-default.

- [ ] **Step 2: Thread `path` parameter through the recursion**

Update the signature and walk:

```java
public final class Interpolator {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+)(?::([^}]*))?\\}");

    private Interpolator() {}

    public static Object interpolate(Object node, Function<String, String> env, BindContext ctx) {
        return interpolate(node, "", env, ctx);
    }

    private static Object interpolate(Object node, String path, Function<String, String> env, BindContext ctx) {
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>(m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = e.getKey().toString();
                String childPath = path.isEmpty() ? key : path + "." + key;
                out.put(key, interpolate(e.getValue(), childPath, env, ctx));
            }
            return out;
        }
        if (node instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object e : l) out.add(interpolate(e, path, env, ctx));
            return out;
        }
        if (node instanceof String s) {
            return interpolateScalar(s, path, env, ctx);
        }
        return node;
    }

    private static String interpolateScalar(String s, String path, Function<String, String> env, BindContext ctx) {
        Matcher m = PLACEHOLDER.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String def = m.group(2);
            String value = env.apply(name);
            if (value == null) {
                if (def != null) {
                    value = def;
                } else {
                    ctx.reportAtPathIfKnown(
                            ConfigIssueCode.INTERPOLATION_UNRESOLVED,
                            path,
                            "${" + name + "} is not set and has no default");
                    value = "";
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
```

Note: this code calls `ctx.reportAtPathIfKnown(...)` which doesn't exist yet — `BindContext` has a *private* `reportAtPath`. Promote it to package-private (or add a public method) so `Interpolator` (same package's `internal` subpackage — different package!) can call it.

Easiest fix: expose a public method on `BindContext` named `reportAtPath` (or rename the private helper to public). Since `Interpolator` lives in `io.tiko.config.internal` and `BindContext` lives in `io.tiko.config`, the call site needs cross-package visibility — `public`.

In `BindContext.java`, change the helper from `private` to `public`:

```java
    /**
     * Reports an error against the known location for {@code dotPath} when one is
     * available, else falls back to {@link #report(ConfigIssueCode, String)} unanchored.
     */
    public void reportAtPath(ConfigIssueCode code, String dotPath, String message) {
        SourceLocation loc = locations.get(dotPath);
        if (loc != null) {
            reportAt(code, loc.line(), loc.column(), message);
        } else {
            report(code, message);
        }
    }
```

(Drop the `private` modifier — make it `public`. Adjust call sites in the same file accordingly — they already reference `reportAtPath` by simple name, no changes needed there.)

Then update `Interpolator.interpolateScalar` to call `ctx.reportAtPath(...)` (not `reportAtPathIfKnown` — same method, the path may or may not be in the locations map; the helper handles the fallback).

- [ ] **Step 3: Verify compile + test**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-config test`
Expected: BUILD SUCCESS. (Existing tests still pass since `interpolate(node, env, ctx)` 3-arg form still exists as a public delegating overload; we just added a 4-arg internal form.)

- [ ] **Step 4: Commit**

```bash
git add tiko-config/src/main/java/io/tiko/config/internal/Interpolator.java tiko-config/src/main/java/io/tiko/config/BindContext.java
git commit -m "feat(config): Interpolator anchors INTERPOLATION_UNRESOLVED to value path"
```

---

## Task 7: `ConfigBootstrap` — thread locations + anchor `UNKNOWN_SECTION`

**Files:**
- Modify: `tiko-config/src/main/java/io/tiko/config/runtime/ConfigBootstrap.java`

- [ ] **Step 1: Update `bind(...)` to use `source.locations()` and thread it into `BindContext`**

In `tiko-config/src/main/java/io/tiko/config/runtime/ConfigBootstrap.java`, find the existing line `BindContext ctx = new BindContext(sourceLabel);` (around line 46). Replace the 2 lines around it:

```java
        // 1. Load
        Map<String, Object> raw = source.load();
        Map<String, io.tiko.SourceLocation> locations = source.locations();

        // 2. Build BindContext with locations
        BindContext ctx = new BindContext(sourceLabel, locations);

        // 3. Interpolate
        @SuppressWarnings("unchecked")
        Map<String, Object> interpolated = (Map<String, Object>) Interpolator.interpolate(raw, System::getenv, ctx);
```

(The original code creates `ctx` first, then loads, then interpolates. The new order is: load → get locations → construct ctx → interpolate. The relative order of "load" and "ctx-construction" is the only change.)

- [ ] **Step 2: Anchor the `UNKNOWN_SECTION` report**

The existing block at line 76-82:

```java
for (String k : interpolated.keySet()) {
    if (!claimed.contains(k)) {
        String suggestion = nearest(k, claimed);
        String hint = suggestion != null ? " Did you mean '" + suggestion + "'?" : "";
        ctx.report(ConfigIssueCode.UNKNOWN_SECTION, "unknown top-level section '" + k + "'." + hint);
    }
}
```

becomes:

```java
for (String k : interpolated.keySet()) {
    if (!claimed.contains(k)) {
        String suggestion = nearest(k, claimed);
        String hint = suggestion != null ? " Did you mean '" + suggestion + "'?" : "";
        ctx.reportAtPath(ConfigIssueCode.UNKNOWN_SECTION, k, "unknown top-level section '" + k + "'." + hint);
    }
}
```

The `DUPLICATE_PREFIX` report stays unanchored — it's a processor-time configuration error, not a YAML-content problem.

- [ ] **Step 3: Run the full tiko-config + 02_config test suites**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-config,tiko-examples/02_config test`
Expected: BUILD SUCCESS (or note failing tests with exact-description assertions for Task 9 to sweep).

- [ ] **Step 4: Commit**

```bash
git add tiko-config/src/main/java/io/tiko/config/runtime/ConfigBootstrap.java
git commit -m "feat(config): ConfigBootstrap threads source locations into BindContext"
```

---

## Task 8: Integration test in `02_config` — anchored error end-to-end

**Files:**
- Create: `tiko-examples/02_config/src/test/resources/bad-config.yaml`
- Create: `tiko-examples/02_config/src/test/java/io/tiko/examples/config/ConfigurationAnchoredErrorsTest.java`

- [ ] **Step 1: Create the fixture `bad-config.yaml`**

```yaml
# Deliberately malformed for ConfigurationAnchoredErrorsTest.
# - missing required "url" inside "db"
# - "port" is not a valid integer
# - unknown top-level "garbage" section
db:
  maxConnections: 5
  connectTimeout: PT3S
app:
  name: anchored-error-fixture
  logLevel: INFO
  server:
    host: 0.0.0.0
    port: not-a-number
garbage:
  key: value
```

- [ ] **Step 2: Create `ConfigurationAnchoredErrorsTest.java`**

```java
package io.tiko.examples.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.ConfigurationFailure;
import io.tiko.ErrorContext;
import io.tiko.config.ConfigSources;
import io.tiko.config.ConfigValidationException;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * End-to-end verification of Issue #19: a misconfigured YAML produces
 * {@link ConfigurationFailure} issues whose descriptions are anchored
 * to the file:line:column where the offending value lives, AND the
 * thrown {@link ConfigValidationException} carries the same anchors.
 */
class ConfigurationAnchoredErrorsTest {

    @Test
    void missingRequiredKeyAndInvalidValueAndUnknownSectionAllAnchored() {
        var recorded = new CopyOnWriteArrayList<ErrorContext>();
        var opts = TikoOptions.builder().errorHandler(recorded::add).build();

        assertThatThrownBy(() -> Tiko.create(opts, ConfigSources.classpath("bad-config.yaml")))
                .isInstanceOf(ConfigValidationException.class);

        assertThat(recorded).singleElement().isInstanceOfSatisfying(ConfigurationFailure.class, f -> {
            var descriptions = f.issues().stream().map(i -> i.description()).toList();

            // Missing required "db.url" → anchored to the "db" section header (line 6 of the fixture).
            assertThat(descriptions).anyMatch(d -> d.contains("bad-config.yaml:6") && d.contains("db.url"));

            // Invalid integer for "app.server.port" → anchored at the bad scalar (line 13).
            assertThat(descriptions).anyMatch(d -> d.contains("bad-config.yaml:13") && d.contains("app.server.port"));

            // Unknown top-level section "garbage" → anchored at the section header (line 14).
            assertThat(descriptions).anyMatch(d -> d.contains("bad-config.yaml:14") && d.contains("garbage"));
        });
    }
}
```

Note: line numbers above assume the fixture lines as written. If line numbers shift due to file-edit-time formatting (e.g. a stripped trailing newline), the test's `:N` substring assertion needs to match the actual line of the offending row. Run the test once and update the assertions to the actual lines emitted; this is a small one-time tuning step, not a test design problem.

- [ ] **Step 3: Run the test**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/02_config test -Dtest=ConfigurationAnchoredErrorsTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0`.

If line numbers differ, adjust the assertion line numbers to match the actual issue descriptions emitted (the test output will print them).

- [ ] **Step 4: Commit**

```bash
git add tiko-examples/02_config/src/test/resources/bad-config.yaml tiko-examples/02_config/src/test/java/io/tiko/examples/config/ConfigurationAnchoredErrorsTest.java
git commit -m "test(examples): 02_config e2e — malformed YAML produces anchored error issues"
```

---

## Task 9: Regression sweep — fix existing tests asserting unanchored descriptions

**Files:**
- Modify (as needed): any test files whose assertions break due to the new anchored description format.

- [ ] **Step 1: Run the full reactor test suite**

Run: `W:/tools/apache-maven/bin/mvn -pl '!tiko-bom' install`
Expected: build will either succeed (no breaking tests) OR fail on tests that previously asserted exact unanchored descriptions like `assertThat(issue.description()).isEqualTo("db.url is required but missing")`.

- [ ] **Step 2: Catalogue any failing tests**

Read each failing test report under `**/target/surefire-reports/`. For each failure, identify whether the failure is:
- (a) An exact-string-match assertion on `ConfigIssue.description()` that now picks up an anchor prefix → update assertion to `startsWith("config.yaml:") and contains(...)` or `contains(...)`.
- (b) A genuine regression → stop and diagnose; likely a bug in Tasks 3-7.

- [ ] **Step 3: Update the assertions**

For each (a) failure, update the assertion to use `contains(...)` matching the original (unanchored) substring of the description. Example:

```java
// before:
assertThat(issue.description()).isEqualTo("db.url is required but missing");
// after:
assertThat(issue.description()).contains("db.url is required but missing");
```

This pattern is forward-compatible: it passes both with and without the anchor.

- [ ] **Step 4: Run the full reactor test suite again**

Run: `W:/tools/apache-maven/bin/mvn -pl '!tiko-bom' install`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add <files updated in Step 3>
git commit -m "test: update existing config-binding assertions to tolerate anchored descriptions"
```

If no test updates were needed (Step 1 already passed), skip this commit entirely.

---

## Task 10: Roadmap entry + final reactor build + push + PR

**Files:**
- Modify: `docs/roadmap.md`

- [ ] **Step 1: Modify `docs/roadmap.md`**

In the `## What ships today` block, AFTER the existing entries, add:

```markdown
- ✅ `@Configuration` validation errors anchored to YAML — binding errors now display `config.yaml:line:column` prefixes pointing at the offending value (or the enclosing section, for missing required keys). New `io.tiko.SourceLocation` record + additive `ConfigSource.locations()` default method expose locations to custom error handlers. (Closes #19.)
```

- [ ] **Step 2: Run the full reactor build**

Run: `W:/tools/apache-maven/bin/mvn -pl "!tiko-bom" install`
Expected: BUILD SUCCESS. All modules build, all tests pass.

- [ ] **Step 3: Confirm working tree clean**

Run: `git status`
Expected: nothing to commit.

- [ ] **Step 4: Commit roadmap**

```bash
git add docs/roadmap.md
git commit -m "docs(roadmap): @Configuration YAML source anchors shipped"
```

- [ ] **Step 5: Push branch**

```bash
git push -u origin feat/config-yaml-source-anchors
```

- [ ] **Step 6: Open the PR**

```bash
"C:/Program Files/GitHub CLI/gh.exe" pr create \
    --title "feat(config): anchor @Configuration validation errors to YAML file:line:column" \
    --body "$(cat <<'EOF'
## Summary

Closes #19. `@Configuration` binding errors now display `config.yaml:line:column` prefixes pointing at the offending value (or the enclosing section, for missing required keys). Dramatically improves error UX for large YAML configs.

Spec at `docs/superpowers/specs/2026-05-16-config-yaml-source-anchors-design.md`. Plan at `docs/superpowers/plans/2026-05-16-config-yaml-source-anchors.md`.

### Key pieces

- **`SourceLocation` record** (new `tiko-api/src/main/java/io/tiko/SourceLocation.java`) — `(source, line, column)`. Kept in `tiko-api` as a plain record so the public surface stays SnakeYAML-free.
- **`ConfigSource.locations()`** — additive default method on `ConfigSource`, returns `Map.of()`. YAML-backed sources override; programmatic sources keep working unchanged.
- **`YamlLoader`** — switches from `yaml.load()` to `yaml.compose()` so SnakeYAML's `Mark`s are available; walks the `Node` tree to produce both the data map (today's shape) and a parallel dot-path → `SourceLocation` index.
- **`BindContext`** — gains a 2-arg constructor accepting the location map and an internal `reportAtPath(...)` helper. Every read method (`requireScalar`, `requireSection`, `scalarOrDefault`, `optionalScalar`, `checkUnknownKeys`) emits anchored errors when a location is known; falls back to unanchored otherwise.
- **`Interpolator`** — anchors `INTERPOLATION_UNRESOLVED` to the value's path so missing `${ENV}` references point at the YAML scalar where the placeholder appears.
- **`ConfigBootstrap`** — threads `source.locations()` into `BindContext`; anchors the `UNKNOWN_SECTION` report at the unknown top-level key's location.

### Test plan

- [x] `YamlLoaderTest` — 4 cases verifying the `LoadedYaml` carrier shape: data map equivalence, leaf-scalar locations, section-header locations, empty-YAML fallback.
- [x] `BindContextLocationTest` — 3 cases: missing-key anchored to parent section, coercion failure anchored to value, unanchored fallback when locations map is empty.
- [x] `ConfigurationAnchoredErrorsTest` (e2e in `02_config`) — malformed YAML through full `Tiko.create(...)` path; asserts `ConfigurationFailure` issues carry source anchors AND `ConfigValidationException` propagates with the same.
- [x] Existing tests across `tiko-config` and examples adjusted from exact-description assertions to substring matches where needed.
- [x] Full reactor `mvn -pl '!tiko-bom' install` green.
- [x] Spotless gate clean.

### Backwards compatibility

- `ConfigSource.locations()` is an additive default method. Existing user-implemented `ConfigSource` types keep working unchanged and produce unanchored output.
- `BindContext(String source)` 1-arg constructor still exists (delegates to the 2-arg form with `Map.of()`).
- `ConfigIssue.description()` format is unchanged structurally — it just gains a `source:line:column ` prefix when a location is known. The Javadoc on `ConfigIssue.description()` explicitly anticipates this format ("may include anchor info like `config.yaml:5:7`").

### Out of scope (future follow-ups)

- Layered / merged config sources with per-key source attribution. The `layered(...)` factory does a simple `putAll` last-source-wins merge for locations; per-key source tracking is a separate issue if needed.
- Anchoring inside list elements (e.g., `servers[0].port`). Lists get anchored to the parent list key.
- Structured access to `SourceLocation` via `ConfigIssue` — the location is folded into the description string; adding a structured field to `ConfigIssue` is a separate public-API change.
EOF
)"
```

- [ ] **Step 7: Watch CI**

```bash
"C:/Program Files/GitHub CLI/gh.exe" pr checks --watch
```

Expected: all checks pass. If any fail, diagnose the specific failure (most likely Spotless formatting — fix with `mvn -pl '!tiko-bom' spotless:apply` and push again).

- [ ] **Step 8: Hand off for manual merge**

Per project policy (branch protection), the user merges in the GitHub UI. After confirmation:

```bash
git checkout main
git pull --ff-only
git branch -d feat/config-yaml-source-anchors
git fetch --prune origin
```

---

## Done

`@Configuration` validation errors now point users straight at the offending YAML line, closing one of the friction points the milestone has accumulated. Issue #19 closes; Milestone 2 has four open issues left (#46, #48, #63, #74).
