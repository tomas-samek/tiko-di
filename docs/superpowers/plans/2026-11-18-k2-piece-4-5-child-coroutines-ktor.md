# K2 Pieces 4 and 5: Child Coroutines (#683) and the Ktor Example (#682) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** the child-coroutine rule of #683 is tested and documented end to end, and the Ktor
example runs each request's work in one unit of work across suspension, with a cookbook page
that shows the pattern.

**Architecture:** piece 3 already delivered the mechanism: a `launch` / `async` child gets a
copy of the unit-of-work element, opens a unit of its own, and that unit descends from the
origin its parent's unit has. Nothing in the runtime or in `tiko-coroutines`' main code is
expected to change here. What remains of #683 is evidence and prose: tests for the origin chain,
the lifecycle pair, a failing and a cancelled child, and the two constructs users will ask
about (`flowOn`, a coroutine of another scope); then the documentation. #682 is the first real
user of `inEventScope`: the Ktor example drops its blocking `unitOfWork` helper.

**Why one pull request:** #683's acceptance names the Ktor cookbook page of #682 as the place
that explains parallel work in a request, so #683 cannot close before that page exists. The
roadmap had two pieces; they are merged here and the PR closes both issues. No main code of a
published module changes, so this is tests, an example and documentation.

**Tech Stack:** Kotlin 2.4.21, kotlinx-coroutines 1.11.0, Ktor 3.6.1 (the version the example
already uses), Gradle 9.8.2 + KSP for the example, JUnit 5 + AssertJ.

**Spec:** issues #683 and #682. Roadmap:
`docs/superpowers/plans/2026-11-18-kotlin-k2-coroutines-roadmap.md` (pieces 4 and 5).

## Decisions this plan assumes

| # | Decision | Assumed |
|---|---|---|
| D5 | A coroutine of another scope that starts inline on the caller's thread (`Dispatchers.Unconfined`, `CoroutineStart.UNDISPATCHED`, a nested `runBlocking`) sees the caller's unit until its first suspension. The element has no hook for a coroutine that does not carry it. | Documented next to the child rule and pinned by a test; no code. |
| | Pieces 4 and 5 in one PR | Yes (see above). |
| | Cookbook page | `docs/cookbooks/ktor.md`, paired with `tiko-examples-gradle/kotlin-ktor-exposed`; Ktor stays at 3.6.1. It is a Plug-in recipe (HTTP), not a Tiko module. |

## What changed while implementing

- **All of Task 1's tests passed against piece 3's code**; no main code changed. Two tests were
  written differently than planned. The child's lifecycle pair is not ordered against the
  parent's `ending`: a child's unit closes as part of the child's completion, which is
  independent of the parent's. The other-scope coroutine is resumed after the unit is over, not
  after a `delay`: started with `Dispatchers.Unconfined` on a `runBlocking` thread, it ran on
  through the `delay` before the caller continued, so it never left the thread the unit was on.
- **Client disconnect in Ktor.** Cancelling the client call in `testApplication` does not
  cancel the server call, and on a real engine Ktor 3.6.1 lets the route run on unless
  `HttpRequestLifecycle` is installed with `cancelCallOnClose = true` (measured on CIO: no
  `EventEndingEvent` 4 s after the hang-up without it, at once with it). The example installs
  the plugin, and the test hangs up a real socket.
- **The Kotlin reference** no longer says `suspend` handlers are rejected "until coroutine
  support": coroutine support exists, and they are rejected because the container calls a
  handler outside a coroutine.

## Global Constraints

