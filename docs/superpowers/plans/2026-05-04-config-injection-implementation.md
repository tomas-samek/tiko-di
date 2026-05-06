# Configuration Injection — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship typed configuration injection backed by YAML — `@Configuration` records, generated per-record binders (no runtime reflection), startup-time YAML validation, end-to-end demo example. Tracking issue: [#15](https://github.com/tomas-samek/tiko-di/issues/15). Spec: [`docs/superpowers/specs/2026-05-04-config-injection-design.md`](../specs/2026-05-04-config-injection-design.md).

**Architecture:** Three new annotations in `tiko-api` (`@Configuration`, `@Default`, `@Key`) plus a `ConfigSource` SPI; a new `tiko-config` module owning runtime YAML loading, the type-coercer registry, the bind-context, and `ConfigSources` factories (sole module that depends on SnakeYAML); `tiko-processor` extended (not split) to scan `@Configuration` records, validate them, and generate a `<Record>ConfigBinder.java` plus a per-module `ConfigBinderRegistry.java`. `tiko-runtime`'s `Tiko.create(ConfigSource)` adds an overload that triggers config bind at startup before any singleton is constructed. Cross-module aggregation reuses the existing `META-INF/tiko/` pattern with a new `configs.txt` resource.

**Tech Stack:** Java 17, Maven (multi-module), JavaPoet for codegen, SnakeYAML for YAML parsing, JUnit 5 + AssertJ + Google `compile-testing` for tests.

---

## Existing patterns to follow

- Annotations live in `io.tiko.annotations.*` (e.g. `tiko-api/src/main/java/io/tiko/annotations/Component.java`). Retention `SOURCE` for processor-only marks, `RUNTIME` for things consulted at runtime.
- The processor entrypoint is `tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java`. It collects per-round, validates in the final round, then runs generators. Add new annotation handling alongside the existing `Component`/`Produces`/`EventHandler` flow.
- Codegen uses JavaPoet (`com.palantir.javapoet:javapoet`). Existing generators in `tiko-processor/src/main/java/io/tiko/processor/generator/` (e.g. `ContainerGenerator.java`, `ComponentFactoryGenerator.java`).
- Processor tests use Google `compile-testing`. Template: `tiko-processor/src/test/java/io/tiko/processor/validation/PrivateConstructorTest.java`.
- Cross-module aggregation discovers `META-INF/tiko/container.properties` and reads `META-INF/tiko/components.txt`. See `tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java`. Add `META-INF/tiko/configs.txt` following the same pattern.
- The `Tiko.create()` factory in `tiko-api/src/main/java/io/tiko/Tiko.java` reflectively loads `io.tiko.generated.TikoContainerImpl` (or hashed variant), constructs it with the `EventBus`, registers event handlers, then calls `start()`. The new `Tiko.create(ConfigSource)` overload extends that flow.

## Conventions

- All generated config classes live in `io.tiko.generated.config` (parallel to `io.tiko.generated` for components).
- Manifest format `<fqn>=<prefix>` per line, `#` for comments. Forward-compatible — readers ignore unknown lines.
- Commit messages: conventional commits, single-line summary, no body, no Co-Authored-By trailer (per project preference).
- Use AssertJ for test assertions to match existing style.

---

## File Structure

### New files

**`tiko-api`:**
- `src/main/java/io/tiko/annotations/Configuration.java` — `@Configuration(prefix)` marker, retention `SOURCE`, target `TYPE`.
- `src/main/java/io/tiko/annotations/Default.java` — `@Default(value)`, retention `SOURCE`, target `PARAMETER`.
- `src/main/java/io/tiko/annotations/Key.java` — `@Key(value)`, retention `SOURCE`, target `PARAMETER`.
- `src/main/java/io/tiko/ConfigSource.java` — SPI interface returning `Map<String,Object>` from `load()`.

**`tiko-config` (new module):**
- `pom.xml`
- `src/main/java/io/tiko/config/ConfigSources.java` — public factory class.
- `src/main/java/io/tiko/config/ConfigBinder.java` — public interface generated binders implement.
- `src/main/java/io/tiko/config/BindContext.java` — public class generated binders call into.
- `src/main/java/io/tiko/config/ConfigValidationException.java` — public throwable.
- `src/main/java/io/tiko/config/runtime/ConfigBootstrap.java` — public; entry point the generated container calls at startup.
- `src/main/java/io/tiko/config/internal/YamlLoader.java` — package-private; SnakeYAML-backed loader.
- `src/main/java/io/tiko/config/internal/Interpolator.java` — package-private; `${VAR}` substitution.
- `src/main/java/io/tiko/config/internal/DeepMerge.java` — package-private; layered-source merge utility.
- `src/main/java/io/tiko/config/internal/coercers/TypeCoercer.java` — package-private interface.
- `src/main/java/io/tiko/config/internal/coercers/TypeCoercerRegistry.java` — package-private registry; bundled coercers registered here.
- `src/main/java/io/tiko/config/internal/coercers/<Type>Coercer.java` — one class per coercer (or grouped where related).
- `src/main/java/io/tiko/config/internal/ConfigError.java` — package-private accumulated-error record.
- `src/main/java/io/tiko/config/internal/ErrorReporter.java` — package-private message formatter.
- `src/main/java/module-info.java` — *omitted; the project does not currently use JPMS.*
- Tests under `src/test/java/io/tiko/config/...` (mirror main).

**`tiko-processor` extensions (new files only):**
- `src/main/java/io/tiko/processor/config/ConfigurationModel.java` — model type for collected `@Configuration` records.
- `src/main/java/io/tiko/processor/config/ConfigFieldModel.java` — model for individual record components.
- `src/main/java/io/tiko/processor/config/ConfigurationCollector.java` — walks `@Configuration`-annotated elements.
- `src/main/java/io/tiko/processor/config/ConfigurationValidator.java` — all compile-time errors.
- `src/main/java/io/tiko/processor/config/ConfigSupportedTypes.java` — central whitelist + per-type bind-method routing (also drives `@Default` parseability).
- `src/main/java/io/tiko/processor/config/ConfigBinderGenerator.java` — JavaPoet generator for `<Record>ConfigBinder.java`.
- `src/main/java/io/tiko/processor/config/ConfigBinderRegistryGenerator.java` — JavaPoet generator for per-module registry.
- `src/main/java/io/tiko/processor/config/ConfigManifestWriter.java` — writes `META-INF/tiko/configs.txt`.
- Tests under `src/test/java/io/tiko/processor/config/...`.

**`tiko-runtime` (no new files):** modify `Tiko.java` and add config-aware container startup hook through generated code (no runtime file changes needed — generators emit the bridging code).

**`tiko-examples/03_config` (new module):**
- `pom.xml`
- `src/main/java/io/tiko/examples/config/Main.java`
- `src/main/java/io/tiko/examples/config/DbConfig.java` (`@Configuration` record)
- `src/main/java/io/tiko/examples/config/DataService.java` (`@Component` consuming `DbConfig`)
- `src/main/resources/config.yaml`

### Modified files

- `pom.xml` (root) — add `tiko-config` module, add `tiko-examples/03_config` to the examples reactor.
- `tiko-bom/pom.xml` — add `tiko-config` and SnakeYAML to dependency management.
- `pom.xml` (root, `<dependencyManagement>`) — add SnakeYAML version property + entry.
- `tiko-processor/pom.xml` — add `tiko-config` at `provided` (compile-only) scope so coercer logic is shared without leaking SnakeYAML to user runtime.
- `tiko-api/src/main/java/io/tiko/Tiko.java` — add `create(ConfigSource)` overload + fail-fast check in no-arg `create()`.
- `tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java` — register `Configuration.class` in supported annotations, wire collector/validator/generators.
- `tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java` — extend to merge `configs.txt` entries and trigger config-aware startup.
- `tiko-examples/pom.xml` — add `03_config` to its `<modules>`.
- `README.md` — move "Configuration injection" entry from Phase 2 to Phase 1; retitle to use `@Configuration`.

---

## Task ordering rationale

- **Phase A (Tasks 1–3):** API + module bootstrap. Mechanical, no behavioural risk.
- **Phase B (Tasks 4–9):** Runtime helpers + coercer infra. End-to-end test possible at end of Phase B using a *hand-written* binder — proves the runtime works before any codegen exists.
- **Phase C (Tasks 10–13):** Processor work — validators and generators. End of Phase C: a `@Configuration` record successfully compiles, generates a binder, and the binder is loaded statically by a generated registry.
- **Phase D (Tasks 14–15):** Runtime integration — wire `Tiko.create(ConfigSource)`, fail-fast for missing source, and cross-module aggregation.
- **Phase E (Tasks 16–17):** Example module proves the end-to-end story; README update reflects roadmap.

Each phase produces working software when its tasks land. You can stop after any phase and have a coherent state to review.

---

## Phase A — API + module bootstrap

### Task 1: Add the three new annotations to `tiko-api`

**Files:**
- Create: `tiko-api/src/main/java/io/tiko/annotations/Configuration.java`
- Create: `tiko-api/src/main/java/io/tiko/annotations/Default.java`
- Create: `tiko-api/src/main/java/io/tiko/annotations/Key.java`

These are pure marker annotations with `SOURCE` retention (matches `@Component`). No tests needed — annotation declarations are compile-checked by their first use in Phase B/C tests. Verification at end is `mvn -pl tiko-api compile`.

- [ ] **Step 1: Create `Configuration.java`**

```java
package io.tiko.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record as a configuration root. The processor generates a binder
 * for this record at compile time; at runtime the container loads YAML
 * matching {@link #prefix()} and binds it to a record instance, registered
 * as a SINGLETON-scoped bean.
 *
 * <p>Only {@code record} types may be annotated. Nested records inside a
 * {@code @Configuration} record are bound automatically and do not need this
 * annotation themselves — {@code @Configuration} marks the top-level entry
 * point that owns a YAML root prefix.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface Configuration {
    /** Top-level YAML key under which this record's data is read. */
    String prefix();
}
```

- [ ] **Step 2: Create `Default.java`**

```java
package io.tiko.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Supplies a default value for a {@code @Configuration} record component
 * when the corresponding YAML key is absent. The string is parsed at
 * compile time using the same coercer the runtime uses for that field's
 * declared type, so a malformed default fails the build.
 *
 * <p>Cannot be combined with {@code Optional<X>} — {@code Optional} already
 * encodes "may be absent" and the two would conflict.</p>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.SOURCE)
public @interface Default {
    String value();
}
```

- [ ] **Step 3: Create `Key.java`**

```java
package io.tiko.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Overrides the YAML key used for a {@code @Configuration} record component.
 * By default the field name is used verbatim (camelCase). Use {@code @Key}
 * when the YAML uses a different naming style (kebab-case, snake_case, etc.).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.SOURCE)
public @interface Key {
    String value();
}
```

- [ ] **Step 4: Verify compilation**

Run: `mvn -pl tiko-api -am clean compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add tiko-api/src/main/java/io/tiko/annotations/Configuration.java \
        tiko-api/src/main/java/io/tiko/annotations/Default.java \
        tiko-api/src/main/java/io/tiko/annotations/Key.java
git commit -m "feat(api): add @Configuration, @Default, @Key annotations"
```

---

### Task 2: Add `ConfigSource` SPI to `tiko-api`

**Files:**
- Create: `tiko-api/src/main/java/io/tiko/ConfigSource.java`

The interface lives in `tiko-api` so user code can implement custom sources without depending on `tiko-config`. Factory methods for the bundled implementations live on `ConfigSources` in `tiko-config` (Task 9).

- [ ] **Step 1: Create `ConfigSource.java`**

```java
package io.tiko;

import java.util.Map;

/**
 * Source of YAML-shaped configuration data. Implementations return a tree
 * of {@code Map<String,Object>}, {@code List<Object>}, and scalars
 * (Strings, Numbers, Booleans, etc.) — the same shape SnakeYAML produces
 * from a YAML document.
 *
 * <p>The container calls {@link #load()} once at startup. Implementations
 * should be deterministic and side-effect free.</p>
 *
 * <p>Bundled implementations are available via {@code io.tiko.config.ConfigSources}:
 * {@code classpath(String)}, {@code file(Path)}, {@code fromMap(Map)},
 * {@code layered(ConfigSource...)}.</p>
 */
public interface ConfigSource {
    /**
     * Returns the parsed configuration tree. Called once at container startup.
     *
     * @throws RuntimeException if the source cannot be loaded (file not found,
     *         malformed YAML, etc.)
     */
    Map<String, Object> load();
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn -pl tiko-api -am clean compile`
Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add tiko-api/src/main/java/io/tiko/ConfigSource.java
git commit -m "feat(api): add ConfigSource SPI"
```

---

### Task 3: Bootstrap the `tiko-config` module

**Files:**
- Create: `tiko-config/pom.xml`
- Modify: `pom.xml` (root) — add `tiko-config` to `<modules>`, add SnakeYAML version property and management entry.
- Modify: `tiko-bom/pom.xml` — add `tiko-config` and SnakeYAML.

Module is empty after this task (placeholder package only). It will gain content in Task 4 onward. SnakeYAML is pinned at `2.3` (currently the latest stable, also what current Jackson YAML transitively pulls).

- [ ] **Step 1: Add SnakeYAML to root `pom.xml` `<properties>`**

In `pom.xml` (root), inside `<properties>`, add after `compile-testing.version`:

```xml
<snakeyaml.version>2.3</snakeyaml.version>
```

In root `pom.xml` `<dependencyManagement>` `<dependencies>`, add (placement: after the existing internal-modules block, before the AutoService block):

```xml
<dependency>
    <groupId>io.tiko</groupId>
    <artifactId>tiko-config</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>org.yaml</groupId>
    <artifactId>snakeyaml</artifactId>
    <version>${snakeyaml.version}</version>
</dependency>
```

In root `pom.xml` `<modules>`, add `<module>tiko-config</module>` immediately after `<module>tiko-runtime</module>`.

- [ ] **Step 2: Update `tiko-bom/pom.xml`**

In `tiko-bom/pom.xml` `<properties>`, add after `slf4j.version`:

```xml
<snakeyaml.version>2.3</snakeyaml.version>
```

In `tiko-bom/pom.xml` `<dependencyManagement>` `<dependencies>`, add `tiko-config` after `tiko-runtime`:

```xml
<dependency>
    <groupId>io.tiko</groupId>
    <artifactId>tiko-config</artifactId>
    <version>${tiko.version}</version>
</dependency>
```

And add SnakeYAML after the logging block:

```xml
<dependency>
    <groupId>org.yaml</groupId>
    <artifactId>snakeyaml</artifactId>
    <version>${snakeyaml.version}</version>
</dependency>
```

- [ ] **Step 3: Create `tiko-config/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.tiko</groupId>
        <artifactId>tiko-parent</artifactId>
        <version>0.1.0</version>
    </parent>

    <artifactId>tiko-config</artifactId>
    <name>Tiko Configuration</name>
    <description>YAML-backed configuration injection for Tiko DI</description>

    <dependencies>
        <dependency>
            <groupId>io.tiko</groupId>
            <artifactId>tiko-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.yaml</groupId>
            <artifactId>snakeyaml</artifactId>
        </dependency>

        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

</project>
```

- [ ] **Step 4: Create empty placeholder package**

Create the directory `tiko-config/src/main/java/io/tiko/config/` (Maven needs at least one source file to consider the module compilable; a `package-info.java` is the minimal touch).

```java
// tiko-config/src/main/java/io/tiko/config/package-info.java
/**
 * Tiko configuration injection — YAML loading, type coercion, binders.
 *
 * <p>Public API: {@link io.tiko.config.ConfigSources},
 * {@link io.tiko.config.ConfigBinder}, {@link io.tiko.config.BindContext},
 * {@link io.tiko.config.ConfigValidationException}.</p>
 */
package io.tiko.config;
```

- [ ] **Step 5: Verify the multi-module build**

Run: `mvn -pl tiko-config -am clean compile`
Expected: `BUILD SUCCESS` for `tiko-api` then `tiko-config`.

- [ ] **Step 6: Commit**

```bash
git add pom.xml tiko-bom/pom.xml tiko-config/pom.xml \
        tiko-config/src/main/java/io/tiko/config/package-info.java
git commit -m "build(config): bootstrap tiko-config module with SnakeYAML"
```

---

## Phase B — Runtime helpers + coercer infrastructure

### Task 4: Core public types — `ConfigBinder<T>`, `ConfigValidationException`, error model

**Files:**
- Create: `tiko-config/src/main/java/io/tiko/config/ConfigBinder.java`
- Create: `tiko-config/src/main/java/io/tiko/config/ConfigValidationException.java`
- Create: `tiko-config/src/main/java/io/tiko/config/internal/ConfigError.java`
- Create: `tiko-config/src/main/java/io/tiko/config/internal/ErrorReporter.java`
- Test: `tiko-config/src/test/java/io/tiko/config/ConfigValidationExceptionTest.java`

`ConfigError` carries one validation failure (path, message, optional location). `ErrorReporter` turns a list of `ConfigError`s into the formatted multi-line message used by `ConfigValidationException`.

- [ ] **Step 1: Write the failing exception-formatting test**

```java
// tiko-config/src/test/java/io/tiko/config/ConfigValidationExceptionTest.java
package io.tiko.config;

