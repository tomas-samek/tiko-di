# Architecture Invariants Registry

This registry is the reference truth that `tiko-architect` checks every release delta against.
Each invariant is a load-bearing rule for tiko-di's architecture — a constraint that, if
violated, erodes the compile-time-safety pitch, breaks the public contract, or introduces a
class of runtime surprise the framework exists to prevent. The registry is independently
citable: contributors, CLAUDE.md, and agent skills reference invariants by ID (ARCH-1 …
ARCH-15). It is a living document — `tiko-architect` proposes additions in its step-5
self-audit whenever a release introduces a new rule that should be codified.

---

### ARCH-1 — tiko-api stays zero-dependency

**Statement.** Nothing in `tiko-api` may add a third-party (or cross-module) runtime
dependency.

**Rationale.** `tiko-api` is the zero-dep core every other module and consumer depends on; a
dep here propagates to everyone and breaks the cold-start / compile-time-safety pitch.

**Anchor.** CLAUDE.md "Module Dependencies (core chain)"; `tiko-api/pom.xml`.

**Violation looks like.** A `<dependency>` added to `tiko-api/pom.xml`, or an `import` of a
non-JDK / non-`io.tiko` type in `tiko-api/src/main`.

---

### ARCH-2 — annotations are SOURCE retention by default

**Statement.** Annotations the processor reads are `RetentionPolicy.SOURCE`. The only
documented RUNTIME exceptions are `@PostConstruct` and `@PreDestroy`, because the container
invokes them via generated code at runtime.

**Rationale.** Compile-time concerns must not leak into runtime bytecode; SOURCE retention is
a code-level invariant, not a version-pinning bet.

**Anchor.** CLAUDE.md "Annotation Retention".

**Violation looks like.** A new framework annotation with `@Retention(RUNTIME)` whose
bytecode is never read by the container or generated code at runtime.

---

### ARCH-3 — event dispatch is type-keyed, never name-keyed

**Statement.** Event routing is keyed by the Java payload type; `@EventTrigger.eventName` is
an optional trace label for topology views, never a routing key.

**Rationale.** Name-keyed dispatch is un-checkable at compile time — a typo or rename can
silently misroute an event. Type-keyed dispatch keeps wiring compile-time-checked, consistent
with tiko's no-runtime-surprises contract.

**Anchor.** `docs/events.md` "Trade-off positions" (the "Routing is by event type, not by name" bullet).

**Violation looks like.** A code path that dispatches or filters handlers by `eventName`
string comparison at runtime; or framework docs / examples implying `eventName` affects which
handlers run.

---

### ARCH-4 — ErrorContext is sealed; new permits are intentionally compile-loud

**Statement.** `ErrorContext` is a sealed interface; adding a top-level permit is a
compile-time-loud breaking change for users with exhaustive `switch` expressions.

**Rationale.** The sealed hierarchy is the contract: users match on it exhaustively. Adding a
permit forces them to handle the new category — which is the point. Circumventing the seal
(e.g. adding a non-sealed escape hatch at the wrong level) silently breaks exhaustive
consumers.

**Anchor.** `tiko-api/src/main/java/io/tiko/ErrorContext.java` Javadoc.

**Violation looks like.** A new top-level error category added as a `non-sealed` class outside
the `permits` list; or a non-framework type implementing `ErrorContext` directly without being
a `TransportError` subtype.

---

### ARCH-5 — the three-scope model (SINGLETON / EVENT / PROTOTYPE); EVENT is single-frame in 0.x

**Statement.** The scope model has exactly three tiers — SINGLETON (application lifetime),
EVENT (one unit of work), and PROTOTYPE (per injection). EVENT is single-frame in `0.x`:
calling `runInEventScope` while a unit is already open throws `IllegalStateException`.
Nestability is explicitly deferred.

**Rationale.** A stable, minimal scope model makes the cross-scope injection rules and proxy
generation decidable at compile time. Changing the count or semantics of scopes is a
framework-wide breaking change.

**Anchor.** CLAUDE.md "Scope Management"; memory `project_scope_model_unification`.

**Violation looks like.** A fourth scope introduced without a spec update; EVENT scope silently
re-entered (no `IllegalStateException`) before nestability is officially supported; or
`runInEventScope` blocking instead of throwing on re-entry.

---

### ARCH-6 — transports are entry points; distributed orchestration across processes is out of scope

**Statement.** Transports (Kafka, future RabbitMQ/JMS) are entry points that deliver events
into the in-process mesh. Distributed orchestration across multiple processes — sagas,
choreography engines, cross-process event chains — is explicitly out of scope; use a service
mesh.

