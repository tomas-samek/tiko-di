# Test Container as Peer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace T12's "test container subclasses main" mechanic with "test container is a standalone peer of main, federated at runtime via `AggregatingContainer` reading a `META-INF/tiko/test-shadows.properties` declaration." Production `@Component`s can live in `src/main/java/`, test fixtures in `src/test/java/` — the natural Maven layout.

**Architecture:** Three-layer change: (a) `TikoOptions` gains a package-private `internalAddOverride(...)` mutation entry point; (b) `AggregatingContainer` reads shadow declarations and registers them as overrides on the shared `TikoOptions` before per-module containers are instantiated; (c) processor stops emitting a fresh main container in test-compile when one is already on the classpath, replaces `generateTestSubclass` with `generateStandaloneTestContainer`, emits `test-shadows.properties`, drops the `extensibleMainContainer` field-visibility toggle.

**Tech Stack:** Java 21, Maven, JUnit 5, Google `compile-testing`, AssertJ, Mockito.

**Spec:** `docs/superpowers/specs/2026-05-22-test-container-peer-via-aggregator-design.md`
**Tracker:** [#129](https://github.com/tomas-samek/tiko-di/issues/129)
**Branch:** continue on `spec/129-test-container-peer-via-aggregator`.

---

## Task 1: `TikoOptions.internalAddOverride(...)` package-private mutation

**Files:**
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/TikoOptions.java`
- Test: `tiko-runtime/src/test/java/io/tiko/runtime/TikoOptionsTest.java` (existing — append)

- [ ] **Step 1: Append failing test**

Append to `TikoOptionsTest.java`:

```java
@Test
void internalAddOverrideAddsToImmutableOptions() {
    TikoOptions opts = TikoOptions.builder().build();
    java.util.function.Supplier<String> sup = () -> "shadowed";

    opts.internalAddOverride(String.class, sup);

    assertThat(opts.hasOverride(String.class)).isTrue();
    assertThat(opts.getOverride(String.class).get()).isEqualTo("shadowed");
}

@Test
void internalAddOverrideRespectsExistingUserOverrideViaSkipFlag() {
    // The contract: when called with skipIfPresent=true, do not overwrite an existing entry.
    java.util.function.Supplier<String> userSup = () -> "user-wins";
    java.util.function.Supplier<String> shadowSup = () -> "shadow-loses";
    TikoOptions opts = TikoOptions.builder().override(String.class, userSup).build();

    opts.internalAddOverrideIfAbsent(String.class, shadowSup);

    assertThat(opts.getOverride(String.class).get()).isEqualTo("user-wins");
}

@Test
void internalAddOverrideIfAbsentRegistersWhenKeyMissing() {
    TikoOptions opts = TikoOptions.builder().build();
    java.util.function.Supplier<String> shadowSup = () -> "shadow";

    opts.internalAddOverrideIfAbsent(String.class, shadowSup);

    assertThat(opts.hasOverride(String.class)).isTrue();
    assertThat(opts.getOverride(String.class).get()).isEqualTo("shadow");
}
```

- [ ] **Step 2: Run the test to confirm it fails**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-runtime test -Dtest=TikoOptionsTest`
Expected: compile error — `internalAddOverride` / `internalAddOverrideIfAbsent` not defined.

- [ ] **Step 3: Implement the mutation methods**

In `TikoOptions.java`, the `overrides` field is currently set to an immutable copy via `Map.copyOf(...)`. Change to a mutable map for runtime augmentation.

Replace the constructor's override-init:

```java
this.overrides = b.overrides == null
        ? new java.util.concurrent.ConcurrentHashMap<>()
        : new java.util.concurrent.ConcurrentHashMap<>(b.overrides);
```

Then add the package-private methods (no `public` modifier — visibility is `package`):

```java
/**
 * Package-private entry point used by {@link AggregatingContainer} to register
 * shadow-declared overrides AFTER {@link Builder#build()} has been called.
 * User code cannot reach this method; it is the only mutation surface on
 * {@link TikoOptions} outside the {@link Builder}. Always overwrites.
 *
 * @see #internalAddOverrideIfAbsent(Class, java.util.function.Supplier)
 */
<T> void internalAddOverride(Class<T> type, java.util.function.Supplier<? extends T> supplier) {
    overrides.put(new OverrideKey(type, ""), supplier);
}

/**
 * Same as {@link #internalAddOverride(Class, java.util.function.Supplier)} but
 * a no-op when the key already has an override. Used to give user-provided
 * overrides precedence over shadow-declared ones.
 */
<T> void internalAddOverrideIfAbsent(Class<T> type, java.util.function.Supplier<? extends T> supplier) {
    overrides.putIfAbsent(new OverrideKey(type, ""), supplier);
}
```

Also add named variants for the qualified-shadow case:

```java
<T> void internalAddOverrideIfAbsent(Class<T> type, String name, java.util.function.Supplier<? extends T> supplier) {
    java.util.Objects.requireNonNull(name, "name");
    overrides.putIfAbsent(new OverrideKey(type, name), supplier);
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-runtime test -Dtest=TikoOptionsTest`
Expected: all tests pass — both the new ones and the existing TikoOptionsTest methods.

- [ ] **Step 5: Full runtime suite**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-runtime test`
Expected: all green. The `overrides` field is no longer immutable (ConcurrentHashMap vs `Map.copyOf`); confirm no existing test asserted on the field's immutability.

- [ ] **Step 6: Commit**

```bash
git add tiko-runtime/src/main/java/io/tiko/runtime/TikoOptions.java \
        tiko-runtime/src/test/java/io/tiko/runtime/TikoOptionsTest.java
git commit -m "feat(runtime): TikoOptions package-private internalAddOverride[IfAbsent] for aggregator shadow registration"
```

(Spotless apply if needed.)

---

## Task 2: `AggregatingContainer` reads `test-shadows.properties` and registers shadow overrides

**Files:**
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java`
- Test: `tiko-runtime/src/test/java/io/tiko/runtime/AggregatingContainerShadowRoutingTest.java`

- [ ] **Step 1: Write the failing test**

Create `tiko-runtime/src/test/java/io/tiko/runtime/AggregatingContainerShadowRoutingTest.java`:

```java
package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.ErrorHandler;
import io.tiko.EventBus;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AggregatingContainerShadowRoutingTest {

    @Test
    void shadowDeclarationRegistersAsOverrideOnSharedOptions(@TempDir Path tmp) throws Exception {
        // Build a synthetic classpath root with one test-container.properties + one
        // test-shadows.properties, plus a stub container implementing Container.
        // The aggregator should read the shadows file and register them as overrides.

        Path meta = tmp.resolve("META-INF").resolve("tiko");
        Files.createDirectories(meta);

        // test-container.properties pointing at io.tiko.runtime.StubContainer (existing test fixture)
        Properties testDescriptor = new Properties();
        testDescriptor.setProperty("impl", "io.tiko.runtime.StubContainer");
        try (var out = Files.newOutputStream(meta.resolve("test-container.properties"))) {
            testDescriptor.store(out, "test");
        }

        // test-shadows.properties: declares String.class is shadowed by StubContainer
        Properties shadows = new Properties();
        shadows.setProperty("java.lang.String", "io.tiko.runtime.StubContainer");
        try (var out = Files.newOutputStream(meta.resolve("test-shadows.properties"))) {
            shadows.store(out, "test");
        }

        URLClassLoader cl = new URLClassLoader(
                new URL[] {tmp.toUri().toURL()},
                AggregatingContainerShadowRoutingTest.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(cl);

        try {
            TikoOptions opts = TikoOptions.builder().build();
            new AggregatingContainer(
                    new LocalEventBus(),
                    ctx -> {},
                    null,
                    java.time.Duration.ZERO,
                    opts,
                    "META-INF/tiko/test-container.properties");

            assertThat(opts.hasOverride(String.class)).isTrue();
        } finally {
            Thread.currentThread().setContextClassLoader(AggregatingContainerShadowRoutingTest.class.getClassLoader());
        }
    }

    @Test
    void userOverrideWinsOverShadowDeclaration(@TempDir Path tmp) throws Exception {
        // Same setup, but with a user-provided override already present. The aggregator
        // must skip its registration for that key.

        Path meta = tmp.resolve("META-INF").resolve("tiko");
        Files.createDirectories(meta);

        Properties testDescriptor = new Properties();
        testDescriptor.setProperty("impl", "io.tiko.runtime.StubContainer");
        try (var out = Files.newOutputStream(meta.resolve("test-container.properties"))) {
            testDescriptor.store(out, "test");
        }
        Properties shadows = new Properties();
        shadows.setProperty("java.lang.String", "io.tiko.runtime.StubContainer");
        try (var out = Files.newOutputStream(meta.resolve("test-shadows.properties"))) {
            shadows.store(out, "test");
        }

        URLClassLoader cl = new URLClassLoader(
                new URL[] {tmp.toUri().toURL()},
                AggregatingContainerShadowRoutingTest.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(cl);

        try {
            TikoOptions opts =
                    TikoOptions.builder().override(String.class, () -> "user-wins").build();
            new AggregatingContainer(
                    new LocalEventBus(),
                    ctx -> {},
                    null,
                    java.time.Duration.ZERO,
                    opts,
                    "META-INF/tiko/test-container.properties");

            // User override stays put — the aggregator's IfAbsent semantics yield to user.
            assertThat(opts.getOverride(String.class).get()).isEqualTo("user-wins");
        } finally {
            Thread.currentThread().setContextClassLoader(AggregatingContainerShadowRoutingTest.class.getClassLoader());
        }
    }
}
```

NOTE: this test reuses the existing `StubContainer` test fixture (created during T5 of #122). Verify `StubContainer.java` exists at `tiko-runtime/src/test/java/io/tiko/runtime/StubContainer.java` and accepts the 6-arg ctor.

- [ ] **Step 2: Run the test to confirm it fails**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-runtime test -Dtest=AggregatingContainerShadowRoutingTest`
Expected: assertion fails — the aggregator does not currently read `test-shadows.properties` at all.

- [ ] **Step 3: Add shadow-registration to `AggregatingContainer.discoverAndInitializeModuleContainers`**

In `AggregatingContainer.java`, find `discoverAndInitializeModuleContainers()` (around line 176). Before the existing per-module loop, add:

```java
// Phase 1: scan classpath for META-INF/tiko/test-shadows.properties and register
// each declared shadow as a runtime override on the shared TikoOptions. The
// shadow Supplier dispatches to whichever container claims the key — that
// container is instantiated below in Phase 2 (we capture references in
// containersByImplName as they are created, and the Supplier closes over the
// map so the lookup resolves at .get() time, after all containers exist).
java.util.Map<String, Container> containersByImplName = new java.util.HashMap<>();

Enumeration<URL> shadowResources = classLoader.getResources("META-INF/tiko/test-shadows.properties");
java.util.Map<String, String> seenShadowDeclarations = new java.util.HashMap<>();
while (shadowResources.hasMoreElements()) {
    URL url = shadowResources.nextElement();
    Properties shadowProps = new Properties();
    try (var in = url.openStream()) {
        shadowProps.load(in);
    }
    for (var entry : shadowProps.entrySet()) {
        String routableKey = entry.getKey().toString();
        String shadowFqn = entry.getValue().toString();
        if (seenShadowDeclarations.containsKey(routableKey)) {
            // Two shadow files claim the same key. First-wins; warn.
            LoggerHolder.LOG.log(System.Logger.Level.WARNING,
                    "Multiple shadow declarations for " + routableKey
                            + " (first: " + seenShadowDeclarations.get(routableKey)
                            + ", ignored: " + shadowFqn + ")");
            continue;
        }
        seenShadowDeclarations.put(routableKey, shadowFqn);
        Class<?> keyClass;
        try {
            keyClass = Class.forName(routableKey, false, classLoader);
        } catch (ClassNotFoundException cnfe) {
            LoggerHolder.LOG.log(System.Logger.Level.WARNING,
                    "Shadow declaration references unknown type: " + routableKey);
            continue;
        }
        registerShadowOverride(keyClass, shadowFqn, containersByImplName);
    }
}
```

Add the helper method:

```java
private <T> void registerShadowOverride(
        Class<T> keyClass, String shadowFqn, java.util.Map<String, Container> containersByImplName) {
    java.util.function.Supplier<T> supplier = () -> {
        Container shadowContainer = containersByImplName.get(shadowFqn);
        if (shadowContainer == null) {
            throw new IllegalStateException(
                    "Shadow declaration target not instantiated: " + shadowFqn);
        }
        return shadowContainer.get(keyClass);
    };
    options.internalAddOverrideIfAbsent(keyClass, supplier);
}
```

In the existing per-module instantiation loop (`processContainerResource`), after each container is created, add the container to the map:

```java
containersByImplName.put(implClassName, moduleContainer);
```

(Pass `containersByImplName` to `processContainerResource` as a parameter, or store it as an instance field of `AggregatingContainer` populated during init. Whichever is cleaner.)

The `LoggerHolder` follows the existing pattern in `AggregatingContainer` (or `LocalEventBus` — use whichever logger holder is local).

- [ ] **Step 4: Run the test to confirm it passes**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-runtime test -Dtest=AggregatingContainerShadowRoutingTest`
Expected: both tests PASS.

- [ ] **Step 5: Full runtime suite**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-runtime test`
Expected: all green. No existing test should break — `test-shadows.properties` is opt-in.

- [ ] **Step 6: Commit**

```bash
git add tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java \
        tiko-runtime/src/test/java/io/tiko/runtime/AggregatingContainerShadowRoutingTest.java
git commit -m "feat(runtime): AggregatingContainer reads test-shadows.properties and registers as overrides"
```

(Spotless apply if needed.)

---

## Task 3: `Tiko.createInternal()` always uses `AggregatingContainer` when test descriptor present

**Files:**
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/Tiko.java`
- Test: `tiko-runtime/src/test/java/io/tiko/runtime/TikoTestDescriptorRoutingTest.java`

- [ ] **Step 1: Write the failing test**

Create `tiko-runtime/src/test/java/io/tiko/runtime/TikoTestDescriptorRoutingTest.java`:

```java
package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TikoTestDescriptorRoutingTest {

    @Test
    void singleModuleTestDescriptorStillRoutesThroughAggregatingContainer(@TempDir Path tmp) throws Exception {
        // Even with exactly 1 test-container.properties on the classpath, Tiko.createInternal
        // must use AggregatingContainer (not the single-module fast path) so that
        // shadow registration runs.

        Path meta = tmp.resolve("META-INF").resolve("tiko");
        Files.createDirectories(meta);

        Properties testDescriptor = new Properties();
        testDescriptor.setProperty("impl", "io.tiko.runtime.StubContainer");
        try (var out = Files.newOutputStream(meta.resolve("test-container.properties"))) {
            testDescriptor.store(out, "test");
        }

        URLClassLoader cl = new URLClassLoader(
                new URL[] {tmp.toUri().toURL()},
                TikoTestDescriptorRoutingTest.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(cl);

        try (Container c = Tiko.create(TikoOptions.builder().build())) {
            // AggregatingContainer is the only impl that wraps modules; we use its class
            // identity as the signal that the aggregator path was taken.
            assertThat(c.getClass().getName()).isEqualTo("io.tiko.runtime.AggregatingContainer");
        } finally {
            Thread.currentThread().setContextClassLoader(TikoTestDescriptorRoutingTest.class.getClassLoader());
        }
    }
}
```

NOTE: there is an existing TransportAwareContainer wrapper. If the assertion above fails because the wrapper changes the runtime class, adjust the assertion to unwrap via reflection — call a method that delegates to the underlying container, or use `instanceof AggregatingContainer` after unwrapping.

- [ ] **Step 2: Run the test to confirm it fails**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-runtime test -Dtest=TikoTestDescriptorRoutingTest`
Expected: FAIL — currently, with exactly 1 test descriptor (the moduleCount==1 case), Tiko.createInternal takes the single-module path via `createSingleModuleContainer`.

- [ ] **Step 3: Update `Tiko.createInternal()` descriptor-routing logic**

In `Tiko.java`, find the descriptor-routing block (around lines 95-105 from T12). Currently:

```java
Container container;
if (moduleCount > 1) {
    container = new AggregatingContainer(...);
} else {
    container = createSingleModuleContainer(...);
}
```

Add a check that forces aggregator when the test descriptor was selected:

```java
boolean testMode = descriptorName.equals("META-INF/tiko/test-container.properties");
Container container;
if (moduleCount > 1 || testMode) {
    container = new AggregatingContainer(
            eventBus, errorHandler, options.eventExecutor(), effectiveShutdownTimeout, options, descriptorName);
} else {
    container = createSingleModuleContainer(
            eventBus, errorHandler, options.eventExecutor(), effectiveShutdownTimeout, options, descriptorName);
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-runtime test -Dtest=TikoTestDescriptorRoutingTest`
Expected: PASS.

- [ ] **Step 5: Full runtime suite**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-runtime test`
Expected: all green. The change is additive — production scenarios still hit the single-module fast path.

- [ ] **Step 6: Commit**

```bash
git add tiko-runtime/src/main/java/io/tiko/runtime/Tiko.java \
        tiko-runtime/src/test/java/io/tiko/runtime/TikoTestDescriptorRoutingTest.java
git commit -m "feat(runtime): always use AggregatingContainer when test descriptor present, so shadow registration runs"
```

(Spotless apply if needed.)

---

## Task 4: `ProcessorContext.getAllActiveComponents()` helper

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/util/ProcessorContext.java`

- [ ] **Step 1: Add the helper method**

In `ProcessorContext.java`, alongside `getActiveMainComponents()` and `getActiveTestContainerComponents()`, add:

```java
/**
 * Returns ALL active components in this round, regardless of whether they are
 * production {@code @Component}s or test-classpath {@code @TestComponent}s.
 * Used by the standalone test container generation to discover every component
 * visible in the test-compile round (where production sources are not presented).
 */
public List<ComponentModel> getAllActiveComponents() {
    return components.values().stream()
            .filter(c -> isProfileActive(c.getProfiles()))
            .toList();
}
```

No test needed — pure accessor mirroring the sibling pattern.

- [ ] **Step 2: Verify compile**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/util/ProcessorContext.java
git commit -m "feat(processor): ProcessorContext.getAllActiveComponents() for standalone test container generation"
```

(Spotless apply if needed.)

---

## Task 5: Drop `extensibleMainContainer` toggle; private fields always

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java`
- Test: `tiko-processor/src/test/java/io/tiko/processor/MainContainerFieldVisibilityTest.java`

- [ ] **Step 1: Write the failing test**

Create `tiko-processor/src/test/java/io/tiko/processor/MainContainerFieldVisibilityTest.java`:

```java
package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class MainContainerFieldVisibilityTest {

    @Test
    void generatedMainContainerKeepsScopeStorageFieldsPrivateEvenWhenTestComponentsPresent() throws Exception {
        var testComponentAnno = JavaFileObjects.forSourceLines(
                "io.tiko.test.TestComponent",
                "package io.tiko.test;",
                "import io.tiko.Scope;",
                "import java.lang.annotation.*;",
                "@Retention(RetentionPolicy.SOURCE)",
                "@Target(ElementType.TYPE)",
                "public @interface TestComponent {",
                "    Class<?> value() default Void.class;",
                "    Scope scope() default Scope.SINGLETON;",
                "    String name() default \"\";",
                "}");
        var prod = JavaFileObjects.forSourceLines(
                "demo.Clock",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Clock {}");
        var fake = JavaFileObjects.forSourceLines(
                "demo.FakeClock",
                "package demo;",
                "import io.tiko.test.TestComponent;",
                "@TestComponent",
                "public class FakeClock extends Clock {}");
        var consumer = JavaFileObjects.forSourceLines(
                "demo.UsesClock",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class UsesClock {",
                "    @Inject public UsesClock(Clock c) {}",
                "}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor())
                .compile(testComponentAnno, prod, fake, consumer);
        assertThat(c).succeeded();

        var main = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl_") && !f.getName().contains("TestTikoContainerImpl"))
                .findFirst().orElseThrow();
        var content = new String(main.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Even with @TestComponent present, the main container's scope-storage fields
        // stay private (no package-private relaxation).
        org.assertj.core.api.Assertions.assertThat(content)
                .contains("private final ConcurrentHashMap<Class<?>, Object> singletons")
                .contains("private final ThreadLocal<Map<Class<?>, Object>> requestScoped")
                .contains("private final ThreadLocal<Map<Class<?>, Object>> eventScoped")
                .contains("private final TikoOptions options");
    }

    @Test
    void generatedMainContainerStaysFinalClassEvenWithTestComponents() throws Exception {
        var testComponentAnno = JavaFileObjects.forSourceLines(
                "io.tiko.test.TestComponent",
                "package io.tiko.test;",
                "import io.tiko.Scope;",
                "import java.lang.annotation.*;",
                "@Retention(RetentionPolicy.SOURCE)",
                "@Target(ElementType.TYPE)",
                "public @interface TestComponent {",
                "    Class<?> value() default Void.class;",
                "    Scope scope() default Scope.SINGLETON;",
                "    String name() default \"\";",
                "}");
        var prod = JavaFileObjects.forSourceLines(
                "demo.Clock",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Clock {}");
        var fake = JavaFileObjects.forSourceLines(
                "demo.FakeClock",
                "package demo;",
                "import io.tiko.test.TestComponent;",
                "@TestComponent",
                "public class FakeClock extends Clock {}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor())
                .compile(testComponentAnno, prod, fake);
        assertThat(c).succeeded();

        var main = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl_") && !f.getName().contains("TestTikoContainerImpl"))
                .findFirst().orElseThrow();
        var content = new String(main.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Class declaration must contain "public final class TikoContainerImpl_..."
        org.assertj.core.api.Assertions.assertThat(content)
                .containsPattern("public final class TikoContainerImpl_");
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=MainContainerFieldVisibilityTest`
Expected: FAIL — current T12 relaxation makes fields package-private and the class non-final when `extensibleMainContainer = true`.

- [ ] **Step 3: Drop the toggle**

In `ContainerGenerator.java`:

1. **Field decl** (around line 58): change `private boolean extensibleMainContainer = false;` → remove entirely.

2. **`generate()` method** (around line 77-78): remove the `dualEmission` assignment to `extensibleMainContainer` (leaving the `dualEmission` variable in place for now — Task 7 will refactor the dispatch).

3. **`scopeStorageModifiers()` method** (around lines 548-552): simplify to:
   ```java
   private Modifier[] scopeStorageModifiers() {
       return new Modifier[] {Modifier.PRIVATE, Modifier.FINAL};
   }
   ```

4. **Main container class modifiers** (around lines 116-122 in `generateOne`): always emit `public final` — remove the conditional:
   ```java
   containerBuilder.addModifiers(Modifier.PUBLIC, Modifier.FINAL);
   ```

5. **`options` field** (around line 737-739): make sure it uses `scopeStorageModifiers()` like the others (already does — the simplification flows through).

- [ ] **Step 4: Run the test to confirm it passes**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=MainContainerFieldVisibilityTest`
Expected: both tests PASS.

- [ ] **Step 5: Full suite — expect T12-era tests to fail**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test`
Expected: T12-era tests that asserted on the package-private relaxation (e.g. `TestContainerEmissionTest`, anything checking `extensibleMainContainer` behavior) may fail. The test container itself currently inherits from the main and needs those fields package-private; this will be addressed in Task 7 when we replace `generateTestSubclass` with a standalone emission.

For now, comment out (with `// TODO #129 Task 7:`) any failing assertion that depends on the subclass mechanic. We delete them properly in Task 7.

- [ ] **Step 6: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java \
        tiko-processor/src/test/java/io/tiko/processor/MainContainerFieldVisibilityTest.java
# Include any T12-era test edits you had to comment out:
git add tiko-processor/src/test/java/io/tiko/processor/TestContainerEmissionTest.java
git commit -m "refactor(processor): drop extensibleMainContainer toggle; scope-storage fields always private"
```

(Spotless apply if needed.)

---

## Task 6: Detect main descriptor on classpath at test-compile time

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java`
- Test: `tiko-processor/src/test/java/io/tiko/processor/MainDescriptorDetectionTest.java`

> **Goal:** add a helper `mainDescriptorIsOnClasspath()` that reads `META-INF/tiko/container.properties` from `StandardLocation.CLASS_PATH` via Filer. Returns `Optional<String>` of the main container's FQN if present.

- [ ] **Step 1: Write the failing test**

Create `tiko-processor/src/test/java/io/tiko/processor/MainDescriptorDetectionTest.java`:

```java
package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class MainDescriptorDetectionTest {

    @Test
    void noMainDescriptorOnClasspathFallsBackToFreshMainGeneration() throws Exception {
        // Standard case (compile-testing harness has no classpath descriptor): processor
        // generates a fresh main container as today.
        var src = JavaFileObjects.forSourceLines(
                "demo.Simple",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Simple {}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        assertThat(c).succeeded();

        // Main container IS emitted.
        boolean mainEmitted = c.generatedSourceFiles().stream()
                .anyMatch(f -> f.getName().contains("TikoContainerImpl_"));
        org.assertj.core.api.Assertions.assertThat(mainEmitted).isTrue();
    }
}
```

(The "main descriptor IS on classpath" case is harder to simulate in compile-testing because we don't have a real Maven test-compile phase. Task 7 will exercise the integration via the `12_testing` example. For Task 6 we just lock in the no-descriptor fallback.)

- [ ] **Step 2: Run the test to confirm it passes (baseline)**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=MainDescriptorDetectionTest`
Expected: PASS even before the new helper, because the current generator unconditionally emits the main container.

- [ ] **Step 3: Add the helper**

In `ContainerGenerator.java`, add:

```java
/**
 * Probes the compile classpath for an existing {@code META-INF/tiko/container.properties}.
 * Returns the main container's FQN if found, or empty if the test-compile round is the
 * first emission of any Tiko container (no main container yet exists).
 *
 * <p>Used to decide whether to regenerate a fresh main container during the
 * {@code test-compile} phase: if one already exists on the classpath, the
 * test container generated here is standalone and peers with the existing main
 * via {@code AggregatingContainer} at runtime.
 */
private java.util.Optional<String> mainDescriptorFqnOnClasspath() {
    try {
        var resource = context.getFiler().getResource(
                javax.tools.StandardLocation.CLASS_PATH,
                "",
                "META-INF/tiko/container.properties");
        try (var reader = resource.openReader(true)) {
            Properties props = new Properties();
            props.load(reader);
            String fqn = props.getProperty("impl");
            return fqn == null || fqn.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(fqn);
        }
    } catch (IOException e) {
        // Resource not present — first emission. Fall through to fresh main generation.
        return java.util.Optional.empty();
    }
}
```

NOTE: `context.getFiler()` may not exist; if not, use `processingEnv.getFiler()` (whatever the existing pattern in `TikoAnnotationProcessor` is). Read the existing code first.

Also confirm `Properties` is imported — if not, add the import.

This task doesn't WIRE the helper yet — Task 7 does that. Keep the helper unused; we just need it on the file before Task 7 references it.

- [ ] **Step 4: Confirm compile + tests still pass**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test`
Expected: all green. The helper is unused; no behavior change.

- [ ] **Step 5: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java \
        tiko-processor/src/test/java/io/tiko/processor/MainDescriptorDetectionTest.java
git commit -m "feat(processor): mainDescriptorFqnOnClasspath helper for test-compile peer-mode detection"
```

(Spotless apply if needed.)

---

## Task 7: Replace `generateTestSubclass` with `generateStandaloneTestContainer` + emit `test-shadows.properties` + skip main regeneration

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java`
- Test: `tiko-processor/src/test/java/io/tiko/processor/TestContainerStandaloneTest.java`

This is the central task. It replaces T12's subclass-emission with standalone-emission + shadow file.

- [ ] **Step 1: Write the failing test**

Create `tiko-processor/src/test/java/io/tiko/processor/TestContainerStandaloneTest.java`:

```java
package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class TestContainerStandaloneTest {

    private static final JavaFileObject TEST_COMPONENT_ANNO = JavaFileObjects.forSourceLines(
            "io.tiko.test.TestComponent",
            "package io.tiko.test;",
            "import io.tiko.Scope;",
            "import java.lang.annotation.*;",
            "@Retention(RetentionPolicy.SOURCE)",
            "@Target(ElementType.TYPE)",
            "public @interface TestComponent {",
            "    Class<?> value() default Void.class;",
            "    Scope scope() default Scope.SINGLETON;",
            "    String name() default \"\";",
            "}");

    @Test
    void testContainerIsStandaloneNotExtendingMain() throws Exception {
        var prod = JavaFileObjects.forSourceLines(
                "demo.Clock",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Clock {}");
        var fake = JavaFileObjects.forSourceLines(
                "demo.FakeClock",
                "package demo;",
                "import io.tiko.test.TestComponent;",
                "@TestComponent",
                "public class FakeClock extends Clock {}");
        var consumer = JavaFileObjects.forSourceLines(
                "demo.UsesClock",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class UsesClock {",
                "    @Inject public UsesClock(Clock c) {}",
                "}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor())
                .compile(TEST_COMPONENT_ANNO, prod, fake, consumer);
        assertThat(c).succeeded();

        var testContainer = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TestTikoContainerImpl_") || f.getName().contains("TestContainerImpl_"))
                .findFirst().orElseThrow();
        var content = new String(testContainer.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Standalone — no "extends" of any TikoContainerImpl.
        org.assertj.core.api.Assertions.assertThat(content)
                .doesNotContain("extends TikoContainerImpl");
        // Implements Container directly.
        org.assertj.core.api.Assertions.assertThat(content)
                .contains("implements Container");
    }

    @Test
    void testShadowsPropertiesEmittedWithShadowedKeys() throws Exception {
        var prod = JavaFileObjects.forSourceLines(
                "demo.Clock",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Clock {}");
        var fake = JavaFileObjects.forSourceLines(
                "demo.FakeClock",
                "package demo;",
                "import io.tiko.test.TestComponent;",
                "@TestComponent",
                "public class FakeClock extends Clock {}");
        var consumer = JavaFileObjects.forSourceLines(
                "demo.UsesClock",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class UsesClock {",
                "    @Inject public UsesClock(Clock c) {}",
                "}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor())
                .compile(TEST_COMPONENT_ANNO, prod, fake, consumer);
        assertThat(c).succeeded();

        var shadowsFile = c.generatedFiles().stream()
                .filter(f -> f.getName().endsWith("META-INF/tiko/test-shadows.properties"))
                .findFirst().orElseThrow();
        var content = new String(shadowsFile.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Shadow declaration: demo.Clock=<test container FQN>
        org.assertj.core.api.Assertions.assertThat(content)
                .contains("demo.Clock=io.tiko.generated.");
    }

    @Test
    void testContainerHasFactoriesOnlyForTestSideComponents() throws Exception {
        var prod = JavaFileObjects.forSourceLines(
                "demo.Clock",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Clock {}");
        var fake = JavaFileObjects.forSourceLines(
                "demo.FakeClock",
                "package demo;",
                "import io.tiko.test.TestComponent;",
                "@TestComponent",
                "public class FakeClock extends Clock {}");
        var consumer = JavaFileObjects.forSourceLines(
                "demo.UsesClock",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class UsesClock {",
                "    @Inject public UsesClock(Clock c) {}",
                "}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor())
                .compile(TEST_COMPONENT_ANNO, prod, fake, consumer);
        assertThat(c).succeeded();

        var testContainer = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TestContainerImpl_") || f.getName().contains("TestTikoContainerImpl_"))
                .findFirst().orElseThrow();
        var content = new String(testContainer.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Test container has the FakeClock factory (test-side).
        // It should also have UsesClock? No — UsesClock is @Component (production); it lives in main.
        // Wait — in compile-testing, all sources are processed in one round, so UsesClock IS in
        // the round here. Without the test-classpath/main-classpath distinction we get in real
        // Maven, the boundary is fuzzier. For this test, we just assert FakeClock IS present
        // and that the test container is non-empty.
        org.assertj.core.api.Assertions.assertThat(content)
                .contains("FakeClock")
                .contains("Container");
    }
}
```

NOTE: in compile-testing, there's no main-compile vs test-compile distinction — all sources are processed in one round. So the test container will include factories for everything visible in the round. This is OK; in real Maven the test-compile round only sees test sources, so the test container naturally has only test-side factories.

The third test's assertion is loose because of this — it just checks the test container is structurally reasonable.

- [ ] **Step 2: Run the tests to confirm they fail**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=TestContainerStandaloneTest`
Expected: FAIL — current emission has `extends TikoContainerImpl_<hash>` (T12 subclass), no `test-shadows.properties` file.

- [ ] **Step 3: Implement `generateStandaloneTestContainer`**

In `ContainerGenerator.java`:

1. **Delete the existing `generateTestSubclass(...)` method.**

2. **Add `generateStandaloneTestContainer(String testContainerClassName, List<ComponentModel> testSideComponents)`:**

```java
private void generateStandaloneTestContainer(
        String testContainerClassName, List<ComponentModel> testSideComponents) throws IOException {
    // Test container is a peer of the main container — implements Container directly,
    // never extends the main. Factories cover only the test-side components passed in.
    // Shadow routing is handled at runtime by AggregatingContainer reading
    // META-INF/tiko/test-shadows.properties.
    generateOne(testContainerClassName, testSideComponents, TEST_DESCRIPTOR);
}
```

(Reuses the existing `generateOne(...)` orchestration — the standalone test container is just another container with a different component list.)

3. **Modify `generate()` to dispatch based on classpath detection:**

```java
public void generate() throws IOException {
    java.util.Optional<String> existingMainFqn = mainDescriptorFqnOnClasspath();

    if (existingMainFqn.isPresent() && context.hasTestComponents()) {
        // Test-compile mode: main container already exists; emit standalone test
        // container + shadow declarations only. Do NOT regenerate a fresh main.
        var testSideComponents = context.getAllActiveComponents();
        String testContainerClassName = "TestContainerImpl_" + computeHash(testSideComponents);
        generateStandaloneTestContainer(testContainerClassName, testSideComponents);
        writeContainerDescriptor(TEST_DESCRIPTOR, testContainerClassName);
        writeTestShadowsFile(testContainerClassName);
        return;
    }

    // Standard emission path: main container + (if test components present) standalone
    // test container in the same round (e.g. tiko-test's own tests, tiko-examples/12_testing
    // before the move).
    String mainContainerClassName = context.getContainerClassName();
    var mainComponents = context.getActiveMainComponents();
    generateOne(mainContainerClassName, mainComponents, MAIN_DESCRIPTOR);
    generateComponentsListFile();

    if (context.hasTestComponents()) {
        var testSideComponents = context.getAllActiveComponents();
        String testContainerClassName = "TestContainerImpl_" + computeHash(testSideComponents);
        generateStandaloneTestContainer(testContainerClassName, testSideComponents);
        writeContainerDescriptor(TEST_DESCRIPTOR, testContainerClassName);
        writeTestShadowsFile(testContainerClassName);
    }
}
```

4. **Add `writeTestShadowsFile(String testContainerFqn)`:**

```java
private void writeTestShadowsFile(String testContainerClassName) throws IOException {
    var shadows = context.getShadowedMainKeys();
    if (shadows.isEmpty()) {
        // No shadows — nothing to declare.
        return;
    }
    String testFqn = GENERATED_PACKAGE + "." + testContainerClassName;
    var resource = processingEnv.getFiler().createResource(
            javax.tools.StandardLocation.CLASS_OUTPUT,
            "",
            "META-INF/tiko/test-shadows.properties");
    try (var writer = resource.openWriter()) {
        writer.write("# Generated by tiko-processor - test-component shadow declarations\n");
        for (String shadowedKey : shadows) {
            // The shadowedKey is the main component's qualified name (possibly with #name suffix).
            // The aggregator strips any #name suffix when keying on Class.forName(...).
            writer.write(shadowedKey);
            writer.write("=");
            writer.write(testFqn);
            writer.write("\n");
        }
    }
}
```

5. **Confirm `context.getShadowedMainKeys()` returns the right thing** — read its declaration in `ProcessorContext.java`. It should be the set of main component keys that have a shadowing `@TestComponent`. Adjust if the actual name differs.

6. **Test container constructor signature** — `generateOne(...)` uses the standard 6-arg constructor (`EventBus, ErrorHandler, ExecutorService, boolean, Duration, TikoOptions`), which is exactly what `AggregatingContainer` reflects on. No additional changes needed.

- [ ] **Step 4: Run the tests to confirm they pass**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=TestContainerStandaloneTest`
Expected: all three tests PASS.

- [ ] **Step 5: Full suite — restore Task 5's commented-out assertions**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test`
Expected: T12-era tests that were temporarily commented out in Task 5 (for `TestContainerEmissionTest` etc.) should now either be rewritten to match the new emission or deleted.

Rewrite or delete:
- `TestContainerEmissionTest` — its assertions about `extends TikoContainerImpl` and the subclass-shape emission are invalid now. Either delete and let `TestContainerStandaloneTest` cover the territory, or rewrite to assert on the standalone shape.

I'd delete `TestContainerEmissionTest` entirely — the new tests cover the contract.

```bash
git rm tiko-processor/src/test/java/io/tiko/processor/TestContainerEmissionTest.java
```

- [ ] **Step 6: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java \
        tiko-processor/src/test/java/io/tiko/processor/TestContainerStandaloneTest.java
git commit -m "feat(processor): standalone test container + test-shadows.properties (replaces T12 subclass emission)"
```

(Spotless apply if needed.)

---

## Task 8: Move `tiko-examples/12_testing/` production components from `src/test/` to `src/main/`

**Files:**
- Move: `tiko-examples/12_testing/src/test/java/io/tiko/examples/testing/...` (most files) → `src/main/java/io/tiko/examples/testing/...`

- [ ] **Step 1: Identify which files move**

Files that represent PRODUCTION components (move to `src/main/java/`):
- `domain/Clock.java`
- `service/PaymentGateway.java` (interface)
- `service/HttpPaymentGateway.java` (impl)
- `service/OrderService.java`
- `events/OrderCreatedEvent.java`
- `events/CreateOrderCommand.java`
- `repo/AccountRepository.java`

Files that STAY in `src/test/java/` (test fixtures + tests):
- `clock/FixedClock.java` (@TestComponent)
- `clock/FixedClockTest.java`
- `payment/MockedPaymentTest.java`
- `order/OrderServiceTest.java`
- `repo/RequestScopedRepoTest.java`
- `async/AsyncListener.java` + `async/AsyncHandlerTest.java`
- `lifecycle/PerClassLifecycleTest.java`

- [ ] **Step 2: Move the files**

Use `git mv` to preserve history:

```bash
cd tiko-examples/12_testing
mkdir -p src/main/java/io/tiko/examples/testing/{domain,service,events,repo}
git mv src/test/java/io/tiko/examples/testing/domain/Clock.java src/main/java/io/tiko/examples/testing/domain/
git mv src/test/java/io/tiko/examples/testing/service/PaymentGateway.java src/main/java/io/tiko/examples/testing/service/
git mv src/test/java/io/tiko/examples/testing/service/HttpPaymentGateway.java src/main/java/io/tiko/examples/testing/service/
git mv src/test/java/io/tiko/examples/testing/service/OrderService.java src/main/java/io/tiko/examples/testing/service/
git mv src/test/java/io/tiko/examples/testing/events/OrderCreatedEvent.java src/main/java/io/tiko/examples/testing/events/
git mv src/test/java/io/tiko/examples/testing/events/CreateOrderCommand.java src/main/java/io/tiko/examples/testing/events/
git mv src/test/java/io/tiko/examples/testing/repo/AccountRepository.java src/main/java/io/tiko/examples/testing/repo/
```

Then `cd` back to repo root.

- [ ] **Step 3: Update `tiko-examples/12_testing/pom.xml` for the new layout**

Verify the pom doesn't have anything that hard-codes `src/test/java` as the only source. Standard Maven layout works as-is. Check the annotation-processor configuration — it should already process both `src/main/java` (in compile phase) and `src/test/java` (in test-compile phase).

- [ ] **Step 4: Run the example tests**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-examples/12_testing -am clean test`
Expected: all 7 example tests PASS. The processor now sees production components in main-compile (generates main container in `target/classes/`), then test-compile (generates standalone test container + shadow file in `target/test-classes/`), and runtime federation via `AggregatingContainer` glues them.

If tests fail, investigate. The most likely failure mode: the standalone test container in test-compile is generating with both main AND test components (because in test-compile, the processor sees ONLY test sources, so `getAllActiveComponents()` returns only test-side — but T7's Task 7 emission may include the wrong slice). Debug by reading the generated `target/test-classes/io/tiko/generated/TestContainerImpl_*.java` and `META-INF/tiko/test-shadows.properties`.

- [ ] **Step 5: Update `tiko-examples/12_testing/README.md`**

Replace the workaround note that explained why everything was in `src/test/java/` with a positive description: "Production components in `src/main/java/`, test fixtures and `@TestComponent`s in `src/test/java/` — the natural Maven layout works."

- [ ] **Step 6: Commit**

```bash
git add tiko-examples/12_testing
git commit -m "feat(tiko-examples): move 12_testing production components to src/main/java (#129 enables natural layout)"
```

(Spotless apply if needed.)

---

## Task 9: Update `docs/testing.md` — remove #129 limitation

**Files:**
- Modify: `docs/testing.md`

- [ ] **Step 1: Remove the #129 Known limitations entry**

Find the "Known limitations" section. After #128 shipped, only #129 was left. Remove that entry entirely.

Replace with a positive section under the main flow describing the natural layout:

```markdown
## Classpath layout

Tiko's processor runs in two Maven phases:

- **`compile`** — sees `src/main/java/` sources; generates the main `TikoContainerImpl` + `META-INF/tiko/container.properties` in `target/classes/`.
- **`test-compile`** — sees `src/test/java/` sources only (Maven's behaviour); generates a standalone `TestContainerImpl` + `META-INF/tiko/test-container.properties` + (if any `@TestComponent` shadows exist) `META-INF/tiko/test-shadows.properties` in `target/test-classes/`.

At runtime, `Tiko.create(...)` detects the test descriptors and uses `AggregatingContainer` to federate both containers. Shadow declarations register as runtime overrides on the shared `TikoOptions` — `@TestComponent FakeClock extends Clock` causes every `Clock` injection across both containers to resolve to `FakeClock`.

Production components live in `src/main/java/`, test fixtures (mocks, `@TestComponent`s, helpers) live in `src/test/java/` — the natural Maven layout.
```

- [ ] **Step 2: Update the Known limitations heading**

If only #129 was listed, remove the section entirely or rephrase as "Out of scope" (e.g. `getAll(Class)` override behaviour, multi-module shadow conflicts).

- [ ] **Step 3: Verify spotless**

Run: `W:\tools\apache-maven\bin\mvn -pl '!tiko-bom' spotless:apply` and `spotless:check`
Expected: clean.

- [ ] **Step 4: Commit**

```bash
git add docs/testing.md
git commit -m "docs(testing): natural src/main + src/test layout is now supported (#129)"
```

---

## Task 10: Update `docs/roadmap.md` — mark #129 shipped

**Files:**
- Modify: `docs/roadmap.md`

- [ ] **Step 1: Move #129 from Phase 3 Open to Shipped**

In `docs/roadmap.md`, Phase 3 currently has #122, #127, #128 in Shipped and #21, #22, #129 in Open. Counter is `3/6 closed`.

Add a new Shipped bullet:

```markdown
- ✅ tiko-test: production components in `src/main/java/` and test fixtures in `src/test/java/` — `AggregatingContainer` federates the test container with the existing main at runtime via `META-INF/tiko/test-shadows.properties` ([#129](https://github.com/tomas-samek/tiko-di/issues/129)).
```

Remove the corresponding Open entry. Update the counter `3/6 closed` → `4/6 closed`.

- [ ] **Step 2: Commit**

```bash
git add docs/roadmap.md
git commit -m "docs(roadmap): mark #129 shipped under Phase 3"
```

---

## Task 11: Final smoke + PR

- [ ] **Step 1: Full clean build**

Run: `W:\tools\apache-maven\bin\mvn clean install`
Expected: BUILD SUCCESS across all modules.

- [ ] **Step 2: Full test run**

Run: `W:\tools\apache-maven\bin\mvn test`
Expected: all green.

- [ ] **Step 3: Push the branch**

```bash
git push -u origin spec/129-test-container-peer-via-aggregator
```

- [ ] **Step 4: Open the PR**

```bash
gh pr create --title "feat: test container as peer of main, federated via AggregatingContainer (#129)" --body "Closes #129. Implements docs/superpowers/specs/2026-05-22-test-container-peer-via-aggregator-design.md."
```

---

## Self-review notes

**Spec coverage:**
- `TikoOptions.internalAddOverride[IfAbsent]`: Task 1 ✓
- `AggregatingContainer` reads test-shadows + registers overrides: Task 2 ✓
- `Tiko.createInternal` always aggregates in test mode: Task 3 ✓
- `ProcessorContext.getAllActiveComponents()`: Task 4 ✓
- Drop `extensibleMainContainer`: Task 5 ✓
- `mainDescriptorFqnOnClasspath` helper: Task 6 ✓
- Standalone test container + shadow file emission + skip main regeneration: Task 7 ✓
- `12_testing` example moves to natural layout: Task 8 ✓
- Docs + roadmap: Tasks 9, 10 ✓
- Final PR: Task 11 ✓

**Type / name consistency:**
- `TestContainerImpl_<hash>` (new name) replaces `TestTikoContainerImpl_<hash>` (old name from T12) — applied uniformly in Tasks 7, 8.
- `test-shadows.properties` resource path used consistently across Tasks 2 (reads) and 7 (writes).
- `internalAddOverrideIfAbsent` signature consistent across Tasks 1 (defines) and 2 (calls).
- `getAllActiveComponents()` defined in Task 4, used in Task 7.

**Known risks:**
- Compile-testing harness doesn't have a real Maven compile/test-compile distinction, so some test assertions are necessarily loose. Task 8 (real Maven build of `12_testing`) is the integration test that proves the design end-to-end.
- Task 5's commented-out T12-era assertions need to be reconciled in Task 7. Order matters; if Task 7 implementer doesn't notice, the commented assertions linger.
- Task 7's `writeTestShadowsFile` uses `context.getShadowedMainKeys()` — verify this matches the actual method name on `ProcessorContext`. The T11 implementer (#127 era) added it; spec referenced the name but the exact form lives in code.
- Multi-module test scenarios where two modules' `test-shadows.properties` claim the same key: Task 2's warn-and-first-wins handles it but doesn't actively test it. Edge case; may file follow-up.
