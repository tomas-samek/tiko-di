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

## Planned

### Phase 2 (current) — configuration & distributed events

- Configuration follow-ups: cross-module aggregation example ([#18](https://github.com/tomas-samek/tiko-di/issues/18)), YAML `file:line:col` error anchoring ([#19](https://github.com/tomas-samek/tiko-di/issues/19))
- Event-system follow-ups: configurable executor shutdown timeout ([#48](https://github.com/tomas-samek/tiko-di/issues/48)), `ErrorContext` permits for lifecycle/config/scope errors ([#52](https://github.com/tomas-samek/tiko-di/issues/52))
- Conditional beans
- Profile isolation: compile-time `forbidProfiles` validation + Maven source-root convention to keep test-only `@Component`s out of prod jars

### Phase 3 (next) — onboarding & tooling

- Maven archetype: quickstart starter (basic + AI-assistant-aware variant)
- Machine-readable topology + config schema, plus an MCP server so AI agents can introspect the wiring

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