import io.tiko.config.internal.ConfigError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigValidationExceptionTest {

    @Test
    void formats_multiple_errors_as_numbered_report() {
        List<ConfigError> errors = List.of(
            ConfigError.at("config.yaml", 5, 7,  "db.url is required but missing"),
            ConfigError.at("config.yaml", 6, 18, "db.maxConnections expected integer, got string \"ten\"")
        );

        ConfigValidationException ex = new ConfigValidationException("config.yaml", errors);

        assertThat(ex.getMessage())
            .contains("2 problem(s) in config.yaml")
            .contains("1. config.yaml:5:7")
            .contains("db.url is required but missing")
            .contains("2. config.yaml:6:18")
            .contains("db.maxConnections expected integer");
    }

    @Test
    void single_error_reports_singular_problem_count() {
        List<ConfigError> errors = List.of(
            ConfigError.at("c.yaml", 1, 1, "boom")
        );
        assertThatThrownBy(() -> { throw new ConfigValidationException("c.yaml", errors); })
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("1 problem(s) in c.yaml");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl tiko-config test -Dtest=ConfigValidationExceptionTest`
Expected: FAIL — `ConfigError` and `ConfigValidationException` don't exist yet.

- [ ] **Step 3: Create `ConfigError`**

```java
// tiko-config/src/main/java/io/tiko/config/internal/ConfigError.java
package io.tiko.config.internal;

/**
 * One accumulated validation failure. The {@code source}/{@code line}/{@code column}
 * fields anchor the failure to a YAML location for error reporting.
 */
public record ConfigError(String source, int line, int column, String message) {

    public static ConfigError at(String source, int line, int column, String message) {
        return new ConfigError(source, line, column, message);
    }

    public static ConfigError unanchored(String message) {
        return new ConfigError(null, -1, -1, message);
    }
}
```

- [ ] **Step 4: Create `ErrorReporter`**

```java
// tiko-config/src/main/java/io/tiko/config/internal/ErrorReporter.java
package io.tiko.config.internal;

import java.util.List;

/** Formats a list of {@link ConfigError}s into the user-facing report. */
public final class ErrorReporter {

    private ErrorReporter() {}

    public static String format(String source, List<ConfigError> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append(errors.size()).append(" problem(s) in ").append(source).append(":\n");
        int idx = 1;
        for (ConfigError e : errors) {
            sb.append("\n  ").append(idx++).append(". ");
            if (e.line() > 0) {
                sb.append(e.source()).append(":").append(e.line()).append(":").append(e.column()).append("  ");
            }
            sb.append(e.message()).append("\n");
        }
        return sb.toString();
    }
}
```

- [ ] **Step 5: Create `ConfigValidationException`**

```java
// tiko-config/src/main/java/io/tiko/config/ConfigValidationException.java
package io.tiko.config;

import io.tiko.config.internal.ConfigError;
import io.tiko.config.internal.ErrorReporter;

import java.util.List;

/**
 * Thrown once at container startup if the loaded configuration fails validation.
 * The exception message is the entire numbered report — anchored to YAML
 * file:line:col where applicable.
 */
public final class ConfigValidationException extends RuntimeException {

    private final transient List<ConfigError> errors;

    public ConfigValidationException(String source, List<ConfigError> errors) {
        super(ErrorReporter.format(source, errors));
        this.errors = List.copyOf(errors);
    }

    /** The raw error list, in the order they were accumulated. */
    public List<ConfigError> errors() {
        return errors;
    }
}
```

- [ ] **Step 6: Create `ConfigBinder<T>` interface**

```java
// tiko-config/src/main/java/io/tiko/config/ConfigBinder.java
package io.tiko.config;

import java.util.Map;

/**
 * One per {@code @Configuration} record. Implementations are generated by
 * {@code tiko-processor} at compile time — users do not write these.
 */
public interface ConfigBinder<T> {
    /** The Java record type this binder produces. */
    Class<T> type();

    /** The YAML root prefix this binder consumes (matches {@code @Configuration#prefix}). */
    String prefix();

    /**
     * Walks {@code root} at {@link #prefix()}, binds, and returns the constructed
     * record. Errors are accumulated into {@code ctx} rather than thrown.
     */
    T bind(Map<String, Object> root, BindContext ctx);
}
```

- [ ] **Step 7: Create `BindContext` skeleton (methods filled in Task 6)**

```java
// tiko-config/src/main/java/io/tiko/config/BindContext.java
package io.tiko.config;

import io.tiko.config.internal.ConfigError;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-bind-call accumulator. Generated binders call into this for required-field
 * resolution, default substitution, type coercion, and unknown-key checking.
 * Errors reported here are aggregated and thrown in one batch at end of validation.
 */
public final class BindContext {

    private final String source;
    private final List<ConfigError> errors = new ArrayList<>();

    public BindContext(String source) {
        this.source = source;
    }

    /** Reports an error against a known YAML location. */
    public void reportAt(int line, int column, String message) {
        errors.add(ConfigError.at(source, line, column, message));
    }

    /** Reports an error with no specific location. */
    public void report(String message) {
        errors.add(ConfigError.unanchored(message));
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<ConfigError> errors() {
        return List.copyOf(errors);
    }

    public String source() {
        return source;
    }
}
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `mvn -pl tiko-config test -Dtest=ConfigValidationExceptionTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add tiko-config/src/main/java/io/tiko/config/ConfigBinder.java \
        tiko-config/src/main/java/io/tiko/config/BindContext.java \
        tiko-config/src/main/java/io/tiko/config/ConfigValidationException.java \
        tiko-config/src/main/java/io/tiko/config/internal/ConfigError.java \
        tiko-config/src/main/java/io/tiko/config/internal/ErrorReporter.java \
        tiko-config/src/test/java/io/tiko/config/ConfigValidationExceptionTest.java
git commit -m "feat(config): add ConfigBinder/BindContext core types and error model"
```

---

### Task 5: TypeCoercer infrastructure + bundled coercers

**Files:**
- Create: `tiko-config/src/main/java/io/tiko/config/internal/coercers/TypeCoercer.java`
- Create: `tiko-config/src/main/java/io/tiko/config/internal/coercers/TypeCoercerRegistry.java`
- Create: `tiko-config/src/main/java/io/tiko/config/internal/coercers/Coercers.java` (one file holding all bundled scalar coercers as static factories — keeps related logic together).
- Test: `tiko-config/src/test/java/io/tiko/config/internal/coercers/CoercersTest.java`
- Test: `tiko-config/src/test/java/io/tiko/config/internal/coercers/TypeCoercerRegistryTest.java`

The registry is package-private. Generated binders call it via `BindContext` (which holds a registry instance). Public exposure as a `TypeCoercer<T>` SPI is a future-additive change — for v1, the registry is closed.

The coercion contract: input is a YAML scalar (a `String`, `Integer`, `Long`, `Double`, `Boolean`, etc. — whatever SnakeYAML produced) plus a source location. Output is a value of the target type, or a thrown `CoercionException` (a package-private throwable carrying a message that the binder converts into a `ConfigError`).

- [ ] **Step 1: Write the failing coercion-roundtrip test**

```java
// tiko-config/src/test/java/io/tiko/config/internal/coercers/CoercersTest.java
package io.tiko.config.internal.coercers;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoercersTest {

    @Test
    void int_coercer_parses_yaml_integer() {
        assertThat(Coercers.intCoercer().coerce(42)).isEqualTo(42);
        assertThat(Coercers.intCoercer().coerce("42")).isEqualTo(42);
    }

    @Test
    void int_coercer_rejects_non_integer_string() {
        assertThatThrownBy(() -> Coercers.intCoercer().coerce("ten"))
            .isInstanceOf(CoercionException.class)
            .hasMessageContaining("expected integer");
    }

    @Test
    void long_coercer_handles_yaml_long_and_string() {
        assertThat(Coercers.longCoercer().coerce(123L)).isEqualTo(123L);
        assertThat(Coercers.longCoercer().coerce("123")).isEqualTo(123L);
    }

    @Test
    void boolean_coercer_handles_yaml_boolean_and_string() {
        assertThat(Coercers.booleanCoercer().coerce(Boolean.TRUE)).isTrue();
        assertThat(Coercers.booleanCoercer().coerce("true")).isTrue();
        assertThat(Coercers.booleanCoercer().coerce("FALSE")).isFalse();
    }

    @Test
    void double_coercer_parses_yaml_number_or_string() {
        assertThat(Coercers.doubleCoercer().coerce(1.5)).isEqualTo(1.5);
        assertThat(Coercers.doubleCoercer().coerce("2.5")).isEqualTo(2.5);
    }

    @Test
    void duration_coercer_parses_iso8601() {
        assertThat(Coercers.durationCoercer().coerce("PT30S")).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void instant_coercer_parses_iso8601() {
        assertThat(Coercers.instantCoercer().coerce("2026-05-04T12:00:00Z"))
            .isEqualTo(Instant.parse("2026-05-04T12:00:00Z"));
    }

    @Test
    void local_date_coercer_parses_iso() {
        assertThat(Coercers.localDateCoercer().coerce("2026-05-04")).isEqualTo(LocalDate.of(2026, 5, 4));
    }

    @Test
    void uuid_coercer_parses_canonical_string() {
        UUID u = UUID.randomUUID();
        assertThat(Coercers.uuidCoercer().coerce(u.toString())).isEqualTo(u);
    }

    @Test
    void uri_path_charset_pattern_bigdecimal_zoneId_localDateTime_round_trip() {
        assertThat(Coercers.uriCoercer().coerce("https://example.com")).isEqualTo(URI.create("https://example.com"));
        assertThat(Coercers.pathCoercer().coerce("/tmp/foo")).isEqualTo(Path.of("/tmp/foo"));
        assertThat(Coercers.charsetCoercer().coerce("UTF-8")).isEqualTo(Charset.forName("UTF-8"));
        assertThat(Coercers.patternCoercer().coerce("[a-z]+").pattern()).isEqualTo(Pattern.compile("[a-z]+").pattern());
        assertThat(Coercers.bigDecimalCoercer().coerce("3.14")).isEqualTo(new BigDecimal("3.14"));
        assertThat(Coercers.zoneIdCoercer().coerce("Europe/Prague")).isEqualTo(ZoneId.of("Europe/Prague"));
        assertThat(Coercers.localDateTimeCoercer().coerce("2026-05-04T12:00:00"))
            .isEqualTo(LocalDateTime.parse("2026-05-04T12:00:00"));
    }

    @Test
    void enum_coercer_matches_name_case_sensitively() {
        TypeCoercer<TestKind> c = Coercers.enumCoercer(TestKind.class);
        assertThat(c.coerce("RED")).isEqualTo(TestKind.RED);
        assertThatThrownBy(() -> c.coerce("red"))
            .isInstanceOf(CoercionException.class)
            .hasMessageContaining("expected one of [RED, BLUE]");
    }

    enum TestKind { RED, BLUE }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl tiko-config test -Dtest=CoercersTest`
Expected: FAIL — none of these classes exist yet.

- [ ] **Step 3: Create `TypeCoercer` and `CoercionException`**

```java
// tiko-config/src/main/java/io/tiko/config/internal/coercers/TypeCoercer.java
package io.tiko.config.internal.coercers;

/**
 * Strategy for coercing a YAML scalar (or composite) into a target Java type.
 * Implementations throw {@link CoercionException} on failure; generated binders
 * catch and convert into accumulated {@link io.tiko.config.internal.ConfigError}s.
 */
public interface TypeCoercer<T> {
    T coerce(Object yamlValue);
}
```

```java
// tiko-config/src/main/java/io/tiko/config/internal/coercers/CoercionException.java
package io.tiko.config.internal.coercers;

/** Package-private failure signal used by coercers; surfaces as ConfigError to users. */
public final class CoercionException extends RuntimeException {
    public CoercionException(String message) { super(message); }
}
```

- [ ] **Step 4: Create `Coercers` static-factory hub**

```java
// tiko-config/src/main/java/io/tiko/config/internal/coercers/Coercers.java
package io.tiko.config.internal.coercers;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Bundled coercer factories. Each method returns a {@link TypeCoercer} for one
 * supported scalar / leaf type. Collections, optionals, and nested records are
 * handled by composite coercers in {@link CompositeCoercers}.
 */
public final class Coercers {

    private Coercers() {}

    public static TypeCoercer<Integer> intCoercer() {
        return v -> {
            if (v instanceof Integer i) return i;
            if (v instanceof Long l) return Math.toIntExact(l);
            if (v instanceof String s) try { return Integer.parseInt(s.trim()); }
                catch (NumberFormatException e) { throw new CoercionException("expected integer, got string \"" + s + "\""); }
            throw new CoercionException("expected integer, got " + describe(v));
        };
    }

    public static TypeCoercer<Long> longCoercer() {
        return v -> {
            if (v instanceof Long l) return l;
            if (v instanceof Integer i) return i.longValue();
            if (v instanceof String s) try { return Long.parseLong(s.trim()); }
                catch (NumberFormatException e) { throw new CoercionException("expected long, got string \"" + s + "\""); }
            throw new CoercionException("expected long, got " + describe(v));
        };
    }

    public static TypeCoercer<Boolean> booleanCoercer() {
        return v -> {
            if (v instanceof Boolean b) return b;
            if (v instanceof String s) {
                String t = s.trim().toLowerCase();
                if (t.equals("true")) return Boolean.TRUE;
                if (t.equals("false")) return Boolean.FALSE;
                throw new CoercionException("expected boolean, got string \"" + s + "\"");
            }
            throw new CoercionException("expected boolean, got " + describe(v));
        };
    }

    public static TypeCoercer<Double> doubleCoercer() {
        return v -> {
            if (v instanceof Double d) return d;
            if (v instanceof Number n) return n.doubleValue();
            if (v instanceof String s) try { return Double.parseDouble(s.trim()); }
                catch (NumberFormatException e) { throw new CoercionException("expected double, got string \"" + s + "\""); }
            throw new CoercionException("expected double, got " + describe(v));
        };
    }

    public static TypeCoercer<Float> floatCoercer() {
        return v -> {
            if (v instanceof Float f) return f;
            if (v instanceof Number n) return n.floatValue();
            if (v instanceof String s) try { return Float.parseFloat(s.trim()); }
                catch (NumberFormatException e) { throw new CoercionException("expected float, got string \"" + s + "\""); }
            throw new CoercionException("expected float, got " + describe(v));
        };
    }

    public static TypeCoercer<Short> shortCoercer() {
        return v -> {
            int i = intCoercer().coerce(v);
            if (i < Short.MIN_VALUE || i > Short.MAX_VALUE) throw new CoercionException("value " + i + " out of short range");
            return (short) i;
        };
    }

    public static TypeCoercer<Byte> byteCoercer() {
        return v -> {
            int i = intCoercer().coerce(v);
            if (i < Byte.MIN_VALUE || i > Byte.MAX_VALUE) throw new CoercionException("value " + i + " out of byte range");
            return (byte) i;
        };
    }

    public static TypeCoercer<Character> charCoercer() {
        return v -> {
            if (v instanceof Character c) return c;
            if (v instanceof String s && s.length() == 1) return s.charAt(0);
            throw new CoercionException("expected single character, got " + describe(v));
        };
    }

    public static TypeCoercer<String> stringCoercer() {
        return v -> v == null ? null : v.toString();
    }

    public static TypeCoercer<Duration>      durationCoercer()      { return parsing("duration",      Duration::parse); }
    public static TypeCoercer<Instant>       instantCoercer()       { return parsing("instant",       Instant::parse); }
    public static TypeCoercer<LocalDate>     localDateCoercer()     { return parsing("local date",    LocalDate::parse); }
    public static TypeCoercer<LocalDateTime> localDateTimeCoercer() { return parsing("local datetime",LocalDateTime::parse); }
    public static TypeCoercer<ZoneId>        zoneIdCoercer()        { return parsing("zone id",       ZoneId::of); }
    public static TypeCoercer<UUID>          uuidCoercer()          { return parsing("UUID",          UUID::fromString); }
    public static TypeCoercer<URI>           uriCoercer()           { return parsing("URI",           URI::create); }
    public static TypeCoercer<Path>          pathCoercer()          { return parsing("path",          Path::of); }
    public static TypeCoercer<Charset>       charsetCoercer()       { return parsing("charset",       Charset::forName); }
    public static TypeCoercer<BigDecimal>    bigDecimalCoercer()    { return parsing("decimal",       BigDecimal::new); }
    public static TypeCoercer<Pattern>       patternCoercer()       { return parsing("pattern",       Pattern::compile); }

    public static <E extends Enum<E>> TypeCoercer<E> enumCoercer(Class<E> type) {
        return v -> {
            String s = stringCoercer().coerce(v);
            try { return Enum.valueOf(type, s); }
            catch (IllegalArgumentException e) {
                StringBuilder names = new StringBuilder();
                E[] constants = type.getEnumConstants();
                for (int i = 0; i < constants.length; i++) {
                    if (i > 0) names.append(", ");
                    names.append(constants[i].name());
                }
                throw new CoercionException("expected one of [" + names + "], got \"" + s + "\"");
            }
        };
    }

    private static <T> TypeCoercer<T> parsing(String label, java.util.function.Function<String, T> parser) {
        return v -> {
            String s = stringCoercer().coerce(v);
            try { return parser.apply(s); }
            catch (RuntimeException e) { throw new CoercionException("expected " + label + ", got \"" + s + "\""); }
        };
    }

    private static String describe(Object v) {
        if (v == null) return "null";
        return v.getClass().getSimpleName();
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -pl tiko-config test -Dtest=CoercersTest`
Expected: PASS.

- [ ] **Step 6: Write the failing composite-coercer test (List, Map, Optional, nested records)**

```java
// tiko-config/src/test/java/io/tiko/config/internal/coercers/CompositeCoercersTest.java
package io.tiko.config.internal.coercers;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositeCoercersTest {

    @Test
    void list_coercer_delegates_to_element_coercer() {
        TypeCoercer<List<Integer>> c = CompositeCoercers.list(Coercers.intCoercer());
        assertThat(c.coerce(List.of(1, 2, "3"))).containsExactly(1, 2, 3);
    }

    @Test
    void list_coercer_rejects_non_list_input() {
        TypeCoercer<List<Integer>> c = CompositeCoercers.list(Coercers.intCoercer());
        assertThatThrownBy(() -> c.coerce("not a list")).hasMessageContaining("expected list");
    }

    @Test
    void map_coercer_delegates_to_value_coercer() {
        TypeCoercer<Map<String, Integer>> c = CompositeCoercers.map(Coercers.intCoercer());
        assertThat(c.coerce(Map.of("a", 1, "b", "2"))).containsEntry("a", 1).containsEntry("b", 2);
    }

    @Test
    void optional_coercer_wraps_present_value() {
        TypeCoercer<Optional<Integer>> c = CompositeCoercers.optional(Coercers.intCoercer());
        assertThat(c.coerce(42)).contains(42);
        assertThat(c.coerce(null)).isEmpty();
    }
}
```

- [ ] **Step 7: Run the failing test**

Run: `mvn -pl tiko-config test -Dtest=CompositeCoercersTest`
Expected: FAIL.

- [ ] **Step 8: Create `CompositeCoercers`**

```java
// tiko-config/src/main/java/io/tiko/config/internal/coercers/CompositeCoercers.java
package io.tiko.config.internal.coercers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Coercers for {@code List<X>}, {@code Map<String,X>}, {@code Optional<X>}. */
public final class CompositeCoercers {

    private CompositeCoercers() {}

    public static <X> TypeCoercer<List<X>> list(TypeCoercer<X> elementCoercer) {
        return v -> {
            if (!(v instanceof List<?> raw)) {
                throw new CoercionException("expected list, got " + (v == null ? "null" : v.getClass().getSimpleName()));
            }
            List<X> out = new ArrayList<>(raw.size());
            for (Object e : raw) out.add(elementCoercer.coerce(e));
            return List.copyOf(out);
        };
    }

    public static <X> TypeCoercer<Map<String, X>> map(TypeCoercer<X> valueCoercer) {
        return v -> {
            if (!(v instanceof Map<?, ?> raw)) {
                throw new CoercionException("expected map, got " + (v == null ? "null" : v.getClass().getSimpleName()));
            }
            Map<String, X> out = new LinkedHashMap<>(raw.size());
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                out.put(entry.getKey().toString(), valueCoercer.coerce(entry.getValue()));
            }
            return Map.copyOf(out);
        };
    }

    public static <X> TypeCoercer<Optional<X>> optional(TypeCoercer<X> innerCoercer) {
        return v -> v == null ? Optional.empty() : Optional.of(innerCoercer.coerce(v));
    }
}
```

- [ ] **Step 9: Run the composite test to verify it passes**

Run: `mvn -pl tiko-config test -Dtest=CompositeCoercersTest`
Expected: PASS.

- [ ] **Step 10: Create `TypeCoercerRegistry`**

The registry maps `Class<?>` keys to coercers for the *bundled* types only. Generated binders look up coercers via this registry. `List<X>` / `Map<String,X>` / `Optional<X>` / nested records are not registered here — the binder generator emits direct calls to `CompositeCoercers.list(...)` etc., parameterised with the appropriate element coercer.

```java
// tiko-config/src/main/java/io/tiko/config/internal/coercers/TypeCoercerRegistry.java
package io.tiko.config.internal.coercers;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Internal closed registry of bundled scalar coercers. Keyed by the boxed
 * representation; primitive types route to their boxed equivalents.
 *
 * <p>Designed so a future public {@code TypeCoercer<T>} SPI is purely additive
 * (e.g. add a {@code register(Class<T>, TypeCoercer<T>)} method without
 * changing existing call sites).</p>
 */
public final class TypeCoercerRegistry {

    private static final Map<Class<?>, TypeCoercer<?>> BUNDLED = new LinkedHashMap<>();

    static {
        BUNDLED.put(Integer.class,       Coercers.intCoercer());
        BUNDLED.put(Long.class,          Coercers.longCoercer());
        BUNDLED.put(Boolean.class,       Coercers.booleanCoercer());
        BUNDLED.put(Double.class,        Coercers.doubleCoercer());
        BUNDLED.put(Float.class,         Coercers.floatCoercer());
        BUNDLED.put(Short.class,         Coercers.shortCoercer());
        BUNDLED.put(Byte.class,          Coercers.byteCoercer());
        BUNDLED.put(Character.class,     Coercers.charCoercer());
        BUNDLED.put(String.class,        Coercers.stringCoercer());
        BUNDLED.put(Duration.class,      Coercers.durationCoercer());
        BUNDLED.put(Instant.class,       Coercers.instantCoercer());
        BUNDLED.put(LocalDate.class,     Coercers.localDateCoercer());
        BUNDLED.put(LocalDateTime.class, Coercers.localDateTimeCoercer());
        BUNDLED.put(ZoneId.class,        Coercers.zoneIdCoercer());
        BUNDLED.put(UUID.class,          Coercers.uuidCoercer());
        BUNDLED.put(URI.class,           Coercers.uriCoercer());
        BUNDLED.put(Path.class,          Coercers.pathCoercer());
        BUNDLED.put(Charset.class,       Coercers.charsetCoercer());
        BUNDLED.put(BigDecimal.class,    Coercers.bigDecimalCoercer());
        BUNDLED.put(Pattern.class,       Coercers.patternCoercer());
    }

    private TypeCoercerRegistry() {}

    @SuppressWarnings("unchecked")
    public static <T> TypeCoercer<T> get(Class<T> type) {
        if (type.isEnum()) {
            @SuppressWarnings({"rawtypes","unchecked"})
            TypeCoercer<T> c = (TypeCoercer<T>) Coercers.enumCoercer((Class<? extends Enum>) type.asSubclass(Enum.class));
            return c;
        }
        Class<?> boxed = boxed(type);
        TypeCoercer<?> c = BUNDLED.get(boxed);
        if (c == null) throw new IllegalArgumentException("No bundled coercer for " + type.getName());
        return (TypeCoercer<T>) c;
    }

    public static boolean isSupported(Class<?> type) {
        return type.isEnum() || BUNDLED.containsKey(boxed(type));
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class)     return Integer.class;
        if (type == long.class)    return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class)  return Double.class;
        if (type == float.class)   return Float.class;
        if (type == short.class)   return Short.class;
        if (type == byte.class)    return Byte.class;
        if (type == char.class)    return Character.class;
        throw new IllegalArgumentException("Unboxable primitive: " + type);
    }
}
```

- [ ] **Step 11: Add registry test**

```java
// tiko-config/src/test/java/io/tiko/config/internal/coercers/TypeCoercerRegistryTest.java
package io.tiko.config.internal.coercers;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TypeCoercerRegistryTest {

    @Test
    void primitive_int_resolves_to_integer_coercer() {
        TypeCoercer<Integer> c = TypeCoercerRegistry.get(int.class);
        assertThat(c.coerce("42")).isEqualTo(42);
    }

    @Test
    void duration_resolves_and_coerces() {
        TypeCoercer<Duration> c = TypeCoercerRegistry.get(Duration.class);
        assertThat(c.coerce("PT5M")).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void unknown_type_throws_with_class_name() {
        assertThatThrownBy(() -> TypeCoercerRegistry.get(java.io.File.class))
            .hasMessageContaining("File");
    }

    @Test
    void isSupported_reports_true_for_bundled_and_enums() {
        assertThat(TypeCoercerRegistry.isSupported(int.class)).isTrue();
        assertThat(TypeCoercerRegistry.isSupported(java.io.File.class)).isFalse();
        assertThat(TypeCoercerRegistry.isSupported(java.time.DayOfWeek.class)).isTrue();
    }
}
```

- [ ] **Step 12: Run the registry test**

Run: `mvn -pl tiko-config test -Dtest=TypeCoercerRegistryTest`
Expected: PASS.

- [ ] **Step 13: Commit**

```bash
git add tiko-config/src/main/java/io/tiko/config/internal/coercers \
        tiko-config/src/test/java/io/tiko/config/internal/coercers
git commit -m "feat(config): add TypeCoercer infrastructure and bundled coercers"
```

---

### Task 6: `BindContext` field-resolution methods

**Files:**
- Modify: `tiko-config/src/main/java/io/tiko/config/BindContext.java`
- Test: `tiko-config/src/test/java/io/tiko/config/BindContextTest.java`

The skeleton from Task 4 has only error accumulation. Generated binders need higher-level methods: section navigation, required-field reads, default substitution, optional wrapping, unknown-key checks. These are the methods the codegen calls into.

- [ ] **Step 1: Write the failing test**

```java
// tiko-config/src/test/java/io/tiko/config/BindContextTest.java
package io.tiko.config;

import io.tiko.config.internal.coercers.Coercers;
import io.tiko.config.internal.coercers.TypeCoercer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BindContextTest {

    @Test
    void requireSection_returns_existing_map_or_empty_with_error() {
        BindContext ctx = new BindContext("c.yaml");
        Map<String, Object> root = Map.of("db", Map.of("url", "x"));

        Map<String, Object> db = ctx.requireSection(root, "db");
        assertThat(db).containsEntry("url", "x");
        assertThat(ctx.hasErrors()).isFalse();

        Map<String, Object> missing = ctx.requireSection(root, "kafka");
        assertThat(missing).isEmpty();
        assertThat(ctx.hasErrors()).isTrue();
    }

    @Test
    void requireScalar_uses_coercer_and_emits_error_on_absent_key() {
        BindContext ctx = new BindContext("c.yaml");
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("port", "8080");

        TypeCoercer<Integer> intC = Coercers.intCoercer();
        int port = ctx.requireScalar(node, "port", "db.port", intC, 0);
        assertThat(port).isEqualTo(8080);

        int missing = ctx.requireScalar(node, "host", "db.host", Coercers.stringCoercer().getClass() == intC.getClass() ? intC : intC, 0);
        assertThat(ctx.hasErrors()).isTrue();
        assertThat(missing).isZero();
    }

    @Test
    void scalarOrDefault_uses_default_when_absent() {
        BindContext ctx = new BindContext("c.yaml");
        Map<String, Object> node = Map.of();

        int v = ctx.scalarOrDefault(node, "max", "db.max", Coercers.intCoercer(), 10);
        assertThat(v).isEqualTo(10);
        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void optionalScalar_returns_empty_when_absent() {
        BindContext ctx = new BindContext("c.yaml");
        Optional<Integer> v = ctx.optionalScalar(Map.of(), "x", "db.x", Coercers.intCoercer());
        assertThat(v).isEmpty();
        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void checkUnknownKeys_emits_one_error_per_extra_key() {
        BindContext ctx = new BindContext("c.yaml");
        Map<String, Object> node = Map.of("url", "x", "foo", "y", "bar", "z");

        ctx.checkUnknownKeys(node, "db", Set.of("url"));
        assertThat(ctx.errors()).hasSize(2);
        assertThat(ctx.errors().get(0).message()).contains("unknown");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl tiko-config test -Dtest=BindContextTest`
Expected: FAIL — methods not yet defined.

- [ ] **Step 3: Replace `BindContext.java` with the full version**

```java
// tiko-config/src/main/java/io/tiko/config/BindContext.java
package io.tiko.config;

import io.tiko.config.internal.ConfigError;
import io.tiko.config.internal.coercers.CoercionException;
import io.tiko.config.internal.coercers.TypeCoercer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-bind-call accumulator and read helpers. Generated binders call into this
 * for required-field resolution, default substitution, type coercion, and
 * unknown-key checking. Errors are accumulated rather than thrown — the
 * caller flushes them into {@link ConfigValidationException} once at the end.
 */
public final class BindContext {

    private final String source;
    private final List<ConfigError> errors = new ArrayList<>();

    public BindContext(String source) {
        this.source = source;
    }

    // -- Error accumulation ------------------------------------------------

    public void reportAt(int line, int column, String message) {
        errors.add(ConfigError.at(source, line, column, message));
    }

    public void report(String message) {
        errors.add(ConfigError.unanchored(message));
    }

    public boolean hasErrors() { return !errors.isEmpty(); }
    public List<ConfigError> errors() { return List.copyOf(errors); }
    public String source() { return source; }

    // -- Section navigation ------------------------------------------------

    /**
     * Returns the sub-map at {@code key}, or an empty map (with an error) if absent.
     * The returned map is read-only; binders consume keys from it via {@link #pop}.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> requireSection(Map<String, Object> root, String key) {
        Object v = root.get(key);
        if (v == null) {
            report("missing required section '" + key + "'");
            return new LinkedHashMap<>();
        }
        if (!(v instanceof Map<?, ?>)) {
            report("expected section '" + key + "' to be a mapping, got " + v.getClass().getSimpleName());
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>((Map<String, Object>) v);
    }

    // -- Field reads -------------------------------------------------------

    /**
     * Reads a required scalar at {@code key} from {@code node}, applying {@code coercer}.
     * On absence, accumulates an error and returns {@code fallback}.
     * On coercion failure, accumulates an error and returns {@code fallback}.
     * The key is removed from {@code node} on success so {@link #checkUnknownKeys}
     * can detect leftovers.
     */
    public <T> T requireScalar(Map<String, Object> node, String key, String fullPath, TypeCoercer<T> coercer, T fallback) {
        if (!node.containsKey(key)) {
            report(fullPath + " is required but missing");
            return fallback;
        }
        Object raw = node.remove(key);
        try {
            return coercer.coerce(raw);
        } catch (CoercionException e) {
            report(fullPath + " " + e.getMessage());
            return fallback;
        }
    }

    /**
     * Reads {@code key} from {@code node} if present (applying {@code coercer}),
     * otherwise returns {@code defaultValue}. Coercion failure produces an error
     * and the default is returned.
     */
    public <T> T scalarOrDefault(Map<String, Object> node, String key, String fullPath, TypeCoercer<T> coercer, T defaultValue) {
        if (!node.containsKey(key)) return defaultValue;
        Object raw = node.remove(key);
        try {
            return coercer.coerce(raw);
        } catch (CoercionException e) {
            report(fullPath + " " + e.getMessage());
            return defaultValue;
        }
    }

    /**
     * Reads {@code key} as {@code Optional<T>} — present-but-coerced if the key exists,
     * empty if absent.
     */
    public <T> Optional<T> optionalScalar(Map<String, Object> node, String key, String fullPath, TypeCoercer<T> coercer) {
        if (!node.containsKey(key)) return Optional.empty();
        Object raw = node.remove(key);
        try {
            return Optional.of(coercer.coerce(raw));
        } catch (CoercionException e) {
            report(fullPath + " " + e.getMessage());
            return Optional.empty();
        }
    }

    // -- Unknown-key check -------------------------------------------------

    /**
     * Emits one error per remaining key in {@code node} after binding consumed
     * all the known fields. Generated binders pass the set of consumed keys.
     */
    public void checkUnknownKeys(Map<String, Object> node, String sectionPath, Set<String> known) {
        for (String k : node.keySet()) {
            if (!known.contains(k)) {
                report("unknown key '" + sectionPath + "." + k + "'");
            }
        }
    }
}
```

- [ ] **Step 4: Run the test**

Run: `mvn -pl tiko-config test -Dtest=BindContextTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tiko-config/src/main/java/io/tiko/config/BindContext.java \
        tiko-config/src/test/java/io/tiko/config/BindContextTest.java
git commit -m "feat(config): add BindContext field-read helpers"
```

---

### Task 7: `${VAR}` interpolator

**Files:**
- Create: `tiko-config/src/main/java/io/tiko/config/internal/Interpolator.java`
- Test: `tiko-config/src/test/java/io/tiko/config/internal/InterpolatorTest.java`

Walks a YAML tree (`Map`/`List`/scalars) and substitutes `${NAME}` and `${NAME:default}` against an env-var lookup function (injected for testability — production uses `System::getenv`). Missing non-defaulted variables become `ConfigError`s rather than thrown exceptions.

- [ ] **Step 1: Write the failing test**

```java
// tiko-config/src/test/java/io/tiko/config/internal/InterpolatorTest.java
package io.tiko.config.internal;

import io.tiko.config.BindContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class InterpolatorTest {

    Function<String, String> env(Map<String, String> entries) {
        return entries::get;
    }

    @Test
    void substitutes_present_variable() {
        Map<String, Object> tree = Map.of("url", "${DB_URL}");
        BindContext ctx = new BindContext("c.yaml");
        Map<String, Object> result = (Map<String, Object>) Interpolator.interpolate(
            tree, env(Map.of("DB_URL", "jdbc:postgres://x")), ctx);
        assertThat(result).containsEntry("url", "jdbc:postgres://x");
        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void uses_default_when_variable_absent() {
        Map<String, Object> tree = Map.of("url", "${DB_URL:jdbc:default}");
        BindContext ctx = new BindContext("c.yaml");
        Map<String, Object> result = (Map<String, Object>) Interpolator.interpolate(tree, env(Map.of()), ctx);
        assertThat(result).containsEntry("url", "jdbc:default");
        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void emits_error_when_variable_absent_and_no_default() {
        Map<String, Object> tree = Map.of("url", "${DB_URL}");
        BindContext ctx = new BindContext("c.yaml");
        Interpolator.interpolate(tree, env(Map.of()), ctx);
        assertThat(ctx.hasErrors()).isTrue();
        assertThat(ctx.errors().get(0).message()).contains("DB_URL");
    }

    @Test
    void multiple_substitutions_per_scalar() {
        Map<String, Object> tree = Map.of("conn", "${HOST}:${PORT}");
        BindContext ctx = new BindContext("c.yaml");
        Map<String, Object> result = (Map<String, Object>) Interpolator.interpolate(
            tree, env(Map.of("HOST", "h", "PORT", "9090")), ctx);
        assertThat(result).containsEntry("conn", "h:9090");
    }

    @Test
    void recurses_into_nested_maps_and_lists() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("a", "${A}");
        inner.put("b", List.of("${B}", "literal"));
        Map<String, Object> tree = Map.of("inner", inner);

        BindContext ctx = new BindContext("c.yaml");
        Map<String, Object> result = (Map<String, Object>) Interpolator.interpolate(
            tree, env(Map.of("A", "alpha", "B", "beta")), ctx);

        Map<String, Object> r = (Map<String, Object>) result.get("inner");
        assertThat(r).containsEntry("a", "alpha");
        assertThat((List<?>) r.get("b")).containsExactly("beta", "literal");
    }

    @Test
    void leaves_keys_untouched() {
        Map<String, Object> tree = Map.of("${LITERAL_KEY}", "value");
        BindContext ctx = new BindContext("c.yaml");
        Map<String, Object> result = (Map<String, Object>) Interpolator.interpolate(
            tree, env(Map.of("LITERAL_KEY", "ignored")), ctx);
        assertThat(result).containsKey("${LITERAL_KEY}");
    }
}
```

- [ ] **Step 2: Run the failing test**

Run: `mvn -pl tiko-config test -Dtest=InterpolatorTest`
Expected: FAIL.

- [ ] **Step 3: Implement `Interpolator`**

```java
// tiko-config/src/main/java/io/tiko/config/internal/Interpolator.java
package io.tiko.config.internal;

import io.tiko.config.BindContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes {@code ${VAR}} and {@code ${VAR:default}} in YAML scalar
 * <em>values</em> (not keys). Missing variables without defaults are reported
 * to the supplied {@link BindContext}.
 */
public final class Interpolator {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?\\}");

    private Interpolator() {}

    public static Object interpolate(Object node, Function<String, String> env, BindContext ctx) {
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>(m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(e.getKey().toString(), interpolate(e.getValue(), env, ctx));
            }
            return out;
        }
        if (node instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object e : l) out.add(interpolate(e, env, ctx));
            return out;
        }
        if (node instanceof String s) {
            return interpolateScalar(s, env, ctx);
        }
        return node;
    }

    private static String interpolateScalar(String s, Function<String, String> env, BindContext ctx) {
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
                    ctx.report("${" + name + "} is not set and has no default");
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

- [ ] **Step 4: Run the test**

Run: `mvn -pl tiko-config test -Dtest=InterpolatorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tiko-config/src/main/java/io/tiko/config/internal/Interpolator.java \
        tiko-config/src/test/java/io/tiko/config/internal/InterpolatorTest.java
git commit -m "feat(config): add \${VAR} interpolator with default-value syntax"
```

---

### Task 8: `YamlLoader` — SnakeYAML-backed parser

**Files:**
- Create: `tiko-config/src/main/java/io/tiko/config/internal/YamlLoader.java`
- Test: `tiko-config/src/test/java/io/tiko/config/internal/YamlLoaderTest.java`

Wraps SnakeYAML's `Yaml` class to produce a `Map<String, Object>` tree. We use SnakeYAML's `Mark` info for line/column when reporting errors elsewhere — but the loader itself just produces the tree.

- [ ] **Step 1: Write the failing test**

```java
// tiko-config/src/test/java/io/tiko/config/internal/YamlLoaderTest.java
package io.tiko.config.internal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlLoaderTest {

    @Test
    void parses_simple_mapping_to_nested_maps() {
        String yaml = """
                db:
                  url: jdbc:postgres
                  maxConnections: 10
                """;
        Map<String, Object> tree = YamlLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> db = (Map<String, Object>) tree.get("db");
        assertThat(db).containsEntry("url", "jdbc:postgres").containsEntry("maxConnections", 10);
    }

    @Test
    void parses_lists_and_nested_mappings() {
        String yaml = """
                servers:
                  - host: a
                    port: 1
                  - host: b
                    port: 2
                """;
        Map<String, Object> tree = YamlLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        List<?> servers = (List<?>) tree.get("servers");
        assertThat(servers).hasSize(2);
        assertThat((Map<?, ?>) servers.get(0)).containsEntry("host", "a");
    }

    @Test
    void empty_document_returns_empty_map() {
        Map<String, Object> tree = YamlLoader.load(new ByteArrayInputStream(new byte[0]));
        assertThat(tree).isEmpty();
    }

    @Test
    void malformed_yaml_throws_runtime_exception_with_message() {
        String yaml = "db:\n  url: [unclosed\n";
        assertThatThrownBy(() -> YamlLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))))
            .isInstanceOf(RuntimeException.class);
    }
}
```

- [ ] **Step 2: Run the failing test**

Run: `mvn -pl tiko-config test -Dtest=YamlLoaderTest`
Expected: FAIL.

- [ ] **Step 3: Implement `YamlLoader`**

```java
// tiko-config/src/main/java/io/tiko/config/internal/YamlLoader.java
package io.tiko.config.internal;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** SnakeYAML-backed loader that produces a {@code Map<String, Object>} tree. */
public final class YamlLoader {

