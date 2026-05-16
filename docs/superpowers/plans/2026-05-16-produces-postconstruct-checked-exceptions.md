# `@Produces` and `@PostConstruct` Checked-Exception Propagation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let `@Produces` factory methods and `@PostConstruct` lifecycle methods declare checked exceptions naturally; the processor catches `Throwable`, publishes a structured `ErrorContext`, and propagates the user's original throwable via sneaky-throw with stack trace + identity preserved.

**Architecture:** New `ProduceFailure` ErrorContext (mirrors `PostConstructFailure`). New `Unchecked.sneakyThrow` helper in `tiko-runtime`. Both generators (`ComponentFactoryGenerator` for `@PostConstruct`, `ContainerGenerator` for `@Produces`) widen their catch from `RuntimeException | Error` to `Throwable` and append a sneaky-throw of the original. For `@Produces`, a per-factory helper method `invokeFactory_<id>()` carries the try/catch so the scoped-storage emission sites (singleton/REQUEST/EVENT/PROTOTYPE) stay one-line lambdas.

**Tech Stack:** Java 21, JavaPoet, Google compile-testing, JUnit 5, AssertJ. No new runtime dependencies.

---

## File structure

```
tiko-api/src/main/java/io/tiko/
├── ErrorContext.java                       (modify — append ProduceFailure to permits)
└── ProduceFailure.java                     (create — new record)

tiko-runtime/src/main/java/io/tiko/runtime/
└── Unchecked.java                          (create — sneakyThrow helper)
tiko-runtime/src/test/java/io/tiko/runtime/
├── UncheckedTest.java                      (create — tiny unit test)
└── CheckedExceptionPropagationIT.java      (create — e2e test of both annotations)

tiko-processor/src/main/java/io/tiko/processor/generator/
├── ComponentFactoryGenerator.java          (modify — widen @PostConstruct catch + sneakyThrow)
└── ContainerGenerator.java                 (modify — invokeFactory_<id> helper + wrap)
tiko-processor/src/test/java/io/tiko/processor/
├── PostConstructCheckedExceptionPropagationTest.java   (create — TDD regression)
└── ProducesCheckedExceptionPropagationTest.java        (create — TDD regression)

tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/infra/
├── JdbcConnectionProvider.java             (modify — revert IllegalStateException wrap)
└── SchemaInitializer.java                  (modify — revert IllegalStateException wrap)

docs/cookbooks/persistence.md               (modify — drop quirk callout, restore natural throws snippet)
docs/roadmap.md                             (modify — add "What ships today" entry)
```

---

## Task 1: `ProduceFailure` record + sealed permits update

**Files:**
- Create: `tiko-api/src/main/java/io/tiko/ProduceFailure.java`
- Modify: `tiko-api/src/main/java/io/tiko/ErrorContext.java`

- [ ] **Step 1: Create `ProduceFailure.java`**

```java
package io.tiko;

/**
 * Error context raised when a {@code @Produces} factory method throws.
 *
 * <p>The framework calls {@link ErrorHandler#onError(ErrorContext)} before
 * re-throwing the cause via sneaky-throw, so observability code sees the
 * failure even though the original throwable continues to propagate (with
 * its type and stack trace intact) to the {@code container.get(...)}
 * caller. Same hard-fail contract as {@link PostConstructFailure}.
 *
 * @param declaringClass the class that declares the {@code @Produces} method
 * @param methodName     the simple method name of the {@code @Produces}
 *     factory (one factory class may carry multiple, qualifier-disambiguated
 *     factories — the qualifier itself is reachable via the method's
 *     {@code @Produces(name=...)}; we don't duplicate it here)
 * @param cause          the throwable thrown by the factory method
 */
public record ProduceFailure(Class<?> declaringClass, String methodName, Throwable cause) implements ErrorContext {}
```

- [ ] **Step 2: Append `ProduceFailure` to `ErrorContext` permits**

In `tiko-api/src/main/java/io/tiko/ErrorContext.java`, change the permits list (currently ends with `TransportError`) to include `ProduceFailure`:

