# `Set<X>` support in `@Configuration` records Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `@Configuration` records accept `Set<X>` fields, deduped via `LinkedHashSet` with a JUL warning per duplicate, order preserved.

**Architecture:** Three production-code edits parallel `List<X>` exactly: (a) a new `CompositeCoercers.set(...)` factory that returns a `TypeCoercer<Set<X>>` wrapping `LinkedHashSet` + JUL warning; (b) `ConfigSupportedTypes` accepts `"java.util.Set"`; (c) `ConfigBinderGenerator.coercerExpr` and `emitNestedCoercersFor` recognise `Set`. The implementation is purely additive — no API changes, no existing field affected.

**Tech Stack:** Java 21, SnakeYAML 2.x, `java.util.logging`, JavaPoet, JUnit 5, AssertJ, Google `compile-testing`.

**Spec:** `docs/superpowers/specs/2026-05-16-config-set-support-design.md` (committed at `cdfdb2d` on `feat/config-set-support`).

---

## File structure

```
tiko-config/src/main/java/io/tiko/config/internal/coercers/
└── CompositeCoercers.java                      (modify — add set() factory + LoggerHolder)

tiko-config/src/test/java/io/tiko/config/internal/coercers/
└── CompositeCoercersTest.java                  (modify — add 5 tests for set())

tiko-processor/src/main/java/io/tiko/processor/config/
├── ConfigSupportedTypes.java                   (modify — accept Set FQN, bundledTypeNames)
└── ConfigBinderGenerator.java                  (modify — coercerExpr + emitNestedCoercersFor)

tiko-processor/src/test/java/io/tiko/processor/config/
└── ConfigBinderGeneratorTest.java              (modify — 3 new Set test cases)

tiko-examples/02_config/src/test/java/io/tiko/examples/config/
└── ConfigurationSetFieldTest.java              (create — e2e dedupe + order preservation)

tiko-examples/02_config/src/test/resources/
└── set-config.yaml                             (create — fixture covering db, app, set sections)

docs/configuration.md                           (modify — mention Set<X> in "Nested records" + example)
docs/roadmap.md                                 (modify — "What ships today" entry closes #63)
```

---

## Task 1: `CompositeCoercers.set(...)` factory + JUL warning (TDD)

**Files:**
- Modify: `tiko-config/src/main/java/io/tiko/config/internal/coercers/CompositeCoercers.java`
- Modify: `tiko-config/src/test/java/io/tiko/config/internal/coercers/CompositeCoercersTest.java`

- [ ] **Step 1: Add five failing tests to `CompositeCoercersTest`**

Open `tiko-config/src/test/java/io/tiko/config/internal/coercers/CompositeCoercersTest.java`. Add these imports near the top (preserve existing imports):

```java
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
```

Append these five tests inside the class body (after the existing `optional_coercer_wraps_present_value` test):

```java
    @Test
    void set_coercer_delegates_to_element_coercer() {
        TypeCoercer<Set<Integer>> c = CompositeCoercers.set(Coercers.intCoercer());
        assertThat(c.coerce(List.of(1, 2, "3"))).containsExactly(1, 2, 3);
    }

    @Test
    void set_coercer_dedupes_with_order_preservation() {
        TypeCoercer<Set<String>> c = CompositeCoercers.set(Coercers.stringCoercer());
        Set<String> result = c.coerce(List.of("alpha", "beta", "alpha", "gamma", "beta"));

        // LinkedHashSet preserves first-occurrence order; duplicates dropped.
        assertThat(result).containsExactly("alpha", "beta", "gamma");
    }

    @Test
    void set_coercer_rejects_non_list_input() {
        TypeCoercer<Set<Integer>> c = CompositeCoercers.set(Coercers.intCoercer());
        assertThatThrownBy(() -> c.coerce("not a list")).hasMessageContaining("expected list");
    }

    @Test
    void set_coercer_handles_empty_list() {
        TypeCoercer<Set<String>> c = CompositeCoercers.set(Coercers.stringCoercer());
        assertThat(c.coerce(List.of())).isEmpty();
    }

    @Test
    void set_coercer_emits_jul_warning_per_duplicate() {
        Logger logger = Logger.getLogger("io.tiko.config");
        var records = new CopyOnWriteArrayList<LogRecord>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                records.add(r);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            TypeCoercer<Set<String>> c = CompositeCoercers.set(Coercers.stringCoercer());
            c.coerce(List.of("a", "b", "a", "c", "b"));

            assertThat(records)
                    .filteredOn(r -> r.getLevel() == Level.WARNING)
                    .extracting(LogRecord::getMessage)
                    .containsExactly(
                            "@Configuration Set<X> field: duplicate value 'a' deduped",
                            "@Configuration Set<X> field: duplicate value 'b' deduped");
        } finally {
            logger.removeHandler(handler);
        }
    }
```