    private YamlLoader() {}

    public static Map<String, Object> load(InputStream input) {
        LoaderOptions opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(opts);
        Object loaded = yaml.load(input);
        if (loaded == null) return new LinkedHashMap<>();
        if (loaded instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> stringKeyed = stringKeyed((Map<Object, Object>) m);
            return stringKeyed;
        }
        throw new IllegalArgumentException("YAML root must be a mapping; got " + loaded.getClass().getSimpleName());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringKeyed(Map<Object, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>(in.size());
        for (Map.Entry<Object, Object> e : in.entrySet()) {
            String k = e.getKey().toString();
            Object v = e.getValue();
            if (v instanceof Map<?, ?> nested) v = stringKeyed((Map<Object, Object>) nested);
            out.put(k, v);
        }
        return out;
    }
}
```

- [ ] **Step 4: Run the test**

Run: `mvn -pl tiko-config test -Dtest=YamlLoaderTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tiko-config/src/main/java/io/tiko/config/internal/YamlLoader.java \
        tiko-config/src/test/java/io/tiko/config/internal/YamlLoaderTest.java
git commit -m "feat(config): add SnakeYAML-backed YamlLoader"
```

---

### Task 9: `ConfigSources` factory — `fromMap`, `classpath`, `file`, `layered` (with deep merge)

**Files:**
- Create: `tiko-config/src/main/java/io/tiko/config/ConfigSources.java`
- Create: `tiko-config/src/main/java/io/tiko/config/internal/DeepMerge.java`
- Test: `tiko-config/src/test/java/io/tiko/config/ConfigSourcesTest.java`
- Test: `tiko-config/src/test/java/io/tiko/config/internal/DeepMergeTest.java`

Layered semantics: maps merge recursively, lists are atomic (replaced not appended), scalars overwrite. This matches Spring/Quarkus/Micronaut convention.

- [ ] **Step 1: Write the failing deep-merge test**

```java
// tiko-config/src/test/java/io/tiko/config/internal/DeepMergeTest.java
package io.tiko.config.internal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeepMergeTest {

    @Test
    void scalar_overwrite_last_wins() {
        Map<String, Object> result = DeepMerge.merge(
            Map.of("a", 1),
            Map.of("a", 2));
        assertThat(result).containsEntry("a", 2);
    }

    @Test
    void nested_maps_merge_recursively() {
        Map<String, Object> a = Map.of("db", Map.of("url", "x", "max", 10));
        Map<String, Object> b = Map.of("db", Map.of("max", 20));
        Map<String, Object> result = DeepMerge.merge(a, b);
        Map<String, Object> db = (Map<String, Object>) result.get("db");
        assertThat(db).containsEntry("url", "x").containsEntry("max", 20);
    }

    @Test
    void lists_replace_not_append() {
        Map<String, Object> a = Map.of("xs", List.of(1, 2));
        Map<String, Object> b = Map.of("xs", List.of(3));
        Map<String, Object> result = DeepMerge.merge(a, b);
        assertThat(result.get("xs")).isEqualTo(List.of(3));
    }

    @Test
    void chained_layers_compose_left_to_right() {
        Map<String, Object> base   = Map.of("a", 1, "b", 1);
        Map<String, Object> mid    = Map.of("b", 2, "c", 2);
        Map<String, Object> top    = Map.of("c", 3);
        Map<String, Object> result = DeepMerge.merge(DeepMerge.merge(base, mid), top);
        assertThat(result).containsEntry("a", 1).containsEntry("b", 2).containsEntry("c", 3);
    }
}
```

- [ ] **Step 2: Run failing test**

Run: `mvn -pl tiko-config test -Dtest=DeepMergeTest`
Expected: FAIL.

- [ ] **Step 3: Implement `DeepMerge`**

```java
// tiko-config/src/main/java/io/tiko/config/internal/DeepMerge.java
package io.tiko.config.internal;

import java.util.LinkedHashMap;
import java.util.Map;

/** Recursive map merge with last-wins for scalars and atomic replacement for lists. */
public final class DeepMerge {