```java
public sealed interface ErrorContext
        permits EventHandlerError,
                PostConstructFailure,
                PreDestroyFailure,
                AutoCloseFailure,
                ConfigurationFailure,
                TransportError,
                ProduceFailure {
```

Also update the Javadoc's `switch (ctx)` example to include the new case (add the line just before `case TransportError`):

```java
 *         case ProduceFailure f        -> metrics.factoryError(f.declaringClass(), f.methodName());
```

And add a one-sentence description in the prose between the existing categories: "{@link ProduceFailure} is a `@Produces` factory method that threw — the framework publishes this before re-throwing the original cause via sneaky-throw so the consumer of `container.get(...)` sees the user's exception unchanged."

- [ ] **Step 3: Verify compile**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-api compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add tiko-api/src/main/java/io/tiko/ProduceFailure.java tiko-api/src/main/java/io/tiko/ErrorContext.java
git commit -m "feat(api): ProduceFailure ErrorContext for @Produces factory throws"
```

---

## Task 2: `Unchecked.sneakyThrow` helper + unit test

**Files:**
- Create: `tiko-runtime/src/main/java/io/tiko/runtime/Unchecked.java`
- Create: `tiko-runtime/src/test/java/io/tiko/runtime/UncheckedTest.java`

- [ ] **Step 1: Create `Unchecked.java`**

```java
package io.tiko.runtime;

/**
 * Internal helper used by Tiko-generated code to propagate checked
 * exceptions across method boundaries that don't declare them. Not part of
 * the public API surface — generated code is the only intended caller.
 *
 * <p>This sidesteps the type-system cascade that would otherwise force
 * {@code Container#get(Class)}, every {@code Provider<T>}, and every
 * intermediate factory accessor to declare {@code throws Throwable}. The
 * user's original throwable propagates as itself; callers handle it via
 * {@code catch (Exception e)} or {@code instanceof}.
 */
public final class Unchecked {

    private Unchecked() {}

    @SuppressWarnings("unchecked")
    public static <T extends Throwable> T sneakyThrow(Throwable e) throws T {
        throw (T) e;
    }
}
```

- [ ] **Step 2: Create `UncheckedTest.java`**

```java
package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/**
 * Verifies the sneaky-throw helper rethrows the original throwable instance
 * (identity, not just type) without adding any wrapping frames.
 */
class UncheckedTest {

    @Test
    void sneakyThrowRethrowsOriginalCheckedException() {
        SQLException original = new SQLException("nope");
        try {
            Unchecked.<RuntimeException>sneakyThrow(original);
            fail("sneakyThrow should have thrown");
        } catch (Throwable t) {
            assertThat(t).isSameAs(original);
        }
    }

    @Test
    void sneakyThrowRethrowsOriginalRuntimeException() {
        IllegalStateException original = new IllegalStateException("nope");
        try {
            Unchecked.<RuntimeException>sneakyThrow(original);
            fail("sneakyThrow should have thrown");
        } catch (RuntimeException t) {
            assertThat(t).isSameAs(original);
        }
    }
}
```

- [ ] **Step 3: Run the tests**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-runtime test -Dtest=UncheckedTest`
Expected: `Tests run: 2, Failures: 0, Errors: 0`. BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add tiko-runtime/src/main/java/io/tiko/runtime/Unchecked.java tiko-runtime/src/test/java/io/tiko/runtime/UncheckedTest.java
git commit -m "feat(runtime): Unchecked.sneakyThrow helper for generated code"
```

---

## Task 3: `@PostConstruct` catch widening — TDD

**Files:**
- Create: `tiko-processor/src/test/java/io/tiko/processor/PostConstructCheckedExceptionPropagationTest.java`
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/ComponentFactoryGenerator.java`

- [ ] **Step 1: Write the failing test**

Create `PostConstructCheckedExceptionPropagationTest.java`:

