# Tiko — Vision & Design Notes

> Working document. Raw material for README, not README itself.

---

## What Tiko is

Tiko is a **compile-time service orchestrator for the JVM**.

> Tiko orchestrates, it doesn't bundle — direct access, compile-time safe,
> nothing wrapped.

You declare your services and their contracts. Tiko wires them, validates the
topology at build time, and stays out of your business logic.

If it builds, it runs.

The agent-facing distillation of this vision lives at
[`.ai-skills/tiko-build/SKILL.md`](../.ai-skills/tiko-build/SKILL.md);
the long-form recipe doc at
[`docs/orchestrator-model.md`](./orchestrator-model.md) is the canonical
cookbook for building on top.

---

## What Tiko is not

- Not a "better Spring". Spring is fine. Tiko is a different trade-off.
- Not a runtime DI container. There's no `ApplicationContext` to query at runtime.
- Not a microservices framework. Tiko orchestrates services **within a single
  JVM process**. For multi-process orchestration, use a service mesh.
- Not opinionated about your business logic. Tiko touches the boundary of your
  services, not their insides.

---

## Three principles

### 1. Compile-time over runtime

What can be validated at compile time, is. The DI graph, the event topology,
the service contracts, plugin descriptors — all resolved at build, not at startup.

If you publish an event nobody listens to, the build fails.
If a service requires a dependency nobody provides, the build fails.
If you rename an event class, every publisher and handler fails compilation
until you fix them.

There is no `ApplicationContext` to query, no classpath scan at startup, no
"bean not found" surprises in production.

The trade-off: no dynamic registration at runtime. If you need that, Tiko is
not the right tool. That's a feature, not an oversight.

### 2. Explicit over implicit

Services declare what they provide, what they require, what they publish, what
they subscribe to. Plugins ship a descriptor that lists the same. The wiring
is generated from declarations, not inferred from annotations scattered across
the classpath.

This makes the application's structure **readable as data**. The build
produces a topology artifact (diagram, JSON, whatever you want) that is the
real, accurate, mechanically-verified architecture of your app. It cannot
drift from reality, because it *is* reality.

### 3. Proportional over total

DI where it solves a real problem (alternative implementations, test doubles,
lifecycle, cross-cutting concerns). Plain `new`, static methods, and records
where it doesn't.

Tiko does not require you to make every utility a bean. If a function is
pure and stateless, write it as a static method. If a value object is
immutable, write it as a record. If a component has no swappable
alternatives, instantiate it directly in its parent service's constructor.

Tiko engages at service boundaries — that's where DI earns its complexity
budget. Inside a service, write whatever Java feels right. Tiko does not
reach in.

---

## Three buckets — Core, Plug in, Open

Every concern in a tiko-built service lands in one of three buckets. The
split is the operational consequence of the principles above, and the
shape the README is now organized around.

### Core (what tiko ships)

The container, the scopes (`SINGLETON` / `EVENT` / `PROTOTYPE`), the event
bus, `@EventHandler` and `@EventTrigger`, compile-time validation,
lifecycle hooks, `@Configuration` typed records. This is the surface tiko
owns end-to-end.

### Plug in (you bring the library; tiko orchestrates the seam)

HTTP servers, databases, caches, template engines, security libraries,
schedulers, retry helpers, any SDK client. Pattern: declare a factory
method annotated `@Produces`, return the library's value, consume it as a
constructor parameter. No wrapper, no adapter layer, no annotation-driven
dispatch.

The default answer to "does tiko support X?" is **Plug in via `@Produces`**.
Tiko owns the seam; it does not own the library surface. This is what the
"backbone, not a framework" line means in practice — when a Spring user
reaches for `@RestController` or `@Transactional`, the tiko-native answer
is a factory and an explicit demarcation, both visible code.

The full redirect table is in
[`.ai-skills/tiko-build/SKILL.md`](../.ai-skills/tiko-build/SKILL.md); the
worked recipes are in
[`docs/orchestrator-model.md`](./orchestrator-model.md); the reference app
that the cookbook cites is
[`tiko-examples/15_quickstart`](../tiko-examples/15_quickstart).

### Open design questions

Async generalisation, scheduling-as-tick-event, retry-as-event-loop — the
small set of unresolved questions about extending the event model itself.
An honest scope note, not a deferral promise: these are where tiko's
position isn't yet earned by observation, and where a contributor's
opinion would land in a real discussion rather than against a settled
default.

---

## The three layers

Tiko applications have three structural layers, each with a clear role:

### Service

Top-level unit of orchestration. Has a lifecycle (start/stop), an explicit
contract (events in, events out, public API), and explicit dependencies on
other services. Tiko knows everything about services: their wiring, their
event topology, their startup order.

### Module / Plugin