    private DeepMerge() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> e : overlay.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            Object existing = out.get(k);
            if (existing instanceof Map<?, ?> em && v instanceof Map<?, ?> nm) {
                out.put(k, merge((Map<String, Object>) em, (Map<String, Object>) nm));
            } else {
                out.put(k, v);
            }
        }
        return out;
    }
}
```

- [ ] **Step 4: Verify deep-merge passes**

Run: `mvn -pl tiko-config test -Dtest=DeepMergeTest`
Expected: PASS.

- [ ] **Step 5: Write the failing `ConfigSources` test**

```java
// tiko-config/src/test/java/io/tiko/config/ConfigSourcesTest.java
package io.tiko.config;

import io.tiko.ConfigSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigSourcesTest {

    @Test
    void fromMap_returns_supplied_tree() {
        Map<String, Object> data = Map.of("db", Map.of("url", "x"));
        ConfigSource src = ConfigSources.fromMap(data);
        assertThat(src.load()).isEqualTo(data);
    }

    @Test
    void classpath_loads_yaml_resource(@TempDir Path tmp) throws IOException {
        // Use file() for the classpath test substitute since classpath fixtures
        // are awkward in surefire — we cover classpath() resolution at integration test time.
        Path yaml = tmp.resolve("c.yaml");
        Files.writeString(yaml, "db:\n  url: jdbc:test\n");

        ConfigSource src = ConfigSources.file(yaml);
        Map<String, Object> root = src.load();
        Map<String, Object> db = (Map<String, Object>) root.get("db");
        assertThat(db).containsEntry("url", "jdbc:test");
    }

    @Test
    void file_throws_when_missing() {
        assertThatThrownBy(() -> ConfigSources.file(Path.of("/no/such/file")).load())
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("/no/such/file");
    }

    @Test
    void layered_deep_merges_in_order() {
        ConfigSource base    = ConfigSources.fromMap(Map.of("a", 1, "b", Map.of("x", 1)));
        ConfigSource overlay = ConfigSources.fromMap(Map.of("b", Map.of("y", 2)));
        Map<String, Object> result = ConfigSources.layered(base, overlay).load();
        assertThat(result).containsEntry("a", 1);
        Map<String, Object> b = (Map<String, Object>) result.get("b");
        assertThat(b).containsEntry("x", 1).containsEntry("y", 2);
    }

    @Test
    void classpath_loads_real_resource() {
        // A test resource will be added to src/test/resources to validate this path
        // (this is set up in step 7 below).
        ConfigSource src = ConfigSources.classpath("test-config.yaml");
        Map<String, Object> root = src.load();
        assertThat(root).containsKey("db");
    }
}
```

- [ ] **Step 6: Run the failing test**

Run: `mvn -pl tiko-config test -Dtest=ConfigSourcesTest`
Expected: FAIL.

- [ ] **Step 7: Add the test resource**

Create `tiko-config/src/test/resources/test-config.yaml`:

```yaml
db:
  url: jdbc:test
  maxConnections: 5
```

- [ ] **Step 8: Implement `ConfigSources`**

```java
// tiko-config/src/main/java/io/tiko/config/ConfigSources.java
package io.tiko.config;

import io.tiko.ConfigSource;
import io.tiko.config.internal.DeepMerge;
import io.tiko.config.internal.YamlLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bundled {@link ConfigSource} factories. */
public final class ConfigSources {

    private ConfigSources() {}

    /** YAML resource loaded via the thread context classloader. */
    public static ConfigSource classpath(String resourcePath) {
        return () -> {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = ConfigSources.class.getClassLoader();
            try (InputStream in = cl.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    throw new RuntimeException("classpath resource not found: " + resourcePath);
                }
                return YamlLoader.load(in);
            } catch (IOException e) {
                throw new RuntimeException("failed to read " + resourcePath, e);
            }
        };
    }

    /** YAML file from the filesystem. */
    public static ConfigSource file(Path path) {
        return () -> {
            try (InputStream in = Files.newInputStream(path)) {
                return YamlLoader.load(in);
            } catch (IOException e) {
                throw new RuntimeException("failed to read " + path, e);
            }
        };
    }

    /** In-memory source — invaluable for tests. */
    public static ConfigSource fromMap(Map<String, Object> data) {
        Map<String, Object> snapshot = new LinkedHashMap<>(data);
        return () -> snapshot;
    }

    /**
     * Layered source. Each subsequent source overrides earlier ones using
     * deep-merge (maps merge recursively, lists replace atomically, scalars overwrite).
     */
    public static ConfigSource layered(ConfigSource... sources) {
        if (sources.length == 0) throw new IllegalArgumentException("layered requires at least one source");
        return () -> {
            Map<String, Object> result = sources[0].load();
            for (int i = 1; i < sources.length; i++) {
                result = DeepMerge.merge(result, sources[i].load());
            }
            return result;
        };
    }
}
```

- [ ] **Step 9: Run the test**

Run: `mvn -pl tiko-config test -Dtest=ConfigSourcesTest`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add tiko-config/src/main/java/io/tiko/config/ConfigSources.java \
        tiko-config/src/main/java/io/tiko/config/internal/DeepMerge.java \
        tiko-config/src/test/java/io/tiko/config/ConfigSourcesTest.java \
        tiko-config/src/test/java/io/tiko/config/internal/DeepMergeTest.java \
        tiko-config/src/test/resources/test-config.yaml
git commit -m "feat(config): add ConfigSources factories with deep-merge layered sources"
```

---

### Task 9.5: End-to-end runtime check via a hand-written binder

**Files:**
- Test: `tiko-config/src/test/java/io/tiko/config/HandWrittenBinderEndToEndTest.java`

Before any codegen exists, we prove the runtime stack works by writing a binder by hand. This catches integration bugs in the bind/coerce/error/interpolation pipeline before Phase C lands.

- [ ] **Step 1: Write the test**

```java
// tiko-config/src/test/java/io/tiko/config/HandWrittenBinderEndToEndTest.java
package io.tiko.config;

import io.tiko.ConfigSource;
import io.tiko.config.internal.Interpolator;
import io.tiko.config.internal.coercers.Coercers;
import io.tiko.config.internal.coercers.CompositeCoercers;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HandWrittenBinderEndToEndTest {

    record DbConfig(String url, int maxConnections, Optional<Duration> connectTimeout) {}

    static class DbConfigBinder implements ConfigBinder<DbConfig> {
        public Class<DbConfig> type() { return DbConfig.class; }
        public String prefix() { return "db"; }
        public DbConfig bind(Map<String, Object> root, BindContext ctx) {
            Map<String, Object> node = ctx.requireSection(root, "db");
            String url = ctx.requireScalar(node, "url", "db.url", Coercers.stringCoercer(), "");
            int max = ctx.scalarOrDefault(node, "maxConnections", "db.maxConnections", Coercers.intCoercer(), 10);
            Optional<Duration> timeout = ctx.optionalScalar(node, "connectTimeout", "db.connectTimeout", Coercers.durationCoercer());
            ctx.checkUnknownKeys(node, "db", Set.of("url", "maxConnections", "connectTimeout"));
            return new DbConfig(url, max, timeout);
        }
    }

    @Test
    void happy_path_binds_record_from_map_source() {
        ConfigSource src = ConfigSources.fromMap(Map.of(
            "db", Map.of("url", "jdbc:postgres", "maxConnections", 20, "connectTimeout", "PT10S")));
        BindContext ctx = new BindContext("test");
        DbConfig cfg = new DbConfigBinder().bind(src.load(), ctx);

        assertThat(ctx.hasErrors()).isFalse();
        assertThat(cfg.url()).isEqualTo("jdbc:postgres");
        assertThat(cfg.maxConnections()).isEqualTo(20);
        assertThat(cfg.connectTimeout()).contains(Duration.ofSeconds(10));
    }

    @Test
    void default_substituted_when_absent() {
        ConfigSource src = ConfigSources.fromMap(Map.of("db", Map.of("url", "x")));
        BindContext ctx = new BindContext("test");
        DbConfig cfg = new DbConfigBinder().bind(src.load(), ctx);
        assertThat(cfg.maxConnections()).isEqualTo(10);
        assertThat(cfg.connectTimeout()).isEmpty();
        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void missing_required_field_accumulates_error() {
        ConfigSource src = ConfigSources.fromMap(Map.of("db", Map.of()));
        BindContext ctx = new BindContext("test");
        new DbConfigBinder().bind(src.load(), ctx);
        assertThat(ctx.hasErrors()).isTrue();
        assertThat(ctx.errors().get(0).message()).contains("db.url is required");
    }

    @Test
    void unknown_key_accumulates_error() {
        ConfigSource src = ConfigSources.fromMap(Map.of("db", Map.of("url", "x", "foo", "bar")));
        BindContext ctx = new BindContext("test");
        new DbConfigBinder().bind(src.load(), ctx);
        assertThat(ctx.errors().stream().anyMatch(e -> e.message().contains("unknown key 'db.foo'"))).isTrue();
    }

    @Test
    void interpolation_substitutes_env_var_before_binding() {
        Map<String, Object> raw = Map.of("db", Map.of("url", "${DB_URL:jdbc:default}"));
        BindContext ctx = new BindContext("test");
        Map<String, Object> after = (Map<String, Object>) Interpolator.interpolate(raw, k -> null, ctx);

        DbConfig cfg = new DbConfigBinder().bind(after, ctx);
        assertThat(cfg.url()).isEqualTo("jdbc:default");
        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void final_exception_carries_full_report() {
        ConfigSource src = ConfigSources.fromMap(Map.of("db", Map.of("maxConnections", "ten", "junk", true)));
        BindContext ctx = new BindContext("test");
        new DbConfigBinder().bind(src.load(), ctx);
        assertThatThrownBy(() -> { throw new ConfigValidationException(ctx.source(), ctx.errors()); })
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("db.url is required")
            .hasMessageContaining("db.maxConnections expected integer")
            .hasMessageContaining("unknown key 'db.junk'");
    }
}
```

- [ ] **Step 2: Run the end-to-end test**

Run: `mvn -pl tiko-config test -Dtest=HandWrittenBinderEndToEndTest`
Expected: PASS.

- [ ] **Step 3: Run the entire `tiko-config` test surface**

Run: `mvn -pl tiko-config test`
Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add tiko-config/src/test/java/io/tiko/config/HandWrittenBinderEndToEndTest.java
git commit -m "test(config): hand-written binder end-to-end test"
```

---

## Phase C — Processor extensions

### Task 10: Wire `@Configuration` collection into `TikoAnnotationProcessor`

**Files:**
- Modify: `tiko-processor/pom.xml` — add `tiko-config` at scope `provided` (compile-only).
- Modify: `tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java` — add `Configuration.class` to supported annotations, call collector + validator + generators in the existing process pipeline.
- Create: `tiko-processor/src/main/java/io/tiko/processor/config/ConfigurationModel.java`
- Create: `tiko-processor/src/main/java/io/tiko/processor/config/ConfigFieldModel.java`
- Create: `tiko-processor/src/main/java/io/tiko/processor/config/ConfigSupportedTypes.java`

`tiko-config` at `provided` scope means: at processor compile/run time it's on the classpath (so the processor can call `TypeCoercerRegistry.isSupported(...)` to validate types and `Coercers.intCoercer().coerce(...)` to validate `@Default` values). At user-runtime it is *not* leaked — `<annotationProcessorPaths>` is its own resolution scope, isolated from compile/runtime, so SnakeYAML never lands on a user runtime classpath.

- [ ] **Step 1: Update `tiko-processor/pom.xml`**

In `tiko-processor/pom.xml`, after the existing `tiko-api` dependency entry, add:

```xml
<dependency>
    <groupId>io.tiko</groupId>
    <artifactId>tiko-config</artifactId>
    <scope>provided</scope>