```java
package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for Issue #97: a {@code @PostConstruct} method may
 * declare a checked exception. The generated factory's {@code create()}
 * catches {@link Throwable} (widened from the prior
 * {@code RuntimeException | Error}), publishes {@code PostConstructFailure}
 * for observability, and sneaky-throws the original to propagate.
 */
class PostConstructCheckedExceptionPropagationTest {

    @Test
    void postConstructDeclaringCheckedExceptionCompilesAndIsCaughtAsThrowable() throws IOException {
        JavaFileObject impl = JavaFileObjects.forSourceLines(
                "io.example.Init",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.PostConstruct;",
                "import java.sql.SQLException;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Init {",
                "  @PostConstruct public void start() throws SQLException {}",
                "}");

        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(impl);

        // Pre-fix: javac fails on the generated InitFactory because instance.start()
        // throws an undeclared SQLException.
        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject factorySource = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("InitFactory"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("InitFactory was not generated"));

        String body = new String(factorySource.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // The catch is widened to Throwable, ErrorHandler is still routed, and
        // the original throwable propagates via sneakyThrow.
        assertThat(body).contains("catch (Throwable __t)");
        assertThat(body).contains("container.getErrorHandler().onError(new PostConstructFailure(");
        assertThat(body).contains("Unchecked.<RuntimeException>sneakyThrow(__t)");
        assertThat(body).doesNotContain("catch (RuntimeException | Error __t)");
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-processor test -Dtest=PostConstructCheckedExceptionPropagationTest`
Expected: FAIL with two distinct symptoms — (a) `CompilationSubject.assertThat(c).succeeded()` fails because javac can't compile `InitFactory` (the `instance.start()` call throws undeclared `SQLException`), or (b) if compilation somehow succeeds, the `catch (Throwable __t)` assertion fails because the generator emits `catch (RuntimeException | Error __t)`.

- [ ] **Step 3: Modify `ComponentFactoryGenerator.java`**

In `tiko-processor/src/main/java/io/tiko/processor/generator/ComponentFactoryGenerator.java`, find the `@PostConstruct` invocation block (currently around lines 148-158). Replace:

```java
for (ExecutableElement postConstruct : component.getPostConstructMethods()) {
    methodBuilder.beginControlFlow("try");
    methodBuilder.addStatement("instance.$L()", postConstruct.getSimpleName());
    methodBuilder.nextControlFlow("catch ($T | $T __t)", RuntimeException.class, Error.class);
    methodBuilder.addStatement(
            "container.getErrorHandler().onError(new $T($T.class, __t))",
            ClassName.get("io.tiko", "PostConstructFailure"),
            componentClass);
    methodBuilder.addStatement("throw __t");
    methodBuilder.endControlFlow();
}
```

with:

```java
for (ExecutableElement postConstruct : component.getPostConstructMethods()) {
    methodBuilder.beginControlFlow("try");
    methodBuilder.addStatement("instance.$L()", postConstruct.getSimpleName());
    methodBuilder.nextControlFlow("catch ($T __t)", Throwable.class);
    methodBuilder.addStatement(
            "container.getErrorHandler().onError(new $T($T.class, __t))",
            ClassName.get("io.tiko", "PostConstructFailure"),
            componentClass);
    methodBuilder.addStatement(
            "throw $T.<$T>sneakyThrow(__t)",
            ClassName.get("io.tiko.runtime", "Unchecked"),
            RuntimeException.class);
    methodBuilder.endControlFlow();
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-processor test -Dtest=PostConstructCheckedExceptionPropagationTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0`. BUILD SUCCESS.

- [ ] **Step 5: Run the full processor suite to confirm no regression**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-processor test`
Expected: BUILD SUCCESS. All existing tests (including `LifecycleErrorRoutingTest` analogues) still pass; only the new test added.

If any existing test asserts the literal string `catch (RuntimeException | Error __t)` in generated output, update its assertion to `catch (Throwable __t)`. The semantic guarantee (PostConstructFailure routed, original throwable rethrown) is unchanged.

- [ ] **Step 6: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/generator/ComponentFactoryGenerator.java tiko-processor/src/test/java/io/tiko/processor/PostConstructCheckedExceptionPropagationTest.java
git commit -m "fix(processor): @PostConstruct can declare checked exceptions"
```

---

## Task 4: `@Produces` accessor wrap via per-factory helper — TDD

**Files:**
- Create: `tiko-processor/src/test/java/io/tiko/processor/ProducesCheckedExceptionPropagationTest.java`
- Modify: `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java`