**Rationale.** Keeping tiko single-process preserves its compile-time-safety guarantee:
cross-process coordination requires runtime contracts the framework cannot verify.

**Anchor.** `docs/VISION.md`, the bold bullet "Explicitly out of scope: distributed
orchestration across processes — use a service mesh" (in the Plug-in / out-of-scope list).

**Violation looks like.** A new module or API that routes events between two running JVM
processes through tiko itself; or a design doc treating tiko as a process-level orchestrator.

---

### ARCH-7 — compile-time safety / no runtime reflection in framework internals

**Statement.** Framework internals use typed dispatch and generated code; `Class.forName`,
`getMethod().invoke()`, and classpath scanning are forbidden in the framework's own hot paths.
All wiring is resolved at compile time via generated code.

**Rationale.** Runtime reflection contradicts tiko's compile-time-safety positioning and makes
errors undetectable until runtime. Typed dispatch is also debuggable and produces readable
generated code.

**Anchor.** CLAUDE.md "Design Philosophy"; memory `feedback_typed_dispatch`.

**Violation looks like.** A new `Class.forName(...)` or `method.invoke(...)` call in
`tiko-runtime` or `tiko-processor` for hot-path dependency resolution; or runtime classpath
scanning added to the container bootstrap.

---

### ARCH-8 — interfaces and composition over impls and inheritance

**Statement.** Framework APIs take and return interfaces; new features compose existing
primitives. Inheritance is a fallback, not a default.

**Rationale.** Interface-first design keeps the framework extensible without breaking changes
and makes components independently testable against the interface contract rather than an
implementation detail.

**Anchor.** Memory `feedback_interfaces_and_composition`.

**Violation looks like.** A new framework feature that requires consumers to extend a concrete
class; or a public API method whose return type is a concrete implementation rather than an
interface.

---

### ARCH-9 — framework output goes only through System.Logger

**Statement.** All framework logging uses `System.Logger` (JDK platform logging facade) with
the lazy-holder pattern. No logging-framework dependency, no `System.err.println`, no
`e.printStackTrace()` in framework or generated code.

**Rationale.** `System.Logger` works with zero logging-binding dependencies — users bridge to
slf4j/log4j2 via a `LoggerFinder`. A direct logging-framework dep in `tiko-runtime` or
`tiko-api` would force it on all consumers.

**Anchor.** CLAUDE.md "Logging in Framework Code".

**Violation looks like.** An `import org.slf4j.*` or `import org.apache.logging.*` in any
`tiko-api` or `tiko-runtime` source file; or a `System.err.println` / `printStackTrace` in
framework or generated code.

---

### ARCH-10 — cross-scope injection (SINGLETON ← EVENT) requires the shorter-scoped bean to implement an interface

**Statement.** When an EVENT-scoped bean is injected into a SINGLETON, the processor generates
a proxy. The proxied bean must implement an interface; direct-class proxying is not supported.

**Rationale.** Proxy generation is the mechanism that makes cross-scope injection safe without
runtime reflection. Interface-backed proxies are compile-checkable and produce readable
generated code. Without an interface the processor must reject the injection with a clear
error.

**Anchor.** CLAUDE.md "Cross-scope injection rules (3×3)".

**Violation looks like.** The processor silently allowing a SINGLETON to inject an EVENT-scoped
concrete class (no interface) without generating a proxy or emitting a compile error; or a
generated proxy that bypasses the interface contract.

---

### ARCH-11 — every generated top-level type carries @Generated via the shared helper

**Statement.** Every top-level type emitted by an annotation processor is marked with
`@javax.annotation.processing.Generated(...)` using the shared helper
`GeneratorAnnotations.generatedBy(GeneratorClass.class)`.

**Rationale.** The `@Generated` marker lets IDEs grey out generated sources and coverage tools
exclude them. Consistency via the shared helper prevents per-generator drift.

**Anchor.** CLAUDE.md "Generated Code Markings".

**Violation looks like.** A new processor-emitted top-level class that lacks the `@Generated`
annotation; or a generator that writes its own ad-hoc `@Generated` string instead of
delegating to `GeneratorAnnotations.generatedBy(...)`.

---

### ARCH-12 — restriction-style features default permissive; tightening is opt-in

**Statement.** Features that restrict or guard what components can do (e.g. `expose = {...}` on
`@Component`, profile isolation, scope-violation checks) default to permissive behaviour.
Tightening is an explicit opt-in by the user.

