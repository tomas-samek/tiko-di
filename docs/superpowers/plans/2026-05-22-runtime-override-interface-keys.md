# Runtime Override Interface-Keys Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `TikoOptions.override(Interface.class, mock)` apply at constructor-injection sites that depend on `Interface`, not just at `container.get(ConcreteImpl.class)`. Achieved by moving the override consultation from the per-component getter to the *injection site* (factory parameter resolution + dispatcher entry points).

**Architecture:** Per-call-site fan-out. `ComponentFactoryGenerator` wraps each dependency resolution in an `options.hasOverride(declaredType.class)` ternary using the parameter's declared type as the key. The `get(Class)` / `get(Class, String)` dispatchers consult overrides at the top before type-arm dispatch. Per-component getter (`getSingleton_X` / `emitScopedGetOrCreate`) loses its override check — becomes a pure factory cache.

**Tech Stack:** Java 21, Maven, JUnit 5, Google `compile-testing`, AssertJ, Mockito.

**Spec:** `docs/superpowers/specs/2026-05-22-runtime-override-interface-keys-design.md`
**Tracker:** [#128](https://github.com/tomas-samek/tiko-di/issues/128)
**Branch:** continue on `spec/128-runtime-override-interface-keys`.

---

## Task 1: Dispatcher consults overrides at `get(Class)` head

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java` (`createGetMethod` around line 1655)
- Test: `tiko-processor/src/test/java/io/tiko/processor/DispatcherGetOverrideTest.java`

- [ ] **Step 1: Write the failing test**

Create `tiko-processor/src/test/java/io/tiko/processor/DispatcherGetOverrideTest.java`:

```java
package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class DispatcherGetOverrideTest {

    @Test
    void getDispatcherChecksOverrideOnLookupTypeFirst() throws Exception {
        var iface = JavaFileObjects.forSourceLines(
                "demo.Gateway",
                "package demo;",
                "public interface Gateway {}");
        var impl = JavaFileObjects.forSourceLines(
                "demo.HttpGateway",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class HttpGateway implements Gateway {}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(iface, impl);
        assertThat(c).succeeded();

        var container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl_"))
                .findFirst().orElseThrow();
        var content = new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // get(Class) must check options.hasOverride(type) before the type-arm dispatch.
        org.assertj.core.api.Assertions.assertThat(content)
                .containsPattern("public <T> T get\\(Class<T> type\\)[^}]*options\\.hasOverride\\(type\\)");
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=DispatcherGetOverrideTest`
Expected: FAIL — `get(Class)` body doesn't currently call `options.hasOverride(type)` at all (overrides have only been checked in per-component getters, keyed on concrete classes).

- [ ] **Step 3: Add override check at the top of `createGetMethod`**

In `ContainerGenerator.java`, find `createGetMethod()` (around line 1655). Locate the method body where the type-arm `if/else` chain starts. Prepend an override check as the very first statement:

```java
method.beginControlFlow("if (options.hasOverride(type))");
method.addStatement("return (T) options.getOverride(type).get()");
method.endControlFlow();
```

(Use the exact JavaPoet idiom matching neighbouring emissions — `beginControlFlow` / `addStatement` / `endControlFlow`.)

- [ ] **Step 4: Run the test to confirm it passes**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=DispatcherGetOverrideTest`
Expected: PASS.

- [ ] **Step 5: Full suite for regressions**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test`
Expected: all green. Existing T6-T8 tests still pass because we haven't removed the per-component getter override check yet (Task 5 + Task 6).

- [ ] **Step 6: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java \
        tiko-processor/src/test/java/io/tiko/processor/DispatcherGetOverrideTest.java
git commit -m "feat(processor): get(Class) dispatcher checks options.hasOverride at head"
```

(Spotless apply if needed.)

---

## Task 2: Dispatcher consults overrides at `get(Class, String)` head

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java` (`createGetWithNameMethod`)
- Test: `tiko-processor/src/test/java/io/tiko/processor/DispatcherGetWithNameOverrideTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class DispatcherGetWithNameOverrideTest {

    @Test
    void getWithNameDispatcherChecksOverrideOnLookupTypeAndNameFirst() throws Exception {
        var iface = JavaFileObjects.forSourceLines(
                "demo.Gateway",
                "package demo;",
                "public interface Gateway {}");
        var impl = JavaFileObjects.forSourceLines(
                "demo.HttpGateway",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON, name = \"primary\")",
                "public class HttpGateway implements Gateway {}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(iface, impl);
        assertThat(c).succeeded();

        var container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl_"))
                .findFirst().orElseThrow();
        var content = new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // get(Class, String) must check options.hasOverride(type, name) at the head, before the per-arm fan-out.
        org.assertj.core.api.Assertions.assertThat(content)
                .containsPattern("public <T> T get\\(Class<T> type, String name\\)[^{]*\\{[^}]*options\\.hasOverride\\(type, name\\)");
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=DispatcherGetWithNameOverrideTest`
Expected: FAIL.

- [ ] **Step 3: Add override check at the top of `createGetWithNameMethod`**

In `ContainerGenerator.java`, find `createGetWithNameMethod()`. Prepend as the first body statements:

```java
method.beginControlFlow("if (options.hasOverride(type, name))");
method.addStatement("return (T) options.getOverride(type, name).get()");
method.endControlFlow();
```

**Important:** leave the existing per-arm `options.hasOverride(key, componentName)` fan-out (the T8 emission around lines 1789-1792) UNCHANGED. The top-of-method check is a fast-path for the user's lookup key; the per-arm checks still catch the case where the user looks up `Impl.class` with name X but overrode under `Interface.class` with name X.

- [ ] **Step 4: Run the test to confirm it passes**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=DispatcherGetWithNameOverrideTest`
Expected: PASS.

- [ ] **Step 5: Full suite**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test`
Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java \
        tiko-processor/src/test/java/io/tiko/processor/DispatcherGetWithNameOverrideTest.java
git commit -m "feat(processor): get(Class, String) dispatcher checks options.hasOverride at head"
```

(Spotless apply if needed.)

---

## Task 3: Factory wraps direct dependency resolution with per-call-site override check

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/ComponentFactoryGenerator.java` (`createFactoryMethod` around lines 122-129)
- Test: `tiko-processor/src/test/java/io/tiko/processor/FactoryOverrideInterfaceKeyTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class FactoryOverrideInterfaceKeyTest {

    @Test
    void factoryWrapsInterfaceParamResolutionWithOverrideKeyedOnInterface() throws Exception {
        var iface = JavaFileObjects.forSourceLines(
                "demo.Gateway",
                "package demo;",
                "public interface Gateway {}");
        var impl = JavaFileObjects.forSourceLines(
                "demo.HttpGateway",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class HttpGateway implements Gateway {}");
        var consumer = JavaFileObjects.forSourceLines(
                "demo.UsesGateway",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class UsesGateway {",
                "    @Inject public UsesGateway(Gateway g) {}",
                "}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(iface, impl, consumer);
        assertThat(c).succeeded();

        var factory = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("UsesGatewayFactory"))
                .findFirst().orElseThrow();
        var content = new String(factory.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // The factory must consult override under Gateway.class (the param's declared type),
        // not just HttpGateway.class.
        org.assertj.core.api.Assertions.assertThat(content)
                .contains("options.hasOverride(Gateway.class)")
                .contains("options.getOverride(Gateway.class).get()");
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=FactoryOverrideInterfaceKeyTest`
Expected: FAIL — factory currently emits `Gateway g = container.getHttpGateway();` with no override check at the call site.

- [ ] **Step 3: Wrap the direct-dependency emission**

In `ComponentFactoryGenerator.java`, find the `else` branch handling direct dependencies (around lines 122-129, the branch after `isPicker()`):

```java
} else {
    // Direct dependency resolution
    methodBuilder.addStatement(
            "$T $L = $L",
            TypeName.get(dependency.getType()),
            paramName,
            generateContainerGetCall(dependency, component));
}
```

Replace it with an override-aware wrap. The declared type is `TypeName.get(dependency.getType())`. For named dependencies (qualifier present), use the `(Type, name)` form; otherwise the type-only form.

```java
} else {
    // Direct dependency resolution. Wrap with an override-aware lookup so a
    // TikoOptions.override(DeclaredType.class, ...) registered by tests applies
    // at the injection site, regardless of which concrete @Component provides
    // the type. The override key is the parameter's declared type — interface
    // when the consumer injects by interface.
    TypeName declaredType = TypeName.get(dependency.getType());
    String existing = generateContainerGetCall(dependency, component);
    if (dependency.getQualifier().isPresent() && !dependency.isPicked()) {
        String qualifier = dependency.getQualifier().get();
        methodBuilder.addStatement(
                "$1T $2L = options.hasOverride($1T.class, $3S) ? ($1T) options.getOverride($1T.class, $3S).get() : $4L",
                declaredType,
                paramName,
                qualifier,
                existing);
    } else {
        methodBuilder.addStatement(
                "$1T $2L = options.hasOverride($1T.class) ? ($1T) options.getOverride($1T.class).get() : $3L",
                declaredType,
                paramName,
                existing);
    }
}
```

**Note on access to `options`:** the generated factory class needs access to `options` from the container. Verify by reading the existing factory's constructor (around lines 80-87 of `ComponentFactoryGenerator.java`). If the factory holds a `container` field, use `container.options` if visible, OR add an `options` accessor on the container that the factory calls (`container.getOptions()` may already exist; check). If not, exposing the field requires a small additional change — flag in your report so we can decide whether to add a getter or change visibility.

- [ ] **Step 4: Run the test to confirm it passes**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=FactoryOverrideInterfaceKeyTest`
Expected: PASS.

- [ ] **Step 5: Full suite**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test`
Expected: all green. (Per-component getter still has its own override check at this point — Task 5 removes it. So overrides keyed by the concrete class are still honored.)

- [ ] **Step 6: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/generator/ComponentFactoryGenerator.java \
        tiko-processor/src/test/java/io/tiko/processor/FactoryOverrideInterfaceKeyTest.java
git commit -m "feat(processor): factory wraps each direct param resolution with override-keyed-by-declared-type"
```

(Spotless apply if needed. If you had to add an accessor or change visibility for `options`, list that file too.)

---

## Task 4: Factory wraps `Provider<T>` lambda with per-call-site override check

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/ComponentFactoryGenerator.java` (`createFactoryMethod` around lines 105-111)
- Test: `tiko-processor/src/test/java/io/tiko/processor/FactoryProviderOverrideTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class FactoryProviderOverrideTest {

    @Test
    void factoryProviderLambdaConsultsOverrideAtGetTime() throws Exception {
        var iface = JavaFileObjects.forSourceLines(
                "demo.Gateway",
                "package demo;",
                "public interface Gateway {}");
        var impl = JavaFileObjects.forSourceLines(
                "demo.HttpGateway",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class HttpGateway implements Gateway {}");
        var consumer = JavaFileObjects.forSourceLines(
                "demo.UsesProvider",
                "package demo;",
                "import io.tiko.Provider;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class UsesProvider {",
                "    @Inject public UsesProvider(Provider<Gateway> g) {}",
                "}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(iface, impl, consumer);
        assertThat(c).succeeded();

        var factory = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("UsesProviderFactory"))
                .findFirst().orElseThrow();
        var content = new String(factory.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Provider's lambda body must consult options.hasOverride(Gateway.class).
        org.assertj.core.api.Assertions.assertThat(content)
                .contains("options.hasOverride(Gateway.class)");
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=FactoryProviderOverrideTest`
Expected: FAIL.

- [ ] **Step 3: Wrap the Provider<T> lambda body**

In `ComponentFactoryGenerator.java`, find the `dependency.isProvider()` branch (around lines 105-111):

```java
if (dependency.isProvider()) {
    methodBuilder.addStatement(
            "$T $L = () -> $L",
            TypeName.get(dependency.getType()),
            paramName,
            generateContainerGetCall(dependency, component));
}
```

Replace with a richer lambda that consults override at `get()` time:

```java
if (dependency.isProvider()) {
    // Provider<T>'s lambda consults the override at call time so overrides
    // registered after Provider construction still take effect, and so the
    // resolution key is the inner type T (not Provider<T>).
    TypeName providerType = TypeName.get(dependency.getType());
    TypeName innerType = TypeName.get(dependency.getUnwrappedType().orElseThrow());
    String existing = generateContainerGetCall(dependency, component);
    if (dependency.getQualifier().isPresent() && !dependency.isPicked()) {
        String qualifier = dependency.getQualifier().get();
        methodBuilder.addStatement(
                "$1T $2L = () -> options.hasOverride($3T.class, $4S) ? ($3T) options.getOverride($3T.class, $4S).get() : $5L",
                providerType,
                paramName,
                innerType,
                qualifier,
                existing);
    } else {
        methodBuilder.addStatement(
                "$1T $2L = () -> options.hasOverride($3T.class) ? ($3T) options.getOverride($3T.class).get() : $4L",
                providerType,
                paramName,
                innerType,
                existing);
    }
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=FactoryProviderOverrideTest`
Expected: PASS.

- [ ] **Step 5: Full suite**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test`
Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/generator/ComponentFactoryGenerator.java \
        tiko-processor/src/test/java/io/tiko/processor/FactoryProviderOverrideTest.java
git commit -m "feat(processor): Provider<T> lambda consults override at get() time on inner type"
```

(Spotless apply if needed.)

---

## Task 5: Remove override check from SINGLETON per-component getter

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java` (SINGLETON path around line 1073)
- Test: `tiko-processor/src/test/java/io/tiko/processor/SingletonGetterPureFactoryTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class SingletonGetterPureFactoryTest {

    @Test
    void generatedSingletonGetterContainsNoOverrideCheck() throws Exception {
        var src = JavaFileObjects.forSourceLines(
                "demo.Service",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Service {}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        assertThat(c).succeeded();

        var container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl_"))
                .findFirst().orElseThrow();
        var content = new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // getService() must not contain options.hasOverride — the override check has
        // moved to the dispatcher entry points (Tasks 1, 2) and to the factory's
        // per-param resolution (Task 3).
        // Extract just the getService body to scope the check.
        int idx = content.indexOf("getService()");
        org.assertj.core.api.Assertions.assertThat(idx)
                .as("getService() method present in generated container")
                .isGreaterThan(0);

        int bodyStart = content.indexOf('{', idx);
        int bodyEnd = content.indexOf("\n    }", bodyStart);
        String body = content.substring(bodyStart, bodyEnd);

        org.assertj.core.api.Assertions.assertThat(body)
                .as("getService() body must not consult overrides")
                .doesNotContain("options.hasOverride")
                .doesNotContain("options.getOverride");
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=SingletonGetterPureFactoryTest`
Expected: FAIL — `getService()` currently contains `options.hasOverride(Service.class)`.

- [ ] **Step 3: Remove the override consultation from the SINGLETON emission**

In `ContainerGenerator.java`, find the SINGLETON case (around line 1073). The current emission:

```java
"return ($1T) singletons.computeIfAbsent($2S, k -> options.hasOverride($3T.class) ? options.getOverride($3T.class).get() : $4L.create())"
```

becomes:

```java
"return ($1T) singletons.computeIfAbsent($2S, k -> $4L.create())"
```

Remove the now-unused `$3T` placeholder argument from the emission's argument list.

- [ ] **Step 4: Run the test to confirm it passes**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=SingletonGetterPureFactoryTest`
Expected: PASS.

- [ ] **Step 5: Full suite — expect existing override tests to potentially fail**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test`
Expected: Some pre-existing tests may fail because they assert `options.hasOverride(...)` appears inside the SINGLETON getter (e.g. `SingletonOverrideTest` from T6 of the #122 work). Read the failures.

If they fail because they're asserting against the OLD getter shape, update them to assert against the NEW location — the dispatcher (`get(Class)`) or the factory (`UsesXFactory`). Specifically:

- `SingletonOverrideTest.singletonGetterChecksOptionsOverrideInsideComputeIfAbsent` — this test's whole premise is wrong post-Task 5. Either:
  - Delete it (the new tests in this plan cover the override flow at its new home), OR
  - Rewrite it to assert that the dispatcher / factory now do the check.

Pick whichever is cleaner. Document the change in the commit message.

- [ ] **Step 6: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java \
        tiko-processor/src/test/java/io/tiko/processor/SingletonGetterPureFactoryTest.java
# Add any T6-era test files you had to update or delete:
git add tiko-processor/src/test/java/io/tiko/processor/SingletonOverrideTest.java
git commit -m "refactor(processor): SINGLETON getter becomes pure factory cache; override moves to dispatcher + factory call sites"
```

(Spotless apply if needed.)

---

## Task 6: Remove override check from `emitScopedGetOrCreate` (REQUEST/EVENT)

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java` (`emitScopedGetOrCreate` around lines 1184-1200)
- Test: `tiko-processor/src/test/java/io/tiko/processor/ScopedGetterPureFactoryTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class ScopedGetterPureFactoryTest {

    @Test
    void requestGetterContainsNoOverrideCheck() throws Exception {
        verifyNoOverride("REQUEST", "RC");
    }

    @Test
    void eventGetterContainsNoOverrideCheck() throws Exception {
        verifyNoOverride("EVENT", "EC");
    }

    private static void verifyNoOverride(String scope, String className) throws Exception {
        var src = JavaFileObjects.forSourceLines(
                "demo." + className,
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope." + scope + ")",
                "public class " + className + " {}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        assertThat(c).succeeded();

        var container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl_"))
                .findFirst().orElseThrow();
        var content = new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        int idx = content.indexOf("get" + className + "()");
        org.assertj.core.api.Assertions.assertThat(idx).isGreaterThan(0);
        int bodyStart = content.indexOf('{', idx);
        int bodyEnd = content.indexOf("\n    }", bodyStart);
        String body = content.substring(bodyStart, bodyEnd);

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("options.hasOverride")
                .doesNotContain("options.getOverride");
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=ScopedGetterPureFactoryTest`
Expected: FAIL — both getters currently contain the override check.

- [ ] **Step 3: Remove the override consultation from `emitScopedGetOrCreate`**

In `ContainerGenerator.java`, find `emitScopedGetOrCreate` (around lines 1184-1200). The current emission inside the `if (__existing == null)` block:

```java
method.addStatement(
        "__existing = options.hasOverride($1T.class) ? ($2T) options.getOverride($1T.class).get() : $3L",
        componentType, returnType, createExpr);
```

becomes:

```java
method.addStatement("__existing = $L", createExpr);
```

Remove the now-unused `componentType` parameter from the method signature if it's no longer used elsewhere in the helper, OR keep it and just stop referencing it (your call — verify by reading the full helper to see if `componentType` is used in other statements).

`emitScopedGetOrCreateNoOverride` (for `@Produces`) stays untouched — it was already override-free.

- [ ] **Step 4: Run the test to confirm it passes**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=ScopedGetterPureFactoryTest`
Expected: PASS.

- [ ] **Step 5: Full suite — update pre-existing tests if needed**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test`
Expected: `ScopedOverrideTest` from T7 of the #122 work may fail because it asserts the override check appears in the REQUEST/EVENT getters. Rewrite it the same way Task 5 handled `SingletonOverrideTest` — either delete or rewrite to assert against the new locations (dispatcher / factory).

- [ ] **Step 6: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java \
        tiko-processor/src/test/java/io/tiko/processor/ScopedGetterPureFactoryTest.java
git add tiko-processor/src/test/java/io/tiko/processor/ScopedOverrideTest.java
git commit -m "refactor(processor): REQUEST + EVENT getters become pure factory caches; override at dispatcher + factory sites"
```

(Spotless apply if needed.)

---

## Task 7: Restore `MockedPaymentTest` in `tiko-examples/12_testing/`

**Files:**
- Create: `tiko-examples/12_testing/src/test/java/io/tiko/examples/testing/payment/MockedPaymentTest.java`
- Modify: `tiko-examples/12_testing/README.md` (restore the row + remove the workaround note)

- [ ] **Step 1: Create the test**

`tiko-examples/12_testing/src/test/java/io/tiko/examples/testing/payment/MockedPaymentTest.java`:

```java
package io.tiko.examples.testing.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.tiko.Container;
import io.tiko.examples.testing.service.OrderService;
import io.tiko.examples.testing.service.PaymentGateway;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import org.junit.jupiter.api.Test;

class MockedPaymentTest {

    @Test
    void runtimeOverrideSwapsInAMockitoMockOfTheInterface() {
        PaymentGateway mock = mock(PaymentGateway.class);
        when(mock.charge(anyString(), anyLong())).thenReturn("MOCK-TXN");

        try (Container c = Tiko.create(TikoOptions.builder()
                .override(PaymentGateway.class, () -> mock)
                .build())) {
            String txn = c.get(OrderService.class).create("alice", 100L);
            assertThat(txn).isEqualTo("MOCK-TXN");
            verify(mock).charge("alice", 100L);
        }
    }
}
```

NOTE: verify the existing `OrderService.create(String, long)` signature in the example. If the actual method has a different name/signature, adjust accordingly.

- [ ] **Step 2: Verify the mockito-core dependency is on the example's test classpath**

Check `tiko-examples/12_testing/pom.xml` — if `org.mockito:mockito-core` isn't already a test-scope dep, add it. (It was originally listed in the spec for T16 of #122; check if T16's implementer dropped it because `MockedPaymentTest` was dropped.)

- [ ] **Step 3: Run the example tests**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-examples/12_testing -am test`
Expected: all tests PASS, including `MockedPaymentTest`. If `MockedPaymentTest` fails with the mock not being used, it indicates the override didn't propagate — re-check Tasks 1-4.

- [ ] **Step 4: Update `tiko-examples/12_testing/README.md`**

Restore the `MockedPaymentTest` row to the demo table. Remove (or reword) any "known limitation" note about runtime override not working through interfaces — the limitation is now fixed.

- [ ] **Step 5: Commit**

```bash
git add tiko-examples/12_testing/src/test/java/io/tiko/examples/testing/payment/MockedPaymentTest.java \
        tiko-examples/12_testing/README.md
# Include pom.xml if you had to add mockito-core:
git add tiko-examples/12_testing/pom.xml
git commit -m "feat(tiko-examples): restore MockedPaymentTest demonstrating interface-keyed override"
```

(Spotless apply if needed.)

---

## Task 8: Update `docs/testing.md` — replace #128 limitation with documented behavior

**Files:**
- Modify: `docs/testing.md`

- [ ] **Step 1: Read the current `docs/testing.md`**

Open `W:\workspace\tiko-di\docs\testing.md` and locate:
1. The section that introduces `TikoOptions.override(...)`.
2. The "Known limitations" entry that mentions #128 (interface-key override not working).

- [ ] **Step 2: Update the override section to lead with interface-keyed usage**

In the section introducing `TikoOptions.override(...)`, lead with the interface-keyed example (matching the project's `[[interfaces-and-composition-over-impls-and-inheritance]]` memory and `[[test-against-interfaces-not-impls]]` memory):

```markdown
### `TikoOptions.override(...)`

Replace any component at runtime. The override key is the *type the consumer
depends on* — typically an interface:

```java
PaymentGateway mock = mock(PaymentGateway.class);
try (Container c = Tiko.create(TikoOptions.builder()
        .override(PaymentGateway.class, () -> mock)
        .build())) {
    // Any @Component that injects PaymentGateway gets `mock`, regardless of
    // which concrete @Component implements it.
}
```

The override applies at every injection site that asks for `PaymentGateway`,
plus at `container.get(PaymentGateway.class)` and `getProvider(PaymentGateway.class)`.

For qualified injection (`@Named("primary") PaymentGateway`), use the
named-key form: `override(PaymentGateway.class, "primary", () -> mock)`.
```

- [ ] **Step 3: Remove the "Known limitations" entry for #128**

Remove the limitation entry that referenced #128. Keep #129 (test-compile main-component visibility) — still real.

- [ ] **Step 4: Verify spotless**

Run: `W:\tools\apache-maven\bin\mvn spotless:apply` and `W:\tools\apache-maven\bin\mvn -pl '!tiko-bom' spotless:check`
Expected: clean.

- [ ] **Step 5: Commit**

```bash
git add docs/testing.md
git commit -m "docs(testing): override applies at every injection site; key by interface"
```

---

## Task 9: Update `docs/roadmap.md` — mark #128 shipped

**Files:**
- Modify: `docs/roadmap.md`

- [ ] **Step 1: Move #128 from Phase 3 "Open" to "Shipped"**

In `docs/roadmap.md`, locate the Phase 3 section. Move the #128 bullet from Open to Shipped. Update the counter (currently `2/6 closed` after #127 shipped; should become `3/6 closed`).

Sample new Shipped entry:

```markdown
- ✅ tiko-test: `TikoOptions.override(...)` applies at injection sites keyed by the parameter's declared type (interfaces work naturally; Mockito mocks of interfaces work without `mockito-inline`) ([#128](https://github.com/tomas-samek/tiko-di/issues/128)).
```

- [ ] **Step 2: Commit**

```bash
git add docs/roadmap.md
git commit -m "docs(roadmap): mark #128 shipped under Phase 3"
```

---

## Task 10: Final smoke + PR

- [ ] **Step 1: Full clean build**

Run: `W:\tools\apache-maven\bin\mvn clean install`
Expected: BUILD SUCCESS across all modules.

- [ ] **Step 2: Full test run**

Run: `W:\tools\apache-maven\bin\mvn test`
Expected: all green.

- [ ] **Step 3: Push the branch**

```bash
git push -u origin spec/128-runtime-override-interface-keys
```

- [ ] **Step 4: Open the PR**

```bash
gh pr create --title "feat: TikoOptions.override applies at injection sites by declared type (#128)" --body "Closes #128. Implements docs/superpowers/specs/2026-05-22-runtime-override-interface-keys-design.md."
```

---

## Self-review notes

**Spec coverage:**
- Per-call-site fan-out in factory: Tasks 3, 4 ✓
- Dispatcher override at `get(Class)`: Task 1 ✓
- Dispatcher override at `get(Class, String)`: Task 2 ✓
- Per-component getter becomes override-free: Tasks 5, 6 ✓
- `MockedPaymentTest` restored: Task 7 ✓
- Docs lead with interface-keyed pattern: Task 8 ✓
- Roadmap update: Task 9 ✓

**Type consistency:**
- `options.hasOverride(type)` / `options.getOverride(type).get()` signature consistent across Tasks 1, 2, 3, 4.
- `dependency.getType()` used in Tasks 3 + 4 for the declared type (matches existing `ComponentFactoryGenerator` usage).
- `dependency.getUnwrappedType()` used in Task 4 for the Provider's inner type (matches existing usage on line 180).
- `dependency.getQualifier()` for named-key form (Tasks 3 + 4) — matches existing usage.

**Known risks:**
- Task 3 assumes `options` is accessible from the generated factory. If the factory currently only has `container`, we may need to expose `options` via `container.getOptions()` or a similar accessor. Task 3 Step 3 flags this — implementer reports if a fix is needed.
- Tasks 5 + 6 will fail the existing `SingletonOverrideTest` and `ScopedOverrideTest` (from T6/T7 of #122). The plan calls out the rewrite; the implementer needs to choose between deletion or rewriting against the new locations. Deletion is fine because the new tests in Tasks 1, 3, 4, 5, 6 cover the same behavior at its new home.
- Picker / @Pick handling: the current `generateContainerGetCall` resolves @Pick to a direct `container.getXxx()` call. The wrap in Task 3 uses `dependency.getType()` as the override key — which for @Pick is the picked impl class (because `dependency.getType()` reflects the parameter's declared type, which is what @Pick'd consumers wrote). Verify by spot-check during Task 3 review; the @Pick case may need its own test if behavior diverges from expectations.

**Scope deferral — `getAll(Class)`:**
- The spec explicitly defers `getAll(Class)` override behavior. No task addresses it; documented in `docs/testing.md` if a user surfaces a need.