**Design note:** the `produce_<id>()` accessor body uses `singletons.computeIfAbsent(key, k -> callExpr)` (or scope-equivalent) where `callExpr` is a single expression like `getX().connection()`. Wrapping a multi-statement try/catch inside the lambda would force a structural rewrite at every scope emission site. Instead, emit one `private <ReturnType> invokeFactory_<id>()` helper per factory that holds the try/catch, and change `callExpr` to call that helper. The lambda stays a one-line expression.

- [ ] **Step 1: Write the failing test**

Create `ProducesCheckedExceptionPropagationTest.java`:

```java
package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for Issue #97: a {@code @Produces} factory method may
 * declare a checked exception. The generated container emits a per-factory
 * helper {@code invokeFactory_<id>()} that catches {@link Throwable},
 * publishes {@code ProduceFailure}, and sneaky-throws the original. The
 * existing scoped getter ({@code produce_<id>()}) is unchanged in shape —
 * its lambda calls the helper instead of the user method directly.
 */
class ProducesCheckedExceptionPropagationTest {

    @Test
    void producesDeclaringCheckedExceptionCompilesAndIsCaughtAsThrowable() throws IOException {
        JavaFileObject factory = JavaFileObjects.forSourceLines(
                "io.example.PoolFactory",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "import javax.sql.DataSource;",
                "import java.sql.SQLException;",
                "@Component(scope = Scope.SINGLETON)",
                "public class PoolFactory {",
                "  @Produces(scope = Scope.SINGLETON)",
                "  public DataSource dataSource() throws SQLException { return null; }",
                "}");

        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(factory);

        // Pre-fix: javac fails because produce_PoolFactory_dataSource()
        // calls getPoolFactory().dataSource() with an undeclared SQLException.
        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject containerSource = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TikoContainerImpl was not generated"));

        String body = new String(containerSource.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // A per-factory invokeFactory_*() helper exists, with the try/catch
        // and the ProduceFailure + sneakyThrow plumbing.
        assertThat(body).contains("invokeFactory_PoolFactory_dataSource");
        assertThat(body).contains("catch (Throwable __t)");
        assertThat(body).contains("getErrorHandler().onError(new ProduceFailure(");
        assertThat(body).contains("\"dataSource\"");
        assertThat(body).contains("Unchecked.<RuntimeException>sneakyThrow(__t)");

        // The public scoped getter still exists and now calls the helper
        // rather than the user method directly.
        assertThat(body).contains("produce_PoolFactory_dataSource");
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-processor test -Dtest=ProducesCheckedExceptionPropagationTest`
Expected: FAIL — javac can't compile `TikoContainerImpl_*.java` because `getPoolFactory().dataSource()` throws undeclared `SQLException`.

- [ ] **Step 3: Modify `ContainerGenerator.java` — change `buildFactoryCallExpression` to call the helper**

In `tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java`, find `buildFactoryCallExpression(FactoryMethodModel factory)` (around line 440) which currently returns expressions like `getPoolFactory().dataSource()` or `PoolFactory.dataSource()` (static path). Replace its body so it ALWAYS returns the per-factory helper name (no args; the helper resolves dependencies internally):

```java
private String buildFactoryCallExpression(FactoryMethodModel factory) {
    // Always route through the per-factory invokeFactory_<id>() helper so the
    // try/catch + ErrorContext routing + sneaky-throw lives in exactly one place.
    // The helper itself, generated by createFactoryInvocationHelper, contains the
    // dependency-resolution + user-method-invocation logic that used to live here.
    return "invokeFactory_" + factory.getFactoryIdentifier() + "()";
}
```

- [ ] **Step 4: Modify `ContainerGenerator.java` — add the helper-method generator**

Still in `ContainerGenerator.java`, add a new private method that builds the helper. Place it adjacent to `createFactoryMethodGetter`:

