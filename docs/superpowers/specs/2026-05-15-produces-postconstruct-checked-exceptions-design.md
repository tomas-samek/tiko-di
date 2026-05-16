# `@Produces` and `@PostConstruct` checked-exception propagation — design

Status: draft (2026-05-15). Closes Issue #97. Companion follow-up
Issue #98 (typed framework-originated `RuntimeException` subtypes) is
deliberately out of scope here.

## Context

Tiko's annotation processor generates wrapper code for `@PostConstruct`
methods (in `ComponentFactoryGenerator`) and `@Produces` factory
accessors (in `ContainerGenerator`). The generated code currently
catches only `RuntimeException | Error` around `@PostConstruct` calls
and uses no try/catch at all around `@Produces` accessor bodies. The
generated method declarations carry no `throws` clauses.

The combined result: a user who writes the natural Java form
`@Produces Connection connection() throws SQLException` or
`@PostConstruct void initialize() throws SQLException` fails to compile
against the generated wrapper, because the generated method neither
declares the checked exception nor catches it.

The current workaround forced on users is to wrap every checked-throwing
factory body in a `try { … } catch (SQLException e) { throw new
IllegalStateException("…", e); }` boilerplate. The persistence cookbook
(`tiko-examples/10_persistence_jdbc/`) does this twice
(`JdbcConnectionProvider.connection()` and
`SchemaInitializer.initialize()`).

## Reference: events already do this right

`EventRegistryGenerator` is the canonical "catch broadly, route to
`ErrorHandler`, preserve the user's throwable" pattern:

- **Sync dispatch** (`EventRegistryGenerator.java:226`):
  `catch ($T __t)` where `$T = Exception.class`. Catches both unchecked
  and checked. The throwable goes into an `EventHandlerError`
  ErrorContext (a record, no extra `fillInStackTrace`), is routed to
  `errorHandler.onError(...)`, and the dispatcher silently continues —
  no rethrow, because event delivery is fire-and-forget.
- **Async dispatch** (`EventRegistryGenerator.java:170-213`):
  `CompletableFuture.runAsync(...).whenComplete((r, __t) -> { ... })` —
  `__t` is `Throwable`, so checked-throws also surface naturally and
  route to ErrorHandler.

The fix for `@Produces` and `@PostConstruct` copies that shape, with one
addition: a sneaky-throw at the end of the catch to propagate the
original throwable up the call stack (unlike events, the consumer is
waiting on a constructed bean and needs the exception to surface).

## Design principles

1. **Framework never substitutes the user's throwable.** Catch
   `Throwable`, observe via `ErrorContext`, propagate the *same*
   throwable instance — original stack trace and identity preserved.
2. **ErrorContext records stay cheap.** They carry the throwable as a
   field (`PostConstructFailure` does this today: `record
   PostConstructFailure(Class<?> component, Throwable cause)
   implements ErrorContext {}`). No new stack-trace allocation on the
   routing path.
3. **Sneaky-throw, not new typed exceptions for the wrap case.** The
   user already authored their exception; the framework does not
   introduce a `ProduceException extends RuntimeException` just to
   rebrand it. The classic
   `<T extends Throwable> T uncheck(Throwable e) throws T { throw (T) e; }`
   trick lets the original propagate as itself through the generated
   factory body — no Tiko frame in the user's debugger view.
4. **Sealed `ErrorContext` permits list is appended.** Adding
   `ProduceFailure` is a compile-time-loud breaking change for users
   with exhaustive `switch (ctx) { case … }` patterns, as the existing
   `ErrorContext` Javadoc explicitly notes is intentional.

## Goals

- A `@Produces` factory method may declare `throws CheckedException` and
  compile cleanly. The generated `produce_*()` accessor catches all
  throwables, publishes `ProduceFailure` for observation, and propagates
  the original throwable up the call stack.
- A `@PostConstruct` lifecycle method may declare
  `throws CheckedException` and compile cleanly. The generated
  `<Component>Factory.create()` catches all throwables, publishes
  `PostConstructFailure`, and propagates the original throwable
  unchanged. (Today's `RuntimeException | Error` catch widens to
  `Throwable`.)
- The persistence cookbook (`tiko-examples/10_persistence_jdbc/`) drops
  its `IllegalStateException` wraps and uses natural `throws`
  declarations end-to-end. The cookbook's "current-Tiko quirk" callout
  is removed.
- No new public `RuntimeException` subtype is introduced. The user's
  original throwable IS the propagated exception.

## Non-goals

- Replacing raw `IllegalStateException` / `IllegalArgumentException`
  throws elsewhere in the framework. That's Issue #98's scope.
- Adding `throws` declarations to `Container.get(Class)` or any other
  caller-facing API. Sneaky-throw bridges the cascade.
- Adding `Container#get` variants for checked-exception propagation. The
  caller sees the user's exception via `catch (Exception e)` or
  `instanceof`; no new public API.