Compositional unit that bundles services. A plugin ships a descriptor:

```
provides_services: [OrderService, InventoryService]
requires_services: [ClockService, KafkaTransport]
publishes_events:  [OrderPlaced, OrderCancelled]
subscribes_events: [PaymentConfirmed]
```

The descriptor is generated at build time from the plugin's declarations.
When a host application links a plugin, Tiko verifies — at compile time —
that the plugin's requirements are satisfiable in the host's topology.

Plugin loading is build-time composition, not runtime classloader magic.
A plugin is a Maven/Gradle dependency. To add or remove plugins, you rebuild.

(Runtime plugin loading with separate static subgraphs is a future possibility,
not a current commitment.)

### Component

The inside of a service. Plain Java. Records, sealed interfaces, pure
functions, regular classes. Tiko injects components into services through
constructors, but components themselves are Tiko-agnostic. You can
instantiate them directly in tests with `new`. There are no annotations,
no framework dependencies, no `ApplicationContext` to bootstrap.

---

## The event pipeline

Tiko unifies local and remote events under a single static topology.

A handler doesn't care whether the event was published by a local
`bus.publish(...)` call or arrived as a Kafka message from another service.
The handler signature is the same. The registration is the same. The test
setup is the same.

What's different is what Tiko knows at compile time:

- Which events have publishers
- Which events have subscribers
- Which transports each event can arrive on
- Which handlers will run, in what order, for any given publish

If you publish an event that has no handler, the build fails.
If you subscribe to an event that nobody publishes, the build fails.
If you rename an event, the compiler walks every publisher, handler, and
transport binding for you.

The build produces a topology artifact — a diagram or structured document
showing the full event graph. This is documentation that cannot lie,
because it is generated from the same declarations that the runtime uses.

### Trade-offs and current positions

The choices below are not final, but they are explicit. README will state
each one and justify it. Most frameworks duck these questions; Tiko's
credibility comes from naming them.

- **Delivery semantics — at-least-once, handlers must be idempotent.** The
  weaker guarantee wins so handler code is portable across transports.
  Local delivery is still typically synchronous in-process, but the
  contract Tiko promises the handler is the same one Kafka offers. A
  handler that breaks under redelivery is buggy regardless of transport.

- **Ordering — per-source FIFO only, no cross-source merge.** Local events
  preserve publisher order. Kafka events preserve partition order. A handler
  subscribed to both sees them in arrival order at the handler — there is
  no synthesized global ordering. Documented explicitly so consumers don't
  assume otherwise.

- **Backpressure — publishers never block on handler work.** Handlers run
  on a bounded executor by default; `bus.publish(...)` returns once the
  event is enqueued. Synchronous in-process delivery is opt-in per handler
  (e.g. `@EventHandler(sync = true)`) and is the exception, not the rule.
  The API surface is uniform across transports because the publisher-side
  semantics are uniform.

- **Transactional semantics — event-scope buffering built in, outbox
  recommended, no implicit phase coupling between transactions and event
  delivery.** Events published inside `runInEventScope` are buffered and
  only released when the scope exits successfully; on failure they are
  dropped. The `@TransactionalEventListener` reflex is replaced by an
  explicit `eventBus.publish(...)` after the write completes — see the
  redirect in [`docs/orchestrator-model.md` §4.1](./orchestrator-model.md).
  Persistence-backed outbox (for crash safety across the JVM boundary) is
  the consumer's responsibility — Tiko does not own a database, so it does
  not own the durable record.

- **Error handling — log + isolate by default, per-handler policy
  configurable.** A throwing handler does not propagate to the publisher
  and does not break sibling handlers. Retry and dead-letter behavior are
  opt-in per handler via annotation (e.g. `onError = RETRY_THEN_DLQ`); DLQs
  target the same transport (in-memory store for local, DLQ topic for
  Kafka). Default for a stock handler is "log the exception, mark this
  delivery attempt failed, move on."

---

## On hot reload

Tiko does not support hot reload.

Tiko apps start in under a second. By the time you'd configure devtools,
you've already restarted twice.

(This is a real position, not just a joke. Quarkus sells the same pitch,
and on the current JVM it is achievable through deliberate design.)

---

## On Spring

Spring is a fine framework for many problems. Tiko isn't trying to replace it.

Spring's strength is runtime flexibility — beans created on demand, profiles
toggled at startup, dynamic registration via classpath scanning. That
flexibility is also its cost: errors surface at runtime, the actual wiring
is invisible until startup, and the framework's concepts leak into your
domain via annotations.

Tiko makes the opposite trade: everything static, everything verified, no
runtime reflection. You give up dynamic registration. In exchange, you get
build-time guarantees and a domain that doesn't depend on Tiko at all.

If you need what Spring offers, use Spring. If the trade Tiko offers sounds
appealing, try Tiko.