- [ ] **Step 2: Run the tests — expect compile failure**

Run: `mvn -pl tiko-config test -Dtest=CompositeCoercersTest`
Expected: compile failure — `CompositeCoercers.set` does not exist.

- [ ] **Step 3: Add `set(...)` factory + `LoggerHolder` to `CompositeCoercers.java`**

In `tiko-config/src/main/java/io/tiko/config/internal/coercers/CompositeCoercers.java`:

Add to the imports block:

```java
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Logger;
```

Update the class Javadoc to include `Set<X>`:

```java
/** Coercers for {@code List<X>}, {@code Set<X>}, {@code Map<String,X>}, {@code Optional<X>}. */
```

Add this nested `LoggerHolder` class inside `CompositeCoercers` (just after the private constructor):

```java
    // Lazy holder — defers java.util.logging.LogManager init until the first
    // duplicate actually fires. Matches the pattern used by DefaultErrorHandler.
    private static final class LoggerHolder {
        static final Logger LOG = Logger.getLogger("io.tiko.config");
    }
```

Add the `set(...)` factory method just after `list(...)`:

```java
    public static <X> TypeCoercer<Set<X>> set(TypeCoercer<X> elementCoercer) {
        return v -> {
            if (!(v instanceof List<?> raw)) {
                throw new CoercionException("expected list, got "
                        + (v == null ? "null" : v.getClass().getSimpleName()));
            }
            Set<X> out = new LinkedHashSet<>(raw.size());
            for (Object e : raw) {
                X coerced = elementCoercer.coerce(e);
                if (!out.add(coerced)) {
                    LoggerHolder.LOG.warning(
                            "@Configuration Set<X> field: duplicate value '" + coerced + "' deduped");
                }
            }
            return Set.copyOf(out);
        };
    }
```

Note on the implementation: `LinkedHashSet.add(...)` returns `false` when the element is already present — that's the dedupe signal we hook into for the warning. `Set.copyOf(...)` returns an immutable view; iteration order is preserved by `LinkedHashSet`'s contract.

- [ ] **Step 4: Run the tests — expect pass**

Run: `mvn -pl tiko-config test -Dtest=CompositeCoercersTest`
Expected: `Tests run: 9, Failures: 0, Errors: 0` — 4 existing (list/list-reject/map/optional) plus 5 new (set/dedupe/non-list-reject/empty/warning).

- [ ] **Step 5: Format + commit**

```
mvn -pl '!tiko-bom' spotless:apply
git add tiko-config/src/main/java/io/tiko/config/internal/coercers/CompositeCoercers.java tiko-config/src/test/java/io/tiko/config/internal/coercers/CompositeCoercersTest.java
git commit -m "feat(config): CompositeCoercers.set with LinkedHashSet dedupe + JUL warning"
```

---

## Task 2: `ConfigSupportedTypes` — accept `Set` FQN

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/config/ConfigSupportedTypes.java`

- [ ] **Step 1: Read existing `ConfigSupportedTypes.java`**

The current `isSupported(...)` method has a check that looks like:

```java
if (fqn.equals("java.util.Optional") || fqn.equals("java.util.List") || fqn.equals("java.util.Map")) {
    return true; // element-type validation happens in ConfigurationValidator
}
```

And `bundledTypeNames()` returns a list ending in `"Optional<X>"`.

- [ ] **Step 2: Extend the FQN check to accept Set**

Modify the conditional to include `"java.util.Set"`:

```java
if (fqn.equals("java.util.Optional")
        || fqn.equals("java.util.List")
        || fqn.equals("java.util.Set")
        || fqn.equals("java.util.Map")) {
    return true; // element-type validation happens in ConfigurationValidator
}
```

- [ ] **Step 3: Add `"Set<X>"` to `bundledTypeNames()`**

In `bundledTypeNames()`, add `"Set<X>"` to the returned list — place it between `"List<X>"` and `"Map<String,X>"` so related collection types stay together:

```java
public static List<String> bundledTypeNames() {
    return List.of(
            "primitives + boxed",
            "String",
            "Duration",
            "Instant",
            "LocalDate",
            "LocalDateTime",
            "ZoneId",
            "UUID",
            "URI",
            "Path",
            "BigDecimal",
            "Pattern",
            "Charset",
            "enums",
            "List<X>",
            "Set<X>",
            "Map<String,X>",
            "nested records",
            "Optional<X>");
}
```

- [ ] **Step 4: Compile to verify**

Run: `mvn -pl tiko-processor compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Format + commit**