```java
/**
 * Creates the per-factory invocation helper. The helper resolves the factory's
 * dependencies, invokes the user's {@code @Produces} method (instance or static),
 * and wraps the call in a {@code Throwable} catch that publishes
 * {@code ProduceFailure} and sneaky-throws the original cause. Each scoped
 * accessor ({@link #createFactoryMethodGetter}) calls this helper from its
 * single-expression lambda body.
 */
private MethodSpec createFactoryInvocationHelper(FactoryMethodModel factory) {
    TypeName returnType = TypeName.get(factory.getReturnType());
    String helperName = "invokeFactory_" + factory.getFactoryIdentifier();
    String declaringClassName = factory.getDeclaringClass().getSimpleName().toString();

    // Build the user-method-invocation expression (the old buildFactoryCallExpression body).
    List<String> args = new ArrayList<>();
    for (DependencyModel dep : factory.getDependencies()) {
        args.add(generateContainerGetCall(dep));
    }
    String argList = String.join(", ", args);
    String userCallExpr = factory.isStatic()
            ? String.format("%s.%s(%s)", declaringClassName, factory.getMethodName(), argList)
            : String.format("get%s().%s(%s)", declaringClassName, factory.getMethodName(), argList);

    return MethodSpec.methodBuilder(helperName)
            .addModifiers(Modifier.PRIVATE)
            .returns(returnType)
            .beginControlFlow("try")
            .addStatement("return $L", userCallExpr)
            .nextControlFlow("catch ($T __t)", Throwable.class)
            .addStatement(
                    "getErrorHandler().onError(new $T($T.class, $S, __t))",
                    ClassName.get("io.tiko", "ProduceFailure"),
                    ClassName.get(factory.getDeclaringClass()),
                    factory.getMethodName())
            .addStatement(
                    "throw $T.<$T>sneakyThrow(__t)",
                    ClassName.get("io.tiko.runtime", "Unchecked"),
                    RuntimeException.class)
            .endControlFlow()
            .build();
}
```

- [ ] **Step 5: Modify `ContainerGenerator.java` — emit the helpers alongside the scoped getters**

Find the spot in `ContainerGenerator` where `createFactoryMethodGetter(factory)` is invoked for each factory (search for `createFactoryMethodGetter(`). Add a sibling call so each factory contributes BOTH methods to the generated class:

```java
for (FactoryMethodModel factory : context.getActiveFactoryMethods()) {
    containerBuilder.addMethod(createFactoryInvocationHelper(factory));  // NEW
    containerBuilder.addMethod(createFactoryMethodGetter(factory));      // existing
}
```

(The exact existing-loop shape may differ; the goal is "for each active factory, emit both methods.")

- [ ] **Step 6: Run the test to confirm it passes**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-processor test -Dtest=ProducesCheckedExceptionPropagationTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0`. BUILD SUCCESS.

- [ ] **Step 7: Run the full processor suite**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-processor test`
Expected: BUILD SUCCESS. The container shape changed (each factory now contributes an extra `private` method), so any test that counts generated methods or asserts the exact class structure may need its assertion updated. The semantic behaviour (factory output reachable via `produce_<id>()` and `container.get(...)`) is unchanged.

- [ ] **Step 8: Run the full reactor to confirm downstream examples still build**

Run: `W:/tools/apache-maven/bin/mvn -pl '!tiko-bom' install`
Expected: BUILD SUCCESS. All examples (01–10) re-generate their containers with the new helper-per-factory shape and still pass their own tests.

- [ ] **Step 9: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java tiko-processor/src/test/java/io/tiko/processor/ProducesCheckedExceptionPropagationTest.java
git commit -m "fix(processor): @Produces can declare checked exceptions"
```

---

## Task 5: Runtime end-to-end test for both annotations

**Files:**
- Create: `tiko-runtime/src/test/java/io/tiko/runtime/CheckedExceptionPropagationIT.java`

This test boots a real `Tiko` container, registers a custom `ErrorHandler` via `TikoOptions.errorHandler(...)`, and asserts both (a) the ErrorContext arrives at the handler, and (b) the user's original throwable is the thrown identity at the `container.get(...)` call site. Covers both `@Produces` and `@PostConstruct` paths.

- [ ] **Step 1: Create the test file**

```java
package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.PostConstructFailure;
import io.tiko.ProduceFailure;
import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.PostConstruct;
import io.tiko.annotations.Produces;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * End-to-end: a custom {@link io.tiko.ErrorHandler} receives the structured
 * {@link ErrorContext} for both {@code @Produces} and {@code @PostConstruct}
 * checked-exception failures, AND the original throwable is the thrown
 * identity at {@code container.get(...)} (same instance, not a wrap).
 */