---

## Demo strategy

The example project is `examples/` inside the main repo (not a separate repo —
discoverability matters). It mirrors a typical interview takehome so reviewers
can mentally project Tiko's approach into their own work.

**Domain: order + warehouse split.**

- Order service publishes `OrderPlaced` locally (audit, email handlers)
  and remotely via Kafka (warehouse service in a separate module).
- Two runnable modules. Shared event definitions. Handlers in each module
  subscribe to a subset of events. Same handler shape on both sides.

**Side-by-side with Spring.**

- `examples/spring/` and `examples/tiko/`, same domain, same feature set.
- Reviewer compares the two themselves. That's the strongest possible argument.

**Three "wow" moments to engineer into the demo:**

1. **Dead publish is a compile error.** A branch where a handler is deleted;
   the build fails with a clear message pointing to the orphaned publisher.
   Screenshot in README.
2. **Generated topology diagram.** `target/event-topology.svg` shipped as a
   build artifact. Image embedded in README.
3. **The unified test.** A test that fluently mixes local and Kafka-sourced
   events for the same handler. No mocks, no embedded Kafka for the unit case,
   no `@MockBean` ceremony.

**Avoid:**

- TODO placeholders for things Tiko doesn't do (transactions, security, etc.).
  Either implement them or scope them out of the demo.
- Marketing-speak README. Concrete numbers (startup time, memory) and
  side-by-side code in the first 30 lines, or reviewers bounce.

---

## Roadmap notes (not commitments)

Today (0.1.0 on Maven Central): core DI, three-scope lifecycle,
multi-module aggregation, `@Configuration` records with typed YAML binding +
source anchors, in-memory event bus with handler isolation + bounded async
executor + graceful drain, `@EventTrigger` chains with origin tracking,
Kafka transport behind a universal `TransportBootstrap` SPI, `System.Logger`
for internal logging.

- **Shipped recently:** Phase 3 (onboarding & tooling — AI-aware
  archetype, `tiko-test` JUnit 5 module, machine-readable topology +
  thirteen-tool MCP server), Phase 4 (runtime hardening — structured
  `RuntimeException` subtypes, framework-managed JVM shutdown hook),
  Phase 5 (Maven Central — POM metadata, GPG signing, sources/javadoc
  jars, CI deploy).
- **Active work:** the "Public framing reset" milestone (orchestrator
  vocabulary lock, agent-readable skill, README rewrite around the three
  buckets, benchmark of skill effectiveness across coding agents).
- **Next:** Phase 6 (MCP enrichment — deeper introspection of the
  compile-time graph), Phase 7 (first-party resiliency layer — timeouts,
  retries, backpressure, executor pool knobs, DLQ; supersedes the prior
  plan to cover this via a cookbook), Phase 8 (RabbitMQ + JMS transports
  through the same SPI, plus a pluggable serializer SPI extracted from
  `tiko-kafka`), Phase 9 (examples & docs polish).
- **Considered, not promised:** NATS or other transports beyond
  RabbitMQ/JMS, runtime plugin loading with classloader isolation,
  observability hooks (metrics, tracing), GraalVM native-image support, AOP
  / interceptors. None of these have a concrete driver yet; they will earn a
  milestone if and when one appears.
- **Explicitly Plug in — not a tiko-shipped feature:** the HTTP layer
  (Javalin, Jetty, Spark, Vert.x via `@Produces` — see
  `tiko-examples/09_http_javalin` and `tiko-examples/15_quickstart`), the
  persistence layer (HikariCP / jOOQ / Flyway via `@Produces` and
  `@EventHandler(ApplicationStartedEvent)` — see
  `tiko-examples/10_persistence_jdbc`, `tiko-examples/15_quickstart`, and
  `docs/cookbooks/persistence.md`), security (open-design caveat in
  `docs/orchestrator-model.md` §6), schedulers, retries, caches, template
  engines.
- **Explicitly out of scope:** distributed orchestration across processes
  — use a service mesh.

---

## Tagline candidates

**Locked one-line pitch** (verbatim across README, skill,
archetype agent-config — see
[`docs/orchestrator-vocabulary.md`](./orchestrator-vocabulary.md)):

> *"Tiko orchestrates, it doesn't bundle — direct access, compile-time safe,
> nothing wrapped."*

Older candidates kept here as historical context — pick from these if a
launch post needs a different tone, but the locked pitch is what
propagates between artifacts so agents see the same words everywhere:

> *"Tiko is a compile-time service orchestrator for the JVM. Declare your
> services and their contracts; Tiko wires them, validates the topology,
> and stays out of your business logic."*

> *"Tiko: compile-time wired services for the JVM. If it builds, it runs."*

> *"A backbone, not a framework."*