```
mvn -pl '!tiko-bom' spotless:apply
git add tiko-processor/src/main/java/io/tiko/processor/config/ConfigSupportedTypes.java
git commit -m "feat(config): ConfigSupportedTypes accepts Set<X>"
```

---

## Task 3: `ConfigBinderGenerator` — `coercerExpr` Set branch + `emitNestedCoercersFor` recursion (TDD)

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/config/ConfigBinderGenerator.java`
- Modify: `tiko-processor/src/test/java/io/tiko/processor/config/ConfigBinderGeneratorTest.java`

- [ ] **Step 1: Add three failing tests to `ConfigBinderGeneratorTest`**

Append these tests inside the class body (after the existing `list_of_nested_records_composes_with_CompositeCoercers_list` test):

```java
    // ---- #63: Set<X> support ----

    @Test
    void set_of_string_emits_CompositeCoercers_set_call() throws IOException {
        JavaFileObject src = JavaFileObjects.forSourceLines(
                "io.example.AllowlistConfig",
                "package io.example;",
                "import java.util.Set;",
                "import io.tiko.annotations.Configuration;",
                "@Configuration(prefix = \"allow\")",
                "public record AllowlistConfig(Set<String> hosts) {}");
        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        JavaFileObject binder = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("AllowlistConfigBinder"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("AllowlistConfigBinder not generated"));

        String content = new String(binder.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content).contains("CompositeCoercers.set(");
        assertThat(content).contains("stringCoercer()");
        assertThat(content).contains("ctx.requireScalar(node, \"hosts\"");
    }

    @Test
    void set_of_enum_emits_set_with_enumCoercer_inner() throws IOException {
        JavaFileObject src = JavaFileObjects.forSourceLines(
                "io.example.FeatureConfig",
                "package io.example;",
                "import java.util.Set;",
                "import io.tiko.annotations.Configuration;",
                "@Configuration(prefix = \"features\")",
                "public record FeatureConfig(Set<Mode> enabled) { public enum Mode { A, B, C } }");
        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        JavaFileObject binder = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("FeatureConfigBinder"))
                .findFirst()
                .orElseThrow();

        String content = new String(binder.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content).contains("CompositeCoercers.set(");
        assertThat(content).contains("enumCoercer(");
        assertThat(content).contains(".Mode.class");
    }

    @Test
    void set_of_nested_records_emits_nested_coercer_and_set_composition() throws IOException {
        JavaFileObject outer = JavaFileObjects.forSourceLines(
                "io.example.AppConfig",
                "package io.example;",
                "import java.util.Set;",
                "import io.tiko.annotations.Configuration;",
                "@Configuration(prefix = \"app\")",
                "public record AppConfig(Set<Endpoint> endpoints) {}");
        JavaFileObject inner = JavaFileObjects.forSourceLines(
                "io.example.Endpoint", "package io.example;", "public record Endpoint(String host, int port) {}");
        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(outer, inner);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        // The nested coercer class is generated for Endpoint.
        JavaFileObject nested = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("EndpointNestedCoercer_"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("EndpointNestedCoercer_<hash> not generated"));

        // The outer binder uses CompositeCoercers.set(...) wrapping the nested coercer.
        JavaFileObject outerBinder = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("AppConfigBinder"))
                .findFirst()
                .orElseThrow();
        String outerContent = new String(outerBinder.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(outerContent).contains("CompositeCoercers.set(");
        assertThat(outerContent).contains("EndpointNestedCoercer_");
        assertThat(outerContent).contains(".coercer()");
    }