**Rationale.** Benevolent defaults lower the adoption barrier: a user who does not configure a
restriction feature gets no surprises. Opt-in tightening is the stated design posture for any
new "guard" feature.

**Anchor.** Memory `feedback_benevolent_defaults`.

**Violation looks like.** A new restriction feature that is on-by-default and breaks existing
tiko users who have not opted in; or a compile error that fires without any user-visible
configuration to disable it.

---

### ARCH-13 — docs describe shipped reality

**Statement.** Agent-facing and user docs — CLAUDE.md, the bundled skills, cookbooks, README,
VISION — match the actual `0.x` API: no documenting non-existent features, all code examples
must be valid, and contracts are described as they actually behave.

**Rationale.** Docs that drift from reality mislead both human contributors and AI agents
working on the codebase. The drift postmortem (#401–#406) showed that stale docs cause
systematic misreads of the architecture.

**Severity (calibration).** Cap a doc-coherence violation at **concern** (→ CONDITIONAL: ship,
file the fixes) unless the drift breaks a *published artifact* (a wrong coordinate in the
install snippet, a broken jar) — then it is a **blocker** (→ NO-GO). A code-sound release must
not be NO-GO'd by stale prose alone; the fixes are filed and shipped next. (Calibrated by the
0.3.0 retrospective dry-run, which would otherwise have NO-GO'd a released, working build.)

**Anchor.** The #401–#406 drift postmortem and the #408 archetype-doc-sync gate (issues on the
GitHub tracker). #408 covers the bundled-docs subset mechanically; ARCH-13 covers the broader
doc surface #408 does not reach automatically. Once #408 lands, its gate test under
`tiko-archetype/src/test/` is the in-repo foothold for the mechanical half.

**Violation looks like.** CLAUDE.md describing an annotation, scope, or API that was removed
or renamed without updating the doc; a cookbook showing an import path that no longer exists;
README install snippets pinning a version that is no longer the current release.

---

### ARCH-14 — runtime test seams live in the `TikoOptions` override family

**Statement.** Test affordances that need a runtime hand-off (holding a fake to assert on it)
are class-keyed, opt-in, container-owned-lifecycle builder methods on `TikoOptions` — the
`override(Class, ...)` / `replaceTransport(Class, Function)` shape. No global test flags, no
system properties, no ambient test mode.

**Rationale.** Codifies the tension resolved in #414: a transport's identity is fixed at
compile time by the generated bootstrap, but a test needs to hold the fake it asserts on. A
class-keyed builder method is the only shape that satisfies both — substitution stays explicit
and typed at the call site, the container keeps owning the lifecycle, and nothing about the
production path changes when no override is registered. Ambient switches (system properties,
static flags, an env-driven "test mode") would move the decision off the call site, leak
across tests through global state, and put a test-only branch in production wiring — the
class of runtime surprise the framework exists to prevent.

**Anchor.** `TikoOptions.Builder#replaceTransport` javadoc (`tiko-runtime`, applied by
`Tiko`); `docs/superpowers/specs/2026-07-04-issue-414-transport-substitution-design.md`.

**Violation looks like.** A new test knob added as a system property (`-Dtiko.test.*`), a
static mutable flag, or an environment variable instead of a `TikoOptions` builder method; a
production code path branching on whether tests are running.

---

### ARCH-15 — async dispatch detaches, never nests

**Statement.** An async `@EventHandler` runs in its own fresh EVENT unit, created by saving,
clearing, and restoring the publisher's frame around the dispatch — detachment, not nesting.
The public scope API (`runInEventScope` / `supplyInEventScope`) keeps throwing on re-entry, so
the single-frame rule of ARCH-5 is unaffected.

**Rationale.** Codifies the model shipped by #220. An async handler must not observe or mutate
the publisher's EVENT-scoped beans: the detached unit publishes its own lifecycle events and
runs its own teardown, which is the same contract a distributed consumer gets. This has to hold
even when the `CALLER_RUNS` overflow policy inlines the task on a borrowed publisher thread —
that case is what forces save/restore of the caller's frame rather than a plain new frame.
ARCH-5 owns the scope count and the single-frame rule; ARCH-15 owns how async dispatch obtains
its unit without violating either.

**Anchor.** `ContainerGenerator.createRunInDetachedEventScopeMethod` javadoc (`tiko-processor`);
`docs/superpowers/specs/2026-07-10-issue-220-async-event-scope-design.md`.

**Violation looks like.** Async dispatch reusing the publisher's open frame; a detached path that
fails to restore the caller's frame in a `finally`; or `runInEventScope` made re-entrant or
silently nesting.