class CheckedExceptionPropagationIT {

    @Test
    void producesCheckedExceptionRoutedAndPropagatedWithIdentityPreserved() {
        SQLException original = new SQLException("pool unavailable");

        List<ErrorContext> seen = new ArrayList<>();
        TikoOptions options = TikoOptions.create().errorHandler(seen::add);

        try (Container container = Tiko.create(options)) {
            FailingPoolFactory.thrownInstance = original;
            try {
                container.get(javax.sql.DataSource.class);
                fail("expected SQLException to propagate from @Produces");
            } catch (Throwable t) {
                assertThat(t).as("identity preserved").isSameAs(original);
            }
        }

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).isInstanceOfSatisfying(ProduceFailure.class, pf -> {
            assertThat(pf.declaringClass()).isEqualTo(FailingPoolFactory.class);
            assertThat(pf.methodName()).isEqualTo("dataSource");
            assertThat(pf.cause()).isSameAs(original);
        });
    }

    @Test
    void postConstructCheckedExceptionRoutedAndPropagatedWithIdentityPreserved() {
        SQLException original = new SQLException("schema migration failed");

        List<ErrorContext> seen = new ArrayList<>();
        TikoOptions options = TikoOptions.create().errorHandler(seen::add);

        try (Container container = Tiko.create(options)) {
            FailingInit.thrownInstance = original;
            try {
                container.get(FailingInit.class);
                fail("expected SQLException to propagate from @PostConstruct");
            } catch (Throwable t) {
                assertThat(t).as("identity preserved").isSameAs(original);
            }
        }

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).isInstanceOfSatisfying(PostConstructFailure.class, pf -> {
            assertThat(pf.component()).isEqualTo(FailingInit.class);
            assertThat(pf.cause()).isSameAs(original);
        });
    }

    @Component(scope = Scope.SINGLETON)
    static class FailingPoolFactory {
        static SQLException thrownInstance;
        @Produces(scope = Scope.SINGLETON)
        public javax.sql.DataSource dataSource() throws SQLException {
            throw thrownInstance;
        }
    }

    @Component(scope = Scope.SINGLETON)
    static class FailingInit {
        static SQLException thrownInstance;
        @Inject public FailingInit() {}
        @PostConstruct public void boom() throws SQLException {
            throw thrownInstance;
        }
    }
}
```

Note: `TikoOptions` and `Tiko.create(TikoOptions)` are imported from `tiko-runtime`. Verify the exact import paths match the rest of `tiko-runtime`'s tests (e.g. `01_basic_di`'s `LifecycleErrorRoutingTest` uses the same shape).

- [ ] **Step 2: Run the test**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-runtime test -Dtest=CheckedExceptionPropagationIT`
Expected: `Tests run: 2, Failures: 0, Errors: 0`. BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add tiko-runtime/src/test/java/io/tiko/runtime/CheckedExceptionPropagationIT.java
git commit -m "test(runtime): e2e — @Produces + @PostConstruct checked exceptions route + propagate"
```

---

## Task 6: Persistence cookbook cleanup

**Files:**
- Modify: `tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/infra/JdbcConnectionProvider.java`
- Modify: `tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/infra/SchemaInitializer.java`
- Modify: `docs/cookbooks/persistence.md`

- [ ] **Step 1: Revert `JdbcConnectionProvider.java` to natural `throws SQLException`**

Replace the current body (the IllegalStateException wrap added during cookbook implementation):

```java
package io.tiko.examples.persistence.infra;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.Produces;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Produces a REQUEST-scoped {@link Connection}. Each REQUEST scope opens a
 * fresh pool connection with {@code autoCommit=false} and returns it on
 * scope teardown (Tiko's implicit-AutoCloseable handling closes the
 * connection, which Hikari intercepts to return it to the pool).
 *
 * <p>Because {@code java.sql.Connection} is an interface, SINGLETON
 * consumers (like {@code OrderRepository}) can inject {@code Connection}
 * directly — the Tiko annotation processor generates an auto-proxy that
 * resolves to the current scope's connection on every method call.
 */
