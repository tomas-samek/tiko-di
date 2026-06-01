# Scope-Model Migration Implementation Plan (#226)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove `REQUEST` from the public scope API and ship `0.x.0` on the unified `SINGLETON / EVENT / PROTOTYPE` shape, with EVENT single-frame and nestability deferred as a strictly-additive future change.

**Architecture:** Five-stage rollout matching the dependency order — `tiko-api` → `tiko-processor` → `tiko-runtime` → `tiko-test` → examples+docs. Each stage is its own commit; the reactor only fully compiles green at the end of stage 3 (when generated code and runtime delegation are aligned with the smaller `tiko-api`). Within each stage, work is broken into small commits so a bad rebase is easy to bisect. The `requestScoped` ThreadLocal in generated `TikoContainerImpl` is collapsed into the existing `eventScoped` ThreadLocal — the public method `runInEventScope` keeps its single-frame shape, and an inner call **throws `IllegalStateException`** so future nestability is purely additive.

**Tech Stack:** Java 21+, Maven 3, JUnit 5, AssertJ, Awaitility, Google `compile-testing` (annotation-processor tests), JavaPoet (code generation), Palantir Java Format (Spotless).

**Branch:** Start with `git checkout -b refactor/issue-226-scope-migration` from `main` (or rebase atop #250 once it merges).

**Spec:** `docs/superpowers/specs/2026-05-29-scope-model-unification-design.md`.

---

## File map

**`tiko-api`** (delete + cleanup, no new files):
- Modify: `tiko-api/src/main/java/io/tiko/Scope.java` (remove REQUEST constant + javadoc fixup)
- Modify: `tiko-api/src/main/java/io/tiko/Container.java` (remove the two Request methods + javadoc)
- Modify: `tiko-api/src/main/java/io/tiko/annotations/PreDestroy.java` (javadoc cleanup)
- Delete: `tiko-api/src/main/java/io/tiko/events/RequestStartedEvent.java`
- Delete: `tiko-api/src/main/java/io/tiko/events/RequestEndingEvent.java`

**`tiko-processor`** (logic deletion + test migration):
- Modify: `tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java` (lines ~474, ~781 — proxy decision)
- Modify: `tiko-processor/src/main/java/io/tiko/processor/ContainerGenerator.java` (drop REQUEST constants/methods/branches at lines ~27, ~590, ~855, ~917–979)
- Modify: processor tests touching REQUEST (`TestComponentScopeMismatchTest`, `RequiredInterfaceForProxyTest`, `ProxyInheritedMethodsAndGenericsTest`, `ProxyForProducesOutputCrossScopeTest`, `ShutdownFailureLoggingTest`, `ShutdownTeardownTimeoutWiringTest`, `CrossScopeMatrixTest`)

**`tiko-runtime`** (delegation removal + nested-call guard test):
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java` (remove `runInRequestScope`/`supplyInRequestScope` overrides at lines ~477–487, fix comments)
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/Tiko.java` (remove the two delegation methods at lines ~528–534 in `TikoDaemon`)
- Add (or modify existing runtime test): one test asserting that nested `runInEventScope` throws `IllegalStateException`.

**`tiko-test`** (annotation + fixture migration):
- Delete: `tiko-test/src/main/java/io/tiko/test/RequestScopeTest.java`
- Modify: `tiko-test/src/main/java/io/tiko/test/TikoTestExtension.java` (drop RequestScopeTest branch at lines ~106, ~126–128)
- Delete: `tiko-test/src/test/java/io/tiko/test/fixtures/RequestScopedService.java`
- Modify: `tiko-test/src/test/java/io/tiko/test/ScopeHelpersTest.java` (use `@EventScopeTest` + an EVENT-scoped fixture)

**Examples** (mechanical migration + one rework):
- `tiko-examples/01_basic_di/*` — REQUEST → EVENT across components, teardown variants, lifecycle tests, ordering probes
- `tiko-examples/03_events/Main.java` + `Observability.java` — drop the REQUEST wrapper level; handlers move from `RequestStarted/EndingEvent` to `EventStarted/EndingEvent`
- `tiko-examples/09_http_javalin/*` — `RequestIdImpl`, `TikoJavalin` middleware, `RequestTimer` handler
- `tiko-examples/10_persistence_jdbc/*` — **rework** the shared-transaction-across-batch into one unit with an internal loop; remove `TransactionalScope`'s `supplyInRequestScope` shape; transaction lives inside a single `runInEventScope`
- `tiko-examples/12_testing/AccountRepository.java` — REQUEST → EVENT, use `@EventScopeTest`
- `tiko-examples/13_mcp_introspection/OrderRepository.java` — REQUEST → EVENT

**Docs**:
- `docs/di-and-scopes.md` — rewrite scope hierarchy + cross-scope matrix + "REQUEST and EVENT scopes together" section
- `CLAUDE.md` — rewrite "Scope Management" (lines ~95–128), "Lifecycle Events" (lines ~203–250), code-example sections
- `README.md` — sweep for any REQUEST mention (current count: 0, but verify)

---

## Stage 1 — `tiko-api` (the breaking part of the surface)

### Task 1: Remove `Scope.REQUEST`

**Files:**
- Modify: `tiko-api/src/main/java/io/tiko/Scope.java`

- [ ] **Step 1: Delete the REQUEST enum constant + its javadoc**

Open `Scope.java`. Delete lines 40–60 (the `REQUEST,` constant block and its javadoc). The remaining order is `SINGLETON`, `EVENT`, `PROTOTYPE`.

- [ ] **Step 2: Rewrite the top-level class javadoc**

Replace the existing javadoc (lines 1–28) with:

```java
/**
 * Defines the lifecycle scope of a component.
 *
 * <p>Scopes form a hierarchy from longest to shortest lifetime:</p>
 * <ol>
 *   <li><strong>SINGLETON</strong> - Application lifetime</li>
 *   <li><strong>EVENT</strong> - One unit of work (HTTP request, consumed message,
 *       scheduled job, async dispatch); the generic unit-of-work primitive</li>
 *   <li><strong>PROTOTYPE</strong> - Per injection (shortest, default)</li>
 * </ol>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * @Component(scope = Scope.SINGLETON)
 * public class UserService { }
 *
 * @Component(scope = Scope.EVENT)
 * public class TransactionContext { }
 *
 * @Component(scope = Scope.PROTOTYPE)
 * public class RequestBuilder { }
 * }</pre>
 *
 * <p>In {@code 0.x.0}, EVENT is single-frame — calling {@link Container#runInEventScope}
 * while a unit is already open throws {@link IllegalStateException}. Nestability is a
 * deferred-but-additive future change.
 */
```

- [ ] **Step 3: Rewrite the `EVENT` javadoc**

In the surviving `EVENT` constant (now where `REQUEST` used to be), replace the existing javadoc with:

```java
/**
 * One instance per unit of work — the synchronous reach of an inbound stimulus
 * (HTTP request, consumed message, scheduled job, async dispatch), bounded at
 * every async/transport hop.
 * <p>
 * Instances are created on {@link Container#runInEventScope(Runnable)} entry and
 * destroyed on exit (LIFO {@code @PreDestroy}).
 * <p>
 * <strong>Lifetime:</strong> One unit of work
 * <p>
 * Note: EVENT-scoped beans injected into SINGLETON beans are automatically proxied
 * to resolve the current unit's instance. This requires the EVENT-scoped bean to
 * implement an interface.
 */
```

- [ ] **Step 4: Verify the file compiles in isolation**

Run: `mvn compile -pl tiko-api`
Expected: FAIL with errors in `tiko-processor` / `tiko-runtime` referencing `Scope.REQUEST`. *That is expected.* The point of this step is to confirm `tiko-api` itself compiles. Look for `BUILD FAILURE` rooted in `tiko-processor` or `tiko-runtime`, **not** in `tiko-api`.

- [ ] **Step 5: Commit**

```bash
git add tiko-api/src/main/java/io/tiko/Scope.java
git commit -m "refactor(api): drop Scope.REQUEST from core enum (#226)"
```

### Task 2: Remove `Container.runInRequestScope` / `supplyInRequestScope`

**Files:**
- Modify: `tiko-api/src/main/java/io/tiko/Container.java`

- [ ] **Step 1: Delete the two method declarations**

In `Container.java`, delete lines 116–137 (the `runInRequestScope` javadoc + method, then the `supplyInRequestScope` javadoc + method).

- [ ] **Step 2: Rewrite the top-of-interface javadoc**

Replace lines 1–40 (the interface-level javadoc and example) with:

```java
/**
 * The main entry point for the Tiko dependency injection container.
 *
 * <p>The Container is responsible for managing the lifecycle of components and providing
 * access to instances. It is typically created once at application startup and shut down
 * at application exit.</p>
 *
 * <p><strong>Lifecycle Events:</strong> The container automatically publishes lifecycle events
 * that can be subscribed to using {@code @EventHandler}:</p>
 * <ul>
 *   <li>{@link io.tiko.events.ApplicationStartedEvent} - On container startup</li>
 *   <li>{@link io.tiko.events.ApplicationEndingEvent} - Before container shutdown</li>
 *   <li>{@link io.tiko.events.EventStartedEvent} - On entering a unit of work</li>
 *   <li>{@link io.tiko.events.EventEndingEvent} - Before exiting a unit of work</li>
 * </ul>
 *
 * <p>Example usage with try-with-resources:</p>
 * <pre>{@code
 * try (Container container = Tiko.create()) {
 *     UserService service = container.get(UserService.class);
 *
 *     // Each unit of work runs in its own EVENT scope
 *     for (Order order : orders) {
 *         container.runInEventScope(() -> {
 *             container.getEventBus().publish(new OrderCreatedEvent(order));
 *         });
 *     }
 * } // Automatic shutdown
 * }</pre>
 */
```

- [ ] **Step 3: Rewrite `runInEventScope` javadoc (single-frame contract)**

Replace the `runInEventScope` javadoc (currently lines 139–147 *before* deletion of lines 116–137; after deletion these shift down) with:

```java
/**
 * Executes the given runnable within an EVENT scope (one unit of work).
 * <p>
 * EVENT-scoped beans created during execution are destroyed when the scope exits
 * (LIFO {@code @PreDestroy}). EVENT is single-frame in {@code 0.x.0}: calling this
 * method while a unit is already open throws {@link IllegalStateException}.
 *
 * @param runnable the code to execute in event scope
 * @throws IllegalStateException if a unit of work is already open on the current thread
 */
```

And `supplyInEventScope`:

```java
/**
 * Executes the given supplier within an EVENT scope (one unit of work) and returns its result.
 * <p>
 * EVENT-scoped beans created during execution are destroyed when the scope exits.
 * EVENT is single-frame in {@code 0.x.0}: calling this method while a unit is
 * already open throws {@link IllegalStateException}.
 *
 * @param supplier the code to execute in event scope
 * @param <T>      the return type
 * @return the result of the supplier
 * @throws IllegalStateException if a unit of work is already open on the current thread
 */
```

- [ ] **Step 4: Confirm `tiko-api` still compiles**

Run: `mvn compile -pl tiko-api`
Expected: `tiko-api` compiles successfully (the dependent modules will still fail — that is fine).

- [ ] **Step 5: Commit**

```bash
git add tiko-api/src/main/java/io/tiko/Container.java
git commit -m "refactor(api): remove runInRequestScope/supplyInRequestScope (#226)"
```

### Task 3: Delete `RequestStartedEvent` and `RequestEndingEvent`

**Files:**
- Delete: `tiko-api/src/main/java/io/tiko/events/RequestStartedEvent.java`
- Delete: `tiko-api/src/main/java/io/tiko/events/RequestEndingEvent.java`

- [ ] **Step 1: Delete both files**

```bash
git rm tiko-api/src/main/java/io/tiko/events/RequestStartedEvent.java
git rm tiko-api/src/main/java/io/tiko/events/RequestEndingEvent.java
```

- [ ] **Step 2: Confirm `tiko-api` compiles**

Run: `mvn compile -pl tiko-api`
Expected: BUILD SUCCESS for `tiko-api`.

- [ ] **Step 3: Commit**

```bash
git commit -m "refactor(api): delete RequestStartedEvent and RequestEndingEvent (#226)"
```

### Task 4: Strip remaining REQUEST javadoc from `tiko-api`

**Files:**
- Modify: `tiko-api/src/main/java/io/tiko/annotations/PreDestroy.java`

- [ ] **Step 1: Update `PreDestroy.java` javadoc**

Lines ~21–22 mention REQUEST lifecycle timing; line ~29 references `RequestEndingEvent`. Replace REQUEST-specific phrasing with EVENT-only wording. Example replacement for line ~29:

```java
// before:
//   ... or before {@link io.tiko.events.RequestEndingEvent} ...
// after:
//   ... or before {@link io.tiko.events.EventEndingEvent} ...
```

If the surrounding paragraph still leans on the REQUEST/EVENT split, condense it to a single sentence about EVENT-scoped teardown.

- [ ] **Step 2: Search `tiko-api` for stray "REQUEST"/"Request*" mentions**

Run: `Grep -r "REQUEST" tiko-api/src` and `Grep -r "Request" tiko-api/src`

Expected: only legitimate matches (e.g. a method named `getRequestId` on a *user* type wouldn't exist here; framework-only). Any framework-side mention of REQUEST scope must be removed.

- [ ] **Step 3: Format**

```bash
mvn -pl '!tiko-bom' spotless:apply
```

- [ ] **Step 4: Confirm `tiko-api` compiles**

Run: `mvn compile -pl tiko-api`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -u tiko-api/
git commit -m "docs(api): drop REQUEST references from tiko-api javadoc (#226)"
```

---

## Stage 2 — `tiko-processor` (compile-time wiring catches up)

### Task 5: Collapse proxy-decision sites in `TikoAnnotationProcessor`

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java`

- [ ] **Step 1: Update line ~474**

Find:

```java
boolean needsProxy = (scope == Scope.REQUEST || scope == Scope.EVENT) && implementedInterface.isPresent();
```

Replace with:

```java
boolean needsProxy = scope == Scope.EVENT && implementedInterface.isPresent();
```

- [ ] **Step 2: Update line ~781 (`@Produces` proxy detection)**

Find:

```java
boolean requiresProxy = (annotation.scope() == Scope.REQUEST || annotation.scope() == Scope.EVENT) && returnType...
```

Replace with:

```java
boolean requiresProxy = annotation.scope() == Scope.EVENT && returnType...
```

(keep the rest of the expression — only the disjunction collapses)

- [ ] **Step 3: Confirm processor compiles in isolation**

Run: `mvn compile -pl tiko-processor`
Expected: BUILD SUCCESS for `tiko-processor` (downstream test compilation may still fail; that's stage 2 task 7).

- [ ] **Step 4: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/TikoAnnotationProcessor.java
git commit -m "refactor(processor): collapse REQUEST/EVENT proxy disjunctions to EVENT-only (#226)"
```

### Task 6: Strip REQUEST from `ContainerGenerator`

**Files:**
- Modify: `tiko-processor/src/main/java/io/tiko/processor/ContainerGenerator.java`

- [ ] **Step 1: Delete the two REQUEST constants**

Lines ~917–918 currently read:

```java
private static final ClassName REQUEST_STARTED = ClassName.get("io.tiko.events", "RequestStartedEvent");
private static final ClassName REQUEST_ENDING = ClassName.get("io.tiko.events", "RequestEndingEvent");
```

Delete both lines.

- [ ] **Step 2: Delete `createRunInRequestScopeMethod()` and `createSupplyInRequestScopeMethod()`**

Lines ~928–979 contain the two emission methods. Delete both entire methods (including their javadoc).

- [ ] **Step 3: Remove call sites that invoke the deleted emitters**

Grep for `createRunInRequestScopeMethod` and `createSupplyInRequestScopeMethod` within `ContainerGenerator.java`. Each call site is in the type-builder list of `MethodSpec` additions — delete those invocation lines.

Verify with:

```bash
Grep -n "createRunInRequestScopeMethod\|createSupplyInRequestScopeMethod" tiko-processor/src/main/java/io/tiko/processor/ContainerGenerator.java
```

Expected: no matches.

- [ ] **Step 4: Collapse the scoped-getter branch at line ~590**

Find:

```java
if (component.getScope() == Scope.REQUEST || component.getScope() == Scope.EVENT) {
    methods.add(createCurrentScopedGetter(component));
}
```

Replace with:

```java
if (component.getScope() == Scope.EVENT) {
    methods.add(createCurrentScopedGetter(component));
}
```

- [ ] **Step 5: Collapse the scoped-getter emission at line ~855**

Find the `if (component.getScope() == Scope.REQUEST) { emitScopedGetOrCreate(method, returnType, "requestScoped.get()", ...); }` branch plus its `else` for EVENT. Delete the REQUEST branch entirely (along with the `else if` collapsing into the EVENT path).

After the change, the generator only emits `eventScoped.get()` accessors.

- [ ] **Step 6: Collapse the `requestScoped` ThreadLocal field emission**

Search for where `ContainerGenerator` adds the `requestScoped` field to the generated container type (it's the analogue of `eventScoped`). Delete that `FieldSpec` and any constructor initialization line for it.

Verify:

```bash
Grep -n "requestScoped" tiko-processor/src/main/java/io/tiko/processor/ContainerGenerator.java
```

Expected: no matches.

- [ ] **Step 7: Add a single-frame nesting guard to the emitted `runInEventScope`**

Find the method that emits `runInEventScope` in the generated container (analogous to the deleted `createRunInRequestScopeMethod`). Add a guard at the top of the generated method body that throws `IllegalStateException` if `eventScoped.get()` is already non-null/non-empty (i.e. a unit of work is already open).

If the existing emitter currently allows nesting silently (the prior behaviour where REQUEST wrapped EVENT relied on EVENT being callable inside REQUEST, not inside *another* EVENT), the guard goes in front of the existing setup. The emitted code should read like:

```java
public void runInEventScope(Runnable runnable) {
    if (eventScoped.get() != null && !eventScoped.get().isEmpty()) {
        throw new IllegalStateException(
            "runInEventScope called while a unit of work is already open. " +
            "EVENT is single-frame in 0.x.0; nesting is not supported.");
    }
    // ... existing setup, EventStartedEvent publish, runnable.run(), teardown, EventEndingEvent publish ...
}
```

Mirror the same guard in `supplyInEventScope`.

- [ ] **Step 8: Update the class javadoc at line ~27**

Find:

```java
// "Scope management methods (runInRequestScope, runInEventScope)"
```

Replace with:

```java
// "Scope management methods (runInEventScope)"
```

- [ ] **Step 9: Format and compile**

```bash
mvn -pl '!tiko-bom' spotless:apply
mvn compile -pl tiko-processor
```

Expected: BUILD SUCCESS for `tiko-processor`.

- [ ] **Step 10: Commit**

```bash
git add tiko-processor/src/main/java/io/tiko/processor/ContainerGenerator.java
git commit -m "refactor(processor): drop REQUEST emission and guard nested runInEventScope (#226)"
```

### Task 7: Migrate REQUEST-touching processor tests

**Files:**
- Modify: `tiko-processor/src/test/java/io/tiko/processor/TestComponentScopeMismatchTest.java` (line ~33)
- Modify: `tiko-processor/src/test/java/io/tiko/processor/RequiredInterfaceForProxyTest.java` (line ~120)
- Modify: `tiko-processor/src/test/java/io/tiko/processor/ProxyInheritedMethodsAndGenericsTest.java` (lines ~55, ~114)
- Modify: `tiko-processor/src/test/java/io/tiko/processor/ProxyForProducesOutputCrossScopeTest.java` (line ~45)
- Modify: `tiko-processor/src/test/java/io/tiko/processor/ShutdownFailureLoggingTest.java` (lines ~52, ~55)
- Modify: `tiko-processor/src/test/java/io/tiko/processor/ShutdownTeardownTimeoutWiringTest.java` (lines ~55, ~58)
- Modify: `tiko-processor/src/test/java/io/tiko/processor/CrossScopeMatrixTest.java` (entire matrix)

- [ ] **Step 1: Rewrite each `@Component(scope = Scope.REQUEST)` → `@Component(scope = Scope.EVENT)`**

For each file in the file list above: replace every occurrence of `Scope.REQUEST` (whether on `@Component` or `@Produces`) with `Scope.EVENT`. Test names should be left alone in this step — a follow-up sweep handles renames.

Verify:

```bash
Grep -rn "Scope.REQUEST" tiko-processor/src/test
```

Expected: no matches.

- [ ] **Step 2: Rewrite `CrossScopeMatrixTest` to the 3×3 shape**

Open `CrossScopeMatrixTest.java`. The current test matrix exercises 4 consumer scopes × 4 dependency scopes = 16 combinations. Reduce to 3 × 3 = 9 combinations: `SINGLETON, EVENT, PROTOTYPE` along both axes. Expected proxy/direct outcomes per spec §2.4:

| Consumer ↓ / Dependency → | SINGLETON | EVENT     | PROTOTYPE |
|---------------------------|-----------|-----------|-----------|
| SINGLETON                 | direct    | **proxy** | direct    |
| EVENT                     | direct    | direct    | direct    |
| PROTOTYPE                 | direct    | direct    | direct    |

Concrete rewrite: if the test uses a `@ParameterizedTest` + `@MethodSource` returning `Arguments.of(consumerScope, dependencyScope, expectedProxy)`, prune rows/columns to the 3-scope set and update the `expectedProxy` column where needed. If the test is a sequence of `@Test` methods, delete the four REQUEST-row methods and the four REQUEST-column methods, leaving the nine remaining methods (or merge into a parameterized test as a cleanup).

- [ ] **Step 3: Run processor tests**

```bash
mvn test -pl tiko-processor
```

Expected: all tests pass. Specifically `CrossScopeMatrixTest` runs 9 combinations and all pass; the formerly REQUEST-specific tests still validate proxy/teardown behaviour but with EVENT-scoped fixtures.

- [ ] **Step 4: Format**

```bash
mvn -pl '!tiko-bom' spotless:apply
```

- [ ] **Step 5: Commit**

```bash
git add tiko-processor/src/test
git commit -m "test(processor): migrate REQUEST-touching tests to EVENT; collapse matrix to 3x3 (#226)"
```

### Task 8: Stage 2 verification

- [ ] **Step 1: Run full processor build**

```bash
mvn clean install -pl tiko-processor -am
```

Expected: BUILD SUCCESS (`-am` pulls in `tiko-api`).

- [ ] **Step 2: If green, no commit needed (verification only)**

If red, diagnose: most likely a missed `Scope.REQUEST` reference. Re-run `Grep -rn "Scope.REQUEST" tiko-processor/`.

---

## Stage 3 — `tiko-runtime` (delegation removal + nesting guard test)

### Task 9: Remove REQUEST delegation from `AggregatingContainer`

**Files:**
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java`

- [ ] **Step 1: Delete the two override methods**

Lines ~476–487 (the `runInRequestScope` override + the `supplyInRequestScope` override). Delete both entire methods, including the comment at line ~478.

- [ ] **Step 2: Update the `runNested` javadoc**

Line ~502's comment currently says "REQUEST/EVENT-scoped beans". Change to "EVENT-scoped beans".

- [ ] **Step 3: Confirm `tiko-runtime` compiles**

Run: `mvn compile -pl tiko-runtime -am`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add tiko-runtime/src/main/java/io/tiko/runtime/AggregatingContainer.java
git commit -m "refactor(runtime): remove runInRequestScope/supplyInRequestScope delegation (#226)"
```

### Task 10: Remove REQUEST delegation from `TikoDaemon`

**Files:**
- Modify: `tiko-runtime/src/main/java/io/tiko/runtime/Tiko.java`

- [ ] **Step 1: Delete the two delegation methods**

Lines ~528–534 inside the `TikoDaemon` inner class. Delete both `runInRequestScope` and `supplyInRequestScope` methods + their bodies.

- [ ] **Step 2: Confirm runtime compiles**

Run: `mvn compile -pl tiko-runtime -am`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add tiko-runtime/src/main/java/io/tiko/runtime/Tiko.java
git commit -m "refactor(runtime): remove TikoDaemon REQUEST delegation methods (#226)"
```

### Task 11: Add nested-call guard test

**Files:**
- Create: `tiko-runtime/src/test/java/io/tiko/runtime/NestedEventScopeRejectionTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.Container;
import io.tiko.Tiko;
import org.junit.jupiter.api.Test;

class NestedEventScopeRejectionTest {

    @Test
    void runInEventScopeThrowsWhenAlreadyInsideAnotherUnit() {
        try (Container container = Tiko.create()) {
            assertThatThrownBy(() -> container.runInEventScope(() ->
                    container.runInEventScope(() -> {
                        // unreachable
                    })))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("single-frame");
        }
    }

    @Test
    void supplyInEventScopeThrowsWhenAlreadyInsideAnotherUnit() {
        try (Container container = Tiko.create()) {
            assertThatThrownBy(() -> container.runInEventScope(() ->
                    container.supplyInEventScope(() -> "never")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("single-frame");
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it asserts the expected behaviour**

```bash
mvn test -pl tiko-runtime -Dtest=NestedEventScopeRejectionTest
```

Expected: PASS, because the guard added in Task 6 step 7 emits the throw in generated `TikoContainerImpl`. If FAIL with "did not throw," revisit Task 6 step 7 — the emitted guard was incomplete or wired to the wrong helper. If FAIL with "wrong message," tweak the guard string.

- [ ] **Step 3: Format**

```bash
mvn -pl '!tiko-bom' spotless:apply
```

- [ ] **Step 4: Commit**

```bash
git add tiko-runtime/src/test/java/io/tiko/runtime/NestedEventScopeRejectionTest.java
git commit -m "test(runtime): assert nested runInEventScope/supplyInEventScope throws (#226)"
```

### Task 12: Stage 3 verification

- [ ] **Step 1: Run full runtime build**

```bash
mvn clean install -pl tiko-runtime -am
```

Expected: BUILD SUCCESS. `tiko-api`, `tiko-processor`, `tiko-runtime` are all green at this point.

---

## Stage 4 — `tiko-test`

### Task 13: Delete `RequestScopeTest` annotation and fixture

**Files:**
- Delete: `tiko-test/src/main/java/io/tiko/test/RequestScopeTest.java`
- Delete: `tiko-test/src/test/java/io/tiko/test/fixtures/RequestScopedService.java`

- [ ] **Step 1: Delete both files**

```bash
git rm tiko-test/src/main/java/io/tiko/test/RequestScopeTest.java
git rm tiko-test/src/test/java/io/tiko/test/fixtures/RequestScopedService.java
```

- [ ] **Step 2: Commit**

```bash
git commit -m "refactor(test): delete RequestScopeTest annotation and RequestScopedService fixture (#226)"
```

### Task 14: Strip RequestScopeTest branch from `TikoTestExtension`

**Files:**
- Modify: `tiko-test/src/main/java/io/tiko/test/TikoTestExtension.java`

- [ ] **Step 1: Delete the RequestScopeTest detection**

Line ~106 reads `boolean req = method.isAnnotationPresent(RequestScopeTest.class);`. Delete the line.

- [ ] **Step 2: Collapse the wrapping branches**

Lines ~126–128 currently handle the three combinations (req + event, req only, event only). Delete the two `req`-bearing branches, leaving only the EventScopeTest path. The remaining flow is: if `method.isAnnotationPresent(EventScopeTest.class)`, wrap in `container.runInEventScope(body)`; otherwise run `body` directly.

- [ ] **Step 3: Remove the unused `RequestScopeTest` import**

- [ ] **Step 4: Compile and run module tests**

```bash
mvn test -pl tiko-test -am
```

Expected: all tests pass. The `ScopeHelpersTest` test will fail in this state — it's migrated in the next task.

If `ScopeHelpersTest` is the *only* failure, that's expected; otherwise diagnose.

- [ ] **Step 5: Commit**

```bash
git add tiko-test/src/main/java/io/tiko/test/TikoTestExtension.java
git commit -m "refactor(test): drop RequestScopeTest branch from TikoTestExtension (#226)"
```

### Task 15: Migrate `ScopeHelpersTest` to `@EventScopeTest`

**Files:**
- Create: `tiko-test/src/test/java/io/tiko/test/fixtures/EventScopedService.java`
- Modify: `tiko-test/src/test/java/io/tiko/test/ScopeHelpersTest.java`

- [ ] **Step 1: Add `EventScopedService` fixture**

```java
package io.tiko.test.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;

@Component(scope = Scope.EVENT)
public class EventScopedService {
    public String label() { return "event-scoped"; }
}
```

- [ ] **Step 2: Rewrite `ScopeHelpersTest`**

Replace contents with:

```java
package io.tiko.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.test.fixtures.EventScopedService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TikoTestExtension.class)
class ScopeHelpersTest {

    @Test
    @EventScopeTest
    void eventScopedBeanResolvableInsideEventScope(Container c) {
        assertThat(c.get(EventScopedService.class).label()).isEqualTo("event-scoped");
    }
}
```

(If the existing test class signature uses a different `Container`-injection mechanism, mirror that; the point is to assert an EVENT-scoped bean is resolvable when `@EventScopeTest` is applied.)

- [ ] **Step 3: Run module tests**

```bash
mvn test -pl tiko-test -am
```

Expected: all tests pass, including `ScopeHelpersTest`.

- [ ] **Step 4: Format**

```bash
mvn -pl '!tiko-bom' spotless:apply
```

- [ ] **Step 5: Commit**

```bash
git add tiko-test/src/test
git commit -m "test(test): migrate ScopeHelpersTest to EVENT scope + EventScopedService fixture (#226)"
```

---

## Stage 5 — Examples and docs

Each example is its own task so failures bisect cleanly.

### Task 16: Migrate `01_basic_di`

**Files:** all listed below under `tiko-examples/01_basic_di/`.

REQUEST-scoped components:
- `RequestContextImpl.java`
- `teardown/AutoCloseableRequestHolder.java`
- `teardown/LifoRequestA.java`, `LifoRequestB.java`, `LifoRequestC.java`
- `teardown/ThrowingCloseRequestBean.java`, `ThrowingPostConstructRequestBean.java`, `ThrowingPreDestroyRequestBean.java`
- `teardown/ThrowingCheckedPostConstructBean.java`, `teardown/ThrowingCheckedProducesFactory.java`

`runInRequestScope`/`supplyInRequestScope` callers and `Request*Event` users:
- `Main.java`
- `teardown/*Test.java`, `ordering/*Test.java`, `LifecycleEventsTest.java`, `CoreDiIntegrationTest.java`
- `teardown/TeardownRecorder.java` (javadoc only)
- `LifecycleRecorder.java`, `ordering/LifecycleOrderProbe.java` (`@EventHandler` methods)

- [ ] **Step 1: Replace all `Scope.REQUEST` with `Scope.EVENT` across `01_basic_di/`**

```bash
Grep -rln "Scope\\.REQUEST" tiko-examples/01_basic_di
```

For each match, swap `Scope.REQUEST` → `Scope.EVENT`. Rename classes if their name encodes the scope (e.g. `LifoRequestA` → `LifoUnitA`, `AutoCloseableRequestHolder` → `AutoCloseableUnitHolder`, `ThrowingCloseRequestBean` → `ThrowingCloseUnitBean`). Move-rename, don't leave stale `Request`-prefixed class names. Track each rename with `git mv` so history is preserved.

- [ ] **Step 2: Replace all `runInRequestScope` / `supplyInRequestScope` calls with `runInEventScope` / `supplyInEventScope`**

```bash
Grep -rln "runInRequestScope\\|supplyInRequestScope" tiko-examples/01_basic_di
```

Swap each call to the EVENT variant. Where `runInRequestScope { runInEventScope { … } }` previously nested, flatten to a single `runInEventScope { … }` (the inner content runs inside the outer unit; in 0.x.0 you can't nest).

If any test deliberately exercised the multi-EVENT-in-one-REQUEST pattern, restructure to a single `runInEventScope` with an internal loop.

- [ ] **Step 3: Replace `RequestStartedEvent` / `RequestEndingEvent` handlers with `EventStartedEvent` / `EventEndingEvent`**

In `LifecycleRecorder.java`, `ordering/LifecycleOrderProbe.java`, and any test that subscribes to these events: change the `@EventHandler` method parameter types and any class-literal references. Field/list assertions that expect specific event types must update too.

- [ ] **Step 4: Run module tests**

```bash
mvn test -pl tiko-examples/01_basic_di -am
```

Expected: all tests pass.

- [ ] **Step 5: Format**

```bash
mvn -pl '!tiko-bom' spotless:apply
```

- [ ] **Step 6: Commit**

```bash
git add tiko-examples/01_basic_di
git commit -m "refactor(examples/01_basic_di): migrate REQUEST → EVENT (#226)"
```

### Task 17: Migrate `03_events`

**Files:**
- Modify: `tiko-examples/03_events/Main.java` (lines 21, 26, 31)
- Modify: `tiko-examples/03_events/Observability.java` (lines 36, 41)

- [ ] **Step 1: Flatten the outer REQUEST wrapper in `Main.java`**

The current example wraps three `runInRequestScope` blocks each containing a `runInEventScope`. Convert to three `runInEventScope` blocks (drop the request layer). If the demonstration was specifically *about* the REQUEST-EVENT split, restructure the narrative — the new shape is a single unit per event.

- [ ] **Step 2: Migrate `Observability.java` handlers**

The two `@EventHandler` methods at lines 36 and 41 currently receive `RequestStartedEvent` / `RequestEndingEvent`. Replace them with handlers for `EventStartedEvent` / `EventEndingEvent`. If both pairs existed (request + event), drop the request-pair handlers and merge their behaviour into the event-pair handlers.

- [ ] **Step 3: Compile + run module tests**

```bash
mvn test -pl tiko-examples/03_events -am
```

Expected: all tests pass.

- [ ] **Step 4: Format + commit**

```bash
mvn -pl '!tiko-bom' spotless:apply
git add tiko-examples/03_events
git commit -m "refactor(examples/03_events): migrate REQUEST → EVENT, flatten Main.java demo (#226)"
```

### Task 18: Migrate `09_http_javalin`

**Files:**
- Modify: `tiko-examples/09_http_javalin/RequestIdImpl.java` (line 12)
- Modify: `tiko-examples/09_http_javalin/TikoJavalin.java` (line 34)
- Modify: `tiko-examples/09_http_javalin/RequestTimer.java` (lines 31, 37)

- [ ] **Step 1: `RequestIdImpl` — change scope to EVENT**

```java
@Component(scope = Scope.EVENT)
public class RequestIdImpl implements RequestId {
    // ... unchanged body ...
}
```

The component keeps its semantic name (it really is a request-id in HTTP context) — *only the scope* changes. When the typed-flavour API lands later, a `@RequestScoped` meta-annotation can re-introduce HTTP-specific typing.

- [ ] **Step 2: `TikoJavalin` middleware — switch to `runInEventScope`**

Find the middleware setup:

```java
ctx -> container.runInRequestScope(() -> { delegate.handle(ctx) })
```

Replace with:

```java
ctx -> container.runInEventScope(() -> delegate.handle(ctx))
```

- [ ] **Step 3: `RequestTimer` — migrate handlers**

Replace `@EventHandler` methods for `RequestStartedEvent` / `RequestEndingEvent` with `EventStartedEvent` / `EventEndingEvent`.

- [ ] **Step 4: Compile + run module tests**

```bash
mvn test -pl tiko-examples/09_http_javalin -am
```

Expected: tests pass.

- [ ] **Step 5: Format + commit**

```bash
mvn -pl '!tiko-bom' spotless:apply
git add tiko-examples/09_http_javalin
git commit -m "refactor(examples/09_http_javalin): migrate REQUEST → EVENT (#226)"
```

### Task 19: Rework `10_persistence_jdbc`

This is the structural change called out in spec §1 and §8.

**Files:**
- Modify: `tiko-examples/10_persistence_jdbc/TransactionalScope.java`
- Modify: `tiko-examples/10_persistence_jdbc/TransactionContext.java` (line 26)
- Modify: `tiko-examples/10_persistence_jdbc/JdbcConnectionProvider.java` (lines 22, 32)
- Modify: the demo entrypoint / `Main.java` that drives the batch
- Add: a short README/comment block in the example explaining the outbox pointer for "genuinely-independent-events" use cases

- [ ] **Step 1: Migrate component scopes**

`TransactionContext` and `JdbcConnectionProvider` both move from `@Component(scope = Scope.REQUEST)` to `@Component(scope = Scope.EVENT)`. Same for the `@Produces(scope = Scope.REQUEST)` Connection factory at `JdbcConnectionProvider.java:32` → `Scope.EVENT`.

- [ ] **Step 2: Rework `TransactionalScope`**

Current shape (line 22):

```java
container.supplyInRequestScope(() -> {
    var tx = container.get(TransactionContext.class);
    // loop iterations, each potentially calling runInEventScope internally
    return result;
});
```

Replace with the "one unit, internal loop" shape:

```java
container.supplyInEventScope(() -> {
    var tx = container.get(TransactionContext.class);
    tx.begin();
    try {
        for (var item : items) {
            // process item using tx — no inner runInEventScope; we are the unit
            processItem(item, tx);
        }
        tx.commit();
        return result;
    } catch (Exception e) {
        tx.rollback();
        throw e;
    }
});
```

Adjust signatures to match what `TransactionalScope` actually does in the current codebase — the *shape* is "open transaction, loop inside, commit/rollback at end, all within one EVENT scope."

- [ ] **Step 3: Add the outbox pointer**

Add a short comment block (or a 5–10-line README addendum) explaining that genuinely-independent events — events that need to be retryable, distributed, or processed out-of-order from the batch — should each own their own EVENT scope (and own their own transaction), with cross-event consistency handled by an outbox/saga pattern above the DI layer. Don't ship an outbox implementation here; just point at the concept.

- [ ] **Step 4: Update Main.java / driver**

Whatever driver kicks off the batch needs to call the reworked `TransactionalScope` shape. Update accordingly.

- [ ] **Step 5: Compile + run module tests**

```bash
mvn test -pl tiko-examples/10_persistence_jdbc -am
```

Expected: tests pass. If a test specifically asserted "many EVENT inside one REQUEST" cardinality, restructure that test to assert "one EVENT containing N iterations".

- [ ] **Step 6: Format + commit**

```bash
mvn -pl '!tiko-bom' spotless:apply
git add tiko-examples/10_persistence_jdbc
git commit -m "refactor(examples/10_persistence_jdbc): one unit per transaction with internal loop (#226)"
```

### Task 20: Migrate `12_testing`

**Files:**
- Modify: `tiko-examples/12_testing/AccountRepository.java` (line 6)
- Modify: any test in this example that uses `@RequestScopeTest`

- [ ] **Step 1: Change `AccountRepository` scope to EVENT**

```java
@Component(scope = Scope.EVENT)
public class AccountRepository { ... }
```

- [ ] **Step 2: Swap `@RequestScopeTest` → `@EventScopeTest` in tests**

```bash
Grep -rln "@RequestScopeTest" tiko-examples/12_testing
```

Replace each with `@EventScopeTest` and update imports.

- [ ] **Step 3: Compile + run module tests**

```bash
mvn test -pl tiko-examples/12_testing -am
```

Expected: tests pass.

- [ ] **Step 4: Format + commit**

```bash
mvn -pl '!tiko-bom' spotless:apply
git add tiko-examples/12_testing
git commit -m "refactor(examples/12_testing): migrate REQUEST → EVENT (#226)"
```

### Task 21: Migrate `13_mcp_introspection`

**Files:**
- Modify: `tiko-examples/13_mcp_introspection/OrderRepository.java` (line 7)

- [ ] **Step 1: Change scope to EVENT**

```java
@Component(scope = Scope.EVENT)
public class OrderRepository { ... }
```

- [ ] **Step 2: Compile + run module tests**

```bash
mvn test -pl tiko-examples/13_mcp_introspection -am
```

Expected: tests pass.

- [ ] **Step 3: Format + commit**

```bash
mvn -pl '!tiko-bom' spotless:apply
git add tiko-examples/13_mcp_introspection
git commit -m "refactor(examples/13_mcp_introspection): migrate REQUEST → EVENT (#226)"
```

### Task 22: Rewrite `docs/di-and-scopes.md`

**Files:**
- Modify: `docs/di-and-scopes.md`

- [ ] **Step 1: Rewrite the "Scopes" section (lines ~7–37)**

Replace the scope hierarchy table with the 3-scope shape:

```markdown
## Scopes

Tiko has three lifecycle scopes, from longest to shortest:

| Scope     | Lifetime                                                         |
|-----------|------------------------------------------------------------------|
| SINGLETON | Application lifetime                                             |
| EVENT     | One unit of work (HTTP request, message, scheduled job, async)  |
| PROTOTYPE | Per injection (default)                                          |

A **unit of work** is the synchronous reach of an inbound stimulus, bounded at every
async / transport hop. EVENT is single-frame in `0.x.0` — calling `runInEventScope`
while a unit is already open throws `IllegalStateException`. Nestability is a
deferred-but-additive future change.

### Cross-scope injection

| Consumer ↓ / Dep → | SINGLETON | EVENT          | PROTOTYPE |
|--------------------|-----------|----------------|-----------|
| SINGLETON          | direct    | **proxy**      | direct    |
| EVENT              | direct    | direct         | direct    |
| PROTOTYPE          | direct    | direct         | direct    |

Proxies require the EVENT-scoped bean to implement an interface.
```

- [ ] **Step 2: Rewrite or remove the "REQUEST and EVENT scopes together" section (lines ~39–68)**

The section's whole premise (one REQUEST holding many EVENTs) is gone. Replace with a short subsection titled **"Unit of work in practice"** that shows the persistence example shape:

```markdown
### Unit of work in practice

A batch that needs one transaction across its items is one unit of work, not many:

\`\`\`java
container.runInEventScope(() -> {
    var tx = container.get(TransactionContext.class);
    tx.begin();
    try {
        for (var item : items) {
            process(item, tx);
        }
        tx.commit();
    } catch (Exception e) {
        tx.rollback();
        throw e;
    }
});
\`\`\`

Genuinely independent events — those that need to be retryable, distributed, or
re-ordered relative to the batch — each own their own unit of work and their own
transaction; cross-event consistency is an outbox/saga concern above the DI layer.
```

(In the actual file, the inner code fence uses real triple backticks — the escapes here are just to display inside this plan.)

- [ ] **Step 3: Commit**

```bash
git add docs/di-and-scopes.md
git commit -m "docs(scopes): rewrite scope hierarchy + cross-scope matrix for 3-scope shape (#226)"
```

### Task 23: Rewrite `CLAUDE.md` scope sections

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Rewrite "Scope Management" section (lines ~95–128)**

Replace the existing scope hierarchy block + cross-scope rules with content that mirrors `docs/di-and-scopes.md`'s 3-scope shape:

- The "Four scopes" intro becomes "Three scopes".
- The hierarchy ASCII art: drop the REQUEST line; the chain becomes `SINGLETON → EVENT → PROTOTYPE`.
- The cross-scope injection rules become the 3×3 matrix from spec §2.4.
- The "Key distinction: One REQUEST can process multiple EVENTs" paragraph is removed; replace with a paragraph defining "unit of work" the way `docs/di-and-scopes.md` does.

- [ ] **Step 2: Rewrite the "Scopes (Scope enum)" subsection (line ~178)**

Drop the `Scope.REQUEST` bullet. The remaining bullets:

```markdown
- `Scope.SINGLETON` - Application lifetime
- `Scope.EVENT` - One unit of work (HTTP request, message, job, async dispatch)
- `Scope.PROTOTYPE` - New instance per injection (default, shortest)
```

- [ ] **Step 3: Rewrite "Lifecycle Events" section (lines ~203–250)**

Remove `RequestStartedEvent` / `RequestEndingEvent` from the event list, and remove the `Request Scope Lifecycle` block. Rework the `MetricsCollector` example to use only `EventStartedEvent` / `EventEndingEvent` (drop the `onRequestStarted`/`onRequestEnding` handlers).

- [ ] **Step 4: Sweep the rest of `CLAUDE.md` for `Scope.REQUEST` / `runInRequestScope` / `RequestStartedEvent` / `RequestEndingEvent` mentions**

```bash
Grep -n "Scope\\.REQUEST\\|runInRequestScope\\|supplyInRequestScope\\|RequestStartedEvent\\|RequestEndingEvent" CLAUDE.md
```

Expected after edits: no matches.

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(claude-md): rewrite scope sections for 3-scope shape (#226)"
```

### Task 24: README sweep

**Files:**
- Modify (if needed): `README.md`

- [ ] **Step 1: Search for any REQUEST mention**

```bash
Grep -n "Scope\\.REQUEST\\|runInRequestScope\\|supplyInRequestScope\\|RequestStartedEvent\\|RequestEndingEvent" README.md
```

- [ ] **Step 2: If matches found, replace per docs/di-and-scopes.md patterns**

If no matches: skip step 3.

- [ ] **Step 3: Commit (only if README changed)**

```bash
git add README.md
git commit -m "docs(readme): drop REQUEST scope references (#226)"
```

---

## Final verification

### Task 25: Full reactor green

- [ ] **Step 1: Run the full reactor**

```bash
mvn clean install
```

Expected: BUILD SUCCESS across all modules (`tiko-api`, `tiko-processor`, `tiko-runtime`, `tiko-config`, `tiko-test`, and every `tiko-examples/*`).

- [ ] **Step 2: Verify REQUEST is gone reactor-wide**

```bash
Grep -rn "Scope\\.REQUEST\\|runInRequestScope\\|supplyInRequestScope\\|RequestStartedEvent\\|RequestEndingEvent" --type java --type md .
```

Expected: zero matches in `src/main`, `src/test`, `docs/`, `CLAUDE.md`, `README.md`. The only legitimate residuals: (a) the spec file at `docs/superpowers/specs/2026-05-29-scope-model-unification-design.md` (it documents what was removed), (b) memory files under `~/.claude` (out of repo), (c) the migration plan itself.

If the grep finds anything in `src/`, fix in a follow-up commit before the PR.

- [ ] **Step 3: Verify Spotless is clean**

```bash
mvn spotless:check
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Push and open the PR**

```bash
git push -u origin refactor/issue-226-scope-migration
gh pr create --title "refactor: migrate to 3-scope public API — REQUEST removed (#226)" --body "Implements the migration designed in #250 (spec PR). \
\
- Drops Scope.REQUEST from the public enum; removes Container.runInRequestScope / supplyInRequestScope; deletes RequestStartedEvent and RequestEndingEvent. \
- Generated TikoContainerImpl uses a single eventScoped ThreadLocal; runInEventScope/supplyInEventScope throw IllegalStateException when a unit is already open (single-frame in 0.x.0; future nestability strictly additive). \
- Processor proxy decision collapses to SINGLETON↔EVENT only; CrossScopeMatrixTest reduced to 3×3. \
- Examples migrated; 10_persistence_jdbc reworked to one-unit-per-transaction with an internal loop. \
- Docs: di-and-scopes.md and CLAUDE.md rewritten to the 3-scope shape. \
\
Closes the implementation half of #226."
```

---

## Spec follow-up (small)

Spec §3 attributes the two ThreadLocals to `AggregatingContainer`; they actually live in generated `TikoContainerImpl`. Open a tiny follow-up doc commit (one paragraph) after this migration lands to correct that detail in the spec. Not blocking; track as a comment on #250.