```

- [ ] **Step 2: Run the new tests — expect failure**

Run: `mvn -pl tiko-processor test -Dtest=ConfigBinderGeneratorTest`
Expected: Two failure modes possible:
- (a) Compile of the test sources fails with `Unsupported field type: Set` thrown from `coercerExpr` because `Set` falls through to `scalarCoercer(fqn)`, OR
- (b) The processor reports the field as unsupported via `ConfigurationValidator` even after Task 2 (unlikely — Task 2 should already accept it).

In either case the new tests fail. Proceed.

- [ ] **Step 3: Extend `coercerExpr` to handle Set**

In `ConfigBinderGenerator.java`, find the `coercerExpr` method's List/Map branch (the block that starts with `if (fqn.equals("java.util.List") || fqn.equals("java.util.Map"))`).

Replace that block with:

```java
            if (fqn.equals("java.util.List")
                    || fqn.equals("java.util.Set")
                    || fqn.equals("java.util.Map")) {
                DeclaredType dt = (DeclaredType) type;
                int valueArgIdx = fqn.equals("java.util.Map") ? 1 : 0;
                CodeBlock elemCoercer = coercerExpr(dt.getTypeArguments().get(valueArgIdx));
                ClassName helper = ClassName.get(CompositeCoercers.class);
                return switch (fqn) {
                    case "java.util.List" -> CodeBlock.of("$T.list($L)", helper, elemCoercer);
                    case "java.util.Set" -> CodeBlock.of("$T.set($L)", helper, elemCoercer);
                    default -> CodeBlock.of("$T.map($L)", helper, elemCoercer);
                };
            }
```

The List and Set arms both use type-arg index 0 (single type parameter); Map keeps index 1 for the value type. Switch expression replaces the prior ternary chain since there are now three arms.

- [ ] **Step 4: Extend `emitNestedCoercersFor` to walk into Set**

Find the block in `emitNestedCoercersFor` that handles `Optional` and `List`:

```java
        if (fqn.equals("java.util.Optional") || fqn.equals("java.util.List")) {
            if (!dt.getTypeArguments().isEmpty()) {
                emitNestedCoercersFor(dt.getTypeArguments().get(0));
            }
            return;
        }
```

Update to include Set:

```java
        if (fqn.equals("java.util.Optional")
                || fqn.equals("java.util.List")
                || fqn.equals("java.util.Set")) {
            if (!dt.getTypeArguments().isEmpty()) {
                emitNestedCoercersFor(dt.getTypeArguments().get(0));
            }
            return;
        }
```

Set, like List and Optional, has a single type argument at index 0 holding the element type. Adding it to the same branch ensures `Set<NestedRecord>` triggers nested-coercer emission for the inner record.

- [ ] **Step 5: Run the tests — expect pass**

Run: `mvn -pl tiko-processor test -Dtest=ConfigBinderGeneratorTest`
Expected: All ConfigBinderGeneratorTest cases pass (existing + 3 new).

- [ ] **Step 6: Format + commit**

```
mvn -pl '!tiko-bom' spotless:apply
git add tiko-processor/src/main/java/io/tiko/processor/config/ConfigBinderGenerator.java tiko-processor/src/test/java/io/tiko/processor/config/ConfigBinderGeneratorTest.java
git commit -m "feat(config): ConfigBinderGenerator emits Set<X> coercers and walks nested records"
```

---

## Task 4: 02_config e2e — `Set<String>` dedupe + order preservation

**Files:**
- Create: `tiko-examples/02_config/src/test/java/io/tiko/examples/config/ConfigurationSetFieldTest.java`
- Create: `tiko-examples/02_config/src/test/resources/set-config.yaml`

- [ ] **Step 1: Create the test fixture**

Create `tiko-examples/02_config/src/test/resources/set-config.yaml` with this exact content:

```yaml
# Fixture for ConfigurationSetFieldTest. Exercises Set<X> dedupe + ordering
# end-to-end through Tiko.create(...). The db/app sections provide the required
# scalars for DbConfig and AppConfig (the example module's main @Configuration
# records) so the container actually starts.

db:
  url: jdbc:h2:mem:test

app:
  name: set-test
  server:
    host: 0.0.0.0

allow:
  hosts: [alpha, beta, alpha, gamma, beta]