</dependency>
```

- [ ] **Step 2: Verify the dep compiles**

Run: `mvn -pl tiko-processor -am clean compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Create `ConfigFieldModel`**

```java
// tiko-processor/src/main/java/io/tiko/processor/config/ConfigFieldModel.java
package io.tiko.processor.config;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

/**
 * One record component on a {@code @Configuration} record.
 */
public record ConfigFieldModel(
    VariableElement element,
    String fieldName,        // record component name (also default YAML key)
    String yamlKey,          // either fieldName or @Key("…") override
    TypeMirror type,         // raw declared type (e.g., Optional<Duration>)
    Cardinality cardinality, // REQUIRED / OPTIONAL / DEFAULTED
    String defaultValue      // raw @Default("…") string; null unless DEFAULTED
) {
    public enum Cardinality { REQUIRED, OPTIONAL, DEFAULTED }
}
```

- [ ] **Step 4: Create `ConfigurationModel`**

```java
// tiko-processor/src/main/java/io/tiko/processor/config/ConfigurationModel.java
package io.tiko.processor.config;

import javax.lang.model.element.TypeElement;
import java.util.List;

/**
 * One {@code @Configuration} record collected from the source set.
 */
public record ConfigurationModel(
    TypeElement element,
    String packageName,
    String simpleName,           // e.g. DbConfig
    String qualifiedName,        // e.g. io.example.app.DbConfig
    String prefix,               // @Configuration#prefix
    List<ConfigFieldModel> fields
) {
    public String binderSimpleName() { return simpleName + "ConfigBinder"; }
    public String binderQualifiedName() { return "io.tiko.generated.config." + binderSimpleName(); }
}
```

- [ ] **Step 5: Create `ConfigSupportedTypes`**

```java
// tiko-processor/src/main/java/io/tiko/processor/config/ConfigSupportedTypes.java
package io.tiko.processor.config;

import io.tiko.config.internal.coercers.Coercers;
import io.tiko.config.internal.coercers.CoercionException;
import io.tiko.config.internal.coercers.TypeCoercer;
import io.tiko.config.internal.coercers.TypeCoercerRegistry;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.util.List;

/**
 * Bridge between processor element types and the runtime coercer set.
 * Centralised so error messages, validation, and codegen all consult the same
 * source of truth for "is this type bindable?".
 */
public final class ConfigSupportedTypes {

    /**
     * Returns true if {@code type} is supported as a record-component type:
     * a bundled scalar, an enum, a {@code List<X>} / {@code Map<String,X>} where
     * X is itself supported (a scalar or a record), an {@code Optional<X>}, or
     * a record (nested binding).
     */
    public static boolean isSupported(TypeMirror type, Types types) {
        if (type.getKind().isPrimitive()) return primitiveSupported(type.getKind());
        if (type.getKind() != TypeKind.DECLARED) return false;
        TypeElement el = (TypeElement) types.asElement(type);
        if (el == null) return false;
        String fqn = el.getQualifiedName().toString();

        // Records (nested binding) — ConfigBinderGenerator emits a recursive call.
        if (el.getKind().name().equals("RECORD")) return true;

        if (el.getKind() == javax.lang.model.element.ElementKind.ENUM) return true;

        // Optional / List / Map are accepted; the generator inspects the type argument.
        if (fqn.equals("java.util.Optional") || fqn.equals("java.util.List") || fqn.equals("java.util.Map")) {
            return true; // element-type validation happens in ConfigurationValidator
        }

        // Bundled scalars: ask the runtime registry directly using Class.forName.
        try {
            Class<?> c = Class.forName(fqn);
            return TypeCoercerRegistry.isSupported(c);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static boolean primitiveSupported(TypeKind kind) {
        return switch (kind) {
            case INT, LONG, BOOLEAN, DOUBLE, FLOAT, SHORT, BYTE, CHAR -> true;
            default -> false;
        };
    }

    /**
     * Validates a {@code @Default("…")} string against the field's declared (effective) type.
     * Returns a description of the failure, or {@code null} on success.
     */
    public static String validateDefault(String defaultValue, Class<?> effectiveType) {
        try {
            TypeCoercer<?> coercer = TypeCoercerRegistry.get(effectiveType);
            coercer.coerce(defaultValue);
            return null;
        } catch (CoercionException e) {
            return e.getMessage();
        } catch (IllegalArgumentException e) {
            return "no coercer for " + effectiveType.getName();
        }
    }

    public static List<String> bundledTypeNames() {
        return List.of(
            "primitives + boxed", "String", "Duration", "Instant", "LocalDate", "LocalDateTime",
            "ZoneId", "UUID", "URI", "Path", "BigDecimal", "Pattern", "Charset",
            "enums", "List<X>", "Map<String,X>", "nested records", "Optional<X>"
        );
    }
}
```

- [ ] **Step 6: Hook the processor entry point**

In `tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java`:

(a) Add an import: `import io.tiko.annotations.Configuration;`

(b) Update `getSupportedAnnotationTypes()` to return:

```java
return Set.of(
        Component.class.getCanonicalName(),
        Produces.class.getCanonicalName(),
        EventHandler.class.getCanonicalName(),
        Configuration.class.getCanonicalName()
);
```

(c) In the `process(...)` method's collection branch (currently calls `collectComponents`, `collectFactoryMethods`, `collectEventHandlers` between lines ~77-79), append:

```java
collectConfigurations(roundEnv);
```

(d) Add a stub method (real impl comes in Task 11):

```java
private void collectConfigurations(RoundEnvironment roundEnv) {
    // Implemented in Task 11 via ConfigurationCollector.
}
```

- [ ] **Step 7: Verify the processor still compiles and existing tests pass**

Run: `mvn -pl tiko-processor test`
Expected: existing tests (e.g. `PrivateConstructorTest`, `ProducesOnComponentClassTest`) still PASS.

- [ ] **Step 8: Commit**

```bash
git add tiko-processor/pom.xml \
        tiko-processor/src/main/java/io/tiko/processor/config/ConfigFieldModel.java \
        tiko-processor/src/main/java/io/tiko/processor/config/ConfigurationModel.java \
        tiko-processor/src/main/java/io/tiko/processor/config/ConfigSupportedTypes.java \
        tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java
git commit -m "feat(processor): wire @Configuration into supported annotations and add core models"
```

---

### Task 11: `ConfigurationCollector` and `ConfigurationValidator`

**Files:**
- Create: `tiko-processor/src/main/java/io/tiko/processor/config/ConfigurationCollector.java`
- Create: `tiko-processor/src/main/java/io/tiko/processor/config/ConfigurationValidator.java`
- Modify: `tiko-processor/src/main/java/io/tiko/processor/util/ProcessorContext.java` — add a `List<ConfigurationModel> configurations` collection (mirrors `components`/`factoryMethods`).
- Modify: `tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java` — replace the stubbed `collectConfigurations` with the real call; add validation + generation calls in the final round.
- Test: `tiko-processor/src/test/java/io/tiko/processor/config/ConfigurationValidatorTest.java`

The validator covers every compile-time error case from the spec:

| Case | Test name |
|---|---|
| `@Configuration` on a non-record class | `nonRecord_emitsError` |
| Unsupported field type | `unsupportedType_emitsError` |
| Duplicate prefix across two records | `duplicatePrefix_emitsError` |
| `@Default` on `Optional<X>` | `defaultOnOptional_emitsError` |
| `@Default` value not parseable | `unparseableDefault_emitsError` |
| Recursive record reference | `recursiveRecord_emitsError` |

- [ ] **Step 1: Write the failing tests**

```java
// tiko-processor/src/test/java/io/tiko/processor/config/ConfigurationValidatorTest.java
package io.tiko.processor.config;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.CompilationSubject.assertThat;

class ConfigurationValidatorTest {

    @Test
    void nonRecord_emitsError() {
        JavaFileObject src = JavaFileObjects.forSourceLines(
            "io.example.NotARecord",
            "package io.example;",
            "import io.tiko.annotations.Configuration;",
            "@Configuration(prefix = \"db\")",
            "public class NotARecord {}"
        );
        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@Configuration must be applied to a record");
    }

    @Test
    void unsupportedType_emitsError() {
        JavaFileObject src = JavaFileObjects.forSourceLines(
            "io.example.WithBigInt",
            "package io.example;",
            "import io.tiko.annotations.Configuration;",
            "@Configuration(prefix = \"x\")",
            "public record WithBigInt(java.math.BigInteger v) {}"
        );
        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("unsupported config type 'BigInteger'");
    }

    @Test
    void duplicatePrefix_emitsError() {
        JavaFileObject a = JavaFileObjects.forSourceLines(
            "io.example.A",
            "package io.example;",
            "import io.tiko.annotations.Configuration;",
            "@Configuration(prefix = \"db\")",
            "public record A(String url) {}"
        );
        JavaFileObject b = JavaFileObjects.forSourceLines(
            "io.example.B",
            "package io.example;",
            "import io.tiko.annotations.Configuration;",
            "@Configuration(prefix = \"db\")",
            "public record B(String url) {}"
        );
        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(a, b);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("Both records declare prefix 'db'");
    }

    @Test
    void defaultOnOptional_emitsError() {
        JavaFileObject src = JavaFileObjects.forSourceLines(
            "io.example.D",
            "package io.example;",
            "import java.util.Optional;",
            "import io.tiko.annotations.Configuration;",
            "import io.tiko.annotations.Default;",
            "@Configuration(prefix = \"d\")",
            "public record D(@Default(\"x\") Optional<String> v) {}"
        );
        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@Default cannot be combined with Optional");
    }

    @Test
    void unparseableDefault_emitsError() {
        JavaFileObject src = JavaFileObjects.forSourceLines(
            "io.example.U",
            "package io.example;",
            "import io.tiko.annotations.Configuration;",
            "import io.tiko.annotations.Default;",
            "@Configuration(prefix = \"u\")",
            "public record U(@Default(\"abc\") int v) {}"
        );
        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@Default('abc') on int field 'v'");
    }
}
```

- [ ] **Step 2: Run the failing tests**

Run: `mvn -pl tiko-processor test -Dtest=ConfigurationValidatorTest`
Expected: FAIL — collector/validator/generator wiring not yet in place.

- [ ] **Step 3: Add `configurations` to `ProcessorContext`**

In `ProcessorContext.java`, find the field declarations section (e.g. where `components`, `factoryMethods` are declared) and add:

```java
private final List<ConfigurationModel> configurations = new ArrayList<>();

public void registerConfiguration(ConfigurationModel cfg) {
    configurations.add(cfg);
}

public List<ConfigurationModel> getConfigurations() {
    return configurations;
}
```

(Imports: `io.tiko.processor.config.ConfigurationModel`.)

- [ ] **Step 4: Implement `ConfigurationCollector`**

```java
// tiko-processor/src/main/java/io/tiko/processor/config/ConfigurationCollector.java
package io.tiko.processor.config;

import io.tiko.annotations.Configuration;
import io.tiko.annotations.Default;
import io.tiko.annotations.Key;
import io.tiko.processor.util.ProcessorContext;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks {@code @Configuration}-annotated elements and builds {@link ConfigurationModel}s.
 * Non-record annotated elements are flagged here (the simplest fail-fast point);
 * deeper validation is in {@link ConfigurationValidator}.
 */
public final class ConfigurationCollector {

    private final ProcessorContext ctx;

    public ConfigurationCollector(ProcessorContext ctx) {
        this.ctx = ctx;
    }

    public void collect(RoundEnvironment roundEnv) {
        for (Element el : roundEnv.getElementsAnnotatedWith(Configuration.class)) {
            if (!(el instanceof TypeElement type)) {
                ctx.getErrorReporter().error(el, "@Configuration can only be applied to types");
                continue;
            }
            if (type.getKind() != ElementKind.RECORD) {
                ctx.getErrorReporter().error(type,
                    "@Configuration must be applied to a record",
                    "Change `class` to `record`");
                continue;
            }

            Configuration ann = type.getAnnotation(Configuration.class);
            String prefix = ann.prefix();
            String pkg = ((javax.lang.model.element.PackageElement) type.getEnclosingElement()).getQualifiedName().toString();
            String simple = type.getSimpleName().toString();
            String qualified = type.getQualifiedName().toString();

            List<ConfigFieldModel> fields = new ArrayList<>();
            for (Element member : type.getEnclosedElements()) {
                if (member.getKind() != ElementKind.RECORD_COMPONENT) continue;
                VariableElement comp = (VariableElement) member;
                fields.add(buildField(comp));
            }

            ctx.registerConfiguration(new ConfigurationModel(type, pkg, simple, qualified, prefix, fields));
        }
    }

    private ConfigFieldModel buildField(VariableElement comp) {
        String name = comp.getSimpleName().toString();
        Key keyAnn = comp.getAnnotation(Key.class);
        String yamlKey = keyAnn != null ? keyAnn.value() : name;

        Default defAnn = comp.getAnnotation(Default.class);
        boolean isOptional = isOptional(comp.asType());
        ConfigFieldModel.Cardinality cardinality;
        String defaultValue = null;
        if (isOptional) {
            cardinality = ConfigFieldModel.Cardinality.OPTIONAL;
        } else if (defAnn != null) {
            cardinality = ConfigFieldModel.Cardinality.DEFAULTED;
            defaultValue = defAnn.value();
        } else {
            cardinality = ConfigFieldModel.Cardinality.REQUIRED;
        }

        return new ConfigFieldModel(comp, name, yamlKey, comp.asType(), cardinality, defaultValue);
    }

    private boolean isOptional(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) return false;
        DeclaredType dt = (DeclaredType) type;
        return ((TypeElement) dt.asElement()).getQualifiedName().toString().equals("java.util.Optional");
    }
}
```

- [ ] **Step 5: Implement `ConfigurationValidator`**

```java
// tiko-processor/src/main/java/io/tiko/processor/config/ConfigurationValidator.java
package io.tiko.processor.config;

import io.tiko.processor.util.ProcessorContext;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** All compile-time checks against the collected {@link ConfigurationModel}s. */
public final class ConfigurationValidator {

    private final ProcessorContext ctx;
    private final Types types;

    public ConfigurationValidator(ProcessorContext ctx, Types types) {
        this.ctx = ctx;
        this.types = types;
    }

    public boolean validate() {
        boolean ok = true;
        ok &= checkPrefixUniqueness();
        for (ConfigurationModel cfg : ctx.getConfigurations()) {
            ok &= checkFields(cfg);
            ok &= checkNoRecursion(cfg);
        }
        return ok;
    }

    private boolean checkPrefixUniqueness() {
        Map<String, ConfigurationModel> seen = new HashMap<>();
        boolean ok = true;
        for (ConfigurationModel cfg : ctx.getConfigurations()) {
            ConfigurationModel prior = seen.put(cfg.prefix(), cfg);
            if (prior != null) {
                ctx.getErrorReporter().error(cfg.element(),
                    prior.simpleName() + ".java, " + cfg.simpleName()
                        + ".java — Both records declare prefix '" + cfg.prefix() + "'."
                        + " Each prefix must be unique.",
                    "Rename one of the prefixes");
                ok = false;
            }
        }
        return ok;
    }

    private boolean checkFields(ConfigurationModel cfg) {
        boolean ok = true;
        for (ConfigFieldModel f : cfg.fields()) {
            // 1. Type-set membership
            TypeMirror inner = unwrapOptional(f.type());
            if (!ConfigSupportedTypes.isSupported(inner, types)) {
                String typeName = simpleName(inner);
                ctx.getErrorReporter().error(f.element(),
                    "Field '" + f.fieldName() + "' uses unsupported config type '" + typeName + "'."
                        + " See *Components → Supported types* in the spec.",
                    "Use one of: " + String.join(", ", ConfigSupportedTypes.bundledTypeNames()),
                    "Or declare as String and parse in your service");
                ok = false;
            }

            // 2. @Default + Optional<X> conflict
            if (f.cardinality() == ConfigFieldModel.Cardinality.OPTIONAL && f.defaultValue() != null) {
                ctx.getErrorReporter().error(f.element(),
                    "@Default cannot be combined with Optional<X> on field '" + f.fieldName() + "'."
                        + " They mean different things.",
                    "Drop the Optional wrapper, or remove @Default");
                ok = false;
            }

            // 3. @Default value parseability for the effective scalar type
            if (f.cardinality() == ConfigFieldModel.Cardinality.DEFAULTED) {
                Class<?> effective = effectiveClass(inner);
                if (effective != null) {
                    String err = ConfigSupportedTypes.validateDefault(f.defaultValue(), effective);
                    if (err != null) {
                        ctx.getErrorReporter().error(f.element(),
                            "@Default('" + f.defaultValue() + "') on " + simpleName(inner)
                                + " field '" + f.fieldName() + "' is not a valid " + effective.getSimpleName() + ": " + err,
                            "Provide a value parseable as " + effective.getSimpleName());
                        ok = false;
                    }
                }
            }
        }
        return ok;
    }

    private boolean checkNoRecursion(ConfigurationModel cfg) {
        Set<String> visiting = new HashSet<>();
        return walk(cfg.element(), visiting, cfg);
    }

    private boolean walk(TypeElement type, Set<String> visiting, ConfigurationModel root) {
        String fqn = type.getQualifiedName().toString();
        if (!visiting.add(fqn)) {
            ctx.getErrorReporter().error(root.element(),
                "Recursive record reference detected at " + fqn);
            return false;
        }
        for (var member : type.getEnclosedElements()) {
            if (!member.getKind().name().equals("RECORD_COMPONENT")) continue;
            TypeMirror t = unwrapOptional(member.asType());
            if (t.getKind() == javax.lang.model.type.TypeKind.DECLARED) {
                TypeElement child = (TypeElement) ((DeclaredType) t).asElement();
                if (child.getKind() == javax.lang.model.element.ElementKind.RECORD) {
                    if (!walk(child, visiting, root)) return false;
                }
            }
        }
        visiting.remove(fqn);
        return true;
    }

    private TypeMirror unwrapOptional(TypeMirror type) {
        if (type.getKind() != javax.lang.model.type.TypeKind.DECLARED) return type;
        DeclaredType dt = (DeclaredType) type;
        TypeElement el = (TypeElement) dt.asElement();
        if (!el.getQualifiedName().toString().equals("java.util.Optional")) return type;
        if (dt.getTypeArguments().isEmpty()) return type;
        return dt.getTypeArguments().get(0);
    }

    private static Class<?> effectiveClass(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return switch (type.getKind()) {
                case INT -> int.class;
                case LONG -> long.class;
                case BOOLEAN -> boolean.class;
                case DOUBLE -> double.class;
                case FLOAT -> float.class;
                case SHORT -> short.class;
                case BYTE -> byte.class;
                case CHAR -> char.class;
                default -> null;
            };
        }
        if (type.getKind() == javax.lang.model.type.TypeKind.DECLARED) {
            String fqn = ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().toString();
            try { return Class.forName(fqn); } catch (ClassNotFoundException e) { return null; }
        }
        return null;
    }

    private static String simpleName(TypeMirror type) {
        if (type.getKind().isPrimitive()) return type.getKind().name().toLowerCase();
        if (type.getKind() == javax.lang.model.type.TypeKind.DECLARED) {
            return ((TypeElement) ((DeclaredType) type).asElement()).getSimpleName().toString();
        }
        return type.toString();
    }
}
```

