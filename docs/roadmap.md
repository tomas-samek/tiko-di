# Roadmap & status

**Current status: Alpha.** Core DI, configuration injection, lifecycle events, and `@EventTrigger` chains are functional end-to-end. The annotation processor generates factories, a container implementation per module, and proxies for cross-scope injection. Each shipped capability below is covered by integration tests in `tiko-examples/`.

The framework is suitable for early-adopter experimentation. **Production use should wait for Phase 2** — see below.

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

## Planned

### Phase 2 (current) — configuration & distributed events

Open work, tracked by the [Phase 2 milestone](https://github.com/tomas-samek/tiko-di/milestone/2):

- **Event system:** `ErrorContext` permits for lifecycle/config/scope errors ([#52](https://github.com/tomas-samek/tiko-di/issues/52)).
- **Multi-module:** eager-init opt-in ([#46](https://github.com/tomas-samek/tiko-di/issues/46)).
- **Framework internals:** switch logging to `java.lang.System.Logger` ([#74](https://github.com/tomas-samek/tiko-di/issues/74)).

Deferred designs (discussed, no tracker issue yet):

- **Conditional beans / locale-based qualifier resolution.** `Map<String, T>` injection is the intended follow-up shape.
- **Profile isolation.** Compile-time `forbidProfiles` validation + Maven source-root + jar excludes to keep test-only `@Component`s out of prod jars.

### Phase 3 (next) — onboarding & tooling

Open work, tracked by the [Phase 3 milestone](https://github.com/tomas-samek/tiko-di/milestone/3):

- AI-assistant-aware Maven archetype variant ([#21](https://github.com/tomas-samek/tiko-di/issues/21)). The plain quickstart archetype already ships (see above).
- Machine-readable topology + config schema, plus an MCP server so AI agents can introspect the wiring ([#22](https://github.com/tomas-samek/tiko-di/issues/22)).

### Phase 4 (future) — runtime hardening

- AOP / interceptors
- Metrics and monitoring hooks
- GraalVM native image optimization

### Phase 5 (future) — publish to Maven Central

- Sonatype Central Portal namespace verification for `io.tiko`
- GPG signing, `central-publishing-maven-plugin`, POM metadata polish
- Javadoc + sources jars, BOM publication, GitHub Actions release workflow

### Kafka follow-ups (future)

- Avro + schema registry support (`tiko-kafka-avro`).
- Full `@EventTrigger` semantics on bridge methods (factor trigger dispatcher out of EventRegistryGenerator).
- Batch / at-most-once commit modes.
- Topic/queue patterns via `@KafkaSource(consumerGroup = "...")` exercised by a demo.
- Pluggable partition-key extractors.
- Per-source DLQ handling.
- Transactional / exactly-once producers.

## Known limitations

- `container.get(Class, String)` uses `isAssignableFrom` matching; `container.get(Class)` uses exact class or exact implemented-interface matching. The asymmetry is intentional for now but may be unified in a future release.
- All open issues are tracked in [GitHub Issues](https://github.com/tomas-samek/tiko-di/issues).
