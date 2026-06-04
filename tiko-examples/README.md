# Tiko DI Examples

Each subdirectory is a self-contained example demonstrating one slice of Tiko. They build alongside the framework and the snippets below assume you've run `mvn install` once at the repo root.

> All `mvn` commands here are run from the repo root.

## 01 — Basic DI &nbsp;<sub>[`01_basic_di/`](./01_basic_di)</sub>

The fundamentals: `@Component`, `@Inject`, `@PostConstruct` / `@PreDestroy`, the four scopes (`SINGLETON`, `REQUEST`, `EVENT`, `PROTOTYPE`), automatic cross-scope proxies, `@Produces` factory methods (instance + static), `@Named` qualifiers, `Provider<T>` lazy lookup, and the `container.pick(Class)` fluent API.

```
mvn -pl tiko-examples/01_basic_di exec:java \
    -Dexec.mainClass=io.tiko.examples.basic.Main
```

## 02 — Configuration &nbsp;<sub>[`02_config/`](./02_config)</sub>

Typed YAML config injection: `@Configuration` records (`DbConfig`, `AppConfig`), generated per-record binders, `@Default` for missing fields, `${VAR:default}` interpolation, and nested-record binding (`AppConfig.server` → `ServerConfig`).

```
mvn -pl tiko-examples/02_config exec:java \
    -Dexec.mainClass=io.tiko.examples.config.Main
```

The same module ships a companion `BrokenConfigMain` that loads a YAML missing the required `db.url` field. `Tiko.create(...)` throws `ConfigValidationException` before the container is constructed — fail-fast at boot beats serving requests against half-bound configuration.

```
mvn -pl tiko-examples/02_config exec:java \
    -Dexec.mainClass=io.tiko.examples.config.BrokenConfigMain
```

## 03 — Events &nbsp;<sub>[`03_events/`](./03_events)</sub>

Lifecycle observability via `ApplicationStarted/Ending` + `Request/EventStarted/Ending` events, plus declarative event chains with `@EventTrigger` (return-as-payload, guards, `spread = true`) and full origin tracking through the `Event<?>` wrapper. `async = true` triggers are shown in example 07.

```
mvn -pl tiko-examples/03_events exec:java \
    -Dexec.mainClass=io.tiko.examples.events.Main
```

## 04 — API / Impl split &nbsp;<sub>[`04_api_impl/`](./04_api_impl)</sub>

Library/consumer separation: the `app` compiles against `module-api` (interfaces and DTOs only) and pulls `module-impl` at **runtime** scope. The container resolves implementations through interface dispatch in `container.get(Class)`, so consumer code never sees the `*Impl` types at compile time.

```
mvn -pl tiko-examples/04_api_impl/app exec:java \
    -Dexec.mainClass=io.tiko.examples.apiimpl.app.Main
```

Run `mvn -pl tiko-examples/04_api_impl/app dependency:tree` to confirm `module-impl` shows up at `runtime` scope and not `compile`.

## 05 — Multi-module aggregation &nbsp;<sub>[`05_multi_module/`](./05_multi_module)</sub>

Two domain modules (`module-a`, `module-b`) each run the annotation processor and emit their own `TikoContainerImpl_<hash>`. The runtime's `AggregatingContainer` finds them via `META-INF/tiko/container.properties` and federates lookups across both — no special wiring code in the app.

```
mvn -pl tiko-examples/05_multi_module/app exec:java \
    -Dexec.mainClass=io.tiko.examples.multimodule.app.Main
```

## 06 — Multi-module configuration &nbsp;<sub>[`06_config_multi_module/`](./06_config_multi_module)</sub>

Sibling modules each ship their own `@Configuration` record plus a baked-in `META-INF/tiko/defaults.yaml`. The `AggregatingContainer` discovers all of them and layers a user-supplied YAML on top.

```
mvn -pl tiko-examples/06_config_multi_module/app exec:java \
    -Dexec.mainClass=io.tiko.examples.multimodule.config.app.Main
```

## 07 — Async startup &nbsp;<sub>[`07_async_start/`](./07_async_start)</sub>

`@EventHandler(async = true)` on `ApplicationStartedEvent` keeps slow warmup work (cache priming, schema migration, remote-config fetch) off the critical path — `Tiko.create()` returns while the handler runs on the framework's bounded executor.