## Components

### 1. `tiko-api/src/main/java/io/tiko/ProduceFailure.java` (new)

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
 * @param declaringClass the {@code @Component}-or-bare-utility class that
 *     declares the {@code @Produces} method
 * @param methodName     the simple method name of the {@code @Produces}
 *     factory (one factory class may carry multiple, qualifier-disambiguated
 *     factories — the qualifier itself is reachable via the method's
 *     {@code @Produces(name=...)}; we don't duplicate it here)
 * @param cause          the throwable thrown by the factory method
 */
public record ProduceFailure(Class<?> declaringClass, String methodName, Throwable cause) implements ErrorContext {}
```

`ErrorContext.java` `permits` list is appended: `… ConfigurationFailure,
TransportError, ProduceFailure`.

### 2. `tiko-runtime/src/main/java/io/tiko/runtime/Unchecked.java` (new)

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

Placed in `tiko-runtime`, not `tiko-api`, because generated code in
`io.tiko.generated.*` is on the runtime classpath alongside `tiko-runtime`
and users don't typically need this helper directly. The `public` modifier
is required so generated code can reach it across packages.

### 3. `ComponentFactoryGenerator` — widen `@PostConstruct` catch

In `tiko-processor/src/main/java/io/tiko/processor/generator/ComponentFactoryGenerator.java`,
the existing block at approximately lines 148–158:

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

becomes:

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

The generated control flow becomes:

```java
try {
    instance.initialize();
} catch (Throwable __t) {
    container.getErrorHandler().onError(new PostConstructFailure(MyBean.class, __t));
    throw Unchecked.<RuntimeException>sneakyThrow(__t);
}
```

The user's `SQLException` (or any other checked) propagates as itself.
Callers of `container.get(...)` who want to handle it use
`catch (SQLException sx)` directly — the compiler doesn't see the throws
declaration, but the runtime type matches.

### 4. `ContainerGenerator` — wrap `@Produces` accessor body

Today, the `produce_<factoryId>()` accessor body in
`tiko-processor/src/main/java/io/tiko/processor/generator/ContainerGenerator.java`
calls the user method directly (no try/catch). It needs the analogous
wrap:

```java
public Connection produce_JdbcConnectionProvider_connection() {
    try {
        return getJdbcConnectionProvider().connection();
    } catch (Throwable __t) {
        getErrorHandler().onError(new ProduceFailure(
                JdbcConnectionProvider.class, "connection", __t));
        throw Unchecked.<RuntimeException>sneakyThrow(__t);
    }
}
```

Both the instance-method path and the `isStatic()` static-factory path
get the same wrap. The exact JavaPoet code lives in `ContainerGenerator`
near `buildFactoryCallExpression(...)` and the accessor-generating
method(s); the implementation plan will pin the exact insertion points.

### 5. Persistence cookbook cleanup

After the processor fix lands:

- `tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/infra/JdbcConnectionProvider.java`
  — revert from the current `IllegalStateException` wrap back to
  `public Connection connection() throws SQLException` with the natural
  body.
- `tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/infra/SchemaInitializer.java`
  — revert `initialize()` to `throws SQLException, IOException`; drop
  the wrap blocks.
- `docs/cookbooks/persistence.md` — drop the "current-Tiko quirk"
  callout under the DataSource wiring section.

All 12 cookbook tests continue to pass.

## Testing strategy

### Processor-level (compile-testing)

Two new test files in
`tiko-processor/src/test/java/io/tiko/processor/`:

- `ProducesCheckedExceptionPropagationTest.java`
  - Compiles a `@Component(SINGLETON)` factory with `@Produces Connection
    connection() throws SQLException`.
  - Asserts compilation succeeds.
  - Asserts the generated `produce_*()` body contains `catch (Throwable`,
    `getErrorHandler().onError(new ProduceFailure(`, and
    `Unchecked.<RuntimeException>sneakyThrow(`.
  - Asserts the generated `produce_*()` method signature does NOT declare
    `throws SQLException` (the type-system bridge is internal).

- `PostConstructCheckedExceptionPropagationTest.java`
  - Compiles a `@Component(SINGLETON)` with `@PostConstruct void init()
    throws SQLException`.
  - Asserts compilation succeeds.
  - Asserts the generated factory's `create()` body now uses
    `catch (Throwable __t)`, the `onError(new PostConstructFailure(...))`
    call is preserved, and `Unchecked.sneakyThrow(__t)` replaces the
    prior `throw __t`.
  - Asserts that the generated `create()` method does NOT declare
    `throws SQLException`.

### Runtime-level

One new test in
`tiko-runtime/src/test/java/io/tiko/runtime/` (or wherever existing
end-to-end runtime tests live):

- Boots a container with a `@Produces` factory that throws `SQLException`
  on every call; registers a custom `ErrorHandler` via
  `TikoOptions.errorHandler(...)` that records ErrorContexts; calls
  `container.get(Connection.class)`.
- Asserts the recorded ErrorContexts contain one `ProduceFailure`
  with the expected `declaringClass` and `methodName`.
- Asserts the thrown exception caught at the call site IS the original
  `SQLException` instance — same identity, same stack trace.
- Symmetric test for `@PostConstruct throws SQLException`.

### Example cleanup verification

After the cookbook reverts in §5:

- `mvn -pl tiko-examples/10_persistence_jdbc test` reports 12 tests
  passing (same as today, no regressions).
- Reactor `mvn -pl '!tiko-bom' install` is BUILD SUCCESS.

## Acceptance

- [ ] `ProduceFailure` record exists at `io.tiko.ProduceFailure`,
      implements `ErrorContext`, added to the sealed permits list.
- [ ] `Unchecked.sneakyThrow` exists at `io.tiko.runtime.Unchecked`,
      package-public class with private constructor and one static
      method.
- [ ] `ComponentFactoryGenerator` emits `catch (Throwable __t)` and
      sneaky-throws via `Unchecked` for `@PostConstruct` methods.
- [ ] `ContainerGenerator` emits a `try { return user.foo(); } catch
      (Throwable __t) { … }` wrap around every `@Produces` accessor
      (instance and static paths).
- [ ] Two new processor regression tests (one per annotation) pass.
- [ ] One new runtime test (or both — pick the granularity at
      writing-plans time) asserts the user's checked exception is the
      thrown identity and the ErrorContext arrives at a custom
      ErrorHandler.
- [ ] Persistence cookbook reverted: `JdbcConnectionProvider` and
      `SchemaInitializer` declare natural `throws`. Cookbook docs drop
      the quirk callout. All 12 cookbook tests pass.
- [ ] Full reactor `mvn -pl '!tiko-bom' install` green.
- [ ] Spotless gate clean.
- [ ] No new code outside `tiko-api/`, `tiko-runtime/`, `tiko-processor/`,
      `tiko-examples/10_persistence_jdbc/`, and `docs/cookbooks/`.

## Risks

- **Sneaky-throw is mildly controversial.** Some teams or static
  analysers (e.g. SpotBugs `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION`) may
  flag the helper. Mitigation: tight Javadoc that scopes the helper to
  framework-internal use ("generated code is the only intended caller"),
  `@SuppressWarnings("unchecked")` on the cast as already standard for
  this idiom.
- **Exhaustive-switch regression.** Adding `ProduceFailure` to the
  sealed `ErrorContext` permits list breaks user code that does
  `switch (ctx) { case … }` exhaustively. This is intentional per
  `ErrorContext`'s Javadoc ("compile-time-loud breaking change … they
  are told to handle the new category") — but worth noting in the
  release notes when this fix ships.
- **Stack trace fidelity.** The user's original throwable carries the
  stack trace from the user's method body. No extra
  `fillInStackTrace()` on the framework boundary. Verified by the
  runtime test (asserting throwable identity, not just type).
- **`Container#get(...)` caller ergonomics.** Java's compiler doesn't
  see `throws SQLException` on `get(...)`, so users wanting to catch
  the specific type write `try { … } catch (Exception e) { if (e
  instanceof SQLException sx) … }` or rely on a propagation-up-the-stack
  outer handler. Documented in the cookbook as a known shape; not
  worth a typed Container variant.

## Out of scope

- Replacing raw `IllegalStateException` / `IllegalArgumentException`
  with typed framework `RuntimeException` subtypes. Issue #98.
- Adding sealed parent class for framework exceptions. Issue #98.
- Changes to `EventHandler` dispatch — it already handles checked
  exceptions correctly per the reference pattern above.
- Changes to `@PreDestroy` or `AutoCloseable.close()` dispatch in
  shutdown. Today these route `PreDestroyFailure` / `AutoCloseFailure`
  but already swallow exceptions to keep tearing down other beans;
  they don't propagate the original to a caller, so the sneaky-throw
  is irrelevant there. Widening their catch to `Throwable` is a
  separate small improvement worth doing alongside this work but kept
  out of the spec to minimise scope creep.

## References

- Issue #97 — the GitHub-tracked issue this spec closes.
- Issue #98 — companion follow-up for framework-originated typed
  exceptions.
- `tiko-processor/src/main/java/io/tiko/processor/generator/EventRegistryGenerator.java`
  — canonical "catch broadly, route to ErrorHandler" pattern (lines
  170-213 async, 215-234 sync).
- `tiko-api/src/main/java/io/tiko/PostConstructFailure.java` — record
  shape that `ProduceFailure` mirrors.
- `tiko-api/src/main/java/io/tiko/ErrorContext.java` — sealed permits
  list and the design note about exhaustive-switch breakage being
  intentional.
- Persistence cookbook `tiko-examples/10_persistence_jdbc/` — the
  example whose `IllegalStateException` wraps are this fix's
  motivating use case and post-merge cleanup target.