- [ ] **Step 6: Wire collector + validator into `TikoAnnotationProcessor`**

In `TikoAnnotationProcessor.java`:

(a) Replace the stubbed `collectConfigurations` with:

```java
private void collectConfigurations(RoundEnvironment roundEnv) {
    new io.tiko.processor.config.ConfigurationCollector(context).collect(roundEnv);
}
```

(b) In the `validate()` method, after the existing validators, add:

```java
io.tiko.processor.config.ConfigurationValidator configValidator =
    new io.tiko.processor.config.ConfigurationValidator(context, processingEnv.getTypeUtils());
if (!configValidator.validate()) valid = false;
```

- [ ] **Step 7: Run the validator tests**

Run: `mvn -pl tiko-processor test -Dtest=ConfigurationValidatorTest`
Expected: PASS for all six error cases.

- [ ] **Step 8: Run the full processor test suite for regressions**

Run: `mvn -pl tiko-processor test`
Expected: All tests pass — old ones unaffected.

- [ ] **Step 9: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/config/ConfigurationCollector.java \
        tiko-processor/src/main/java/io/tiko/processor/config/ConfigurationValidator.java \
        tiko-processor/src/main/java/io/tiko/processor/util/ProcessorContext.java \
        tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java \
        tiko-processor/src/test/java/io/tiko/processor/config/ConfigurationValidatorTest.java
git commit -m "feat(processor): collect and validate @Configuration records at compile time"
```

---

### Task 12: `ConfigBinderGenerator` — JavaPoet codegen for per-record binders

**Files:**
- Create: `tiko-processor/src/main/java/io/tiko/processor/config/ConfigBinderGenerator.java`
- Modify: `tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java` — call generator in the `generate()` final-round step.
- Test: `tiko-processor/src/test/java/io/tiko/processor/config/ConfigBinderGeneratorTest.java`

The generator produces, for each `@Configuration` record, a `<Record>ConfigBinder.java` matching the hand-written binder shape from Task 9.5.

The JavaPoet mechanics: build a `MethodSpec` for `bind`, emit one statement per field (`String url = ctx.requireString(...)`, `int max = ctx.scalarOrDefault(...)`, etc.), call `checkUnknownKeys` at the end with the literal set of consumed keys, then `return new <Record>(...)`.

For each field, the generator emits one of these BindContext calls based on `(Cardinality, type)`:

| Cardinality | Type | Emitted call |
|---|---|---|
| REQUIRED | scalar (any bundled / enum) | `ctx.requireScalar(node, "yamlKey", "prefix.yamlKey", <coercer>, <fallback>)` |
| REQUIRED | `List<X>` | `ctx.requireScalar(node, "yamlKey", "prefix.yamlKey", CompositeCoercers.list(<elem>), List.of())` |
| REQUIRED | `Map<String,X>` | `ctx.requireScalar(node, "yamlKey", "prefix.yamlKey", CompositeCoercers.map(<elem>), Map.of())` |
| REQUIRED | nested record | calls the nested record's binder recursively (next sub-task) |
| DEFAULTED | scalar | `ctx.scalarOrDefault(node, "yamlKey", "prefix.yamlKey", <coercer>, <coercer>.coerce("<defaultValue>"))` — default is parsed at codegen time using the same coercer |
| OPTIONAL | scalar | `ctx.optionalScalar(node, "yamlKey", "prefix.yamlKey", <coercer>)` |

The `<coercer>` resolution table:

| Java type | Coercer expression |
|---|---|
| `int` / `Integer` | `Coercers.intCoercer()` |
| `long` / `Long` | `Coercers.longCoercer()` |
| ... (similarly for all bundled scalars) | ... |
| `List<X>` | `CompositeCoercers.list(<X-coercer>)` |
| `Map<String,X>` | `CompositeCoercers.map(<X-coercer>)` |
| `Optional<X>` | handled by cardinality, not by coercer |
| enum `E` | `Coercers.enumCoercer(E.class)` |
| nested record `R` | not via coercer — recursive binder call |

- [ ] **Step 1: Write the failing generator test**

```java
// tiko-processor/src/test/java/io/tiko/processor/config/ConfigBinderGeneratorTest.java
package io.tiko.processor.config;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.CompilationSubject.assertThat;

class ConfigBinderGeneratorTest {

    @Test
    void simple_record_generates_binder_with_expected_calls() {
        JavaFileObject src = JavaFileObjects.forSourceLines(
            "io.example.DbConfig",
            "package io.example;",
            "import java.time.Duration;",
            "import java.util.Optional;",
            "import io.tiko.annotations.Configuration;",
            "import io.tiko.annotations.Default;",
            "@Configuration(prefix = \"db\")",
            "public record DbConfig(String url, @Default(\"10\") int maxConnections, Optional<Duration> connectTimeout) {}"
        );
        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        assertThat(c).succeeded();
        assertThat(c).generatedSourceFile("io.tiko.generated.config.DbConfigBinder")
            .contentsAsUtf8String()
            .contains("ctx.requireSection(root, \"db\")")
            .contains("ctx.requireScalar(node, \"url\"")
            .contains("ctx.scalarOrDefault(node, \"maxConnections\"")
            .contains("ctx.optionalScalar(node, \"connectTimeout\"")
            .contains("ctx.checkUnknownKeys(node, \"db\"")
            .contains("return new DbConfig(");
    }
}
```

- [ ] **Step 2: Run the failing test**

Run: `mvn -pl tiko-processor test -Dtest=ConfigBinderGeneratorTest`
Expected: FAIL.

- [ ] **Step 3: Implement `ConfigBinderGenerator`**

```java
// tiko-processor/src/main/java/io/tiko/processor/config/ConfigBinderGenerator.java
package io.tiko.processor.config;

import com.palantir.javapoet.*;
import io.tiko.config.BindContext;
import io.tiko.config.ConfigBinder;
import io.tiko.config.internal.coercers.Coercers;
import io.tiko.config.internal.coercers.CompositeCoercers;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Generates {@code <Record>ConfigBinder.java} for each {@code @Configuration} record.
 */
public final class ConfigBinderGenerator {

    private static final String GENERATED_PACKAGE = "io.tiko.generated.config";

    private final Filer filer;

    public ConfigBinderGenerator(Filer filer) {
        this.filer = filer;
    }

    public void generate(ConfigurationModel cfg) throws IOException {
        ClassName recordType = ClassName.get(cfg.packageName(), cfg.simpleName());
        ClassName binderName = ClassName.get(GENERATED_PACKAGE, cfg.binderSimpleName());

        // bind(Map<String, Object> root, BindContext ctx)
        MethodSpec.Builder bind = MethodSpec.methodBuilder("bind")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(recordType)
            .addParameter(ParameterizedTypeName.get(Map.class, String.class, Object.class), "root")
            .addParameter(BindContext.class, "ctx");

        bind.addStatement("$T<$T, $T> node = ctx.requireSection(root, $S)",
            Map.class, String.class, Object.class, cfg.prefix());

        Set<String> consumedKeys = new LinkedHashSet<>();
        StringBuilder ctorArgs = new StringBuilder();

        for (int i = 0; i < cfg.fields().size(); i++) {
            ConfigFieldModel f = cfg.fields().get(i);
            consumedKeys.add(f.yamlKey());

            String varName = f.fieldName();
            String fullPath = cfg.prefix() + "." + f.yamlKey();
            TypeMirror inner = unwrapOptional(f.type());
            CodeBlock coercer = coercerExpr(inner);
            TypeName javaType = TypeName.get(f.type());

            switch (f.cardinality()) {
                case OPTIONAL -> bind.addStatement("$T $L = ctx.optionalScalar(node, $S, $S, $L)",
                    javaType, varName, f.yamlKey(), fullPath, coercer);
                case DEFAULTED -> bind.addStatement(
                    "$T $L = ctx.scalarOrDefault(node, $S, $S, $L, $L.coerce($S))",
                    javaType, varName, f.yamlKey(), fullPath, coercer, coercer, f.defaultValue());
                case REQUIRED -> bind.addStatement(
                    "$T $L = ctx.requireScalar(node, $S, $S, $L, $L)",
                    javaType, varName, f.yamlKey(), fullPath, coercer, fallbackExpr(inner));
            }

            if (i > 0) ctorArgs.append(", ");
            ctorArgs.append(varName);
        }

        bind.addStatement("ctx.checkUnknownKeys(node, $S, $T.of($L))",
            cfg.prefix(), Set.class, quotedJoin(consumedKeys));
        bind.addStatement("return new $T($L)", recordType, ctorArgs.toString());

        // type() and prefix() trivial overrides
        MethodSpec typeM = MethodSpec.methodBuilder("type")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(ParameterizedTypeName.get(ClassName.get(Class.class), recordType))
            .addStatement("return $T.class", recordType)
            .build();

        MethodSpec prefixM = MethodSpec.methodBuilder("prefix")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(String.class)
            .addStatement("return $S", cfg.prefix())
            .build();

        TypeSpec binderClass = TypeSpec.classBuilder(binderName)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(ParameterizedTypeName.get(ClassName.get(ConfigBinder.class), recordType))
            .addMethod(typeM)
            .addMethod(prefixM)
            .addMethod(bind.build())
            .build();

        JavaFile.builder(GENERATED_PACKAGE, binderClass).build().writeTo(filer);
    }

