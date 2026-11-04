# Kotlin support: KSP processing and coroutine units of work

Status: design, 2026-11-04. Covers milestones K1, K1.5 and K2, planned after 0.7.0.

## Intent

Tiko should work properly in Kotlin, not merely survive it. A Kotlin service should be able to
use Tiko the way that's idiomatic in its own ecosystem:

- the KSP build integration, on Gradle;
- Kotlin language constructs that are either handled correctly or rejected with a clear,
  Tiko-style compile-time error;
- coroutines that keep the unit of work (EVENT scope) attached as they suspend and resume.

The owner's words: "without coroutines we'd be one-handed". Kotlin support without coroutines
isn't the goal. It's only the first of two steps.

The first real workloads are a **Ktor** HTTP service and **Kafka** consumption.

**No legacy infrastructure.** The integration is KSP. kapt is not a supported or documented
path.

## Goals

### Milestone K1: Kotlin / KSP

1. **Shared processor layer, `tiko-processor-core`.** A new artifact under both `tiko-processor`
   and `tiko-kafka-processor`, containing:
   - a neutral model of types and declarations (records that know nothing of
     `javax.lang.model` or the KSP API);
   - diagnostics in the Tiko error format (location, what's wrong, suggested fixes);
   - generated-file output, the `@Generated` marking, and the topology / wiring-errors JSON
     helpers;
   - **two frontend adapters**: javac (`javax.lang.model` → core) and KSP (KSP API → core).

   DI logic (validators, generators) and Kafka logic are written against the core model only.

2. **Both processors run on javac and on KSP from the same jar.** Each processor jar registers
   a `javax.annotation.processing.Processor` and a KSP `SymbolProcessorProvider`. Users point
   `annotationProcessor(...)` or `ksp(...)` at the same coordinates, so there are no `*-ksp`
   artifacts. The KSP API is a `provided` dependency. The KSP runtime supplies it, and javac
   never loads those classes.

3. **Generated output stays Java** (JavaPoet), for both frontends. KSP can emit `.java`
   sources, and the Kotlin Gradle toolchain compiles them. That leaves one generator code path.

4. **Kotlin constructs are handled or rejected at compile time.** At minimum:
   - final-by-default classes, which matters for anything Tiko might subclass;
   - `object` and `companion object`;
   - constructors with default parameter values, and the extra constructors they can produce;
   - annotation use-site targets on properties (`@field:` / `@param:` / `@get:`);
   - `internal` visibility and its name mangling;
   - nullability (`T?`) at injection points and in `@Produces` return types;
   - top-level functions annotated `@Produces`;
   - `suspend fun` annotated `@EventHandler`. **K1 rejects these** with an error that points
     to K2.

   Each construct either behaves correctly and has a test showing it, or has a negative test
   asserting a clear error.

   **Rule (owner's decision):** a construct that maps 1:1 onto a Java construct Tiko already
   supports is supported. Anything that would add new DI semantics is rejected with a
   Tiko-style error. Applied to the list above:
   - **Supported:**
     - final classes (proxies are interface-based, ARCH-10, so nothing is subclassed);
     - use-site targets;
     - `internal`;
     - default parameter values, where Tiko injects the primary constructor with **all** its
       parameters and the defaults are never used as "optional dependency".
   - **Rejected:**
     - top-level `@Produces`;
     - `object` / `companion object` as a component or as a `@Produces` host;
     - `suspend` handlers (until K2).
   - **Nullability (owner's decision):**
     - `T?` never means "optional / possibly absent dependency". A `T?` injection point with no
       provider of `T` is the usual missing-dependency error.
     - Null is legitimate only when the **provider itself may return null**: a `@Produces`
       method whose return type is `T?`.
     - Nullability is checked end to end at compile time. A nullable provider may only be
       injected into `T?` points. Injecting it into a non-null `T` is an error that names the
       producer.
     - *Designer's call:* a non-null provider may be injected into either `T` or `T?`.
     - *Designer's call:* Java-declared providers (platform types) count as non-null.
     - The generated container must store and return a `null` instance correctly for SINGLETON
       and EVENT caching. That rules out map operations that treat `null` as "absent".

5. **Gradle is the supported build for Kotlin.** One Kotlin example app is built with Gradle +
   KSP. It's a separate Gradle build inside the repo, consumes the reactor's artifacts from
   `mavenLocal()`, and runs as its own CI job after `mvn install`. The repo's own build stays
   Maven.

6. **Docs.** The `tiko-build` skill and the bundled reference cover Kotlin + Gradle + KSP setup
   and the construct rules from (4). The site gets a Kotlin mention, following the
   `tiko-site-maintainer` framing rules.

**Order inside K1** (each step ships as its own PR):

1. Create `tiko-processor-core` and move the helpers both processors already need into it.
   `tiko-kafka-processor` currently inlines its own `@Generated` instead of reusing
   `GeneratorAnnotations`; see `KafkaTransportBootstrapGenerator.java:57`.
2. Port `tiko-kafka-processor` to the neutral model as the **pilot**. It's about 0.9 kLOC, so
   it's a cheap test of the model's shape.
3. Port `tiko-processor` to the neutral model. It's about 10 kLOC, and 31 of its 40 files use
   `javax.lang.model` directly. `ComponentModel`, the validators and the generators hold
   `TypeElement` / `ExecutableElement` / `TypeMirror` today.
4. Add the KSP adapter, the Kotlin construct rules, and the Gradle example and CI job.

Steps 1–3 change no behaviour for Java users. The existing compile-testing suites are the
safety net and must pass unchanged, apart from mechanical import moves.

### Milestone K1.5: `tiko-test` on KSP

Owner's decision: Kotlin needs this, as a follow-up step after K1. In K1 the docs state that
`@TikoTest` / `@TestComponent` don't work on the KSP path yet.

- `@TikoTest`, `@TestComponent` shadowing and `replaceTransport` work for a Gradle + KSP
  test compile.
- Today the javac path tells a test compile from a main compile, and finds shadow targets, by
  reading `META-INF/tiko/container.properties` and `META-INF/tiko/components.txt` from the
  compile classpath (`TikoAnnotationProcessor.java` around lines 458 and 1352). KSP has no
  API for reading classpath resources, and the test compile runs as a separate KSP task.
- The replacement mechanism lives **once**, in `tiko-processor-core`, and is used by both
  frontends. Java's generated output must not change: either javac keeps its resource-based
  detection behind the same core interface, or the new mechanism adds no code to Java builds.
  Duplicating detection logic per frontend is the anti-goal "two diverging copies".

### Milestone K2: Coroutines

1. **A unit of work travels in the coroutine context.** A new runtime module (working name
   `tiko-coroutines`, Kotlin, depends on `kotlinx-coroutines-core`) provides a context element
   built on `ThreadContextElement`. Whenever a coroutine resumes, it installs that coroutine's
   unit on the executing thread, and on suspend it restores what was there before. Existing
   SINGLETON ← EVENT proxies (ARCH-10) keep working unchanged, because they still resolve "the
   current unit" from the thread.

2. **A `suspend` counterpart of `runInEventScope` / `supplyInEventScope`.** Code that opens a
   unit itself, such as a Ktor route, uses it.

3. **`suspend fun` event handlers.** The unit closes when the **coroutine completes**, not when
   the call returns `COROUTINE_SUSPENDED`. SEC-14 ("a unit of work always closes") has to hold
   on every completion path: normal completion, exception, and cancellation. Each suspend
   handler owns its own unit, the same model async handlers have had since #220. Generated Java
   calls suspend handlers through a small runtime helper, so K2 doesn't require generating
   Kotlin.

   **Dispatch (owner's decision): a `suspend` handler is always async, and it goes through the
   same machinery as today's async handlers.**
   - It is launched on a dispatcher backed by the container's async executor.
   - Backpressure (`queueCapacity` / `OverflowPolicy`, #109) applies at submit time.
   - Failures route as `EventHandlerError`, including the dead-letter route `ROUTE_TO_DLQ`
     (#111).
   - `@EventTrigger` chaining fires when the coroutine completes successfully.
   - `suspend` combined with an explicit `async = false` is a compile-time error.

   The publisher never blocks a thread waiting for a coroutine. That pattern defeats the point
   of coroutines. Launching a handler outside the bounded queue is also out, because it would
   silently lose the backpressure guarantee.

4. **The container owns a `CoroutineScope`.** It's backed by a `SupervisorJob`, created at
   container start and cancelled at shutdown, the same way the async executor's lifecycle
   works today.

5. **The resiliency features carry over.** `@EventHandler` timeouts (#107) and retries with
   backoff (#108) work on suspend handlers, with suspend-native waiting: cancellation-based
   timeout and non-blocking backoff.

6. **Ktor and Kafka.**
   - **Ktor:** a cookbook page plus a numbered Kotlin example that opens one unit per request.
     It is not a `tiko-ktor` module, matching how HTTP integrations are handled today (09).
   - **Kafka:** `@KafkaSource` bridge methods may be `suspend`, each record running in its own
     unit, with the existing ingest-error and SEEK / backoff semantics (#313, #478) intact.

## Non-goals

- Android and Kotlin Multiplatform: only if someone asks.
- Maven + Kotlin as a supported or tested path. We don't prevent it: any Maven plugin that can
  run a KSP `SymbolProcessorProvider` should work, and the docs say "works, untested". If users
  ask, we revisit.
- kapt, whether documented, tested or recommended.
- Generating Kotlin source (KotlinPoet). Revisit only if Java output turns out to block
  something concrete.
- A Tiko Gradle plugin. Users apply the standard `com.google.devtools.ksp` plugin.
- Kotlin-flavoured API sugar for its own sake (reified `get<T>()` and so on). It's acceptable as
  a small addition if it falls out of K2, but it isn't a goal.
- Nestable EVENT scope (#252). EVENT stays single-frame (ARCH-5), including for coroutines.

## Anti-goals (failure even if it technically works)

- A unit of work silently "escaping": its EVENT beans used by a coroutine on a thread where a
  different unit, or no unit, is installed, with no error.
- A unit that never closes after its coroutine is cancelled (SEC-14 broken).
- Two diverging copies of DI logic, one per frontend. Validation and generation exist once.
- Java users seeing any behaviour or generated-code change from K1's refactor.
- Weakening compile-time strictness for Kotlin: anything Tiko rejects in Java, it rejects in
  Kotlin.

## Constraints

- Java 21+ runtime and the existing invariants: `docs/architecture-invariants.md` and
  `docs/security-model.md`.
- **KSP2 only** (designer's call). KSP1 is the legacy line and ties each release to an exact
  Kotlin version. The supported floor is the first Kotlin 2.x / KSP2 release current when K1
  starts. The Gradle example's CI job pins that version.
- No runtime reflection; generated code stays readable.
- Annotations stay `SOURCE` retention (KSP reads source, so nothing changes there).
- The processor jars must keep working on javac without KSP on the path.
- New dependencies follow CLAUDE.md "Adding Dependencies": latest stable version, pinned in
  the BOM and root `dependencyManagement`, license check. Expected candidates:
  - the KSP API (`com.google.devtools.ksp:symbol-processing-api`, `provided`);
  - `kotlin-compile-testing` (test);
  - `kotlinx-coroutines-core` (K2 runtime);
  - the Kotlin stdlib, plus the Kotlin Maven plugin to compile `tiko-coroutines`.

## Facts about today's runtime the builder needs

- EVENT storage lives in the **generated** container: a `ThreadLocal<Map<String, Object>>`
  field emitted by `ContainerGenerator.createEventScopeField` (around line 346). The generated
  container also has a `__unitFrameOpen` `ThreadLocal` flag. The event-origin chain lives in a
  separate `ThreadLocal` (`tiko-runtime/.../EventChainContext.java:37`). Making a unit
  installable on another thread therefore needs a way to capture and install all three. It
  should be a container-internal SPI, and the context element in (K2.1) uses it.
- `runInEventScope` while a unit is open throws `IllegalStateException` (single-frame). The
  context element must not trip this when it re-installs the **same** unit on resume.

## Open design points for the builder (inside K2)

- **Structured concurrency within one unit.** Children launched inside a unit inherit its
  context. Their EVENT beans can then be touched concurrently from several threads, which
  today's EVENT beans don't expect. Decide on one, document it, and test it:
  - allow it, and document that EVENT beans must be thread-safe under coroutines;
  - restrict it;
  - give each `launch` its own unit.

  Whichever is picked must not create the anti-goal "silent escape".
- **Cancellation versus `ApplicationEndingEvent` / `@PreDestroy` ordering** at shutdown.
- **Kafka suspend bridges:** what "record done" means for offset commit when the bridge
  suspends.

## Left to the builder

- Exact names: the core packages, the context element, the suspend scope functions.
- The shape of the neutral model, as long as both frontends and both processors use only it.
- Which kotlin-compile-testing fork to use (pick an actively maintained one with KSP2 support).
- How the Gradle example build is wired into CI.

## Success

- K1: a Gradle + KSP Kotlin service boots with `Tiko.create()`. Every construct in (K1.4) is
  covered by a positive or negative test, and Java users see no change.
- K1.5: the Kotlin example's tests use `@TikoTest` with a `@TestComponent` override and pass
  on Gradle + KSP.
- K2: a Ktor route and a Kafka `suspend` bridge each run their work in their own unit across
  suspension points. The unit is closed on completion, failure and cancellation, and tests
  show it.
