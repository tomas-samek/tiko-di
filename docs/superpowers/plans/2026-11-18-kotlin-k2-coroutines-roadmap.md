# Kotlin K2 (coroutines) Roadmap

This is the order of work for milestone K2, not a task-level plan. K2 is cut into pieces. Each
piece is one pull request against `main`, and each piece gets its own task-level plan in this
directory, written right before the piece starts and approved by the owner before any code.

**Goal:** a unit of work that travels with a coroutine. `suspend` code opens a unit, `suspend`
event handlers and `suspend` `@KafkaSource` bridges run in a unit of their own, and every unit
closes exactly once on success, failure and cancellation.

**Spec:** `docs/superpowers/specs/2026-11-04-kotlin-ksp-coroutines-design.md` (section
"Milestone K2: Coroutines").

**Issues:** #677, #678, #679, #680, #681, #682, #683, #685, #686 (milestone "Kotlin K2 — coroutines", due 2026-12-30).

## How a piece runs

1. I write the piece's task-level plan (`docs/superpowers/plans/<date>-k2-<piece>.md`). It names
   the files, the signatures and the tests.
2. The owner reads it and answers the decisions listed for that piece.
3. I implement it test-first on a branch off `main`, open the PR and wait for CI and Sonar.
4. The owner merges. I rebase nothing on top of an open PR: the next piece starts from the merged
   `main`.

A piece that turns out larger than planned is split, and the rest becomes the next piece. This
file is updated in the same PR when the order or the scope changes.

## Decisions made since the spec