@Component(scope = Scope.REQUEST)
public class JdbcConnectionProvider {

    private final DataSource ds;

    @Inject
    public JdbcConnectionProvider(DataSource ds) {
        this.ds = ds;
    }

    @Produces(scope = Scope.REQUEST)
    public Connection connection() throws SQLException {
        var c = ds.getConnection();
        c.setAutoCommit(false);
        return c;
    }
}
```

- [ ] **Step 2: Revert `SchemaInitializer.java` to natural `throws SQLException, IOException`**

Update `initialize()` to drop the IllegalStateException wrap (preserve everything else):

```java
@PostConstruct
public void initialize() throws SQLException, IOException {
    String script;
    try (InputStream in = SchemaInitializer.class.getResourceAsStream("/schema.sql")) {
        if (in == null) throw new IllegalStateException("schema.sql not found on classpath");
        try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            script = reader.lines().collect(Collectors.joining("\n"));
        }
    }
    try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
        for (String stmt : script.split(";")) {
            String trimmed = stmt.trim();
            if (!trimmed.isEmpty()) st.execute(trimmed);
        }
        c.commit();
    }
}
```

- [ ] **Step 3: Drop the "current-Tiko quirk" callout in `docs/cookbooks/persistence.md`**

In `docs/cookbooks/persistence.md`, find the JdbcConnectionProvider code snippet and the callout that follows it (added during cookbook implementation):

> The checked-exception wrap is a current-Tiko quirk: the annotation processor
> emits `produce_*()` accessors with no `throws` clause, so `@Produces` methods
> can't declare checked exceptions today. The same constraint applies to
> `@PostConstruct` (you'll see it again in `SchemaInitializer`). Tracking the
> gap as a framework follow-up — for now, wrap and propagate the cause.

Restore the code snippet to:

```java
@Component(scope = Scope.REQUEST)
public class JdbcConnectionProvider {
    private final DataSource ds;

    @Inject JdbcConnectionProvider(DataSource ds) { this.ds = ds; }

    @Produces(scope = Scope.REQUEST)
    public Connection connection() throws SQLException {
        var c = ds.getConnection();
        c.setAutoCommit(false);
        return c;
    }
}
```

And delete the callout paragraph entirely.

- [ ] **Step 4: Run the cookbook tests**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/10_persistence_jdbc test`
Expected: `Tests run: 12, Failures: 0, Errors: 0`. BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/infra/JdbcConnectionProvider.java tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/infra/SchemaInitializer.java docs/cookbooks/persistence.md
git commit -m "docs(cookbooks): drop checked-exception wraps now that processor propagates them"
```

---

## Task 7: Roadmap entry + final reactor build + push + PR

**Files:**
- Modify: `docs/roadmap.md` — add a "What ships today" entry.

- [ ] **Step 1: Modify `docs/roadmap.md`**

In `docs/roadmap.md`, in the `## What ships today` block, AFTER the existing entries, add:

```markdown
- ✅ `@Produces` and `@PostConstruct` may declare checked exceptions — the processor catches `Throwable`, publishes `ProduceFailure` / `PostConstructFailure` ErrorContext, and propagates the user's original throwable via sneaky-throw so identity and stack trace are preserved at `container.get(...)`. Persistence cookbook drops its `IllegalStateException` wraps in `JdbcConnectionProvider` / `SchemaInitializer`. (Closes #97.)
```

- [ ] **Step 2: Run the full reactor build**

Run: `W:/tools/apache-maven/bin/mvn -pl "!tiko-bom" install`
Expected: BUILD SUCCESS. All modules build, all tests pass. Reactor summary includes every persistence-cookbook test (12) + new processor tests + new runtime tests.

- [ ] **Step 3: Confirm working tree clean**

Run: `git status`
Expected: nothing to commit.

- [ ] **Step 4: Commit roadmap**

