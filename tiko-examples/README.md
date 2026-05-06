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

Typed YAML config injection: `@Configuration` records, generated per-record binders, `@Default` and `@Key`, layered `ConfigSources`, `${VAR}` interpolation, strict-mode error reporting that fails the boot rather than serving with broken config.

```
mvn -pl tiko-examples/02_config exec:java \
    -Dexec.mainClass=io.tiko.examples.config.Main
```

## 03 — Events &nbsp;<sub>[`03_events/`](./03_events)</sub>

Lifecycle observability via `ApplicationStarted/Ending` + `Request/EventStarted/Ending` events, plus declarative event chains with `@EventTrigger` (return-as-payload, guards, `spread = true`, `async = true`) and full origin tracking through the `Event<?>` wrapper.

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

---

## Running the integration tests

Each example also ships JUnit 5 tests that pin the behaviour. To run only one example's suite:

```
mvn -pl tiko-examples/01_basic_di test
```

Or from the repo root, `mvn test` runs everything.
