# Roadmap & status

**Current status: on Maven Central, latest release 0.7.0. The nine phases of the original plan are closed.** Core DI, configuration injection (records, nested types, `Set<X>`, YAML source anchors, naturally-nested dotted-prefix YAML), lifecycle events, `@EventTrigger` chains, the in-memory event bus with handler-isolation + bounded async executor, the universal Kafka transport, and the full AI-assistant-aware tooling stack (`tiko-test` JUnit 5 extension, archetype with AGENTS / Copilot / Junie pointer files, machine-readable topology + 13-tool MCP server) are all functional end-to-end. The annotation processor generates factories, a container implementation per module, and proxies for cross-scope injection. `@PreDestroy` honours LIFO across `@Component` and `@Produces` factory beans. Each shipped capability below is covered by tests in `tiko-examples/` plus the framework's own modules, with project-wide `maven-failsafe-plugin` wiring so `*IT` integration tests actually run on `mvn verify`.

The framework is suitable for early-adopter experimentation. Runtime hardening (Phase 4) and the resiliency layer (Phase 7), which this page used to ask production users to wait for, shipped in 0.1.0 and 0.3.0 — see below.

## What ships today

- ✅ Core API design
- ✅ Module structure
- ✅ Annotation processor: `@Component`, `@Produces`, `@EventHandler` collection and validation
- ✅ Dependency graph validation, circular-dependency detection, scope rules
- ✅ Compile-time ambiguity detection for unnamed providers of the same type
- ✅ Code generation: per-component factories, `TikoContainerImpl`, cross-scope proxies, event registry
- ✅ Runtime container: constructor injection, SINGLETON/EVENT/PROTOTYPE scopes, `@PostConstruct`/`@PreDestroy` (LIFO at all scopes) plus implicit `AutoCloseable` cleanup, scope management (`runInEventScope`/`supplyInEventScope`)
- ✅ Container lookup API: `get(Class)`, `get(Class, String)` with interface dispatch, `getProvider(...)` (lazy, scope-preserving)
- ✅ `@Produces` factory methods: instance + static, named + unnamed, with dependency injection
- ✅ In-memory event bus (`LocalEventBus` in `tiko-runtime`) with `@EventHandler` subscription
- ✅ Multi-module aggregation via `AggregatingContainer` + `META-INF/tiko/` metadata
- ✅ `container.pick(Class)` fluent API for multi-axis lookup (`withName`, `resolve`, `asProvider`, `orDefault`)
- ✅ Configuration injection v1: `@Configuration` records with typed YAML binding, generated per-record binders, `${VAR}` interpolation, layered `ConfigSources`, strict-mode validation — see [#15](https://github.com/tomas-samek/tiko-di/issues/15)
- ✅ Lifecycle events (`ApplicationStartedEvent`, `EventStartedEvent`, etc.) — automatically published around `start()`/`shutdown()` and every `runIn*Scope`/`supplyIn*Scope` ([#4](https://github.com/tomas-samek/tiko-di/issues/4))
- ✅ `@EventTrigger` chains — declarative event workflows with return-as-payload, guards, spread, async, and full origin tracking via `Event<?>` ([#5](https://github.com/tomas-samek/tiko-di/issues/5))
- ✅ API/impl split example — consumer compiles against an interface-only api jar, impl loaded via runtime-scope Maven dep ([#6](https://github.com/tomas-samek/tiko-di/issues/6))
- ✅ Handler-exception isolation + `ErrorHandler` hook — `LocalEventBus.publish()` no longer kills the dispatch loop; sealed `ErrorContext` / `EventHandlerError` route handler throws to a configurable hook (default `java.util.logging` `WARNING`, no extra dependency required), override via `TikoOptions.errorHandler(...)` ([#44](https://github.com/tomas-samek/tiko-di/issues/44))
- ✅ `@EventHandler(async = true)` honoured — bounded `ThreadPoolExecutor` (default sized for typical small-to-medium services) with `TikoOptions.eventExecutor(...)` override; the static `EventChainContext.ASYNC_EXECUTOR` is retired and shared between async handlers and `@EventTrigger(async)` ([#43](https://github.com/tomas-samek/tiko-di/issues/43))
- ✅ Kafka transport (`tiko-kafka`, `tiko-kafka-processor`) — universal transport-adapter pattern via `@KafkaSource` / `@KafkaSink`, `TransportBootstrap` SPI, JSON serializer, per-record commit + seek-back, `FakeKafkaBroker` for tests. Runnable cross-JVM demo at `tiko-examples/08_kafka_order_warehouse`. See the [Kafka cookbook](./cookbooks/kafka.md).
- ✅ Maven archetype `tiko-archetype-quickstart` — scaffolds a minimal Tiko app skeleton ([#20](https://github.com/tomas-samek/tiko-di/issues/20)). The AI-assistant-aware variant is Phase 3.
- ✅ Cross-module configuration aggregation example — multiple `@Configuration` records distributed across sibling modules, aggregated by `AggregatingContainer` ([#18](https://github.com/tomas-samek/tiko-di/issues/18)).
- ✅ HTTP + Javalin integration example — `tiko-examples/09_http_javalin/`: a runnable demo showing how Tiko lives behind any HTTP server (`TikoJavalin.scoped` middleware opens a unit of work (EVENT scope) around each route, sync request→response path is independent of the event bus, three subscribers demonstrate sync/async side effects).
- ✅ Persistence cookbook + example — `docs/cookbooks/persistence.md` paired with `tiko-examples/10_persistence_jdbc/`. EVENT-scoped JDBC transactions (one per unit of work) across HTTP and batch flows; first cookbook in the cookbook track for friction points Tiko deliberately doesn't ship.
- ✅ `@Produces` and `@PostConstruct` may declare checked exceptions — the processor catches `Throwable`, publishes `ProduceFailure` / `PostConstructFailure` ErrorContext, and propagates the user's original throwable via sneaky-throw so identity and stack trace are preserved at `container.get(...)`. Persistence cookbook drops its `IllegalStateException` wraps in `JdbcConnectionProvider` / `SchemaInitializer`. (Closes #97.)
- ✅ `@Configuration` validation errors anchored to YAML — binding errors now display `config.yaml:line:column` prefixes pointing at the offending value (or the enclosing section, for missing required keys). New `io.tiko.SourceLocation` record + additive `ConfigSource.locations()` default expose locations to custom error handlers. (Closes #19.)
- ✅ `Set<X>` in `@Configuration` records — YAML lists bind to `LinkedHashSet` with insertion-order preserved and duplicates deduped (one JUL warning per duplicate at `io.tiko.config`). Composes with enums and nested records via the existing `CompositeCoercers` shapes. (Closes #63.)
- ✅ `TikoOptions.shutdownTimeout(Duration)` + `tiko.shutdownTimeout` YAML key — graceful drain window for the framework-owned event executor; default 10s, `Duration.ZERO` skips the wait. Precedence: programmatic > YAML > default. `09_http_javalin` example sources the value from `config.yaml` end-to-end: stopping the HTTP server does not interrupt in-flight async event handlers; they finish within the configured budget before `container.close()` returns. (Closes #48.)
- ✅ Internal logging via `java.lang.System.Logger` — framework, codegen, and example handlers now use the JDK-standard `System.Logger` SPI. Default routing stays JUL (zero-config "just works"); users plug in slf4j or log4j2 by adding the appropriate `LoggerFinder` bridge to their classpath. New `tiko-examples/11_custom_logger` demonstrates the slf4j recipe end-to-end. (Closes #74.)

## Phases of the original plan

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

- ✅ `tiko-test` JUnit 5 extension + module ([#122](https://github.com/tomas-samek/tiko-di/issues/122)) — `@TikoTest` boots a container per test method or per class, `ParameterResolver` injects `Container` / `EventBus` / `RecordingEventBus` / any container-managed type, `@TestComponent` registers compile-time overrides into a separate `TestTikoContainerImpl_<hash>`, `TikoOptions.override(Class, [name,] Supplier)` registers runtime overrides, `RecordingEventBus` spies on publishes with fluent assertions and `awaitAsyncDispatch(Duration)`, and `@EventScopeTest` wraps the test body in an event scope.
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

### Phase 4 — runtime hardening

✅ **Closed 2026-05-28, shipped in 0.1.0.** [Phase 4 milestone](https://github.com/tomas-samek/tiko-di/milestone/4) — 24/24 issues done.

Tiko's behaviour under production conditions: structured error types (no string-matching on `IllegalStateException` messages), checked-exception propagation that preserves the user's stack trace, framework-managed lifecycle plumbing so adopters don't reinvent JVM shutdown ordering, plus the build-quality infrastructure (coverage + static analysis). QA pass #1 added a test-coverage and error-UX cluster. The earlier AOP / metrics / GraalVM theme was dropped from the phase.

- ✅ Runtime: `@Produces` and `@PostConstruct` may declare checked exceptions ([#97](https://github.com/tomas-samek/tiko-di/issues/97)); typed `RuntimeException` subtypes for framework-originated failures ([#98](https://github.com/tomas-samek/tiko-di/issues/98)); framework-managed JVM shutdown hook ([#92](https://github.com/tomas-samek/tiko-di/issues/92)); `computeIfAbsent` for scoped getters ([#100](https://github.com/tomas-samek/tiko-di/issues/100)); one log line per failed `@PreDestroy` / `close()` ([#116](https://github.com/tomas-samek/tiko-di/issues/116)).
- ✅ Processor correctness: the missing-interface check for cross-scope proxy injection ([#164](https://github.com/tomas-samek/tiko-di/issues/164)), `@Produces` signature validation ([#165](https://github.com/tomas-samek/tiko-di/issues/165)), error messages in the "show + explain + suggest" format ([#166](https://github.com/tomas-samek/tiko-di/issues/166)).
- ✅ Test coverage from the QA pass #1 audit: `CircularDependencyDetector` ([#158](https://github.com/tomas-samek/tiko-di/issues/158)), missing-interface proxy injection ([#159](https://github.com/tomas-samek/tiko-di/issues/159)), `@Inject` sites ([#160](https://github.com/tomas-samek/tiko-di/issues/160)), the cross-scope matrix ([#161](https://github.com/tomas-samek/tiko-di/issues/161)), `@EventTrigger` on handler failure and its edge cases ([#162](https://github.com/tomas-samek/tiko-di/issues/162), [#163](https://github.com/tomas-samek/tiko-di/issues/163)), lifecycle event ordering ([#167](https://github.com/tomas-samek/tiko-di/issues/167)), `@Key` ([#168](https://github.com/tomas-samek/tiko-di/issues/168)), config error UX ([#169](https://github.com/tomas-samek/tiko-di/issues/169)).
- ✅ Build quality: JaCoCo coverage with multi-module aggregation ([#124](https://github.com/tomas-samek/tiko-di/issues/124)), SonarCloud analysis with PR decoration ([#125](https://github.com/tomas-samek/tiko-di/issues/125)).

### Phase 5 — publish to Maven Central

✅ **Closed 2026-06-05.** [Phase 5 milestone](https://github.com/tomas-samek/tiko-di/milestone/5) — 9/9 issues done. 0.1.0 went to Maven Central on 2026-06-04 as `io.github.tomas-samek:tiko-*`, and every release since is published there.

- ✅ POM metadata required by Central ([#210](https://github.com/tomas-samek/tiko-di/issues/210)), `-sources` + `-javadoc` jars ([#211](https://github.com/tomas-samek/tiko-di/issues/211)), GPG signing ([#212](https://github.com/tomas-samek/tiko-di/issues/212)), `central-publishing-maven-plugin` + GitHub Actions release deploy ([#213](https://github.com/tomas-samek/tiko-di/issues/213)).
- ✅ The Maven groupId is `io.github.tomas-samek`, the namespace verified through GitHub ([#257](https://github.com/tomas-samek/tiko-di/issues/257)); Java packages stay `io.tiko.*`.
- ✅ `@Named` stays in the public API, documented next to `@Pick` ([#170](https://github.com/tomas-samek/tiko-di/issues/170)); the scope model unified to SINGLETON / EVENT / PROTOTYPE before the API surface was published ([#226](https://github.com/tomas-samek/tiko-di/issues/226)).

### Phase 6 — MCP enrichment

✅ **Closed, shipped in 0.2.0.** [Phase 6 milestone](https://github.com/tomas-samek/tiko-di/milestone/10) — 5/5 issues done. The MCP server registers 13 tools.

- ✅ `get_generated_artifact` for generated-source pointers ([#148](https://github.com/tomas-samek/tiko-di/issues/148)).
- ✅ `list_lifecycle_hooks`, the lifecycle-hook query tool ([#147](https://github.com/tomas-samek/tiko-di/issues/147)).
- ✅ `explain_proxy`: proxy interface, proxied methods and the scope-boundary reason, also written to `topology.json` ([#146](https://github.com/tomas-samek/tiko-di/issues/146)).
- ✅ `topology_overview` orientation tool ([#280](https://github.com/tomas-samek/tiko-di/issues/280)); `list_components` / `list_events` require filters ([#278](https://github.com/tomas-samek/tiko-di/issues/278)).

### Phase 7 — resiliency layer

✅ **Closed, shipped in 0.3.0** (per-component shutdown timeouts already in 0.1.0). [Phase 7 milestone](https://github.com/tomas-samek/tiko-di/milestone/7) — 8/8 issues closed. First-party resiliency for the framework-owned async event bus and lifecycle hooks; every knob is opt-in.

- ✅ Per-component shutdown timeouts on `@PreDestroy` + `AutoCloseable.close()` ([#106](https://github.com/tomas-samek/tiko-di/issues/106)).
- ✅ Event handler execution timeouts ([#107](https://github.com/tomas-samek/tiko-di/issues/107)).
- ✅ Event handler retries with backoff ([#108](https://github.com/tomas-samek/tiko-di/issues/108)).
- ✅ Async event bus backpressure — bounded queue + `OverflowPolicy` ([#109](https://github.com/tomas-samek/tiko-di/issues/109)).
- ✅ Executor pool settings + `eventExecutorMetrics()` ([#110](https://github.com/tomas-samek/tiko-di/issues/110)).
- ✅ Dead-letter routing for failed / timed-out events ([#111](https://github.com/tomas-samek/tiko-di/issues/111)).
- ✅ Async events accepted during shutdown are reported instead of vanishing ([#346](https://github.com/tomas-samek/tiko-di/issues/346)).
- Closed without implementation: keeping a scope alive across an async continuation ([#221](https://github.com/tomas-samek/tiko-di/issues/221)). An async handler owns its unit of work instead (0.5.0).

### Phase 8 — distributed transports

✅ **Closed.** [Phase 8 milestone](https://github.com/tomas-samek/tiko-di/milestone/8) — 3/3 issues done.

- ✅ Pluggable serializer SPI, `io.tiko.EventSerializer` ([#119](https://github.com/tomas-samek/tiko-di/issues/119)) — shipped in 0.4.0.
- ✅ Kafka ingest opens an EVENT unit per consumed message ([#347](https://github.com/tomas-samek/tiko-di/issues/347)) — shipped in 0.3.0.
- ✅ `tiko-kafka` and `tiko-kafka-processor` in `tiko-bom` ([#298](https://github.com/tomas-samek/tiko-di/issues/298)) — shipped in 0.2.2.

The further transports first listed under this phase were taken out of it and are unscheduled: see [Planned](#planned).

### Phase 9 — examples & docs polish

✅ **Closed 2026-08-15.** [Phase 9 milestone](https://github.com/tomas-samek/tiko-di/milestone/9) — 5/5 issues done.

- ✅ `@Component(profiles=...)` demo, `tiko-examples/14_profiles` ([#171](https://github.com/tomas-samek/tiko-di/issues/171)).
- ✅ Strict-mode config-fail-at-boot demo ([#172](https://github.com/tomas-samek/tiko-di/issues/172)).
- ✅ Advanced-feature examples — `@EventTriggers`, `supplyInEventScope`, origin chain, `TikoOptions`, MCP walkthrough ([#173](https://github.com/tomas-samek/tiko-di/issues/173)).
- ✅ The `01_basic_di` and `03_events` examples run again ([#456](https://github.com/tomas-samek/tiko-di/issues/456)); the Kafka end-to-end test is stable with Docker present ([#372](https://github.com/tomas-samek/tiko-di/issues/372)).

## Releases after the phase plan

After 0.3.0 the work is planned per release. Each release's notes are on the [releases page](https://github.com/tomas-samek/tiko-di/releases).

| Release | Date | Headline | Milestones |
| ------- | ---- | -------- | ---------- |
| 0.4.0 | 2026-07-09 | The test-seam release: fake-broker Kafka testing (`replaceTransport`, `FakeKafkaTransport`), the serializer SPI, the measured agent-DX overhaul | [Skill & docs accuracy](https://github.com/tomas-samek/tiko-di/milestone/14) |
| 0.5.0 | 2026-08-15 | Async handlers own their unit of work; Kafka ingest error decisions | [0.5.0](https://github.com/tomas-samek/tiko-di/milestone/15) |
| 0.6.0 | 2026-10-11 | The hardening release: security hardening and the config pipeline | [0.6.0](https://github.com/tomas-samek/tiko-di/milestone/16) |
| 0.7.0 | 2026-11-16 | Kotlin on Gradle and KSP2, `tiko-test` on KSP, and the whole-repository review | [0.7.0](https://github.com/tomas-samek/tiko-di/milestone/17), [Kotlin K1](https://github.com/tomas-samek/tiko-di/milestone/18), [Kotlin K1.5](https://github.com/tomas-samek/tiko-di/milestone/19) |

## Planned

- **0.8.0** — [milestone](https://github.com/tomas-samek/tiko-di/milestone/21), due 2026-12-30. Ships together with Kotlin K2.
- **Kotlin K2 — coroutines** — [milestone](https://github.com/tomas-samek/tiko-di/milestone/20), due 2026-12-30. Units of work carried in the coroutine context, suspend event handlers, a Ktor cookbook + example, suspend Kafka bridges.

### Further transports (unscheduled)

- RabbitMQ transport adapter — `tiko-rabbitmq` + `tiko-rabbitmq-processor` ([#117](https://github.com/tomas-samek/tiko-di/issues/117)).
- JMS transport adapter — covers ActiveMQ Artemis, IBM MQ, others ([#120](https://github.com/tomas-samek/tiko-di/issues/120)).
- `TransportBootstrap` SPI audit — surface and fix Kafka-shaped assumptions exposed by a second implementor ([#118](https://github.com/tomas-samek/tiko-di/issues/118)).

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