```

- [ ] **Step 2: Create the test class with a test-only `@Configuration` record**

Create `tiko-examples/02_config/src/test/java/io/tiko/examples/config/ConfigurationSetFieldTest.java`:

```java
package io.tiko.examples.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.annotations.Configuration;
import io.tiko.config.ConfigSources;
import io.tiko.runtime.Tiko;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * End-to-end verification of #63: {@code Set<X>} fields bind from YAML lists,
 * dedupe duplicates via {@code LinkedHashSet}, and preserve first-occurrence
 * order. Uses a test-only {@code @Configuration} record so the assertion can
 * inspect the bound value directly through {@code container.get(...)}.
 */
class ConfigurationSetFieldTest {

    @Configuration(prefix = "allow")
    public record AllowlistConfig(Set<String> hosts) {}

    @Test
    void setFieldDedupesAndPreservesOrderEndToEnd() {
        try (Container c = Tiko.create(ConfigSources.classpath("set-config.yaml"))) {
            AllowlistConfig cfg = c.get(AllowlistConfig.class);
            assertThat(cfg.hosts()).containsExactly("alpha", "beta", "gamma");
        }
    }
}
```

The annotation processor runs over test sources at test-compile time, so this test-only record gets its own generated `AllowlistConfigBinder` automatically.

- [ ] **Step 3: Run the test**

Run: `mvn -pl tiko-examples/02_config test -Dtest=ConfigurationSetFieldTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0`.

If the test fails on container startup with a missing-key error for `db.url` or similar, double-check that the YAML fixture (Step 1) covers every required field of the example module's existing `@Configuration` records.

- [ ] **Step 4: Format + commit**

```
mvn -pl '!tiko-bom' spotless:apply
git add tiko-examples/02_config/src/test/java/io/tiko/examples/config/ConfigurationSetFieldTest.java tiko-examples/02_config/src/test/resources/set-config.yaml
git commit -m "test(examples): 02_config e2e — Set<X> dedupe + order preservation"
```

---

## Task 5: Docs — `docs/configuration.md` mentions Set<X>

**Files:**
- Modify: `docs/configuration.md`

- [ ] **Step 1: Update the "Nested records" intro paragraph**

Open `docs/configuration.md` and find the paragraph at line ~67:

> A `@Configuration` record can contain plain records as field types — directly, or inside `Optional<X>`, `List<X>`, `Map<String,X>`.

Replace with:

> A `@Configuration` record can contain plain records as field types — directly, or inside `Optional<X>`, `List<X>`, `Set<X>`, `Map<String,X>`.

- [ ] **Step 2: Update the example code block**

In the same section, the example record currently shows:

```java
@Configuration(prefix = "app")
public record AppConfig(
        String name,
        DbConfig db,                            // direct nested
        List<Endpoint> endpoints,               // list of nested
        Map<String, FeatureFlag> flags,         // map of nested
        Optional<DbConfig> readReplica          // optional nested
        ) {}
```

Add a `Set<X>` line so users see all four collection shapes:

```java
@Configuration(prefix = "app")
public record AppConfig(
        String name,
        DbConfig db,                            // direct nested
        List<Endpoint> endpoints,               // list of nested
        Set<String> allowedHosts,               // set of scalars (deduped, order-preserving)
        Map<String, FeatureFlag> flags,         // map of nested
        Optional<DbConfig> readReplica          // optional nested
        ) {}