    private CodeBlock coercerExpr(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return primitiveCoercer(type.getKind().name());
        }
        if (type.getKind() == javax.lang.model.type.TypeKind.DECLARED) {
            TypeElement el = (TypeElement) ((DeclaredType) type).asElement();
            String fqn = el.getQualifiedName().toString();
            if (fqn.equals("java.util.List") || fqn.equals("java.util.Map")) {
                DeclaredType dt = (DeclaredType) type;
                int valueArgIdx = fqn.equals("java.util.Map") ? 1 : 0;
                CodeBlock elemCoercer = coercerExpr(dt.getTypeArguments().get(valueArgIdx));
                ClassName helper = ClassName.get(CompositeCoercers.class);
                return fqn.equals("java.util.List")
                    ? CodeBlock.of("$T.list($L)", helper, elemCoercer)
                    : CodeBlock.of("$T.map($L)", helper, elemCoercer);
            }
            if (el.getKind() == javax.lang.model.element.ElementKind.ENUM) {
                ClassName enumType = ClassName.get(el);
                return CodeBlock.of("$T.enumCoercer($T.class)", Coercers.class, enumType);
            }
            return scalarCoercer(fqn);
        }
        throw new IllegalArgumentException("Unsupported field type: " + type);
    }

    private CodeBlock primitiveCoercer(String kind) {
        return switch (kind) {
            case "INT"     -> CodeBlock.of("$T.intCoercer()", Coercers.class);
            case "LONG"    -> CodeBlock.of("$T.longCoercer()", Coercers.class);
            case "BOOLEAN" -> CodeBlock.of("$T.booleanCoercer()", Coercers.class);
            case "DOUBLE"  -> CodeBlock.of("$T.doubleCoercer()", Coercers.class);
            case "FLOAT"   -> CodeBlock.of("$T.floatCoercer()", Coercers.class);
            case "SHORT"   -> CodeBlock.of("$T.shortCoercer()", Coercers.class);
            case "BYTE"    -> CodeBlock.of("$T.byteCoercer()", Coercers.class);
            case "CHAR"    -> CodeBlock.of("$T.charCoercer()", Coercers.class);
            default -> throw new IllegalArgumentException("Unsupported primitive: " + kind);
        };
    }

    private CodeBlock scalarCoercer(String fqn) {
        return switch (fqn) {
            case "java.lang.Integer"      -> CodeBlock.of("$T.intCoercer()", Coercers.class);
            case "java.lang.Long"         -> CodeBlock.of("$T.longCoercer()", Coercers.class);
            case "java.lang.Boolean"      -> CodeBlock.of("$T.booleanCoercer()", Coercers.class);
            case "java.lang.Double"       -> CodeBlock.of("$T.doubleCoercer()", Coercers.class);
            case "java.lang.Float"        -> CodeBlock.of("$T.floatCoercer()", Coercers.class);
            case "java.lang.Short"        -> CodeBlock.of("$T.shortCoercer()", Coercers.class);
            case "java.lang.Byte"         -> CodeBlock.of("$T.byteCoercer()", Coercers.class);
            case "java.lang.Character"    -> CodeBlock.of("$T.charCoercer()", Coercers.class);
            case "java.lang.String"       -> CodeBlock.of("$T.stringCoercer()", Coercers.class);
            case "java.time.Duration"     -> CodeBlock.of("$T.durationCoercer()", Coercers.class);
            case "java.time.Instant"      -> CodeBlock.of("$T.instantCoercer()", Coercers.class);
            case "java.time.LocalDate"    -> CodeBlock.of("$T.localDateCoercer()", Coercers.class);
            case "java.time.LocalDateTime"-> CodeBlock.of("$T.localDateTimeCoercer()", Coercers.class);
            case "java.time.ZoneId"       -> CodeBlock.of("$T.zoneIdCoercer()", Coercers.class);
            case "java.util.UUID"         -> CodeBlock.of("$T.uuidCoercer()", Coercers.class);
            case "java.net.URI"           -> CodeBlock.of("$T.uriCoercer()", Coercers.class);
            case "java.nio.file.Path"     -> CodeBlock.of("$T.pathCoercer()", Coercers.class);
            case "java.nio.charset.Charset"->CodeBlock.of("$T.charsetCoercer()", Coercers.class);
            case "java.math.BigDecimal"   -> CodeBlock.of("$T.bigDecimalCoercer()", Coercers.class);
            case "java.util.regex.Pattern"-> CodeBlock.of("$T.patternCoercer()", Coercers.class);
            default -> throw new IllegalArgumentException("Unsupported scalar: " + fqn);
        };
    }

    private CodeBlock fallbackExpr(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return switch (type.getKind()) {
                case INT, LONG, SHORT, BYTE, CHAR -> CodeBlock.of("0");
                case DOUBLE, FLOAT -> CodeBlock.of("0.0");
                case BOOLEAN -> CodeBlock.of("false");
                default -> CodeBlock.of("null");
            };
        }
        // Object types — null fallback. The error has already been reported; the value isn't used.
        return CodeBlock.of("null");
    }

    private TypeMirror unwrapOptional(TypeMirror type) {
        if (type.getKind() != javax.lang.model.type.TypeKind.DECLARED) return type;
        DeclaredType dt = (DeclaredType) type;
        TypeElement el = (TypeElement) dt.asElement();
        if (!el.getQualifiedName().toString().equals("java.util.Optional")) return type;
        return dt.getTypeArguments().isEmpty() ? type : dt.getTypeArguments().get(0);
    }

    private static String quotedJoin(Set<String> keys) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String k : keys) {
            if (!first) sb.append(", ");
            sb.append("\"").append(k).append("\"");
            first = false;
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: Hook the generator into `TikoAnnotationProcessor.generate()`**

In the `generate()` method, after the existing generators and before the container generator, add:

```java
io.tiko.processor.config.ConfigBinderGenerator configBinderGen =
    new io.tiko.processor.config.ConfigBinderGenerator(processingEnv.getFiler());
for (io.tiko.processor.config.ConfigurationModel cfg : context.getConfigurations()) {
    configBinderGen.generate(cfg);
}
```

- [ ] **Step 5: Run the generator test**

Run: `mvn -pl tiko-processor test -Dtest=ConfigBinderGeneratorTest`
Expected: PASS.

- [ ] **Step 6: Run the entire processor suite**

Run: `mvn -pl tiko-processor test`
Expected: All tests pass.

- [ ] **Step 7: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/config/ConfigBinderGenerator.java \
        tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java \
        tiko-processor/src/test/java/io/tiko/processor/config/ConfigBinderGeneratorTest.java
git commit -m "feat(processor): generate per-record ConfigBinder classes"
```

---

### Task 13: `ConfigBinderRegistryGenerator` + `ConfigManifestWriter`

**Files:**
- Create: `tiko-processor/src/main/java/io/tiko/processor/config/ConfigBinderRegistryGenerator.java`
- Create: `tiko-processor/src/main/java/io/tiko/processor/config/ConfigManifestWriter.java`
- Modify: `tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java` — call both at end of `generate()`.
- Test: `tiko-processor/src/test/java/io/tiko/processor/config/ConfigRegistryAndManifestTest.java`

The generated `ConfigBinderRegistry_<hash>` is a static utility that returns a list of all per-record binders. The hash suffix is the same one already used by `TikoAnnotationProcessor.computeContainerClassName()` (and stored on `ProcessorContext.getContainerClassName()` after Stage-3 setup) — this lets multi-module setups place each module's registry in the same package without FQN collision, the same way `TikoContainerImpl_<hash>` does today.

The manifest is a plain text file in `META-INF/tiko/configs.txt` with `<fqn>=<prefix>` lines plus a header comment listing the registry FQN, used by `AggregatingContainer` for cross-module aggregation.

- [ ] **Step 1: Write the failing test**

```java
// tiko-processor/src/test/java/io/tiko/processor/config/ConfigRegistryAndManifestTest.java
package io.tiko.processor.config;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

class ConfigRegistryAndManifestTest {

    @Test
    void registry_class_lists_all_binders() {
        JavaFileObject a = JavaFileObjects.forSourceLines("io.example.A",
            "package io.example;",
            "import io.tiko.annotations.Configuration;",
            "@Configuration(prefix = \"a\") public record A(String x) {}");
        JavaFileObject b = JavaFileObjects.forSourceLines("io.example.B",
            "package io.example;",
            "import io.tiko.annotations.Configuration;",
            "@Configuration(prefix = \"b\") public record B(String x) {}");

        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(a, b);
        assertThat(c).succeeded();
        // Registry class name carries the same hash as TikoContainerImpl_<hash>;
        // search by simple-name pattern across generated files.
        var generated = c.generatedSourceFiles();
        var registry = generated.stream()
            .filter(f -> f.getName().contains("ConfigBinderRegistry_"))
            .findFirst().orElseThrow();
        try {
            String src = new String(registry.openInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            assertThat(src).contains("new AConfigBinder()").contains("new BConfigBinder()");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void manifest_lists_fqn_prefix_pairs() throws IOException {
        JavaFileObject a = JavaFileObjects.forSourceLines("io.example.A",
            "package io.example;",
            "import io.tiko.annotations.Configuration;",
            "@Configuration(prefix = \"a\") public record A(String x) {}");
        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(a);
        assertThat(c).succeeded();
        var manifestOpt = c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/configs.txt");
        assertThat(manifestOpt).isPresent();
        String content;
        try (var r = new InputStreamReader(manifestOpt.get().openInputStream(), StandardCharsets.UTF_8)) {
            content = new java.io.BufferedReader(r).lines().reduce("", (acc, line) -> acc + line + "\n");
        }
        assertThat(content).contains("io.example.A=a");
    }
}
```

- [ ] **Step 2: Run the failing test**

Run: `mvn -pl tiko-processor test -Dtest=ConfigRegistryAndManifestTest`
Expected: FAIL.

- [ ] **Step 3: Implement `ConfigBinderRegistryGenerator`**

```java
// tiko-processor/src/main/java/io/tiko/processor/config/ConfigBinderRegistryGenerator.java
package io.tiko.processor.config;

import com.palantir.javapoet.*;
import io.tiko.config.ConfigBinder;

import javax.annotation.processing.Filer;
import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.List;
import java.util.StringJoiner;

/**
 * Generates {@code io.tiko.generated.config.ConfigBinderRegistry_<hash>} per module.
 * Hash matches the container's hash so multiple modules don't collide on FQN.
 */
public final class ConfigBinderRegistryGenerator {

    private static final String GENERATED_PACKAGE = "io.tiko.generated.config";

    private final Filer filer;
    private final String hashSuffix;   // e.g. "ab12cd34" — same as TikoContainerImpl_<hash>

    public ConfigBinderRegistryGenerator(Filer filer, String hashSuffix) {
        this.filer = filer;
        this.hashSuffix = hashSuffix;
    }

    public String registryClassFqn() {
        return GENERATED_PACKAGE + ".ConfigBinderRegistry_" + hashSuffix;
    }

    public void generate(List<ConfigurationModel> configs) throws IOException {
        if (configs.isEmpty()) return;

        String className = "ConfigBinderRegistry_" + hashSuffix;

        StringJoiner instances = new StringJoiner(", ");
        for (ConfigurationModel cfg : configs) {
            instances.add("new " + cfg.binderSimpleName() + "()");
        }

        TypeName listOfBinders = ParameterizedTypeName.get(
            ClassName.get(List.class),
            ParameterizedTypeName.get(ClassName.get(ConfigBinder.class), WildcardTypeName.subtypeOf(Object.class)));

        MethodSpec all = MethodSpec.methodBuilder("all")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(listOfBinders)
            .addStatement("return $T.of($L)", List.class, instances.toString())
            .build();

        TypeSpec registry = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(all)
            .build();

        JavaFile.builder(GENERATED_PACKAGE, registry).build().writeTo(filer);
    }
}
```

- [ ] **Step 4: Implement `ConfigManifestWriter`**

```java
// tiko-processor/src/main/java/io/tiko/processor/config/ConfigManifestWriter.java
package io.tiko.processor.config;

import javax.annotation.processing.Filer;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * Writes META-INF/tiko/configs.txt — one {@code <fqn>=<prefix>} per record,
 * preceded by a {@code # registry=<fqn>} header line so the runtime can locate
 * each module's per-module ConfigBinderRegistry_<hash> class.
 */
public final class ConfigManifestWriter {

    private static final String PATH = "META-INF/tiko/configs.txt";

    private final Filer filer;
    private final String registryFqn;

    public ConfigManifestWriter(Filer filer, String registryFqn) {
        this.filer = filer;
        this.registryFqn = registryFqn;
    }

    public void write(List<ConfigurationModel> configs) throws IOException {
        if (configs.isEmpty()) return;
        FileObject f = filer.createResource(StandardLocation.CLASS_OUTPUT, "", PATH);
        try (Writer w = f.openWriter()) {
            w.write("# Auto-generated by tiko-processor — see tiko-config docs\n");
            w.write("# registry=" + registryFqn + "\n");
            for (ConfigurationModel cfg : configs) {
                w.write(cfg.qualifiedName());
                w.write("=");
                w.write(cfg.prefix());
                w.write("\n");
            }
        }
    }
}
```

- [ ] **Step 5: Hook both into `TikoAnnotationProcessor.generate()`**

The container hash is already computed earlier in `generate()` and stored on `context`. The container class name is `TikoContainerImpl_<hash>`; extract the hash suffix for use in the registry name. After the per-record binder generation loop, add:

```java
List<io.tiko.processor.config.ConfigurationModel> configs = context.getConfigurations();
String containerClassName = context.getContainerClassName();           // "TikoContainerImpl_<hash>"
String hashSuffix = containerClassName.substring(containerClassName.lastIndexOf('_') + 1);
io.tiko.processor.config.ConfigBinderRegistryGenerator regGen =
    new io.tiko.processor.config.ConfigBinderRegistryGenerator(processingEnv.getFiler(), hashSuffix);
regGen.generate(configs);
new io.tiko.processor.config.ConfigManifestWriter(processingEnv.getFiler(), regGen.registryClassFqn()).write(configs);
```

- [ ] **Step 6: Run the test**

Run: `mvn -pl tiko-processor test -Dtest=ConfigRegistryAndManifestTest`
Expected: PASS.

- [ ] **Step 7: Run the full processor suite**

Run: `mvn -pl tiko-processor test`
Expected: All tests pass.

- [ ] **Step 8: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/config/ConfigBinderRegistryGenerator.java \
        tiko-processor/src/main/java/io/tiko/processor/config/ConfigManifestWriter.java \
        tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java \
        tiko-processor/src/test/java/io/tiko/processor/config/ConfigRegistryAndManifestTest.java
git commit -m "feat(processor): generate ConfigBinderRegistry and configs.txt manifest"
```

---

## Phase D — Runtime integration

### Task 14: `ConfigBootstrap` runtime entry + `Tiko.create(ConfigSource)` overload

**Files:**
- Create: `tiko-config/src/main/java/io/tiko/config/runtime/ConfigBootstrap.java`
- Create: `tiko-config/src/test/java/io/tiko/config/runtime/ConfigBootstrapTest.java`
- Modify: `tiko-api/src/main/java/io/tiko/Tiko.java` — add `create(ConfigSource)` overload, fail-fast in `create()`.
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java` — emit an `injectConfigs(Map<Class<?>, Object>)` method on the generated container, plus a check inside `start()` that any pre-injected configs have been registered as singletons.

`ConfigBootstrap` orchestrates the runtime flow described in the spec's *Data flow* section. It does not depend on `tiko-api`'s `Tiko` class — it just consumes a `ConfigSource` and a list of `ConfigBinder<?>` and returns a `Map<Class<?>, Object>` (or throws `ConfigValidationException`).

`Tiko.create(ConfigSource)`:
1. Discovers `ConfigBinder<?>` instances (reflectively reads `META-INF/tiko/configs.txt`, looks up the per-module `ConfigBinderRegistry` class via `Class.forName`, calls `all()`).
2. Calls `ConfigBootstrap.bind(source, binders)` — returns `Map<Class<?>, Object>` or throws.
3. Constructs the container (single- or multi-module path, same as today).
4. Reflectively calls `injectConfigs(map)` on the container.
5. Calls `start()`.

The container's `injectConfigs` method is generated by `ContainerGenerator` — it iterates the map and registers each entry into the existing singleton map keyed by record type.

`Tiko.create()` (no-arg) gains a fail-fast check: if any `META-INF/tiko/configs.txt` is on the classpath with non-empty entries, throw with the message from the spec.

- [ ] **Step 1: Write the failing `ConfigBootstrap` test**

```java
// tiko-config/src/test/java/io/tiko/config/runtime/ConfigBootstrapTest.java
package io.tiko.config.runtime;

import io.tiko.ConfigSource;
import io.tiko.config.BindContext;
import io.tiko.config.ConfigBinder;
import io.tiko.config.ConfigSources;
import io.tiko.config.ConfigValidationException;
import io.tiko.config.internal.coercers.Coercers;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigBootstrapTest {

    record DbConfig(String url, int max) {}

    static class DbConfigBinder implements ConfigBinder<DbConfig> {
        public Class<DbConfig> type() { return DbConfig.class; }
        public String prefix() { return "db"; }
        public DbConfig bind(Map<String, Object> root, BindContext ctx) {
            Map<String, Object> node = ctx.requireSection(root, "db");
            String url = ctx.requireScalar(node, "url", "db.url", Coercers.stringCoercer(), "");
            int max = ctx.scalarOrDefault(node, "max", "db.max", Coercers.intCoercer(), 10);
            ctx.checkUnknownKeys(node, "db", Set.of("url", "max"));
            return new DbConfig(url, max);
        }
    }

    @Test
    void happy_path_returns_map_of_bound_records() {
        ConfigSource src = ConfigSources.fromMap(Map.of("db", Map.of("url", "x")));
        Map<Class<?>, Object> result = ConfigBootstrap.bind("config.yaml", src, List.of(new DbConfigBinder()));
        assertThat(result).hasSize(1);
        DbConfig cfg = (DbConfig) result.get(DbConfig.class);
        assertThat(cfg.url()).isEqualTo("x");
        assertThat(cfg.max()).isEqualTo(10);
    }

    @Test
    void unknown_top_level_section_is_an_error() {
        ConfigSource src = ConfigSources.fromMap(Map.of("db", Map.of("url", "x"), "kafkka", Map.of()));
        assertThatThrownBy(() -> ConfigBootstrap.bind("c.yaml", src, List.of(new DbConfigBinder())))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("unknown top-level section 'kafkka'");
    }

    @Test
    void interpolation_failure_aggregates_with_bind_errors() {
        ConfigSource src = ConfigSources.fromMap(Map.of("db", Map.of("url", "${MISSING}")));
        assertThatThrownBy(() -> ConfigBootstrap.bind("c.yaml", src, List.of(new DbConfigBinder())))
            .hasMessageContaining("MISSING");
    }
}
```

- [ ] **Step 2: Run the failing test**

Run: `mvn -pl tiko-config test -Dtest=ConfigBootstrapTest`
Expected: FAIL.

- [ ] **Step 3: Implement `ConfigBootstrap`**

```java
// tiko-config/src/main/java/io/tiko/config/runtime/ConfigBootstrap.java
package io.tiko.config.runtime;

import io.tiko.ConfigSource;
import io.tiko.config.BindContext;
import io.tiko.config.ConfigBinder;
import io.tiko.config.ConfigValidationException;
import io.tiko.config.internal.Interpolator;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runtime entry point for typed-config binding. Used by {@code Tiko.create(ConfigSource)}.
 */
public final class ConfigBootstrap {

    private ConfigBootstrap() {}

    /**
     * Loads the YAML, interpolates {@code ${VAR}}s, validates top-level prefixes,
     * runs every binder, and either returns a {@code Map<Class<?>, Object>} of bound
     * records or throws {@link ConfigValidationException} with the full report.
     */
    public static Map<Class<?>, Object> bind(String sourceLabel, ConfigSource source, List<ConfigBinder<?>> binders) {
        BindContext ctx = new BindContext(sourceLabel);

        // 1. Load
        Map<String, Object> raw = source.load();

        // 2. Interpolate
        @SuppressWarnings("unchecked")
        Map<String, Object> interpolated = (Map<String, Object>) Interpolator.interpolate(raw, System::getenv, ctx);

        // 3. Top-level prefix check
        Set<String> claimed = new LinkedHashSet<>();
        for (ConfigBinder<?> b : binders) claimed.add(b.prefix());
        for (String k : interpolated.keySet()) {
            if (!claimed.contains(k)) {
                String suggestion = nearest(k, claimed);
                String hint = suggestion != null ? " Did you mean '" + suggestion + "'?" : "";
                ctx.report("unknown top-level section '" + k + "'." + hint);
            }
        }

        // 4. Bind each record
        Map<Class<?>, Object> bound = new LinkedHashMap<>();
        for (ConfigBinder<?> b : binders) {
            Object instance = b.bind(interpolated, ctx);
            bound.put(b.type(), instance);
        }

        // 5. Throw if anything accumulated
        if (ctx.hasErrors()) {
            throw new ConfigValidationException(sourceLabel, ctx.errors());
        }
        return bound;
    }

    private static String nearest(String input, Set<String> candidates) {
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String c : candidates) {
            int d = levenshtein(input, c);
            if (d < bestDist) { bestDist = d; best = c; }
        }
        return (bestDist <= 2) ? best : null;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] curr = new int[b.length() + 1];
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            prev = curr;
        }
        return prev[b.length()];
    }
}
```

- [ ] **Step 4: Run the test**

Run: `mvn -pl tiko-config test -Dtest=ConfigBootstrapTest`
Expected: PASS.

- [ ] **Step 5: Modify `ContainerGenerator` to emit `injectConfigs`**

Read `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java` and locate the `generate()` flow that builds the `TikoContainerImpl_<hash>` `TypeSpec`. Add a new method spec on the generated container:

```java
// inside ContainerGenerator, where other MethodSpecs are added:
MethodSpec injectConfigs = MethodSpec.methodBuilder("injectConfigs")
    .addModifiers(Modifier.PUBLIC)
    .addParameter(ParameterizedTypeName.get(Map.class, Class.class, Object.class), "configs")
    .addStatement("this.configSingletons.putAll(configs)")
    .build();
```

Where `configSingletons` is a new `private final Map<Class<?>, Object> configSingletons = new java.util.HashMap<>();` field on the generated class. The generated `get(Class)` method must consult `configSingletons` first before its existing component lookup. (Edit the generator's `createGetMethod()` accordingly: add an `if (configSingletons.containsKey(type)) return type.cast(configSingletons.get(type));` at the top.)

This is a small but real edit to the existing generator. Verify by inspecting `ContainerGenerator`'s `createGetMethod` and inserting the lookup at the top of the body.

- [ ] **Step 6: Modify `tiko-api/src/main/java/io/tiko/Tiko.java`**

Refactor the existing `create()` body to share with a new overload. Add `create(ConfigSource)`, `failIfConfigsMissingSource()`, and route through a shared `createInternal(ConfigSource)`. The refactor preserves all existing behaviour for the no-arg path when no `@Configuration` records are present.

```java
public static Container create() {
    failIfConfigsMissingSource();
    return createInternal(null);
}

public static Container create(ConfigSource source) {
    return createInternal(java.util.Objects.requireNonNull(source, "source"));
}

private static void failIfConfigsMissingSource() {
    try {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = Tiko.class.getClassLoader();
        var resources = cl.getResources("META-INF/tiko/configs.txt");
        java.util.List<String> declared = new java.util.ArrayList<>();
        while (resources.hasMoreElements()) {
            java.net.URL url = resources.nextElement();
            try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(
                    url.openStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq > 0) declared.add(line.substring(0, eq));
                }
            }
        }
        if (!declared.isEmpty()) {
            throw new IllegalStateException(
                "You declared @Configuration records (" + String.join(", ", declared)
                    + ") but called Tiko.create() without a ConfigSource. "
                    + "Use Tiko.create(ConfigSources.classpath(\"config.yaml\")) or similar.");
        }
    } catch (java.io.IOException ignored) { /* no manifest — no configs declared */ }
}

private static Container createInternal(ConfigSource source) {
    try {
        Class<?> eventBusClass = Class.forName("io.tiko.event.local.LocalEventBus");
        EventBus eventBus = (EventBus) eventBusClass.getDeclaredConstructor().newInstance();

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) classLoader = Tiko.class.getClassLoader();

        var resources = classLoader.getResources("META-INF/tiko/container.properties");
        int moduleCount = 0;
        while (resources.hasMoreElements()) { resources.nextElement(); moduleCount++; }

        Container container;
        if (moduleCount > 1) {
            Class<?> aggregatingClass = Class.forName("io.tiko.runtime.AggregatingContainer");
            container = (Container) aggregatingClass.getDeclaredConstructor(EventBus.class).newInstance(eventBus);
        } else {
            container = createSingleModuleContainer(eventBus);
        }

        if (source != null) {
            java.util.Map<Class<?>, Object> bound = bindConfigs(source, classLoader);
            // Single-module path: container is TikoContainerImpl_<hash> — invoke generated injectConfigs.
            // Multi-module path: AggregatingContainer.injectConfigs is a public method.
            container.getClass().getMethod("injectConfigs", java.util.Map.class).invoke(container, bound);
        }

        return container;
    } catch (RuntimeException e) {
        throw e;
    } catch (ClassNotFoundException e) {
        throw new IllegalStateException(
            "Tiko container implementation not found. Did you include tiko-processor in your annotation processor path?", e);
    } catch (Exception e) {
        throw new IllegalStateException("Failed to create container instance", e);
    }
}