| Decision | Source |
|---|---|
| Each `launch` / `async` child runs in its own unit. `withContext` keeps the unit. The origin chain carries over, EVENT instances do not. | owner, 2026-11-12 (#683) |
| A narrow injectable interface opens units; `Container` stays non-injectable. The `suspend` variants land on that interface. | owner, 2026-11-12 (#685) |
| The context element is a `CopyableThreadContextElement`, not a plain `ThreadContextElement`: `copyForChild()` runs for `launch` / `async` and not for `withContext`, which is what tells the two apart. | spike, 2026-11-17 (comment on #677) |
| The element opens its own unit on the coroutine's first resume and closes it from `Job.invokeOnCompletion`. An entry function that opens a unit and hands it to the element does not work, because `withContext(element)` installs a copy. Entry and child are one mechanism. | spike, 2026-11-17 |
| An entry called while a unit is installed arrives in `mergeForChild`. That is where it throws, so ARCH-5 (one unit, no nesting) holds for coroutines too. | spike, 2026-11-17 |
| Generated code stays Java. A Java lambda typed `Function1<Continuation<? super Unit>, Object>` passes as a `suspend` lambda to a Kotlin helper. | spike, 2026-11-17 |
| With an executor-backed dispatcher every resume is an executor task. The overflow policy applies to the first submit only; a resume is never rejected. | spike, 2026-11-17 (comment on #679) |

## Pieces

Order is top to bottom. "Needs" names the pieces that must be merged first.

| # | Piece | Issues | Needs | Kotlin code |
|---|---|---|---|---|
| 1 | Injectable unit-of-work interface | closes #685 | none | no |
| 2 | A unit that can be carried between threads (container SPI) | part of #677 | none | no |
| 3 | `tiko-coroutines`: the context element and the `suspend` unit function | part of #677 | 1, 2 | yes |
| 4 | Child coroutines: origin chain, lifecycle events, documentation | closes #683 | 3 | yes |
| 5 | Ktor example and cookbook on the `suspend` unit function | closes #682 | 4 | example only |
| 6 | Container-owned coroutine scope and the shutdown order | part of #678 | 3 | yes |
| 7 | `suspend` `@EventHandler` | closes #677, #678 | 6 | yes |
| 8 | Backpressure and error routing for `suspend` handlers | closes #679 | 7 | yes |
| 9 | Timeouts and retries on `suspend` handlers | closes #680 | 8 | yes |
| 10 | `suspend` `@KafkaSource` bridges | closes #681 | 7 | yes |
| 11 | HTTP cookbook with a container-owned server | closes #686 | 1 | no |

Piece 11 depends only on piece 1 and is Java and documentation. It can run whenever another
piece waits for a decision.

### Status

| # | State | Where |
|---|---|---|
| 1 | merged 2026-11-18 | PR #835; plan `2026-11-18-k2-piece-1-unit-of-work-interface.md` |
| 2 | merged 2026-11-18 | PR #836; plan `2026-11-18-k2-piece-2-carried-unit.md` |
| 3 | merged 2026-11-18 | PR #838; plan `2026-11-18-k2-piece-3-tiko-coroutines.md` |
| 4 + 5 | in review, one PR | plan `2026-11-18-k2-piece-4-5-child-coroutines-ktor.md`; #683's acceptance names the Ktor cookbook of #682, so the two close together |

Decided for piece 3 (owner, 2026-11-18): D2 `tiko-coroutines`, D3 accepted with the kotlinx
version left out of the BOM, D4 `inEventScope`.

Found in piece 3, and what later pieces must keep in mind:

- **A unit can be on two threads during a hand-over.** kotlinx installs a context element on
  the thread a coroutine is resumed on before the thread it left has taken the element off
  (8,048 of 159,769 installs in a measurement on kotlinx-coroutines 1.11.0). The single-thread
  guard of piece 2 rejected that, so it is gone: `CarriedUnit` keeps the set of threads that
  have it, and a close left to them is run by the last one to take the unit off.
- **A child's unit closes as part of the child's completion.** Whoever joined the child can
  resume a moment before the teardown has run. `inEventScope` itself returns only after its unit
  is closed.
- **An element handed to a coroutine is copied** (`withContext(element)`, `launch(element)`), and
  the copy is made when the coroutine is created, also for a `LAZY` one that never starts. A
  copy must not open anything.

What later pieces build on: `UnitOfWork.inEventScope` (`tiko-coroutines`),
`UnitCarrier.openCarriedUnit(origin)` and `hasUnitOpen()`, `CarriedUnit.install()` /
`restore(displaced)` / `close()`, `EventChainContext.carriedUnitOpener(carrier)`. A child
coroutine's unit already descends from the origin its parent's unit has: the element passes its
opener on to its copies.

### Piece 1: injectable unit-of-work interface (#685)

- **Delivers:** a `tiko-api` interface with `runInEventScope` and `supplyInEventScope`.
  `Container` extends it. A `@Component` constructor and a `@Produces` method can take it, on
  javac and on KSP, the way they take `EventBus` today (`DependencyModel.isEventBus()` and its
  four call sites are the pattern). In a multi-module application the injected instance opens
  the application's unit, not one module's.
- **Touches:** `tiko-api`, `tiko-processor` (`DependencyModel`, `ComponentFactoryGenerator`,
  `ContainerGenerator`, `DependencyGraphValidator`, `NullabilityValidator`), `tiko-runtime`
  (`AggregatingContainer`, `Tiko`), `tiko-test`, `docs/di-and-scopes.md`, the API signature sheet
  and its archetype copy.
- **Done when:** the acceptance list of #685 is covered by tests, including the failed
  `@PostConstruct` server start that makes `Tiko.create(...)` throw and shuts down what was
  started.
- **Owner decides:** the interface name (D1).

### Piece 2: a unit that can be carried between threads (part of #677)

- **Delivers:** a container-internal way to open a unit without running a task in it, install
  it on a thread, put back what the thread had, and close it. Today a unit lives in three
  thread-locals (`eventScoped` and `__unitFrameOpen` in the generated container, the origin chain
  in `EventChainContext`) and exists only for the duration of one `Runnable`. The unit covers
  every module of an application, as `DetachedUnitFrame` does since #811, and the aggregate
  publishes the one lifecycle pair. `runInDetachedFrame` is rebuilt on the same four steps, so
  there is one implementation.
- **Touches:** `tiko-runtime` (`DetachedUnitFrame`, `AggregatingContainer`, `TikoOptions`,
  `EventChainContext`, one new class that represents the carried unit), `tiko-processor`
  (`ContainerGenerator`, golden files), `tiko-test` containers.
- **Done when:** a Java test opens a unit on thread A, installs it on thread B, sees the same
  EVENT instances on both, closes it once with the `@PreDestroy` order unchanged, and sees one
  lifecycle pair; the same in a two-module application. Installing a unit on a thread that has
  another unit open puts that one back afterwards. Existing behaviour and generated code for
  applications that never carry a unit change only by the added methods.
- **Security:** SEC-14 review (a unit always closes).

### Piece 3: `tiko-coroutines`, the context element and the `suspend` unit function (part of #677)

- **Delivers:** the new module (Kotlin, depends on `kotlinx-coroutines-core`), the context
  element in the shape the spike proved, and the `suspend` counterpart of `supplyInEventScope` /
  `runInEventScope` as an extension on the interface from piece 1. `launch` / `async` children
  already get a unit of their own here, because the element cannot be shipped without an answer
  for `copyForChild()`. An entry called inside a unit throws.
- **Build work in this piece:** `kotlin-maven-plugin` in the reactor, the module in the BOM and
  in `tiko-coverage`, Kotlin sources visible to Sonar, a javadoc jar Maven Central accepts for a
  Kotlin module, and `mvn -Prelease -Dgpg.skip=true -DskipTests verify` green (the 0.7.0 release
  failed its first attempt on a packaging step no PR build had run).
- **Touches:** new `tiko-coroutines`, root `pom.xml`, `tiko-bom`, `tiko-coverage`, CI workflow if
  the Kotlin compile needs it, `docs/di-and-scopes.md`, `.ai-skills/tiko-build/reference/kotlin.md`
  and its archetype copy.
- **Done when:** tests on the real container show the same EVENT instances before and after a
  suspension and after a dispatcher change; one close on success, exception, cancellation and
  cancellation with a suspending `NonCancellable` cleanup; a `launch` child with different EVENT
  instances; `withContext` with the same ones; an entry inside a unit failing with the ARCH-5
  message. A second test class pins the kotlinx behaviours the design rests on (when
  `copyForChild` and `mergeForChild` run), so a kotlinx upgrade that changes them fails the build.
- **Owner decides:** the artifact name (D2), building on an experimental kotlinx API (D3), the
  name of the `suspend` function (D4).
- **Security:** SEC-14 review.

### Piece 4: child coroutines (#683)

- **Delivers:** what #683 asks beyond the mechanism from piece 3: the parent's origin chain is
  readable in the child through `Event<?>`, a child's unit publishes the same lifecycle pair as
  an async handler's unit, a child that outlives its parent keeps and closes its own unit, a
  `LAZY` child that never runs opens none.
- **Documentation:** `docs/di-and-scopes.md` and `docs/events.md` say that parallel work inside
  a unit does not share its EVENT instances, that `map { async { } }` opens one unit per element,
  and that the upstream of `flowOn` runs in another unit than the collector. The architecture
  invariants get the coroutine wording (ARCH-5, ARCH-15).
- **Done when:** the acceptance list of #683 is covered by tests.
- **Owner decides:** how a coroutine of another scope is described (D5).

### Piece 5: Ktor example and cookbook (#682)

- **Delivers:** `tiko-examples-gradle/kotlin-ktor-exposed` without the blocking `unitOfWork`
  helper: a route's work suspends inside one unit. A cookbook page under `docs/cookbooks/`.
- **Why here:** it is the first real user of the API from pieces 1, 3 and 4, before handlers and
  Kafka build on it. A name or a shape that reads badly in a route is cheap to change at this
  point.
- **Done when:** the acceptance list of #682 is covered, and the CI Gradle job builds and
  smoke-runs the example.

### Piece 6: container-owned coroutine scope and the shutdown order (part of #678)

- **Delivers:** a `CoroutineScope` with a `SupervisorJob`, created with the container and
  cancelled by `close()`, plus the internal launch helper that pieces 7 and 10 call. A coroutine
  it launched counts as a pending delivery until its `Job` completes, so `close()` waits for a
  suspended one and `pendingAsyncDeliveries()` reports it. Coroutine threads still running after
  close are reported by `@TikoTest` like other leaked threads.
- **Touches:** `tiko-coroutines`, `tiko-runtime` (shutdown sequence, `PendingDeliveries`,
  `OwnedThreads`), `ContainerGenerator` (shutdown method), `tiko-test`, `docs/events.md`.
- **Done when:** the acceptance list of #678 is covered except the item that needs a `suspend`
  handler, which piece 7 adds.
- **Owner decides:** the shutdown order (D7).
- **Security:** SEC-14 review (a unit of a cancelled coroutine closes).

### Piece 7: `suspend` `@EventHandler` (#677, #678)

- **Delivers:** the processor accepts a `suspend` handler on KSP (today `KotlinConstructRules.
  suspendUntilK2` rejects it), `async = false` on one is a located compile error, generated Java
  calls the launch helper from piece 6, each invocation runs in its own unit, a failure reaches
  the `ErrorHandler` as `EventHandlerError`, and `@EventTrigger` fires after the coroutine
  completes successfully.
- **Touches:** `tiko-processor-core` (`KotlinConstructRules`), `tiko-processor`
  (`KotlinConstructValidator`, handler model, `EventRegistryGenerator`), `tiko-coroutines`,
  `tiko-runtime`, `docs/events.md`, the Kotlin reference and its archetype copy.
- **Done when:** the remaining acceptance items of #677 and #678 are covered, including
  cancellation during container shutdown.
- **Owner decides:** how a resume reaches the executor and whether suspended handlers are capped
  (D6). This is settled before the piece's plan is written, with a short experiment against the
  real executor (`DefaultEventExecutorFactory`, `OverflowRejectionHandler`).

### Piece 8: backpressure and error routing (#679)

- **Delivers:** a test per `OverflowPolicy` showing a `suspend` handler behaves like an
  `async = true` handler when the queue is full at submit, `ROUTE_TO_DLQ` included; a handler
  that already started is never dropped, cancelled or moved to a thread the container does not
  own when the queue is full at a resume; `EventBus.publish` returns without waiting.
- **Done when:** the acceptance list of #679 is covered.

### Piece 9: timeouts and retries (#680)

- **Delivers:** `timeout`, `retries` and `backoff` on `suspend` handlers with cancellation-based
  timeout and a backoff that suspends. Each attempt has its own unit.
- **Done when:** the acceptance list of #680 is covered, including the single-thread-executor
  test showing a waiting backoff occupies no thread.

### Piece 10: `suspend` `@KafkaSource` bridges (#681)

- **Delivers:** a `suspend` bridge runs each record in its own unit across suspension; the
  offset is committed after the bridge completes; a failure takes the existing ingest-error
  path. `kotlin-kafka-consumer` uses one.
- **Touches:** `tiko-kafka-processor` (`KotlinBridgeValidator`, generation), `tiko-kafka`,
  `tiko-coroutines`, `docs/cookbooks/kafka.md`, the Kotlin reference and its archetype copy.
- **Done when:** the acceptance list of #681 is covered on `FakeKafkaBroker`.
- **Owner decides:** when a record counts as done and how the consumer thread waits for it (D8).

### Piece 11: HTTP cookbook with a container-owned server (#686)

- **Delivers:** `docs/cookbooks/http.md`, and the skill, `15_quickstart`, `09_http_javalin` and
  both outbox examples following one shape: a unit per request through the interface from
  piece 1, the server started and stopped by the container.
- **Done when:** the acceptance list of #686 is covered.

## Decisions for the owner

Each is asked when its piece's plan is written, not earlier. My recommendation is given so the
owner can answer with a word.

| # | Piece | Decision | Recommendation |
|---|---|---|---|
| D1 | 1 | Name of the injectable interface. It is public API in `tiko-api`. | `UnitOfWork` |
| D2 | 3 | Artifact name. It is permanent once on Maven Central. | `tiko-coroutines` |
| D3 | 3 | The design rests on `CopyableThreadContextElement`, which kotlinx marks `@ExperimentalCoroutinesApi` and `@DelicateCoroutinesApi`. No stable API tells `launch` from `withContext`. | Accept it. Pin the kotlinx version in the BOM, name the tested version in the docs, and keep the test class that pins the behaviour. |
| D4 | 3 | Name of the `suspend` unit function. It cannot be an overload of `runInEventScope`: Kotlin resolves the member taking `Runnable` first. | One generic function, `inEventScope { }`, for both the returning and the non-returning case. |
| D5 | 4 | A coroutine of another scope that starts inline on the caller's thread (`Dispatchers.Unconfined`, `CoroutineStart.UNDISPATCHED`, nested `runBlocking`) sees the caller's unit until its first suspension. The element has no hook for a coroutine that does not carry it. A Java callback run inline on a thread with an open unit behaves the same today. | Document it next to the child-coroutine rule; no code. |
| D6 | 7 | How a resume reaches the executor without being rejected, and whether the number of suspended handlers is capped. A suspended handler holds no queue slot, so the queue bound alone does not limit how many are in flight. | Options and measurements come with the experiment before piece 7. |
| D7 | 6 | Where cancelling the scope sits in `close()`. | Transports stop taking work in, `ApplicationEndingEvent`, wait for pending deliveries (suspended handlers included) up to `tiko.shutdownTimeout`, cancel the scope and wait for the cancelled coroutines to close their units, stop delivering, close transports, `@PreDestroy`. |
| D8 | 10 | When a Kafka record counts as done. | When the bridge's coroutine completes; the consumer thread, which the runner owns, waits for it. |

## What stays out of K2

- Nestable EVENT scope (#252).
- Sharing a unit between concurrent coroutines (rejected by the owner, #683).
- `suspend` `@KafkaSink`, `suspend` `@Produces` and `suspend` members of proxied interfaces.
- A Ktor module or plugin, and generating Kotlin.
- The coroutine scope as public API for application code (#678).