```bash
git add docs/roadmap.md
git commit -m "docs(roadmap): @Produces + @PostConstruct checked-exception propagation shipped"
```

- [ ] **Step 5: Push branch**

```bash
git push -u origin fix/produces-postconstruct-checked-exceptions
```

- [ ] **Step 6: Open the PR**

```bash
"C:/Program Files/GitHub CLI/gh.exe" pr create \
    --title "fix(processor): @Produces and @PostConstruct may declare checked exceptions" \
    --body "$(cat <<'EOF'
## Summary

Closes #97. The processor now catches `Throwable` (widened from `RuntimeException | Error`) around `@PostConstruct` and `@Produces` user-method invocations, publishes a structured `ErrorContext` (`PostConstructFailure` / new `ProduceFailure`) for observability, and sneaky-throws the original throwable so the consumer of `container.get(...)` sees the user's exception unchanged — same instance, same stack trace, no synthetic wrapper.

Spec at `docs/superpowers/specs/2026-05-15-produces-postconstruct-checked-exceptions-design.md`. Plan at `docs/superpowers/plans/2026-05-16-produces-postconstruct-checked-exceptions.md`.

### Key pieces

- **`ProduceFailure` ErrorContext** (new `tiko-api/src/main/java/io/tiko/ProduceFailure.java`) — appended to the sealed `ErrorContext` permits list. Carries `(declaringClass, methodName, cause)`.
- **`Unchecked.sneakyThrow` helper** (new `tiko-runtime/src/main/java/io/tiko/runtime/Unchecked.java`) — generated-code-only utility, sidesteps the type-system cascade that would otherwise force `Container#get` to declare `throws Throwable`.
- **`ComponentFactoryGenerator`** — `@PostConstruct` catch widened to `Throwable`; rethrow path now goes through `Unchecked.sneakyThrow`.
- **`ContainerGenerator`** — per-factory `private invokeFactory_<id>()` helper carries the try/catch + `ProduceFailure` routing + sneaky-throw. The public scoped getter (`produce_<id>()`) keeps its single-expression lambda shape; it just calls the helper instead of the user method directly.

### Cookbook cleanup

`tiko-examples/10_persistence_jdbc/` reverts its `IllegalStateException` wraps in `JdbcConnectionProvider` and `SchemaInitializer` to natural `throws SQLException` (and `, IOException`) declarations. The "current-Tiko quirk" callout in `docs/cookbooks/persistence.md` is removed.

### Test plan

- [x] `UncheckedTest` — sneakythrow preserves throwable identity for both checked and unchecked types (2 tests).
- [x] `PostConstructCheckedExceptionPropagationTest` — `@PostConstruct throws SQLException` compiles, generator emits the widened catch + sneakythrow plumbing.
- [x] `ProducesCheckedExceptionPropagationTest` — `@Produces ... throws SQLException` compiles, generator emits per-factory helper + ProduceFailure routing + sneakythrow.
- [x] `CheckedExceptionPropagationIT` (runtime, e2e) — custom `ErrorHandler` receives the structured ErrorContext AND the user's original throwable is the thrown identity at `container.get(...)`. Covers both annotations.
- [x] Persistence cookbook (`10_persistence_jdbc`) — all 12 tests still pass with natural `throws` declarations restored.
- [x] Full reactor `mvn -pl '!tiko-bom' install` green.
- [x] Spotless gate clean.

### Breaking change

Adding `ProduceFailure` to the sealed `ErrorContext` permits list is a compile-time-loud breaking change for users with exhaustive `switch (ctx)` patterns — they'll be told to handle the new case. This is intentional per `ErrorContext`'s existing Javadoc ("Adding a new top-level permit here is intentionally a compile-time-loud breaking change").
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
git branch -d fix/produces-postconstruct-checked-exceptions
git fetch --prune origin
```

---

## Done

`@Produces` and `@PostConstruct` methods can now declare checked exceptions naturally; the processor handles the wiring; the persistence cookbook is one less workaround uglier; and Issue #97 closes. The companion typed-`RuntimeException` cleanup (Issue #98) remains as the natural next pickup in the Phase 4 milestone.