private static java.util.Map<Class<?>, Object> bindConfigs(ConfigSource source, ClassLoader cl) throws Exception {
    java.util.List<io.tiko.config.ConfigBinder<?>> binders = new java.util.ArrayList<>();
    var resources = cl.getResources("META-INF/tiko/configs.txt");
    while (resources.hasMoreElements()) {
        java.net.URL url = resources.nextElement();
        try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(
                url.openStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("# registry=")) {
                    String registryFqn = line.substring("# registry=".length()).trim();
                    Class<?> registryClass = Class.forName(registryFqn, true, cl);
                    @SuppressWarnings("unchecked")
                    java.util.List<io.tiko.config.ConfigBinder<?>> moduleBinders =
                        (java.util.List<io.tiko.config.ConfigBinder<?>>) registryClass.getMethod("all").invoke(null);
                    binders.addAll(moduleBinders);
                    break;
                }
            }
        }
    }
    return io.tiko.config.runtime.ConfigBootstrap.bind("config", source, binders);
}
```

The existing `createSingleModuleContainer` method and `registerEventHandlers` helper stay unchanged. The `start()` invocation already happens inside `createSingleModuleContainer` — for the multi-module path, `AggregatingContainer` does its own per-module start. Both paths run `injectConfigs` *before* `start()` because `createSingleModuleContainer` calls `start` itself; we therefore need to reorder: extract the `start()` call out of `createSingleModuleContainer` so it can run after `injectConfigs`.

**Reorder (concrete edit):** in the existing `createSingleModuleContainer`, find both `var startMethod = implClass.getMethod("start");` lines and remove the immediate `startMethod.invoke(container);`. Move that invocation into `createInternal` after the `injectConfigs` call:

```java
// in createInternal, after the injectConfigs block (and after, even when source == null):
if (moduleCount <= 1) {
    container.getClass().getMethod("start").invoke(container);
}
```

(The aggregator's `start()` is already called per-module inside `AggregatingContainer`'s own constructor flow — verify by reading `AggregatingContainer.processContainerResource` — and we leave that alone.)

- [ ] **Step 7: Verify the integration via existing examples**

Run: `mvn -pl tiko-examples/01_basic_di -am clean install`
Expected: all existing examples still build and pass — no `@Configuration` records in `01_basic_di`, so the new code paths are dormant.

- [ ] **Step 8: Add a smoke-test integration test**

Create `tiko-config/src/test/java/io/tiko/config/runtime/FailFastIntegrationTest.java`:

```java
package io.tiko.config.runtime;

import io.tiko.Tiko;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailFastIntegrationTest {

    /**
     * If a META-INF/tiko/configs.txt exists on the classpath, the no-arg Tiko.create()
     * must throw with the prescribed message rather than constructing the container.
     */
    @Test
    void no_arg_create_throws_when_manifest_present(@TempDir Path tmp) throws IOException {
        // Synthesize a fake manifest in a directory and add to the context classloader.
        Path metaDir = tmp.resolve("META-INF/tiko");
        Files.createDirectories(metaDir);
        Files.writeString(metaDir.resolve("configs.txt"), "io.example.FakeConfig=fake\n");

        ClassLoader synthetic = new java.net.URLClassLoader(new java.net.URL[] { tmp.toUri().toURL() },
            Thread.currentThread().getContextClassLoader());

        ClassLoader prior = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(synthetic);
        try {
            assertThatThrownBy(Tiko::create)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FakeConfig")
                .hasMessageContaining("ConfigSource");
        } finally {
            Thread.currentThread().setContextClassLoader(prior);
        }
    }
}
```

Note: this test pulls `tiko-runtime` and `tiko-event-local` transitively via `Tiko.create`. Add to `tiko-config/pom.xml` as test deps:

```xml
<dependency>
    <groupId>io.tiko</groupId>
    <artifactId>tiko-runtime</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>io.tiko</groupId>
    <artifactId>tiko-event-local</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 9: Run the smoke test**

Run: `mvn -pl tiko-config test -Dtest=FailFastIntegrationTest`
Expected: PASS — `Tiko.create()` throws when the synthesized manifest is on the classloader.

- [ ] **Step 10: Commit**

```bash
git add tiko-config/src/main/java/io/tiko/config/runtime/ConfigBootstrap.java \
        tiko-config/src/test/java/io/tiko/config/runtime/ConfigBootstrapTest.java \
        tiko-config/src/test/java/io/tiko/config/runtime/FailFastIntegrationTest.java \
        tiko-config/pom.xml \
        tiko-api/src/main/java/io/tiko/Tiko.java \
        tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java
git commit -m "feat(runtime): wire Tiko.create(ConfigSource) and fail-fast for missing source"
```

---

### Task 15: `AggregatingContainer` — cross-module config aggregation

**Files:**
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java`
- Modify: `tiko-api/src/main/java/io/tiko/Tiko.java` — multi-module branch calls aggregator's new `injectConfigs` path.
- Test: covered via `tiko-examples/03_config` (Task 16). No standalone unit test — `AggregatingContainer` is integration-shaped.

The aggregator already discovers `META-INF/tiko/container.properties` per module. Extend it to also read `META-INF/tiko/configs.txt`, build a combined list of `ConfigBinder<?>` from each module's `ConfigBinderRegistry`, run `ConfigBootstrap.bind(...)` once across the union, then route each bound record into the per-module container that owns the type.

- [ ] **Step 1: Modify `AggregatingContainer`**

Add fields and methods (insert near the top of the class):

```java
private final Map<Class<?>, Container> configToContainer = new ConcurrentHashMap<>();
```

After the existing `discoverAndInitializeModuleContainers()` call, add a new method called from the constructor:

```java
public void injectConfigs(java.util.Map<Class<?>, Object> configs) {
    for (Map.Entry<Class<?>, Object> e : configs.entrySet()) {
        Container target = configToContainer.get(e.getKey());
        if (target == null) {
            throw new IllegalStateException("No module owns config type " + e.getKey().getName());
        }
        try {
            target.getClass().getMethod("injectConfigs", java.util.Map.class)
                .invoke(target, java.util.Map.of(e.getKey(), e.getValue()));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to inject config " + e.getKey().getName(), ex);
        }
    }
}
```

In `processContainerResource`, after components are loaded, also load `configs.txt` from the same module folder and populate `configToContainer`:

```java
String configsPath = resourcePath.replace("container.properties", "configs.txt");
java.net.URL configsUrl = new java.net.URL(resourceUrl.getProtocol(), resourceUrl.getHost(),
    resourceUrl.getPort(), configsPath);
try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(configsUrl.openStream()))) {
    String line;
    while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) continue;
        int eq = line.indexOf('=');
        if (eq > 0) {
            String fqn = line.substring(0, eq).trim();
            Class<?> typeClass = Class.forName(fqn, false, classLoader);
            configToContainer.put(typeClass, moduleContainer);
        }
    }
} catch (java.io.IOException ignored) {
    // no configs.txt for this module — fine
}
```

`get(Class<T> type)` already routes to `componentToContainerMap`. Add a fallthrough at the top:

```java
@Override
public <T> T get(Class<T> type) {
    Container ccfg = configToContainer.get(type);
    if (ccfg != null) return ccfg.get(type);
    Container container = componentToContainerMap.get(type);
    // ... existing body
}
```

- [ ] **Step 2: Update `Tiko.createInternal` config-discovery to walk all per-module registries**

In `Tiko.createInternal`, when a `ConfigSource` is provided, iterate every `META-INF/tiko/configs.txt` on the classpath, parse each file's `# registry=<fqn>` header line to discover the per-module `ConfigBinderRegistry_<hash>` class, load it via `Class.forName`, invoke its static `all()` method, and accumulate the union into a single `List<ConfigBinder<?>>`. Then call `ConfigBootstrap.bind(label, source, allBinders)` once across the union.

```java
// Inside Tiko.createInternal — only when source != null:
ClassLoader cl = Thread.currentThread().getContextClassLoader();
if (cl == null) cl = Tiko.class.getClassLoader();

List<ConfigBinder<?>> binders = new ArrayList<>();
var resources = cl.getResources("META-INF/tiko/configs.txt");
while (resources.hasMoreElements()) {
    java.net.URL url = resources.nextElement();
    try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(
            url.openStream(), java.nio.charset.StandardCharsets.UTF_8))) {
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("# registry=")) {
                String registryFqn = line.substring("# registry=".length()).trim();
                Class<?> registryClass = Class.forName(registryFqn, true, cl);
                @SuppressWarnings("unchecked")
                List<ConfigBinder<?>> moduleBinders =
                    (List<ConfigBinder<?>>) registryClass.getMethod("all").invoke(null);
                binders.addAll(moduleBinders);
                break; // only one registry per file
            }
        }
    }
}
java.util.Map<Class<?>, Object> bound =
    io.tiko.config.runtime.ConfigBootstrap.bind("config", source, binders);
```

- [ ] **Step 3: Wire `injectConfigs` invocation for both paths**

After `bound` is computed:

- **Single-module path** (`container instanceof TikoContainerImpl_<hash>` shape): reflectively invoke `container.getClass().getMethod("injectConfigs", java.util.Map.class).invoke(container, bound)`.
- **Multi-module path**: call `((AggregatingContainer) container).injectConfigs(bound)` (the new public method added in Step 1).

In both cases, do this **before** the existing `start()` invocation.

- [ ] **Step 4: Verify build**

Run: `mvn clean install`
Expected: All modules build, all existing tests pass.

- [ ] **Step 5: Commit**

```bash
git add tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java \
        tiko-api/src/main/java/io/tiko/Tiko.java
git commit -m "feat(runtime): aggregate configs across modules via AggregatingContainer"
```

---

## Phase E — Example module + roadmap

### Task 16: `tiko-examples/03_config` — end-to-end demo and integration smoke test

**Files:**
- Create: `tiko-examples/03_config/pom.xml`
- Create: `tiko-examples/03_config/src/main/java/io/tiko/examples/config/DbConfig.java`
- Create: `tiko-examples/03_config/src/main/java/io/tiko/examples/config/AppConfig.java`
- Create: `tiko-examples/03_config/src/main/java/io/tiko/examples/config/DataService.java`
- Create: `tiko-examples/03_config/src/main/java/io/tiko/examples/config/Main.java`
- Create: `tiko-examples/03_config/src/main/resources/config.yaml`
- Modify: `tiko-examples/pom.xml` — add `03_config` to `<modules>`.

The example demonstrates: `@Configuration` records (one with defaults, one with optional + env interpolation), a `@Component` consuming both, and `Main` running the container and printing the bound values.

- [ ] **Step 1: Add the new module to `tiko-examples/pom.xml`**

Inside `<modules>`, after the existing entries, add `<module>03_config</module>`.

- [ ] **Step 2: Create `tiko-examples/03_config/pom.xml`**

Mirror an existing example pom (e.g., `tiko-examples/01_basic_di/pom.xml`) — same parent, `tiko-api`, `tiko-runtime`, `tiko-event-local`, `tiko-config` runtime deps + `tiko-processor` on the annotation processor path.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.tiko.examples</groupId>
        <artifactId>tiko-examples</artifactId>
        <version>0.1.0</version>
    </parent>

    <artifactId>tiko-example-03-config</artifactId>
    <name>Tiko Example — Configuration Injection</name>

    <dependencies>
        <dependency><groupId>io.tiko</groupId><artifactId>tiko-api</artifactId></dependency>
        <dependency><groupId>io.tiko</groupId><artifactId>tiko-runtime</artifactId></dependency>
        <dependency><groupId>io.tiko</groupId><artifactId>tiko-event-local</artifactId></dependency>
        <dependency><groupId>io.tiko</groupId><artifactId>tiko-config</artifactId></dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>io.tiko</groupId>
                            <artifactId>tiko-processor</artifactId>
                            <version>${project.version}</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

(If `tiko-examples`'s parent pom uses a different artifactId / parent path, copy the structure from `01_basic_di/pom.xml` exactly — only the artifactId and dependencies above differ.)

- [ ] **Step 3: Create the records and service**

```java
// tiko-examples/03_config/src/main/java/io/tiko/examples/config/DbConfig.java
package io.tiko.examples.config;

import io.tiko.annotations.Configuration;
import io.tiko.annotations.Default;

import java.time.Duration;
import java.util.Optional;

@Configuration(prefix = "db")
public record DbConfig(
    String url,
    @Default("10") int maxConnections,
    Optional<Duration> connectTimeout
) {}
```

```java
// tiko-examples/03_config/src/main/java/io/tiko/examples/config/AppConfig.java
package io.tiko.examples.config;

import io.tiko.annotations.Configuration;
import io.tiko.annotations.Default;

@Configuration(prefix = "app")
public record AppConfig(
    String name,
    @Default("INFO") String logLevel
) {}
```

```java
// tiko-examples/03_config/src/main/java/io/tiko/examples/config/DataService.java
package io.tiko.examples.config;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;

@Component(scope = Scope.SINGLETON)
public class DataService {
    private final DbConfig db;
    private final AppConfig app;

    @Inject
    public DataService(DbConfig db, AppConfig app) {
        this.db = db;
        this.app = app;
    }

    public String describe() {
        return app.name() + " connecting to " + db.url()
            + " (max=" + db.maxConnections()
            + ", timeout=" + db.connectTimeout().orElse(java.time.Duration.ZERO)
            + ", logLevel=" + app.logLevel() + ")";
    }
}
```

```java
// tiko-examples/03_config/src/main/java/io/tiko/examples/config/Main.java
package io.tiko.examples.config;

import io.tiko.Container;
import io.tiko.Tiko;
import io.tiko.config.ConfigSources;

public class Main {
    public static void main(String[] args) {
        try (Container container = Tiko.create(ConfigSources.classpath("config.yaml"))) {
            DataService service = container.get(DataService.class);
            System.out.println(service.describe());
        }
    }
}
```

- [ ] **Step 4: Create the YAML fixture**

```yaml
# tiko-examples/03_config/src/main/resources/config.yaml
db:
  url: ${DB_URL:jdbc:postgres://localhost:5432/example}
  maxConnections: 20
  connectTimeout: PT5S

app:
  name: example-service
  logLevel: ${LOG_LEVEL:INFO}
```

- [ ] **Step 5: Build the example**

Run: `mvn -pl tiko-examples/03_config -am clean install`
Expected: `BUILD SUCCESS`. Inspect generated sources under `tiko-examples/03_config/target/generated-sources/annotations/io/tiko/generated/config/` — `DbConfigBinder.java`, `AppConfigBinder.java`, `ConfigBinderRegistry_<hash>.java` should exist.

- [ ] **Step 6: Run the example**

Run: `mvn -pl tiko-examples/03_config exec:java -Dexec.mainClass=io.tiko.examples.config.Main`

Expected output (env vars unset → defaults applied):

```
example-service connecting to jdbc:postgres://localhost:5432/example (max=20, timeout=PT5S, logLevel=INFO)
```

(If `mvn exec:java` isn't already configured for the examples module, you can also run via `java -cp <classpath> io.tiko.examples.config.Main` after a build — see how `01_basic_di/pom.xml` handles it.)

- [ ] **Step 7: Run with env vars to demonstrate interpolation**

On Linux/macOS:
```
DB_URL=jdbc:postgres://prod:5432/main LOG_LEVEL=DEBUG mvn -pl tiko-examples/03_config exec:java -Dexec.mainClass=io.tiko.examples.config.Main
```

On Windows PowerShell:
```
$env:DB_URL = "jdbc:postgres://prod:5432/main"; $env:LOG_LEVEL = "DEBUG"; mvn -pl tiko-examples/03_config exec:java -Dexec.mainClass=io.tiko.examples.config.Main
```

Expected: env-supplied values appear in the output.

- [ ] **Step 8: Run the full project build**

Run: `mvn clean install`
Expected: `BUILD SUCCESS` across all modules.

- [ ] **Step 9: Commit**

```bash
git add tiko-examples/pom.xml tiko-examples/03_config
git commit -m "feat(examples): add 03_config end-to-end demo"
```

---

### Task 17: README roadmap update

**Files:**
- Modify: `README.md`

Move the configuration entry from Phase 2 to Phase 1 and use the actual annotation name. Also note that the Phase 2 milestone in GitHub should be retitled (covered in the issue body — actual rename happens after merge).

- [ ] **Step 1: Edit `README.md` — Phase 1 entries**

Find the `## Roadmap` → `### Planned Features` → `**Phase 1** (Current)` block. Add the configuration entry as a new bullet after the existing items:

```
- Configuration injection (`@Configuration` records, YAML-backed, generated binders) — see [#15](https://github.com/tomas-samek/tiko-di/issues/15)
```

- [ ] **Step 2: Edit `README.md` — Phase 2 entries**

Find the `**Phase 2** (Next)` block. Remove the `Configuration injection (\`@Value\`)` bullet entirely. Leave the rest of the bullets unchanged.

- [ ] **Step 3: Add a quick-start example for `@Configuration`**

Add a new section under `## Usage Examples` (placement: after the existing `### Constructor Injection (Recommended)` section, before `### Named Qualifiers`):

```
### Typed Configuration

```java
@Configuration(prefix = "db")
public record DbConfig(
    String url,
    @Default("10") int maxConnections,
    Optional<Duration> connectTimeout
) {}

@Component(scope = Scope.SINGLETON)
public class UserRepository {
    @Inject
    public UserRepository(DbConfig config) {
        // config.url(), config.maxConnections(), config.connectTimeout()
    }
}

// At startup:
Container container = Tiko.create(ConfigSources.classpath("config.yaml"));
```

```yaml
db:
  url: ${DB_URL:jdbc:postgres://localhost/example}
  maxConnections: 20
  connectTimeout: PT5S
```

YAML mismatches (missing required keys, wrong types, unknown keys) fail at container startup with a single report listing every problem — never partway through serving requests.
```

- [ ] **Step 4: Verify README still renders cleanly**

(No automated check; just eyeball the diff.)

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: promote configuration injection to Phase 1 in roadmap"
```

---

## Self-review checklist (for the implementer)

Before opening the PR:

- [ ] Run `mvn clean install` from the repo root — all modules build, all tests pass.
- [ ] Inspect `tiko-examples/03_config/target/generated-sources/annotations/io/tiko/generated/config/` — binder + registry files present.
- [ ] Run `tiko-examples/03_config/Main` with and without env vars — output matches expected lines from Task 16 Steps 6–7.
- [ ] Trigger every compile-time error from `ConfigurationValidatorTest` by hand-editing one example record temporarily — confirm error messages match the spec.
- [ ] Confirm `tiko-runtime`'s manifest (`mvn -pl tiko-runtime dependency:tree`) does *not* include SnakeYAML.
- [ ] Confirm `tiko-config`'s manifest *does* include SnakeYAML at compile scope.
- [ ] Confirm `Tiko.create()` (no-arg) on a fresh `tiko-examples/01_basic_di` build succeeds — backwards compatibility for non-config users.
- [ ] Confirm `Tiko.create()` (no-arg) inside `tiko-examples/03_config` throws the prescribed message.

Once all green, push the branch and open the PR linking issue #15.