```
mvn -pl tiko-examples/07_async_start exec:java \
    -Dexec.mainClass=io.tiko.examples.asyncstart.Main
```

## 08 — Kafka order / warehouse &nbsp;<sub>[`08_kafka_order_warehouse/`](./08_kafka_order_warehouse)</sub>

Cross-JVM demo. An order service publishes `OrderPlaced` over Kafka via `@KafkaSink`; a warehouse service in a separate process subscribes via `@KafkaSource`. The same `@EventHandler` shape works for both local and Kafka-sourced events. Testcontainers runs a real broker in CI.

See the example's own README for the multi-process run sequence.

## 09 — HTTP / Javalin integration &nbsp;<sub>[`09_http_javalin/`](./09_http_javalin)</sub>

How Tiko lives behind an existing HTTP server. `TikoJavalin.scoped` middleware opens a REQUEST scope around each route so REQUEST-scoped beans (`HttpRequestContext`) are valid for the handler's lifetime. The sync request→response path is independent of the event bus; three subscribers demonstrate sync vs. async side effects.

## 10 — Persistence (raw JDBC + HikariCP) &nbsp;<sub>[`10_persistence_jdbc/`](./10_persistence_jdbc)</sub>

Persistence cookbook as a **test-only example** — no `Main` to `exec:java`; the pattern is exercised by `BatchEntryIT` and `HttpEntryIT` under `src/test/java`. REQUEST-scoped JDBC transactions wrap both an HTTP entry point and a batch flow; the same `OrderRepository` is reused across both. Demonstrates the auto-proxy mechanism on a JDK interface (`java.sql.Connection`) and the practical REQUEST-vs-EVENT scope distinction. Run via `mvn -pl tiko-examples/10_persistence_jdbc verify`. See [docs/cookbooks/persistence.md](../docs/cookbooks/persistence.md).

## 11 — Custom logger &nbsp;<sub>[`11_custom_logger/`](./11_custom_logger)</sub>

Routes Tiko's internal logging through slf4j + logback by adding `slf4j-jdk-platform-logging` to the classpath. Zero Tiko-side configuration; the JDK's `System.Logger` SPI does the dispatch.

```
mvn -pl tiko-examples/11_custom_logger exec:exec
```

Note this example uses `exec:exec` (forks a JVM), not `exec:java`. `System.LoggerFinder` is resolved once per JVM via the system classloader's `ServiceLoader`; under `exec:java` the SPI provider lives on the exec plugin's child classloader and is invisible, so the JDK binds to its default JUL finder. See the example's own README for the full explanation.

## 12 — Testing &nbsp;<sub>[`12_testing/`](./12_testing)</sub>

Runnable demo of the `tiko-test` JUnit 5 extension: `@TikoTest` boots a container around each test (`PER_METHOD` default, `PER_CLASS` opt-in), parameters are resolved via JUnit's `ParameterResolver` (no field injection), `RecordingEventBus` provides fluent publish-assertions including `awaitAsyncDispatch(Duration)` for `@EventHandler(async = true)`, and `@RequestScopeTest` / `@EventScopeTest` wrap the test body in container scopes. See the example's own README for the per-file feature map and the full guide at [docs/testing.md](../docs/testing.md).

```
mvn -pl tiko-examples/12_testing -am test
```

## 14 — Profile-Based Selection &nbsp;<sub>[`14_profiles/`](./14_profiles)</sub>

`@Component(profiles = {...})` ships two impls of the same interface (`DevGreeter`, `ProdGreeter`) and a `GreetingService` consumer that injects `Greeter` via constructor. Activate a profile to pick exactly one impl at build time. Profile selection is a build flag, not a runtime switch — consistent with Tiko's compile-time-DI design. The module wraps the underlying `-Atiko.profiles=...` annotation processor argument in two Maven profiles for ergonomics; `dev` is the default, `mvn -P prod` swaps it.

```
mvn -pl tiko-examples/14_profiles -P dev compile
mvn -pl tiko-examples/14_profiles -P dev exec:java \
    -Dexec.mainClass=io.tiko.examples.profiles.Main
```

---

## Running the integration tests

Each example also ships JUnit 5 tests that pin the behaviour. To run only one example's suite:

```
mvn -pl tiko-examples/01_basic_di test
```

Or from the repo root, `mvn test` runs everything.
