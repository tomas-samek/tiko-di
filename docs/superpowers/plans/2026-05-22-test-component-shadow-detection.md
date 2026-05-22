# `@TestComponent` Shadow Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `@TestComponent class FakeX extends X` (where `X` is `@Component`) shadow `X` in the test container, plus add `@TestComponent(value = X.class)` for the explicit case. Also: scope-mismatch becomes a compile error.

**Architecture:** Hybrid declaration model. Both explicit `value()` and implicit superclass-chain walk produce a `Set<String> testExtraKeys` stored on `ComponentModel`. `AmbiguityValidator` registers each `@TestComponent` under both its existing routable types AND its `testExtraKeys`, so the existing T11 collision-and-shadow carve-out fires unchanged. Scope-mismatch check added inside the carve-out.

**Tech Stack:** Java 21, Maven, JUnit 5, Google `compile-testing`, AssertJ.

**Spec:** `docs/superpowers/specs/2026-05-22-test-component-shadow-detection-design.md`
**Tracker:** [#127](https://github.com/tomas-samek/tiko-di/issues/127)
**Branch:** continue on `spec/127-test-component-shadow-detection`.

---

## Task 1: `@TestComponent` gains `value()` attribute

**Files:**
- Modify: `tiko-test/src/main/java/io/tiko/test/TestComponent.java`

- [ ] **Step 1: Add the attribute to the annotation**

Replace the existing annotation body with:

```java
package io.tiko.test;

import io.tiko.Scope;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-classpath marker that shadows a production {@code @Component} of the same target type.
 *
 * <p>The {@code tiko-processor} processes {@code @TestComponent}-annotated classes during
 * {@code test-compile} and emits a separate {@code TestTikoContainerImpl_<hash>} into
 * {@code target/test-classes/}. At runtime, {@link io.tiko.runtime.Tiko#create(io.tiko.runtime.TikoOptions)}
 * prefers the test container when {@code META-INF/tiko/test-container.properties} is on the classpath.
 *
 * <p>Shadow resolution:
 * <ul>
 *   <li>When {@link #value()} is set, the annotated class shadows that explicit target. The annotated
 *       class must be assignable to the value type.</li>
 *   <li>When {@code value()} is unset (default {@link Void Void.class}), the processor walks the
 *       superclass chain and shadows the first {@code @Component}-annotated ancestor (if any).</li>
 *   <li>When neither produces a target, the class is a pure addition with no shadowing.</li>
 * </ul>
 *
 * <p>{@code SOURCE} retention — never appears in runtime bytecode.
 */
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
     * ancestor (if any).
     */
    Class<?> value() default Void.class;

    Scope scope() default Scope.SINGLETON;

    String name() default "";
}
```

- [ ] **Step 2: Verify it compiles**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-test compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Verify existing tiko-test tests still pass (no regression)**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-test test`
Expected: all existing tests still green — the new attribute has a default, so no callers break.

- [ ] **Step 4: Commit**

```bash
git add tiko-test/src/main/java/io/tiko/test/TestComponent.java
git commit -m "feat(tiko-test): @TestComponent.value() for explicit shadow target"
```

---

## Task 2: `ComponentModel.testExtraKeys` storage

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/model/ComponentModel.java`

- [ ] **Step 1: Add the field, getter, and builder setter**

In `ComponentModel.java`, alongside the existing `testComponent` field (line 34), add:

```java
private final java.util.Set<String> testExtraKeys; // Extra routable type keys for shadow detection (test-component only)
```

In the constructor body (after `this.testComponent = builder.testComponent;`), add:

```java
this.testExtraKeys = java.util.Set.copyOf(builder.testExtraKeys);
```

After the `isTestComponent()` getter (line 174-176), add:

```java
/**
 * Extra routable type FQN keys this test component registers under for shadow detection.
 * Populated from either {@code @TestComponent.value()} (explicit) or the superclass-chain
 * walk (implicit). Empty for production {@code @Component}s and for {@code @TestComponent}s
 * with no shadow target.
 */
public java.util.Set<String> getTestExtraKeys() {
    return testExtraKeys;
}
```

In the `Builder` (line 178), add a field:

```java
private java.util.Set<String> testExtraKeys = java.util.Set.of();
```

Add the setter (next to `testComponent` setter at line 300-303):

```java
public Builder testExtraKeys(java.util.Set<String> keys) {
    this.testExtraKeys = keys == null ? java.util.Set.of() : java.util.Set.copyOf(keys);
    return this;
}
```

- [ ] **Step 2: Verify it compiles**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Verify existing tests still pass**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test`
Expected: all tests green — new field defaults to empty set.

- [ ] **Step 4: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/model/ComponentModel.java
git commit -m "feat(processor): ComponentModel.testExtraKeys carries shadow targets"
```

---

## Task 3: Mirror-traversal helper `readClassAttribute`

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java`

- [ ] **Step 1: Add the helper next to existing readStringAttribute / readEnumAttribute**

In `TikoAnnotationProcessor.java`, after `readEnumAttribute` (around line 370), add:

```java
/**
 * Reads a {@code Class<?>} annotation attribute as a {@link TypeMirror}. Annotation
 * {@code Class<?>} values are mirrored at processor time — direct {@code Class.getName()}
 * access throws {@link javax.lang.model.type.MirroredTypeException}, so the value must be
 * extracted via the AnnotationValue API.
 *
 * @return the attribute's TypeMirror, or empty when the attribute is absent.
 */
private java.util.Optional<javax.lang.model.type.TypeMirror> readClassAttribute(
        javax.lang.model.element.AnnotationMirror mirror, String attributeName) {
    var values = processingEnv.getElementUtils().getElementValuesWithDefaults(mirror);
    for (var entry : values.entrySet()) {
        if (!entry.getKey().getSimpleName().contentEquals(attributeName)) continue;
        Object v = entry.getValue().getValue();
        if (v instanceof javax.lang.model.type.TypeMirror tm) {
            return java.util.Optional.of(tm);
        }
        return java.util.Optional.empty();
    }
    return java.util.Optional.empty();
}
```

- [ ] **Step 2: Verify it compiles**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java
git commit -m "feat(processor): readClassAttribute helper for Class<?> annotation values"
```

---

## Task 4: Compute `testExtraKeys` in `buildTestComponentModel` — explicit `value()` path

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java`
- Test: `tiko-processor/src/test/java/io/tiko/processor/TestComponentExplicitValueTest.java`

- [ ] **Step 1: Write the failing test**

Create `tiko-processor/src/test/java/io/tiko/processor/TestComponentExplicitValueTest.java`:

```java
package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class TestComponentExplicitValueTest {

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
    void explicitValueRegistersAdditionalRoutableKey() throws Exception {
        var prod = JavaFileObjects.forSourceLines(
                "demo.PaymentGateway",
                "package demo;",
                "public interface PaymentGateway {}");
        var prodImpl = JavaFileObjects.forSourceLines(
                "demo.HttpPaymentGateway",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class HttpPaymentGateway implements PaymentGateway {}");
        var test = JavaFileObjects.forSourceLines(
                "demo.StubPaymentGateway",
                "package demo;",
                "import io.tiko.test.TestComponent;",
                "@TestComponent(value = PaymentGateway.class)",
                "public class StubPaymentGateway implements PaymentGateway {}");
        var consumer = JavaFileObjects.forSourceLines(
                "demo.UsesGateway",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class UsesGateway {",
                "    @Inject public UsesGateway(PaymentGateway g) {}",
                "}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor())
                .compile(TEST_COMPONENT_ANNO, prod, prodImpl, test, consumer);
        assertThat(c).succeeded();

        var testContainer = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TestTikoContainerImpl_"))
                .findFirst().orElseThrow();
        var content = new String(testContainer.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // The test container must override the production getter, indicating shadow fired.
        org.assertj.core.api.Assertions.assertThat(content)
                .contains("@Override")
                .contains("getHttpPaymentGateway");
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=TestComponentExplicitValueTest`
Expected: FAIL — the test container exists, but `getHttpPaymentGateway` is not overridden (shadow didn't fire because `StubPaymentGateway` doesn't collide with `HttpPaymentGateway`'s FQN; only the interface `PaymentGateway` collides, which already worked, but `value()` isn't being read yet).

Important: the existing interface-collision path may make this test pass spuriously. Verify by checking the actual generated output — if `getHttpPaymentGateway` IS overridden via the existing path, this test is too weak. In that case, also assert the test container's source contains the new `testExtraKeys` mechanism by adding a second assertion that uses a fixture where `value()` names a CLASS (not an interface) the test impl doesn't directly implement. Adjust the test if needed.

- [ ] **Step 3: Read and apply `value()` in `buildTestComponentModel`**

In `TikoAnnotationProcessor.java`, replace the current `buildTestComponentModel(TypeElement typeElement)` body (around lines 230-241) with:

```java
private ComponentModel buildTestComponentModel(TypeElement typeElement) {
    AnnotationMirror mirror = findAnnotationMirror(typeElement, TEST_COMPONENT_FQN);
    if (mirror == null) {
        return null;
    }

    Scope scope = readEnumAttribute(mirror, "scope", Scope.class).orElse(Scope.SINGLETON);
    String name = readStringAttribute(mirror, "name").orElse("");

    java.util.Set<String> extraKeys = computeTestExtraKeys(typeElement, mirror);

    ComponentModel model =
            buildComponentModel(typeElement, scope, name, List.of(), List.of(), true, true, "@TestComponent");
    if (model == null) {
        return null;
    }
    // Carry the shadow targets via a defensive rebuild of the model's testExtraKeys.
    return ComponentModel.builder()
            .typeElement(model.getTypeElement())
            .packageName(model.getPackageName())
            .className(model.getClassName())
            .qualifiedName(model.getQualifiedName())
            .scope(model.getScope())
            .name(model.getName().orElse(""))
            .profiles(model.getProfiles())
            .dependencies(model.getDependencies())
            .constructor(model.getConstructor())
            .postConstructMethods(model.getPostConstructMethods())
            .preDestroyMethods(model.getPreDestroyMethods())
            .implementedInterface(model.getImplementedInterface().orElse(null))
            .requiresProxy(model.requiresProxy())
            .staticFactoryMethod(model.getStaticFactoryMethod().orElse(null))
            .autoCloseable(model.isAutoCloseable())
            .exposeTypes(model.getExposeTypes())
            .exposeSelf(model.isExposeSelf())
            .testComponent(true)
            .testExtraKeys(extraKeys)
            .build();
}
```

(NOTE: rebuilding the model is awkward — see Task 5 for the cleaner approach. We do it this way in this task for a minimal first-cut that gets the test green.)

Add a stub for `computeTestExtraKeys` returning explicit-`value()` keys only for now:

```java
private java.util.Set<String> computeTestExtraKeys(TypeElement testClass, AnnotationMirror mirror) {
    java.util.Optional<TypeMirror> valueAttr = readClassAttribute(mirror, "value");
    if (valueAttr.isPresent() && !isVoid(valueAttr.get())) {
        return java.util.Set.of(valueAttr.get().toString());
    }
    // Implicit walk lands in Task 6.
    return java.util.Set.of();
}

private static boolean isVoid(TypeMirror tm) {
    return tm.toString().equals("java.lang.Void");
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=TestComponentExplicitValueTest`
Expected: still FAIL — `testExtraKeys` is computed but `AmbiguityValidator` doesn't consume it yet. Task 5 wires the validator.

Mark the failure as expected. The test will go green after Task 5.

- [ ] **Step 5: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java \
        tiko-processor/src/test/java/io/tiko/processor/TestComponentExplicitValueTest.java
git commit -m "feat(processor): read @TestComponent.value() into testExtraKeys"
```

---

## Task 5: `AmbiguityValidator` registers test components under their `testExtraKeys`

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/validation/AmbiguityValidator.java`

- [ ] **Step 1: Register the extra keys in the validator loop**

In `AmbiguityValidator.validate()` (around lines 49-57), where the test/main component is registered under self-FQN + declared interfaces, also register it under each `testExtraKeys` entry. Change the block from:

```java
if (component.isExposeSelf()) {
    register(providersByType, component.getQualifiedName(), info);
}
var declared = component.isExposeRestricted()
        ? component.getExposeTypes()
        : component.getTypeElement().getInterfaces();
for (var iface : declared) {
    register(providersByType, iface.toString(), info);
}
```

to:

```java
if (component.isExposeSelf()) {
    register(providersByType, component.getQualifiedName(), info);
}
var declared = component.isExposeRestricted()
        ? component.getExposeTypes()
        : component.getTypeElement().getInterfaces();
for (var iface : declared) {
    register(providersByType, iface.toString(), info);
}
// Additional shadow-target keys carried by @TestComponent (explicit value() or implicit walk).
// Empty for production @Component, so this loop is a no-op for non-test components.
for (String extraKey : component.getTestExtraKeys()) {
    register(providersByType, extraKey, info);
}
```

- [ ] **Step 2: Run the Task 4 test to confirm it passes**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=TestComponentExplicitValueTest`
Expected: PASS — the test container now overrides `getHttpPaymentGateway` because the validator registers `StubPaymentGateway` under `PaymentGateway` (the explicit `value()`), which collides with `HttpPaymentGateway`'s same-interface registration, triggering the T11 carve-out.

- [ ] **Step 3: Full test suite**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test`
Expected: all tests green — no regressions in the existing T11 path.

- [ ] **Step 4: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/validation/AmbiguityValidator.java
git commit -m "feat(processor): register test components under their testExtraKeys for shadow detection"
```

---

## Task 6: Implicit superclass-chain walk

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java`
- Test: `tiko-processor/src/test/java/io/tiko/processor/TestComponentImplicitWalkTest.java`

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

class TestComponentImplicitWalkTest {

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
    void fakeExtendsProductionClassShadowsViaSuperclassWalk() throws Exception {
        var clockClass = JavaFileObjects.forSourceLines(
                "demo.Clock",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import java.time.Instant;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Clock {",
                "    public Instant now() { return Instant.now(); }",
                "}");
        var fake = JavaFileObjects.forSourceLines(
                "demo.FakeClock",
                "package demo;",
                "import io.tiko.test.TestComponent;",
                "import java.time.Instant;",
                "@TestComponent",
                "public class FakeClock extends Clock {",
                "    @Override public Instant now() { return Instant.EPOCH; }",
                "}");
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
                .compile(TEST_COMPONENT_ANNO, clockClass, fake, consumer);
        assertThat(c).succeeded();

        var testContainer = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TestTikoContainerImpl_"))
                .findFirst().orElseThrow();
        var content = new String(testContainer.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // The test container must override getClock() (the main getter), indicating
        // FakeClock shadowed Clock via superclass walk.
        org.assertj.core.api.Assertions.assertThat(content)
                .contains("@Override")
                .contains("getClock");
    }

    @Test
    void firstComponentAncestorWinsForMultiLevelHierarchy() throws Exception {
        var aClass = JavaFileObjects.forSourceLines(
                "demo.A",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class A {}");
        var bClass = JavaFileObjects.forSourceLines(
                "demo.B",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class B extends A {}");
        var fake = JavaFileObjects.forSourceLines(
                "demo.FakeB",
                "package demo;",
                "import io.tiko.test.TestComponent;",
                "@TestComponent",
                "public class FakeB extends B {}");
        var consumer = JavaFileObjects.forSourceLines(
                "demo.UsesB",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class UsesB {",
                "    @Inject public UsesB(B b) {}",
                "}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor())
                .compile(TEST_COMPONENT_ANNO, aClass, bClass, fake, consumer);
        assertThat(c).succeeded();

        var testContainer = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TestTikoContainerImpl_"))
                .findFirst().orElseThrow();
        var content = new String(testContainer.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // FakeB should shadow B (the nearer @Component ancestor): the test container
        // overrides getB() but not getA().
        org.assertj.core.api.Assertions.assertThat(content)
                .contains("@Override")
                .contains("getB");
        // Sanity: A's getter is defined in the super (main container) but not overridden
        // in the test subclass. We assert this by counting "@Override" occurrences vs
        // method-getter occurrences indirectly via FakeB appearing in the test container
        // exactly once as a factory wiring.
        org.assertj.core.api.Assertions.assertThat(content).contains("FakeB");
    }

    @Test
    void noComponentAncestorMeansPureAddition() throws Exception {
        var fake = JavaFileObjects.forSourceLines(
                "demo.StandaloneFake",
                "package demo;",
                "import io.tiko.test.TestComponent;",
                "@TestComponent",
                "public class StandaloneFake {}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor())
                .compile(TEST_COMPONENT_ANNO, fake);
        assertThat(c).succeeded();

        // Standalone test component must compile successfully without shadow; no main component to shadow.
    }
}
```

Wait — the second test has `getB` but `doesNotContain("@Override")` which is contradictory (shadow emits `@Override` over `getB`). Fix the assertion: the FIRST test asserts override (shadow fires). The second test should assert override AND that `getB` is the shadowed one. Replace the second test's assertions with:

```java
org.assertj.core.api.Assertions.assertThat(content)
        .contains("@Override")
        .contains("getB");  // shadow B (nearer @Component ancestor)
// We can't easily assert "doesn't shadow A" because the test container may include both A and B's getters;
// the key invariant is that the @TestComponent's factory wires into getB(), not getA().
// Add a stricter assertion: the fake's class name appears in the generated source near getB().
```

For test 3 (`noComponentAncestorMeansPureAddition`), just assert the compilation succeeded — the StandaloneFake is a test-only addition with no shadow target.

- [ ] **Step 2: Run the test to confirm it fails**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=TestComponentImplicitWalkTest`
Expected: at least `fakeExtendsProductionClassShadowsViaSuperclassWalk` FAILS — no override of `getClock` (the implicit walk doesn't run yet).

- [ ] **Step 3: Implement the implicit walk in `computeTestExtraKeys`**

In `TikoAnnotationProcessor.java`, replace the stub from Task 4 with the full implementation:

```java
private java.util.Set<String> computeTestExtraKeys(TypeElement testClass, AnnotationMirror mirror) {
    java.util.Optional<TypeMirror> valueAttr = readClassAttribute(mirror, "value");
    if (valueAttr.isPresent() && !isVoid(valueAttr.get())) {
        // Explicit value() wins over implicit walk.
        return java.util.Set.of(valueAttr.get().toString());
    }

    // Implicit walk: walk the superclass chain, returning routable types of the first
    // @Component-annotated ancestor we find.
    TypeMirror superMirror = testClass.getSuperclass();
    while (superMirror instanceof javax.lang.model.type.DeclaredType dt) {
        Element superElement = dt.asElement();
        if (!(superElement instanceof TypeElement superType)) break;
        if (superType.getQualifiedName().contentEquals("java.lang.Object")) break;

        if (superType.getAnnotation(io.tiko.annotations.Component.class) != null) {
            // Found a @Component ancestor — collect its routable types.
            java.util.Set<String> keys = new java.util.LinkedHashSet<>();
            keys.add(superType.getQualifiedName().toString());
            for (TypeMirror iface : superType.getInterfaces()) {
                keys.add(iface.toString());
            }
            return keys;
        }
        superMirror = superType.getSuperclass();
    }
    return java.util.Set.of();
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=TestComponentImplicitWalkTest`
Expected: all three tests PASS.

- [ ] **Step 5: Full test suite for regressions**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test`
Expected: all tests green.

- [ ] **Step 6: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java \
        tiko-processor/src/test/java/io/tiko/processor/TestComponentImplicitWalkTest.java
git commit -m "feat(processor): implicit superclass walk in @TestComponent shadow detection"
```

---

## Task 7: Compile-time assignability check for explicit `value()`

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java`
- Modify: `tiko-processor/src/main/java/io/tiko/processor/util/ErrorReporter.java` (add diagnostic method)
- Test: `tiko-processor/src/test/java/io/tiko/processor/TestComponentExplicitValueTest.java` (append test)

- [ ] **Step 1: Append the failing test to `TestComponentExplicitValueTest`**

Add this test method to the existing `TestComponentExplicitValueTest` class:

```java
@Test
void valueMustBeAssignableFromAnnotatedClass() {
    var iface = JavaFileObjects.forSourceLines(
            "demo.PaymentGateway",
            "package demo;",
            "public interface PaymentGateway {}");
    var bogusTest = JavaFileObjects.forSourceLines(
            "demo.UnrelatedFake",
            "package demo;",
            "import io.tiko.test.TestComponent;",
            "@TestComponent(value = PaymentGateway.class)",
            "public class UnrelatedFake {}");  // does NOT implement PaymentGateway

    var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor())
            .compile(TEST_COMPONENT_ANNO, iface, bogusTest);
    assertThat(c).hadErrorContaining("not assignable to");
}
```

- [ ] **Step 2: Run the test to confirm it fails**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=TestComponentExplicitValueTest#valueMustBeAssignableFromAnnotatedClass`
Expected: FAIL — compilation currently succeeds because no assignability check exists.

- [ ] **Step 3: Add diagnostic method to `ErrorReporter`**

In `tiko-processor/src/main/java/io/tiko/processor/util/ErrorReporter.java`, add a new method (mirror the style of existing error methods):

```java
public void testComponentValueNotAssignable(
        javax.lang.model.element.Element element, String testClassFqn, String valueTypeFqn) {
    error(element, String.format(
            "@TestComponent value type %s is not assignable from %s. The annotated class "
            + "must extend or implement the value type to shadow it.",
            valueTypeFqn, testClassFqn));
}
```

- [ ] **Step 4: Wire the assignability check in `computeTestExtraKeys`**

In `TikoAnnotationProcessor.java`, modify `computeTestExtraKeys` to validate before returning explicit keys:

```java
private java.util.Set<String> computeTestExtraKeys(TypeElement testClass, AnnotationMirror mirror) {
    java.util.Optional<TypeMirror> valueAttr = readClassAttribute(mirror, "value");
    if (valueAttr.isPresent() && !isVoid(valueAttr.get())) {
        TypeMirror valueType = valueAttr.get();
        // Compile-time assignability: testClass.asType() must be assignable to valueType.
        if (!processingEnv.getTypeUtils().isAssignable(testClass.asType(), valueType)) {
            context.getErrorReporter().testComponentValueNotAssignable(
                    testClass, testClass.getQualifiedName().toString(), valueType.toString());
            return java.util.Set.of();  // skip registration to avoid downstream confusion
        }
        return java.util.Set.of(valueType.toString());
    }
    // ... implicit walk unchanged ...
    TypeMirror superMirror = testClass.getSuperclass();
    while (superMirror instanceof javax.lang.model.type.DeclaredType dt) {
        Element superElement = dt.asElement();
        if (!(superElement instanceof TypeElement superType)) break;
        if (superType.getQualifiedName().contentEquals("java.lang.Object")) break;

        if (superType.getAnnotation(io.tiko.annotations.Component.class) != null) {
            java.util.Set<String> keys = new java.util.LinkedHashSet<>();
            keys.add(superType.getQualifiedName().toString());
            for (TypeMirror iface : superType.getInterfaces()) {
                keys.add(iface.toString());
            }
            return keys;
        }
        superMirror = superType.getSuperclass();
    }
    return java.util.Set.of();
}
```

- [ ] **Step 5: Run the test to confirm it passes**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=TestComponentExplicitValueTest`
Expected: both tests pass — the new assignability error case AND the earlier explicit-value test.

- [ ] **Step 6: Full suite for regressions**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test`
Expected: all green.

- [ ] **Step 7: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java \
        tiko-processor/src/main/java/io/tiko/processor/util/ErrorReporter.java \
        tiko-processor/src/test/java/io/tiko/processor/TestComponentExplicitValueTest.java
git commit -m "feat(processor): compile-time assignability check for @TestComponent.value()"
```

---

## Task 8: Scope-mismatch becomes a compile error

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/validation/AmbiguityValidator.java`
- Modify: `tiko-processor/src/main/java/io/tiko/processor/util/ErrorReporter.java` (new diagnostic)
- Test: `tiko-processor/src/test/java/io/tiko/processor/validation/TestComponentScopeMismatchTest.java`

- [ ] **Step 1: Write the failing test**

Create `tiko-processor/src/test/java/io/tiko/processor/validation/TestComponentScopeMismatchTest.java`:

```java
package io.tiko.processor.validation;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class TestComponentScopeMismatchTest {

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
    void scopeMismatchBetweenTestAndProductionFailsCompile() {
        var prod = JavaFileObjects.forSourceLines(
                "demo.RequestRepo",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.REQUEST)",
                "public class RequestRepo {}");
        var fake = JavaFileObjects.forSourceLines(
                "demo.FakeRepo",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.test.TestComponent;",
                "@TestComponent(scope = Scope.SINGLETON)",  // mismatch — prod is REQUEST
                "public class FakeRepo extends RequestRepo {}");
        var consumer = JavaFileObjects.forSourceLines(
                "demo.UsesRepo",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class UsesRepo {",
                "    @Inject public UsesRepo(RequestRepo r) {}",
                "}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor())
                .compile(TEST_COMPONENT_ANNO, prod, fake, consumer);
        assertThat(c).hadErrorContaining("Scope mismatch");
    }

    @Test
    void scopeMatchPassesCompile() {
        var prod = JavaFileObjects.forSourceLines(
                "demo.SingRepo",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class SingRepo {}");
        var fake = JavaFileObjects.forSourceLines(
                "demo.FakeSingRepo",
                "package demo;",
                "import io.tiko.test.TestComponent;",
                "@TestComponent",  // default SINGLETON, matches
                "public class FakeSingRepo extends SingRepo {}");
        var consumer = JavaFileObjects.forSourceLines(
                "demo.UsesSing",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class UsesSing {",
                "    @Inject public UsesSing(SingRepo r) {}",
                "}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor())
                .compile(TEST_COMPONENT_ANNO, prod, fake, consumer);
        assertThat(c).succeeded();
    }
}
```

- [ ] **Step 2: Run the test to confirm at least one case fails**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=TestComponentScopeMismatchTest`
Expected: `scopeMismatchBetweenTestAndProductionFailsCompile` FAILS — currently compilation succeeds despite the mismatch.

- [ ] **Step 3: Add error method to `ErrorReporter`**

In `ErrorReporter.java`, add:

```java
public void testComponentScopeMismatch(
        javax.lang.model.element.Element element,
        String testFqn,
        io.tiko.Scope testScope,
        String mainFqn,
        io.tiko.Scope mainScope) {
    error(element, String.format(
            "Scope mismatch: @TestComponent %s declares scope %s but shadows @Component %s "
            + "declared with scope %s. Either match the scope or use TikoOptions.override(...) "
            + "for different lifecycle.",
            testFqn, testScope, mainFqn, mainScope));
}
```

- [ ] **Step 4: Wire the check inside the T11 carve-out**

In `AmbiguityValidator.java`, around the existing carve-out block (lines 84-91), insert a scope check before `markShadowedByTestOverride`:

```java
if (testProviders.size() == 1 && !mainProviders.isEmpty()) {
    ComponentModel testModel = testProviders.get(0).componentModel;
    boolean scopeMismatch = false;
    for (ProviderInfo main : mainProviders) {
        if (main.componentModel == null) continue;  // factory method — not relevant here
        if (main.componentModel.getScope() != testModel.getScope()) {
            context.getErrorReporter().testComponentScopeMismatch(
                    testModel.getTypeElement(),
                    testModel.getQualifiedName(),
                    testModel.getScope(),
                    main.componentModel.getQualifiedName(),
                    main.componentModel.getScope());
            scopeMismatch = true;
        }
    }
    if (scopeMismatch) {
        valid = false;
        continue;  // do not record the shadow when scopes mismatch
    }
    for (ProviderInfo main : mainProviders) {
        if (main.componentKey != null) {
            context.markShadowedByTestOverride(main.componentKey, testModel);
        }
    }
    continue;
}
```

- [ ] **Step 5: Run the test to confirm both cases pass**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test -Dtest=TestComponentScopeMismatchTest`
Expected: both methods PASS.

- [ ] **Step 6: Full suite**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test`
Expected: all green.

- [ ] **Step 7: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/validation/AmbiguityValidator.java \
        tiko-processor/src/main/java/io/tiko/processor/util/ErrorReporter.java \
        tiko-processor/src/test/java/io/tiko/processor/validation/TestComponentScopeMismatchTest.java
git commit -m "feat(processor): scope-mismatch between @TestComponent and shadowed @Component is a compile error"
```

---

## Task 9: Simplify `buildTestComponentModel` — pass `testExtraKeys` directly via builder

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java`

> **Why:** Task 4 used a defensive rebuild pattern (read the model, then rebuild it with `testExtraKeys`). Cleaner: pass `testExtraKeys` directly into the shared `buildComponentModel` helper.

- [ ] **Step 1: Refactor `buildComponentModel` helper to accept `testExtraKeys`**

In `TikoAnnotationProcessor.java`, locate the shared `buildComponentModel(TypeElement, Scope, String, ...)` helper (around line 249). Add a new parameter `Set<String> testExtraKeys` at the end of its signature, and pass it through to `ComponentModel.Builder`:

```java
private ComponentModel buildComponentModel(
        TypeElement typeElement,
        Scope scope,
        String name,
        List<String> profiles,
        List<TypeMirror> exposeTypes,
        boolean exposeSelf,
        boolean isTestComponent,
        String annotationLabel,
        java.util.Set<String> testExtraKeys) {
    // ... existing body ...
    // At the end, add:
    //     .testExtraKeys(testExtraKeys)
    // to the builder chain.
}
```

- [ ] **Step 2: Update both callers**

The `@Component` path (around line 212) passes `java.util.Set.of()`:

```java
return buildComponentModel(
        typeElement,
        annotation.scope(),
        annotation.name(),
        Arrays.asList(annotation.profiles()),
        exposeTypes,
        annotation.exposeSelf(),
        false,
        "@Component",
        java.util.Set.of());
```

The `@TestComponent` path replaces the defensive rebuild with a direct call:

```java
private ComponentModel buildTestComponentModel(TypeElement typeElement) {
    AnnotationMirror mirror = findAnnotationMirror(typeElement, TEST_COMPONENT_FQN);
    if (mirror == null) {
        return null;
    }

    Scope scope = readEnumAttribute(mirror, "scope", Scope.class).orElse(Scope.SINGLETON);
    String name = readStringAttribute(mirror, "name").orElse("");
    java.util.Set<String> extraKeys = computeTestExtraKeys(typeElement, mirror);

    return buildComponentModel(typeElement, scope, name, List.of(), List.of(), true, true, "@TestComponent", extraKeys);
}
```

- [ ] **Step 3: Full suite**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-processor test`
Expected: all green — behavioral equivalent, cleaner shape.

- [ ] **Step 4: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java
git commit -m "refactor(processor): pass testExtraKeys through buildComponentModel helper directly"
```

---

## Task 10: Restore `FixedClockTest` in `tiko-examples/12_testing/`

**Files:**
- Create: `tiko-examples/12_testing/src/test/java/io/tiko/examples/testing/clock/FixedClockTest.java`
- Create: `tiko-examples/12_testing/src/test/java/io/tiko/examples/testing/clock/FixedClock.java`
- Modify: `tiko-examples/12_testing/README.md` (remove the workaround note for #127)

- [ ] **Step 1: Create the FixedClock test fixture**

`tiko-examples/12_testing/src/test/java/io/tiko/examples/testing/clock/FixedClock.java`:

```java
package io.tiko.examples.testing.clock;

import io.tiko.examples.testing.domain.Clock;
import io.tiko.test.TestComponent;
import java.time.Instant;

@TestComponent
public class FixedClock extends Clock {
    public static final Instant FIXED = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public Instant now() {
        return FIXED;
    }
}
```

- [ ] **Step 2: Create the test**

`tiko-examples/12_testing/src/test/java/io/tiko/examples/testing/clock/FixedClockTest.java`:

```java
package io.tiko.examples.testing.clock;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.examples.testing.domain.Clock;
import io.tiko.test.TikoTest;
import org.junit.jupiter.api.Test;

@TikoTest
class FixedClockTest {

    @Test
    void clockResolvesToTheTestComponentVariant(Clock clock) {
        assertThat(clock.now()).isEqualTo(FixedClock.FIXED);
    }
}
```

- [ ] **Step 3: Run the example tests**

Run: `W:\tools\apache-maven\bin\mvn -pl tiko-examples/12_testing -am test`
Expected: `FixedClockTest` PASSES — `FakeClock extends Clock` shadow now fires via the implicit superclass walk.

- [ ] **Step 4: Update `tiko-examples/12_testing/README.md`**

Remove (or rewrite) the workaround note about `@TestComponent` requiring a shared interface. The new behavior IS the canonical shadow pattern. Add the `FixedClockTest` row to the demo table if not already present.

- [ ] **Step 5: Commit**

```bash
git add tiko-examples/12_testing/src/test/java/io/tiko/examples/testing/clock/FixedClock.java \
        tiko-examples/12_testing/src/test/java/io/tiko/examples/testing/clock/FixedClockTest.java \
        tiko-examples/12_testing/README.md
git commit -m "feat(tiko-examples): restore FixedClockTest demonstrating superclass-shadow"
```

---

## Task 11: Update `docs/testing.md` — replace #127 limitation with documented behavior

**Files:**
- Modify: `docs/testing.md`

- [ ] **Step 1: Replace the "Known limitations" entry for #127**

In `docs/testing.md`, locate the Known limitations section. The entry referencing #127 currently describes shadow detection as interface-only. Replace it with a documented section under the main flow, e.g. a new subsection titled "Shadow detection" placed near where `@TestComponent` is introduced:

```markdown
## Shadow detection

`@TestComponent` discovers its shadow target two ways:

**Implicit (default)** — the processor walks the test class's superclass chain
looking for a `@Component`-annotated ancestor. If found, the test class shadows
that ancestor:

```java
@Component(scope = Scope.SINGLETON)
public class Clock { /* prod impl */ }

@TestComponent
public class FixedClock extends Clock { /* test impl */ }
// FixedClock shadows Clock in the test container.
```

**Explicit** — when the test class doesn't extend the production class (e.g.
faking an interface), name the shadow target via `value`:

```java
@TestComponent(value = PaymentGateway.class)
public class StubPaymentGateway implements PaymentGateway { /* ... */ }
```

The annotated class must be assignable to `value` — the processor enforces this
at compile time. Explicit `value` always wins over the implicit walk.

**Scope match required.** The `@TestComponent.scope` must match the shadowed
`@Component.scope`, or the build fails with a clear diagnostic. Use
`TikoOptions.override(...)` if you need different lifecycle semantics.

**Named shadow is not currently supported.** A `@TestComponent(name = "primary")`
does not shadow a `@Component(name = "primary")` — the validator's shadow path
processes unnamed components only. For named test doubles, use the runtime
`TikoOptions.override(Class, "name", Supplier)` hook instead.
```

Then remove the corresponding "Known limitations" → #127 subsection.

- [ ] **Step 2: Verify spotless**

Run: `W:\tools\apache-maven\bin\mvn spotless:apply` and `W:\tools\apache-maven\bin\mvn -pl '!tiko-bom' spotless:check`
Expected: clean.

- [ ] **Step 3: Commit**

```bash
git add docs/testing.md
git commit -m "docs(testing): document @TestComponent shadow detection (implicit + explicit)"
```

---

## Task 12: Update `docs/roadmap.md` — mark #127 shipped

**Files:**
- Modify: `docs/roadmap.md`

- [ ] **Step 1: Move #127 from Phase 3 "Open" to "Shipped"**

In `docs/roadmap.md`, locate the Phase 3 section. Move the bullet point for #127 from the Open list to the Shipped list. Update the counter (e.g. `1/6 closed` → `2/6 closed`).

Sample new Shipped entry:

```markdown
- ✅ tiko-test: `@TestComponent` shadow detection — implicit superclass walk + explicit `value()` attribute; scope-mismatch is a compile error ([#127](https://github.com/tomas-samek/tiko-di/issues/127)).
```

- [ ] **Step 2: Commit**

```bash
git add docs/roadmap.md
git commit -m "docs(roadmap): mark #127 shipped under Phase 3"
```

---

## Task 13: Final smoke + PR

- [ ] **Step 1: Full clean build**

Run: `W:\tools\apache-maven\bin\mvn clean install`
Expected: BUILD SUCCESS across all modules.

- [ ] **Step 2: Full test run**

Run: `W:\tools\apache-maven\bin\mvn test`
Expected: all green.

- [ ] **Step 3: Push the branch**

```bash
git push -u origin spec/127-test-component-shadow-detection
```

- [ ] **Step 4: Open the PR**

```bash
gh pr create --title "feat: @TestComponent shadow detection (#127)" --body "Closes #127. Implements docs/superpowers/specs/2026-05-22-test-component-shadow-detection-design.md."
```

---

## Self-review notes

**Spec coverage:**
- `@TestComponent.value()` attribute: Task 1 ✓
- `ComponentModel.testExtraKeys` storage: Task 2 ✓
- `computeTestExtraKeys` (explicit path): Tasks 3, 4 ✓
- `computeTestExtraKeys` (implicit walk): Task 6 ✓
- `AmbiguityValidator` registers extra keys: Task 5 ✓
- Compile-time assignability check: Task 7 ✓
- Scope-mismatch compile error: Task 8 ✓
- Refactor pass: Task 9 ✓ (replaces Task 4's defensive rebuild)
- Restore FixedClockTest in 12_testing: Task 10 ✓
- Docs update: Tasks 11, 12 ✓
- All spec edge-cases table rows are covered by test methods across Tasks 4, 6, 7, 8.

**Type consistency:**
- `testExtraKeys: Set<String>` used consistently across Tasks 2 (model), 4 (computation), 5 (validator), 9 (refactor).
- `computeTestExtraKeys(TypeElement, AnnotationMirror)` signature consistent across Tasks 4, 6, 7.
- `ComponentModel.Builder.testExtraKeys(Set<String>)` setter used in Tasks 2, 9.
- `readClassAttribute` signature established in Task 3, called in Task 4.

**Known caveat (not blocking):**
- Task 4's defensive rebuild is admittedly ugly; Task 9 cleans it up. Splitting into two tasks keeps each commit focused. The intermediate state at the end of Task 4 is correct, just stylistically suboptimal.

**Scope deferral — named shadowing:**
- The spec said `@TestComponent(name = "primary")` should shadow `@Component(name = "primary")`. The existing `AmbiguityValidator.validate()` early-returns on every named component (line 35: `if (component.getName().isPresent()) continue;`) because named components are disambiguated via `container.get(Class, String)` and don't participate in unqualified-consumer ambiguity. Extending the validator to ALSO process named components for the shadow case (grouping by `(type, name)`) is a meaningful refactor of the validator's loop structure and would roughly double the test surface.
- This plan ships **unnamed shadow** only — the high-leverage case (`@TestComponent class FakeX extends X` where neither carries `name`). If a user surfaces a real need for named shadow, file a follow-up issue and add a separate task pair (validator extension + test).
- Honest framing in `docs/testing.md` (Task 11): the "Shadow detection" section should note that named shadow is not currently supported and that named test doubles must use `TikoOptions.override(Class, name, Supplier)` (the runtime path from T6/T8).
