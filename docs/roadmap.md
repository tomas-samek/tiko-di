# Roadmap & status

**Current status: Alpha — Phase 1, Phase 2, and Phase 3 closed.** Core DI, configuration injection (records, nested types, `Set<X>`, YAML source anchors, naturally-nested dotted-prefix YAML), lifecycle events, `@EventTrigger` chains, the in-memory event bus with handler-isolation + bounded async executor, the universal Kafka transport, and the full AI-assistant-aware tooling stack (`tiko-test` JUnit 5 extension, archetype with AGENTS / Copilot / Junie pointer files, machine-readable topology + nine-tool MCP server) are all functional end-to-end. The annotation processor generates factories, a container implementation per module, and proxies for cross-scope injection. `@PreDestroy` honours LIFO across `@Component` and `@Produces` factory beans. Each shipped capability below is covered by tests in `tiko-examples/` plus the framework's own modules, with project-wide `maven-failsafe-plugin` wiring so `*IT` integration tests actually run on `mvn verify`.

The framework is suitable for early-adopter experimentation. **Production use should wait for Phase 4 (runtime hardening) and Phase 5 (resiliency)** — see below.

## What ships today

- ✅ Core API design
- ✅ Module structure
- ✅ Annotation processor: `@Component`, `@Produces`, `@EventHandler` collection and validation
- ✅ Dependency graph validation, circular-dependency detection, scope rules
- ✅ Compile-time ambiguity detection for unnamed providers of the same type
- ✅ Code generation: per-component factories, `TikoContainerImpl`, cross-scope proxies, event registry
- ✅ Runtime container: constructor injection, SINGLETON/REQUEST/EVENT/PROTOTYPE scopes, `@PostConstruct`/`@PreDestroy` (LIFO at all scopes) plus implicit `AutoCloseable` cleanup, scope management (`runInRequestScope`/`runInEventScope` + `supplyIn*`)
- ✅ Container lookup API: `get(Class)`, `get(Class, String)` with interface dispatch, `getProvider(...)` (lazy, scope-preserving)
- ✅ `@Produces` factory methods: instance + static, named + unnamed, with dependency injection
- ✅ In-memory event bus (`LocalEventBus` in `tiko-runtime`) with `@EventHandler` subscription
- ✅ Multi-module aggregation via `AggregatingContainer` + `META-INF/tiko/` metadata
- ✅ `container.pick(Class)` fluent API for multi-axis lookup (`withName`, `resolve`, `asProvider`, `orDefault`)
- ✅ Configuration injection v1: `@Configuration` records with typed YAML binding, generated per-record binders, `${VAR}` interpolation, layered `ConfigSources`, strict-mode validation — see [#15](https://github.com/tomas-samek/tiko-di/issues/15)
- ✅ Lifecycle events (`ApplicationStartedEvent`, `RequestStartedEvent`, etc.) — automatically published around `start()`/`shutdown()` and every `runIn*Scope`/`supplyIn*Scope` ([#4](https://github.com/tomas-samek/tiko-di/issues/4))
- ✅ `@EventTrigger` chains — declarative event workflows with return-as-payload, guards, spread, async, and full origin tracking via `Event<?>` ([#5](https://github.com/tomas-samek/tiko-di/issues/5))
- ✅ API/impl split example — consumer compiles against an interface-only api jar, impl loaded via runtime-scope Maven dep ([#6](https://github.com/tomas-samek/tiko-di/issues/6))
- ✅ Handler-exception isolation + `ErrorHandler` hook — `LocalEventBus.publish()` no longer kills the dispatch loop; sealed `ErrorContext` / `EventHandlerError` route handler throws to a configurable hook (default `java.util.logging` `WARNING`, no extra dependency required), override via `TikoOptions.errorHandler(...)` ([#44](https://github.com/tomas-samek/tiko-di/issues/44))
- ✅ `@EventHandler(async = true)` honoured — bounded `ThreadPoolExecutor` (default sized for typical small-to-medium services) with `TikoOptions.eventExecutor(...)` override; the static `EventChainContext.ASYNC_EXECUTOR` is retired and shared between async handlers and `@EventTrigger(async)` ([#43](https://github.com/tomas-samek/tiko-di/issues/43))
- ✅ Kafka transport (`tiko-kafka`, `tiko-kafka-processor`) — universal transport-adapter pattern via `@KafkaSource` / `@KafkaSink`, `TransportBootstrap` SPI, JSON serializer, per-record commit + seek-back, `FakeKafkaBroker` for tests. Runnable cross-JVM demo at `tiko-examples/08_kafka_order_warehouse`. See [Kafka spec](./superpowers/specs/2026-05-12-kafka-event-bus-design.md).
- ✅ Maven archetype `tiko-archetype-quickstart` — scaffolds a minimal Tiko app skeleton ([#20](https://github.com/tomas-samek/tiko-di/issues/20)). The AI-assistant-aware variant is Phase 3.
- ✅ Cross-module configuration aggregation example — multiple `@Configuration` records distributed across sibling modules, aggregated by `AggregatingContainer` ([#18](https://github.com/tomas-samek/tiko-di/issues/18)).
- ✅ HTTP + Javalin integration example — `tiko-examples/09_http_javalin/`: a runnable demo showing how Tiko lives behind any HTTP server (`TikoJavalin.scoped` middleware opens a request scope around each route, sync request→response path is independent of the event bus, three subscribers demonstrate sync/async side effects).
- ✅ Persistence cookbook + example — `docs/cookbooks/persistence.md` paired with `tiko-examples/10_persistence_jdbc/`. REQUEST-scoped JDBC transactions across HTTP and batch flows; first cookbook in the cookbook track for friction points Tiko deliberately doesn't ship.
- ✅ `@Produces` and `@PostConstruct` may declare checked exceptions — the processor catches `Throwable`, publishes `ProduceFailure` / `PostConstructFailure` ErrorContext, and propagates the user's original throwable via sneaky-throw so identity and stack trace are preserved at `container.get(...)`. Persistence cookbook drops its `IllegalStateException` wraps in `JdbcConnectionProvider` / `SchemaInitializer`. (Closes #97.)
- ✅ `@Configuration` validation errors anchored to YAML — binding errors now display `config.yaml:line:column` prefixes pointing at the offending value (or the enclosing section, for missing required keys). New `io.tiko.SourceLocation` record + additive `ConfigSource.locations()` default expose locations to custom error handlers. (Closes #19.)
- ✅ `Set<X>` in `@Configuration` records — YAML lists bind to `LinkedHashSet` with insertion-order preserved and duplicates deduped (one JUL warning per duplicate at `io.tiko.config`). Composes with enums and nested records via the existing `CompositeCoercers` shapes. (Closes #63.)
- ✅ `TikoOptions.shutdownTimeout(Duration)` + `tiko.shutdownTimeout` YAML key — graceful drain window for the framework-owned event executor; default 10s, `Duration.ZERO` skips the wait. Precedence: programmatic > YAML > default. `09_http_javalin` example sources the value from `config.yaml` end-to-end: stopping the HTTP server does not interrupt in-flight async event handlers; they finish within the configured budget before `container.close()` returns. (Closes #48.)
- ✅ Internal logging via `java.lang.System.Logger` — framework, codegen, and example handlers now use the JDK-standard `System.Logger` SPI. Default routing stays JUL (zero-config "just works"); users plug in slf4j or log4j2 by adding the appropriate `LoggerFinder` bridge to their classpath. New `tiko-examples/11_custom_logger` demonstrates the slf4j recipe end-to-end. (Closes #74.)

## Planned

### Phase 1 — Alpha completion

✅ **Closed.** [Phase 1 milestone](https://github.com/tomas-samek/tiko-di/milestone/1) — 6/6 issues done. Compile-time validation, `container.pick(...)` fluent API, multi-module module-name qualifier, lifecycle-event publishing, and `@EventTrigger` codegen verified.

### Phase 2 — configuration & distributed events

✅ **Closed.** [Phase 2 milestone](https://github.com/tomas-samek/tiko-di/milestone/2) — 14/14 items done. Headline shipments:

- `@Configuration` v1 with typed YAML binding ([#15](https://github.com/tomas-samek/tiko-di/issues/15)), nested records inside fields / lists / maps ([#17](https://github.com/tomas-samek/tiko-di/issues/17)), cross-module aggregation ([#18](https://github.com/tomas-samek/tiko-di/issues/18)), YAML `file:line:column` source anchors on validation errors ([#19](https://github.com/tomas-samek/tiko-di/issues/19)), `Set<X>` fields ([#63](https://github.com/tomas-samek/tiko-di/issues/63)).
- Kafka transport (`tiko-kafka` + `tiko-kafka-processor`) behind the universal `TransportBootstrap` SPI — `@KafkaSource` / `@KafkaSink`, JSON serializer, `FakeKafkaBroker` for tests, cross-JVM demo at `tiko-examples/08_kafka_order_warehouse`.
- Handler-exception isolation + `ErrorHandler` hook ([#44](https://github.com/tomas-samek/tiko-di/issues/44)), `ErrorContext` permits for lifecycle/config/scope errors ([#52](https://github.com/tomas-samek/tiko-di/issues/52)).
- `@EventHandler(async = true)` honoured by a bounded executor with `TikoOptions.eventExecutor(...)` override ([#43](https://github.com/tomas-samek/tiko-di/issues/43)); `TikoOptions.shutdownTimeout(Duration)` graceful drain ([#48](https://github.com/tomas-samek/tiko-di/issues/48)).
- `@PostConstruct` / `@PreDestroy` semantics on REQUEST / EVENT scope ([#57](https://github.com/tomas-samek/tiko-di/issues/57)).
- Internal logging migrated to `java.lang.System.Logger` — JUL by default, slf4j / log4j2 via standard bridges, no Tiko-side SPI ([#55](https://github.com/tomas-samek/tiko-di/issues/55) → superseded by [#74](https://github.com/tomas-samek/tiko-di/issues/74)).

Deferred designs (discussed, no tracker issue yet, not bound to a phase):

- **Conditional beans / locale-based qualifier resolution.** `Map<String, T>` injection is the intended follow-up shape.
- **Profile isolation.** Compile-time `forbidProfiles` validation + Maven source-root + jar excludes to keep test-only `@Component`s out of prod jars.
- **Multi-module eager-init opt-in** ([#46](https://github.com/tomas-samek/tiko-di/issues/46)) — kept open; orthogonal to the milestone, will land when the use case sharpens.

### Phase 3 — onboarding & tooling

✅ **Closed 2026-05-24.** [Phase 3 milestone](https://github.com/tomas-samek/tiko-di/milestone/3) — 30/30 issues done, a week ahead of the 2026-05-31 due date.

Original scope (6 headline shipments):

- ✅ `tiko-test` JUnit 5 extension + module ([#122](https://github.com/tomas-samek/tiko-di/issues/122)) — `@TikoTest` boots a container per test method or per class, `ParameterResolver` injects `Container` / `EventBus` / `RecordingEventBus` / any container-managed type, `@TestComponent` registers compile-time overrides into a separate `TestTikoContainerImpl_<hash>`, `TikoOptions.override(Class, [name,] Supplier)` registers runtime overrides, `RecordingEventBus` spies on publishes with fluent assertions and `awaitAsyncDispatch(Duration)`, and `@RequestScopeTest` / `@EventScopeTest` wrap the test body in container scopes.
- ✅ tiko-test: `@TestComponent` shadow detection — implicit superclass walk + explicit `value()` attribute; scope-mismatch is a compile error ([#127](https://github.com/tomas-samek/tiko-di/issues/127)).
- ✅ tiko-test: `TikoOptions.override(Class, Supplier)` applies at injection sites keyed by the parameter's declared type — interface mocks work naturally, no `mockito-inline` required ([#128](https://github.com/tomas-samek/tiko-di/issues/128)).
- ✅ tiko-test: production components in `src/main/java/` and test fixtures in `src/test/java/` — `AggregatingContainer` federates the test container with the existing main at runtime via `META-INF/tiko/test-shadows.properties` ([#129](https://github.com/tomas-samek/tiko-di/issues/129)).
- ✅ tiko-archetype: ships AGENTS.md + `.github/copilot-instructions.md` + `.junie/guidelines.md` pointer files alongside the existing `CLAUDE.md` / `.cursor/rules/tiko.md` / `.ai-skills/SKILL.md` — generated projects come fully AI-aware, every tool's file points at `CLAUDE.md` as the single source of truth ([#21](https://github.com/tomas-samek/tiko-di/issues/21)).
- ✅ Machine-readable topology + config schema, plus an MCP server so AI agents can introspect the wiring ([#22](https://github.com/tomas-samek/tiko-di/issues/22)).

QA pass #1 + post-batch follow-ups (24 additional issues, all closed):

- ✅ **MCP introspection layer hardening** — five new tools on top of the original four. `reload` ([#145](https://github.com/tomas-samek/tiko-di/issues/145)), `list_wiring_errors` ([#142](https://github.com/tomas-samek/tiko-di/issues/142)), `find_dependents` ([#141](https://github.com/tomas-samek/tiko-di/issues/141), with `@Produces`-factory walking in [#183](https://github.com/tomas-samek/tiko-di/issues/183)), `trace_event_flow` ([#140](https://github.com/tomas-samek/tiko-di/issues/140)), profile-aware queries + `list_profile_conflicts` ([#144](https://github.com/tomas-samek/tiko-di/issues/144)), `@Produces` surfaced in `list_components` / `explain_wiring` ([#143](https://github.com/tomas-samek/tiko-di/issues/143)). Nine MCP tools advertised in total. Internal cleanup: shared `ToolArgs` helper ([#182](https://github.com/tomas-samek/tiko-di/issues/182)), `ToolRegistration` record + registry refactor ([#184](https://github.com/tomas-samek/tiko-di/issues/184)), stale Javadoc + logging hygiene ([#186](https://github.com/tomas-samek/tiko-di/issues/186), [#187](https://github.com/tomas-samek/tiko-di/issues/187)).
- ✅ **Examples QA cleanup** — fixed broken copy-paste run commands across examples 04–07 ([#152](https://github.com/tomas-samek/tiko-di/issues/152)), rewrote stale design-phase README on example 01 ([#153](https://github.com/tomas-samek/tiko-di/issues/153)), refreshed example 13 README for the 9-tool surface ([#185](https://github.com/tomas-samek/tiko-di/issues/185)), trimmed overselling claims on examples 02 / 03 ([#155](https://github.com/tomas-samek/tiko-di/issues/155), [#154](https://github.com/tomas-samek/tiko-di/issues/154)), expanded example 02 output narration + clarified example 10's test-only nature ([#157](https://github.com/tomas-samek/tiko-di/issues/157)).
- ✅ **slf4j routing in example 11 actually engages** — `exec:exec` configured so the `System.LoggerFinder` SPI binds correctly ([#150](https://github.com/tomas-samek/tiko-di/issues/150)). `LoggerRoutingTest` pins the contract via a forked-JVM subprocess.
- ✅ **`@PreDestroy` LIFO contract honored** across `@Component` and `@Produces` factory beans — unified topo-sort over the dep graph, replacing the previous hash-bucket-iteration assumption ([#151](https://github.com/tomas-samek/tiko-di/issues/151), [#189](https://github.com/tomas-samek/tiko-di/issues/189)).
- ✅ **Integration tests no longer silently skip** — `maven-failsafe-plugin` wired project-wide in the root pom ([#193](https://github.com/tomas-samek/tiko-di/issues/193)); existing `*IT`-named tests in tiko-mcp ([#192](https://github.com/tomas-samek/tiko-di/issues/192)), tiko-examples/08 ([#149](https://github.com/tomas-samek/tiko-di/issues/149)), and tiko-examples/09 + /10 ([#156](https://github.com/tomas-samek/tiko-di/issues/156)) now actually run on `mvn verify`. The e2e Kafka IT in examples/08 surfaced two latent bugs in turn (next bullet).
- ✅ **`@Configuration(prefix = "tiko.kafka")` binds naturally-nested user YAML** — `tiko: kafka:` form now works (previously required the literal `"tiko.kafka":` flat-dotted key — a UX bug ConfigBootstrap's literal-key match had been hiding). `tiko-kafka`'s `defaults.yaml` reshaped to nested form so layered deep-merge unifies with user overrides ([#149](https://github.com/tomas-samek/tiko-di/issues/149) investigation).
- ✅ **QA discipline codified** — [qa-playbook.md](./qa-playbook.md) and [issue-fix-playbook.md](./issue-fix-playbook.md) linked from CLAUDE.md + README.md. The no-speculation-in-issue-bodies rule is baked in: bug reports state symptoms + acceptance only, no "probable cause" or "suggested fix" sections — the implementer analyses the evidence with a clean head.

### Phase 4 — runtime hardening (in progress)

[Phase 4 milestone](https://github.com/tomas-samek/tiko-di/milestone/4) — 2/19 closed, due 2026-06-14.

Tighten Tiko's behaviour under production conditions: structured error types (no more string-matching on `IllegalStateException` messages), checked-exception propagation that preserves the user's stack trace, framework-managed lifecycle plumbing so adopters don't reinvent JVM shutdown ordering, plus the build-quality infrastructure (coverage + static analysis) that supports the rest of the phase. QA pass #1 added a substantial test-coverage + error-UX cluster — Tiko's compile-time-safety pitch had several validation checks with zero regression nets, and one was hiding a real bug. The previous AOP / metrics / GraalVM theme was speculative and has been dropped — those will get their own milestones if and when they become concrete.

Shipped:

- ✅ `@Produces` and `@PostConstruct` may declare checked exceptions — propagated via sneaky-throw with stack trace preserved at `container.get(...)` ([#97](https://github.com/tomas-samek/tiko-di/issues/97)).
- ✅ `computeIfAbsent` for REQUEST / EVENT scoped getters, consistent with SINGLETON ([#100](https://github.com/tomas-samek/tiko-di/issues/100)).

Open — original runtime-hardening scope:

- Typed `RuntimeException` subtypes for framework-originated failures ([#98](https://github.com/tomas-samek/tiko-di/issues/98)).
- Framework-managed JVM shutdown hook — let users subscribe to `ApplicationEndingEvent` instead of wiring their own hook ([#92](https://github.com/tomas-samek/tiko-di/issues/92)).
- JaCoCo coverage: per-module reports + multi-module aggregation, generated sources excluded ([#124](https://github.com/tomas-samek/tiko-di/issues/124)).
- SonarCloud integration: static analysis, coverage view (consumes #124), PR decoration; quality gate = "Sonar Way" ([#125](https://github.com/tomas-samek/tiko-di/issues/125)).
- Flaky 1ms absolute-time assertion in `PostShutdownGetTest` ([#85](https://github.com/tomas-samek/tiko-di/issues/85)).

Open — processor correctness (QA pass #1 follow-ups):

- Missing-interface check for cross-scope proxy injection doesn't fire ([#164](https://github.com/tomas-samek/tiko-di/issues/164)) — real bug that QA's coverage probe surfaced.
- `@Produces` signature validation missing — bad return type cascades into a stream of generated-code javac errors ([#165](https://github.com/tomas-samek/tiko-di/issues/165)).
- Processor error messages don't follow CLAUDE.md's "show + explain + suggest" format ([#166](https://github.com/tomas-samek/tiko-di/issues/166)).

Open — test-coverage gaps (QA pass #1 audit):

- `CircularDependencyDetector` coverage (currently zero) ([#158](https://github.com/tomas-samek/tiko-di/issues/158)).
- Negative tests for missing-interface proxy injection ([#159](https://github.com/tomas-samek/tiko-di/issues/159)).
- `@Inject` site validation tests ([#160](https://github.com/tomas-samek/tiko-di/issues/160)).
- Complete cross-scope matrix coverage with focused per-cell tests ([#161](https://github.com/tomas-samek/tiko-di/issues/161)).
- `@EventTrigger` fires only on successful return — handler-throws suppression ([#162](https://github.com/tomas-samek/tiko-di/issues/162)).
- `@EventTrigger` edge cases — `spread` on empty/null/Map, guard AND order, `findInChain` not-found ([#163](https://github.com/tomas-samek/tiko-di/issues/163)).
- Lifecycle event ordering contracts (vs `@PostConstruct`, user `@EventHandler`, async drain) ([#167](https://github.com/tomas-samek/tiko-di/issues/167)).
- `@Key` annotation coverage — zero tests on a shipped, production-used annotation ([#168](https://github.com/tomas-samek/tiko-di/issues/168)).
- Config error-UX edge cases — malformed YAML, nested interpolation, invalid `@Default` ([#169](https://github.com/tomas-samek/tiko-di/issues/169)).

### Phase 5 — resiliency layer

[Phase 5 milestone](https://github.com/tomas-samek/tiko-di/milestone/7) — 0/7 closed. First-party resiliency for the framework-owned async event bus and lifecycle hooks. Supersedes the prior plan to cover resilience via a cookbook; the surface is small enough and load-bearing enough that Tiko ships it directly.

- Per-component shutdown timeouts on `@PreDestroy` + `AutoCloseable.close()` ([#106](https://github.com/tomas-samek/tiko-di/issues/106)).
- Event handler execution timeouts ([#107](https://github.com/tomas-samek/tiko-di/issues/107)).
- Event handler retries with backoff ([#108](https://github.com/tomas-samek/tiko-di/issues/108)).
- Async event bus backpressure — bounded queue + overflow policy ([#109](https://github.com/tomas-samek/tiko-di/issues/109)).
- Executor pool management knobs + observability hook ([#110](https://github.com/tomas-samek/tiko-di/issues/110)).
- Dead-letter handling for failed / timed-out events ([#111](https://github.com/tomas-samek/tiko-di/issues/111)).
- Framework double-logs `@PreDestroy` and `AutoCloseable.close()` failures ([#116](https://github.com/tomas-samek/tiko-di/issues/116) — bug, gates the timeout work).

### Phase 6 — distributed transports

[Phase 6 milestone](https://github.com/tomas-samek/tiko-di/milestone/8) — 0/4 closed. Second (and beyond) first-party transport implementations behind the `TransportBootstrap` SPI. Composes with the Phase 5 resiliency knobs.

- RabbitMQ transport adapter — `tiko-rabbitmq` + `tiko-rabbitmq-processor` ([#117](https://github.com/tomas-samek/tiko-di/issues/117)).
- JMS transport adapter — covers ActiveMQ Artemis, IBM MQ, others ([#120](https://github.com/tomas-samek/tiko-di/issues/120)).
- `TransportBootstrap` SPI audit — surface and fix Kafka-shaped assumptions exposed by a second implementor ([#118](https://github.com/tomas-samek/tiko-di/issues/118)).
- Pluggable serializer SPI extracted from `tiko-kafka` ([#119](https://github.com/tomas-samek/tiko-di/issues/119)).

### Phase 7 — publish to Maven Central

[Phase 7 milestone](https://github.com/tomas-samek/tiko-di/milestone/5). Deliberately positioned after Phase 5/6 so the `TikoOptions` surface (resiliency knobs, executor configuration) and the `TransportBootstrap` SPI can stabilise before downstream users pin a version.

- Sonatype Central Portal namespace verification for `io.tiko`.
- GPG signing, `central-publishing-maven-plugin`, POM metadata polish.
- Javadoc + sources jars, BOM publication, GitHub Actions release workflow gated on tag pushes.

### Kafka follow-ups (unscheduled)

- Avro + schema registry support (`tiko-kafka-avro`).
- Full `@EventTrigger` semantics on bridge methods (factor trigger dispatcher out of `EventRegistryGenerator`).
- Batch / at-most-once commit modes.
- Topic/queue patterns via `@KafkaSource(consumerGroup = "...")` exercised by a demo.
- Pluggable partition-key extractors.
- Per-source DLQ handling.
- Transactional / exactly-once producers.

## Known limitations

- `container.get(Class, String)` uses `isAssignableFrom` matching; `container.get(Class)` uses exact class or exact implemented-interface matching. The asymmetry is intentional for now but may be unified in a future release.
- All open issues are tracked in [GitHub Issues](https://github.com/tomas-samek/tiko-di/issues).