- No Tiko Ktor plugin or module (#682, out of scope). The example owns its few lines of glue.
- A unit of work is single-threaded; a child never shares its parent's unit (#683). The docs say
  what a child does not see and how to hand it values, without presenting it as a restriction
  to work around.
- Public prose follows the framing rule (three buckets; no "gap", "missing", "not yet supported",
  "limitation"). The cookbook page follows `.ai-skills/tiko-cookbook-extension/SKILL.md`.
- The example's blocking JDBC work stays off the request thread: it runs on `Dispatchers.IO`
  inside the unit.
- The CI Gradle job still builds and smoke-runs the example (`./gradlew build run --args=--smoke`).
- Coroutine tests carry a time limit and never assert on an install / take-off order across
  threads (piece 3).
- Commits: one conventional summary line, no body. Sonar pre-sweep before the first push.

## Review Focus

1. **A child that fails or is cancelled.** Its unit closes once; the parent's unit is not closed
   by it and closes on its own when the parent ends. Covered by Task 1.
2. **Events published from a child.** A handler sees the origin the parent's work descends
   from. Covered by Task 1.
3. **The upstream of `flowOn`.** It runs in a unit of its own, not the collector's. Covered by
   Task 1 and documented in Task 3.
4. **A request whose client goes away.** The route's coroutine is cancelled and its unit closes.
   Covered by Task 2.
5. **Two requests at once.** Each has its own `RequestId`, also when both suspend. Covered by
   Task 2.

---

### Task 1: the rest of #683's evidence in `tiko-coroutines`

**Files:**
- Create: `tiko-coroutines/src/test/java/io/tiko/coroutines/fixtures/Note.java` (event record),
  `NoteOrigins.java` (SINGLETON with a synchronous `@EventHandler` that takes `Note` and its
  `Event<?>` wrapper and records the root payload of the origin chain)
- Modify: `tiko-coroutines/src/test/kotlin/io/tiko/coroutines/ChildCoroutineUnitTest.kt`
- Modify: `tiko-coroutines/src/test/kotlin/io/tiko/coroutines/BootedContainerTest.kt` (a helper
  that counts lifecycle pairs)

**Interfaces:**
- Consumes: `UnitOfWork.inEventScope`, `EventChainContext.enter` / `exit` / `wrap` (to give the
  test's thread an origin, as generated handler code does).

- [ ] **Step 1: Write the tests**

  - `eventsPublishedInAUnitAndInItsChildDescendFromTheCallersOrigin`: the test thread enters an
    origin, runs `inEventScope`, publishes a `Note` in the block and another in an `async`
    child; `NoteOrigins` recorded the caller's origin for both.
  - `aChildsUnitPublishesItsOwnLifecyclePair`: parent and one child give two `started` / `ending`
    pairs with two ids, the child's pair complete before the parent's `ending`.
  - `aChildThatEndsClosesItsOwnUnitOnly` (parameterized: the child throws inside
    `supervisorScope`, the child is cancelled): the child's id is in `CloseLog`, the parent's is
    not until the block ends, and the parent's instance is unchanged afterwards.
  - `theUpstreamOfFlowOnRunsInAUnitOfItsOwn`: `flow { emit(requests.currentId()) }
    .flowOn(Dispatchers.IO).first()` inside the block differs from the collector's id.
  - `aCoroutineOfAnotherScopeIsNotPartOfTheUnit` (D5): `otherScope.launch(Dispatchers.Unconfined)`
    started inside the block sees the caller's id until its first suspension; after it, resolving
    the EVENT component throws `NoActiveEventScopeException`.

- [ ] **Step 2: Run them.** They are expected to pass against piece 3's code: this task adds
  evidence, not behaviour. A test that fails names something piece 3 did not deliver; then the
  fix comes first in this task, test-first, and the PR says so.
- [ ] **Step 3: Run the module three times** (`mvn -o -pl tiko-coroutines clean install`), rc=0.
- [ ] **Step 4: Commit** `test(coroutines): pin the origin chain, lifecycle pair and end of a child coroutine's unit (#683)`

---

### Task 2: the Ktor example on `inEventScope`

**Files:**
- Modify: `tiko-examples-gradle/kotlin-ktor-exposed/build.gradle.kts` (add
  `io.github.tomas-samek:tiko-coroutines`)
- Modify: `.../src/main/kotlin/io/tiko/examples/ktor/Routes.kt` (routes use `inEventScope`; the
  blocking `Container.unitOfWork` helper is removed), `RequestId.kt` (KDoc)
- Modify: `.../src/test/kotlin/io/tiko/examples/ktor/TicketApiTest.kt`
- Modify: `tiko-examples-gradle/README.md` (the example's section)

**Interfaces:**
- `fun Application.ticketModule(unitOfWork: UnitOfWork, tickets: TicketService)`: the module
  takes what it uses. `main` and the tests pass the container as the `UnitOfWork` (a `Container`
  is one) and the service from it.
- A route reads the request, runs the work in one unit and responds:
  ```kotlin
  post("/tickets") {
      val request = call.receive<OpenTicket>()
      val (ticket, requestId) = unitOfWork.inEventScope {
          val opened = withContext(Dispatchers.IO) { tickets.open(request) }   // blocking JDBC
          opened to tickets.requestId()                                        // same unit after the hop
      }
      call.response.header("X-Request-Id", requestId)
      call.respond(HttpStatusCode.Created, ticket)
  }
  ```
- A new route, `GET /tickets/summary`, counts open tickets per reporter with
  `map { async { ... } }`, as the example of parallel work: each `async` is a unit of its own
  and is handed the reporter it works on.

- [ ] **Step 1: Failing tests** in `TicketApiTest`:
  - `theRequestIdIsTheSameBeforeAndAfterASuspension`: the `X-Request-Id` header (read after the
    hop to `Dispatchers.IO`) equals the id in the published `TicketOpened`.
  - `twoConcurrentRequestsGetDifferentUnits`: two `POST`s in parallel give two different
    `X-Request-Id`s, each matching its own `TicketOpened`.
  - `aRequestWhoseClientGoesAwayClosesItsUnit`: a route added in the test suspends inside
    `inEventScope`; the client call is cancelled; the unit's `EventEndingEvent` arrives.
    Step 1a is a short experiment: whether cancelling the client call in Ktor's
    `testApplication` cancels the server call. If it does not, the test starts the CIO engine on
    an ephemeral port and closes a raw socket mid-request.
  - `parallelWorkInARequestRunsInUnitsOfItsOwn`: `GET /tickets/summary` answers the counts, and
    the recording bus saw one unit lifecycle pair per reporter plus the request's.
- [ ] **Step 2: Run** `./gradlew build` after `mvn -o install -DskipTests` of the reactor;
  see the new tests fail. **Step 3: implement.**
- [ ] **Step 4: Run** `./gradlew clean build run --args=--smoke`: `BUILD SUCCESSFUL`, the smoke
  output unchanged.
- [ ] **Step 5: Commit** `feat(examples): Ktor routes run one unit of work across suspension (#682)`

---

### Task 3: documentation

**Files:**
- Create: `docs/cookbooks/ktor.md`; modify `docs/cookbooks/README.md` (entry under "Available")
- Modify: `docs/di-and-scopes.md` ("A unit of work in Kotlin coroutines": what a child does not
  see and how to hand it values, `flowOn`, a coroutine of another scope, the origin chain)
- Modify: `docs/events.md` ("Async handlers own their unit of work": a concurrent child
  coroutine is the same kind of hop; its unit publishes its own lifecycle pair; the origin chain
  carries over)
- Modify: `.ai-skills/tiko-build/reference/kotlin.md` and its archetype copy (pointer to the
  cookbook page; the `flowOn` sentence)
- Modify: `.ai-skills/tiko-build/SKILL.md` cookbook table row and its archetype copy, if the
  table lists cookbooks by page
- Modify: `tiko-examples-gradle/README.md` (the paragraph that describes the blocking helper is
  replaced by what the example does now)
- Modify: `docs/architecture-invariants.md` only if a sentence still needs the child rule
  (piece 3 wrote most of it); the roadmap's status table

**The cookbook page** (`docs/cookbooks/ktor.md`), following the cookbook skill's template:
one unit per request with `inEventScope`; where blocking JDBC goes (`withContext(Dispatchers.IO)`
inside the unit); what cancellation does (client gone, the unit closes); parallel work in a
request (`async` children are units of their own: a child gets its own `RequestId` and its own
transaction, so pass it what it needs; `map { async { } }` is one unit per element); testing with
`testApplication` and `@TikoTest`; and that the module function is the example's own glue, since
Tiko ships no Ktor plugin.

- [ ] **Step 1: Load the cookbook skill and write the page and the other changes.**
- [ ] **Step 2: Full build** `mvn -o clean install` (rc=0; the archetype's copy checks pass) and
  the Gradle example once more.
- [ ] **Step 3: Commit** `docs: Ktor cookbook and the child-coroutine rule in the scopes and events docs (#682, #683)`

---

## After the tasks

- Push, PR (`Closes #682`, `Closes #683`), CI incl. the Gradle job, Sonar open issues checked.
- Next on the roadmap: piece 6 (the container-owned coroutine scope and the shutdown order,
  needs D7).