```

Add the corresponding `import java.util.Set;` line near the top of the example if the doc block currently shows imports. Otherwise leave as-is.

- [ ] **Step 3: Commit**

```
git add docs/configuration.md
git commit -m "docs(config): mention Set<X> alongside List/Map/Optional in @Configuration"
```

---

## Task 6: Roadmap entry + full reactor build

**Files:**
- Modify: `docs/roadmap.md`

- [ ] **Step 1: Update the roadmap**

Open `docs/roadmap.md`.

In the **"What ships today"** block, after the most recent `✅` entry (the `@Configuration` YAML source anchors line that closes #19), add:

```markdown
- ✅ `Set<X>` in `@Configuration` records — YAML lists bind to `LinkedHashSet` with insertion-order preserved and duplicates deduped (one JUL warning per duplicate at `io.tiko.config`). Composes with enums and nested records via the existing `CompositeCoercers` shapes. (Closes #63.)
```

In the **Phase 2** "Configuration:" bullet, remove the `Set<X>` mention so the line reads (if it had any remaining items, list them; otherwise delete the bullet entirely):

Current line (after #19 shipped):
```
- **Configuration:** `Set<X>` in `@Configuration` records ([#63](https://github.com/tomas-samek/tiko-di/issues/63)).
```

Since #63 is now the only open Configuration item and we're closing it, **delete this bullet entirely** from the Phase 2 list.

- [ ] **Step 2: Run the full reactor build**

Run: `mvn -pl '!tiko-bom' install`
Expected: BUILD SUCCESS. All modules build, all tests pass.

- [ ] **Step 3: Confirm clean working tree**

Run: `git status`
Expected: only the roadmap change uncommitted.

- [ ] **Step 4: Commit roadmap**

```
git add docs/roadmap.md
git commit -m "docs(roadmap): Set<X> in @Configuration records shipped"
```

---

## Task 7: Push branch + open PR

- [ ] **Step 1: Push**

```
git push -u origin feat/config-set-support
```

- [ ] **Step 2: Open the PR**

```bash
"C:/Program Files/GitHub CLI/gh.exe" pr create \
    --title "feat(config): Set<X> support in @Configuration records" \
    --body "$(cat <<'EOF'
## Summary

Closes #63. `@Configuration` records now accept `Set<X>` fields. YAML lists coerce into immutable `LinkedHashSet` views — first-occurrence order preserved, duplicates deduped silently with one JUL `WARNING` per duplicate at the `io.tiko.config` namespace.

Spec at `docs/superpowers/specs/2026-05-16-config-set-support-design.md`. Plan at `docs/superpowers/plans/2026-05-16-config-set-support.md`.

### Key pieces

- **`CompositeCoercers.set(elementCoercer)`** — new factory mirroring `list(...)`. Wraps `LinkedHashSet` so iteration order matches YAML order; uses `Set.copyOf(...)` to return an immutable view. Warns through `java.util.logging` (`io.tiko.config` namespace, lazy-init holder) when `LinkedHashSet.add(...)` returns `false`.
- **`ConfigSupportedTypes`** — `"java.util.Set"` added to the accepted FQN list; `bundledTypeNames()` lists `"Set<X>"` between `"List<X>"` and `"Map<String,X>"`.
- **`ConfigBinderGenerator`** — `coercerExpr` emits `CompositeCoercers.set(...)` for `Set<X>` fields; `emitNestedCoercersFor` recurses into `Set`'s type argument so `Set<NestedRecord>` triggers nested-coercer generation.
- **Docs** — `docs/configuration.md` mentions `Set<X>` alongside `List`/`Map`/`Optional`; `docs/roadmap.md` "What ships today" closes #63.

### Test plan

- [x] `CompositeCoercersTest` — element delegation, dedupe + order preservation, non-list rejection, empty-list edge, JUL warning emission per duplicate (5 new tests).
- [x] `ConfigBinderGeneratorTest` — codegen for `Set<String>`, `Set<Enum>`, `Set<NestedRecord>` (3 new tests).
- [x] `ConfigurationSetFieldTest` (02_config e2e) — `Set<String>` field bound from real YAML through `Tiko.create(...)`; assert dedupe + order preservation end-to-end.
- [x] Full reactor `mvn -pl '!tiko-bom' install` green; Spotless clean.

### Backwards compatibility

Pure addition. No existing `@Configuration` field declaration is affected. `CompositeCoercers.set(...)` is additive on the final class. `bundledTypeNames()` gains one entry — only documentation surface, no behavioural callers.

### Out of scope

- `TreeSet` / sorted-set semantics (separate design).
- `Set` as element of another collection (`List<Set<X>>`, `Map<String, Set<X>>`) — recurses naturally but no test coverage planned.
- Per-element source anchors inside a set — lists have the same gap today.
- Promotion of the dedupe warning from JUL to a structured `ConfigurationWarning` `ErrorContext` permit — reversible later if needed.
EOF
)"
```

- [ ] **Step 3: Watch CI**

```
"C:/Program Files/GitHub CLI/gh.exe" pr checks --watch
```

Expected: all checks pass. If Spotless fails on CI, run `mvn -pl '!tiko-bom' spotless:apply` locally, commit, and push.

- [ ] **Step 4: Hand off for manual merge**

Per project policy (branch protection), the user merges via the GitHub UI. After confirmation:

```
git checkout main
git pull --ff-only
git branch -d feat/config-set-support
git fetch --prune origin
```

---

## Done

`@Configuration` records now bind `Set<X>` fields. Issue #63 closes. Milestone 2 has two open issues remaining: #48 (executor shutdown timeout) and #74 (java.lang.System.Logger).
