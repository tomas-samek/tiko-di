# Tiko DI

> A modern, compile-time dependency injection framework for Java 21+ with event-driven architecture

[![Build](https://github.com/tomas-samek/tiko-di/actions/workflows/maven.yml/badge.svg?branch=main)](https://github.com/tomas-samek/tiko-di/actions/workflows/maven.yml)
[![Java](https://img.shields.io/badge/Java-21%20%7C%2025%20%7C%2026-blue.svg)](https://www.oracle.com/java/)
[![Maven](https://img.shields.io/badge/Maven-3.8+-red.svg)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=tomas-samek_tiko-di&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=tomas-samek_tiko-di)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=tomas-samek_tiko-di&metric=coverage)](https://sonarcloud.io/summary/new_code?id=tomas-samek_tiko-di)

**Status: 0.4.0 on Maven Central.** Suitable for early-adopter experimentation. See [docs/roadmap.md](./docs/roadmap.md) for what ships today and what's next.

## What Tiko is

A **compile-time orchestrator** with an integrated event model and full compile-time validation.

> Tiko orchestrates, it doesn't bundle — direct access, compile-time safe, nothing wrapped.

The container, the scopes, the event bus, and the wiring graph ship as the framework. HTTP servers, databases, caches, template engines, security libraries, and any other library — you bring directly, and tiko orchestrates the seam via `@Produces`. Nothing is wrapped or rewritten on the library side; nothing is re-invented on the framework side.

For the longer pitch — design principles, three-layer architecture, event-pipeline trade-offs — see [docs/VISION.md](./docs/VISION.md).

## The three buckets

Every concern in a tiko-built service lands in exactly one bucket. Classify, then write code.

### Core — what tiko ships

DI container, scopes (`SINGLETON` / `EVENT` / `PROTOTYPE`), event bus, `@EventHandler` + `@EventTrigger`, compile-time validation, lifecycle hooks (`@PostConstruct`, `@PreDestroy`, `ApplicationStartedEvent`, `ApplicationEndingEvent`), `@Configuration` typed records.

### Plug in — you bring the library; tiko orchestrates the seam

HTTP (Javalin, Jetty, Spark, Vert.x), DataSource (HikariCP, Agroal), schema migrations (Flyway, Liquibase), caching (Caffeine), templates (FreeMarker, Pebble), security (JWT / OAuth via the library of your choice), schedulers, retries, any SDK client. Pattern: declare a factory method annotated `@Produces`, return the library's value, consume as a constructor parameter. No wrapper, no adapter layer, no annotation-driven dispatch.

### Open design questions

Async generalisation, scheduling-as-tick-event, retry-as-event-loop — the small set of unresolved questions about extending the event model itself. An honest scope note, not a deferral promise.

## What you plug in

Lookup-table answer to "does tiko support X?":

| Need | Pattern | Recipe in |
|---|---|---|
| HTTP layer | `@Produces Javalin` (or your choice) + plain route methods | `tiko-build` skill, example `15_quickstart` |
| Postgres / MySQL | `@Produces DataSource` returning `HikariDataSource` | `tiko-build` skill, example `15_quickstart` |
| Schema migrations | `@EventHandler(ApplicationStartedEvent)` calling `Flyway.migrate()` | `tiko-build` skill, example `15_quickstart` |
| Kafka | `@KafkaSource` / `@KafkaSink` — tiko-native module with compile-time validation | `tiko-kafka`, example `08_kafka_order_warehouse` |
| In-process cache | `@Produces Cache<K,V>` (Caffeine) | `tiko-build` skill |
| Templates | `@Produces freemarker.template.Configuration` (or Pebble, Mustache) | `tiko-build` skill |
| Security | Javalin `before` handler + `Context` attributes (open-design caveat in skill §6) | `tiko-build` skill §6 |
| Scheduling | `@EventHandler` on a `Tick` event published by a small scheduler thread | `tiko-build` skill §4.2 |
| Async work | `@EventHandler(async = true)` for event-shaped work; `CompletableFuture` / virtual threads for ad-hoc | `tiko-build` skill §4.3 |
| Retry | Small utility wrapper — visible code, no AOP | `tiko-build` skill §4.4 |
| Configuration | Typed `@Configuration` records | `tiko-config`, example `02_config` |
| Metrics / tracing | Plug in your library; expose via routes on your HTTP layer | — |

## Benchmarked for AI-friendliness

[llm-framework-benchmark](https://github.com/tomas-samek/llm-framework-benchmark): on an external-oracle-graded build task (a Kafka → H2 → merged-notification service), run across three models (Claude Sonnet 4.6 / Fable 5 / Opus 4.8, n=5). tiko — *absent from the models' training data* — reaches **86–100% median compliance**, on par with Spring on versions the models know, and clears the brand-new Spring Boot 4.0.6 wall that broke Sonnet 4.6 (median 0%). See the benchmark for the full picture, per-build token cost, and caveats.

## Start building

- **New service?** Read [`.ai-skills/tiko-build/SKILL.md`](./.ai-skills/tiko-build/SKILL.md) — decision tree, `@Produces` cookbook, anti-pattern redirect table so an agent reaches for the tiko-native primitive.
- **Long-form prose?** [`docs/orchestrator-model.md`](./docs/orchestrator-model.md).
- **Reference shape?** [`tiko-examples/15_quickstart`](./tiko-examples/15_quickstart) — the canonical small service the skill cites.
- **Scaffold a fresh project?** Maven archetype — see [Scaffold a new project](#scaffold-a-new-project-archetype) below.
- **Library not in the cookbook?** Read [`.ai-skills/tiko-cookbook-extension/SKILL.md`](./.ai-skills/tiko-cookbook-extension/SKILL.md) — the procedural skill for adding a new recipe. Load-bearing rule: **ask, don't fabricate.**

## Quick example

```java
// 1. Define your components
@Component(scope = Scope.SINGLETON)
public class UserRepository {
    public User findById(String id) { /* ... */ return null; }
}

@Component(scope = Scope.SINGLETON)
public class UserService {
    private final UserRepository repository;

    @Inject
    public UserService(UserRepository repository) {
        this.repository = repository;
    }

    public User getUser(String id) {
        return repository.findById(id);
    }
}

// 2. Use the container
public class Main {
    public static void main(String[] args) {
        try (Container container = Tiko.create()) {
            UserService service = container.get(UserService.class);
            User user = service.getUser("123");
        }
    }
}
```

The annotation processor validates all dependencies at compile-time and generates the wiring code — plain Java you can read and step through. No reflection, no classpath scanning in your wiring.

**Lifecycle & imperative publish.** `Tiko.create(...)` returns an `AutoCloseable` container you manage (try-with-resources above). For a long-lived headless process — a Kafka consumer or scheduler with no foreground server — use `Tiko.daemon(...)` (it installs a graceful-shutdown hook so `@PreDestroy` runs on `Ctrl+C` / `SIGTERM`) and keep the process alive with the one canonical idiom `daemon.awaitShutdown()`. To publish events imperatively from inside a component, inject `EventBus` as a constructor dependency — it is built-in, no plain-class workaround. Details: the [`tiko-build`](./.ai-skills/tiko-build/SKILL.md) skill and [events.md](./docs/events.md).

## Installation

Tiko ships to Maven Central. Import the BOM once — then declare the artifacts (and the annotation-processor path) without restating the version:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.tomas-samek</groupId>
            <artifactId>tiko-bom</artifactId>
            <version>0.4.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.github.tomas-samek</groupId>
        <artifactId>tiko-api</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.tomas-samek</groupId>
        <artifactId>tiko-runtime</artifactId>
    </dependency>
    <!-- Optional, only if you use @Configuration -->
    <dependency>
        <groupId>io.github.tomas-samek</groupId>
        <artifactId>tiko-config</artifactId>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.13.0</version>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>io.github.tomas-samek</groupId>
                        <artifactId>tiko-processor</artifactId>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

The `tiko-bom` import is the single place the version lives; `maven-compiler-plugin` ≥ 3.12.0 resolves the `annotationProcessorPaths` version from it too (3.13.0 pinned above for JDK 23+ — see the note below). The BOM also manages the optional `tiko-kafka` / `tiko-mcp` / `tiko-test` modules.

> **On JDK 23+?** `javac` no longer runs annotation processing implicitly — the snippet above is already correct (it requires `maven-compiler-plugin` ≥ 3.13.0). For Gradle, plain `javac`, and the legacy `<proc>full</proc>` opt-in, see [docs/jdk-23-setup.md](./docs/jdk-23-setup.md).

### Scaffold a new project (archetype)

The fastest way to start a fresh project — generates a runnable single-module Tiko DI project with a minimal `Main` + one `@Component`, plus AI-assistant context files:

```bash
mvn archetype:generate \
    -DarchetypeGroupId=io.github.tomas-samek \
    -DarchetypeArtifactId=tiko-archetype \
    -DarchetypeVersion=0.4.0 \
    -DgroupId=com.example \
    -DartifactId=my-app \
    -DinteractiveMode=false

cd my-app
mvn exec:java   # prints: Hello, world!
```

The generated project ships with AI-context files for the major coding agents — `CLAUDE.md` (canonical), `AGENTS.md`, `.cursor/rules/tiko.md`, `.github/copilot-instructions.md`, `.junie/guidelines.md`, `.ai-skills/SKILL.md`, and `.ai-skills/tiko-build/SKILL.md` (the orchestrator-model skill — decision tree + `@Produces` cookbook + anti-pattern redirects). Each tool-specific file points at the canonical sources, so edit one file when conventions change.

### AI-agent topology server (MCP)

Every Tiko build emits machine-readable topology + config schema to
`META-INF/tiko/`. The `tiko-mcp` companion jar exposes them to any
MCP-aware coding agent (Claude Code, Cursor, …):

    java -jar tiko-mcp.jar /path/to/your/project

The metadata ships inside the jar so MCP can also introspect Tiko
dependencies you didn't build yourself. To suppress emission for a
module (closed-source service, sensitive jars), add
`-Atiko.topology.bundle=false` to the annotation processor args.

See [`tiko-examples/13_mcp_introspection`](./tiko-examples/13_mcp_introspection)
for a runnable demo. Projects scaffolded from `tiko-archetype` ship a
ready-to-use `.mcp.json` (jbang-resolved) — MCP-aware agents auto-connect
on open with no setup beyond installing jbang.

## Annotations at a glance

| Annotation                          | Purpose                                                                  | Deep dive                              |
|-------------------------------------|--------------------------------------------------------------------------|----------------------------------------|
| `@Component(scope, name, profiles)` | Marks a class for DI                                                     | [di-and-scopes.md](./docs/di-and-scopes.md) |
| `@Inject`                           | Marks the constructor to wire (constructor-only — no field injection)    | [di-and-scopes.md](./docs/di-and-scopes.md) |
| `@Named("...")` / `@Pick(Class)`    | Disambiguate when multiple impls exist (string vs class-literal)         | [di-and-scopes.md](./docs/di-and-scopes.md#qualifiers--named-pick-pickert-pick) |
| `@Produces(scope, name, profiles)`  | Factory method — instance or static                                      | [di-and-scopes.md](./docs/di-and-scopes.md#produces-factory-methods) |
| `@PostConstruct` / `@PreDestroy`    | Lifecycle hooks (`AutoCloseable` is the recommended cleanup form)        | [di-and-scopes.md](./docs/di-and-scopes.md#lifecycle-hooks) |
| `@Configuration(prefix)`            | Marks a record as a YAML-backed config root                              | [configuration.md](./docs/configuration.md) |
| `@EventHandler(async, eventType)`   | Subscribe to events (sync by default, opt-in async)                      | [events.md](./docs/events.md) |
| `@EventTrigger(eventName, ...)`     | Declarative event chains — return-as-payload, guards, spread, async      | [events.md](./docs/events.md#event-chains-with-eventtrigger) |

Scopes: `SINGLETON` > `EVENT` > `PROTOTYPE` (longest to shortest lifetime). Shorter-lived beans injected into longer-lived scopes are automatically proxied — see [docs/di-and-scopes.md](./docs/di-and-scopes.md#cross-scope-injection).

## Runnable examples

Fifteen worked examples ship under [`tiko-examples/`](./tiko-examples/README.md), each a self-contained Maven project:

| #  | Module                                                              | Demonstrates                                                                                            |
|----|---------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| 01 | [`01_basic_di`](./tiko-examples/01_basic_di)                        | `@Component`, scopes, cross-scope proxies, `@Produces`, `@Named`, `@Pick`, `Provider<T>`, `Picker<T>`, `pick()` |
| 02 | [`02_config`](./tiko-examples/02_config)                            | `@Configuration` records, layered `ConfigSources`, `${VAR}` interpolation                               |
| 03 | [`03_events`](./tiko-examples/03_events)                            | Lifecycle events, `@EventTrigger` chains with guards/spread/async, `Event<?>` origin tracking           |
| 04 | [`04_api_impl`](./tiko-examples/04_api_impl)                        | API/impl split — app compiles against an interface jar, impl supplied at runtime                        |
| 05 | [`05_multi_module`](./tiko-examples/05_multi_module)                | Multi-module aggregation via `AggregatingContainer`                                                     |
| 06 | [`06_config_multi_module`](./tiko-examples/06_config_multi_module)  | Module-baked `META-INF/tiko/defaults.yaml` discovery + user override                                    |
| 07 | [`07_async_start`](./tiko-examples/07_async_start)                  | `@EventHandler(async = true)` on `ApplicationStartedEvent` — keep slow warmup off the critical path     |
| 08 | [`08_kafka_order_warehouse`](./tiko-examples/08_kafka_order_warehouse) | Cross-JVM Kafka demo — `@KafkaSource` / `@KafkaSink`, shared event class, Testcontainers e2e         |
| 09 | [`09_http_javalin`](./tiko-examples/09_http_javalin)                | `TikoJavalin.scoped` middleware opens an EVENT scope per route; sync request→response independent of the bus |
| 10 | [`10_persistence_jdbc`](./tiko-examples/10_persistence_jdbc)        | Persistence cookbook — EVENT-scoped JDBC transactions across HTTP and batch flows ([docs](./docs/cookbooks/persistence.md)) |
| 11 | [`11_custom_logger`](./tiko-examples/11_custom_logger)              | Routing framework logs through slf4j + logback via `System.LoggerFinder`                                |
| 12 | [`12_testing`](./tiko-examples/12_testing)                          | `@TikoTest` JUnit 5 extension — parameter resolution, `RecordingEventBus` assertions, scope helpers, `awaitAsyncDispatch` |
| 13 | [`13_mcp_introspection`](./tiko-examples/13_mcp_introspection)      | Runnable demo of the `tiko-mcp` topology server reading a built project's `META-INF/tiko/` artifacts |
| 14 | [`14_profiles`](./tiko-examples/14_profiles)                        | `@Component(profiles = "...")` — dev/prod implementation switching with default-profile resolution |
| 15 | [`15_quickstart`](./tiko-examples/15_quickstart)                    | **Orchestrator-model reference app** — HikariCP + Javalin + event-driven handler the `tiko-build` skill cites |

## Measured cold-start

The `comparisons/` directory holds eight self-contained, side-by-side implementations of the same four-singleton, two-module workload — plain Java (no DI), Tiko, Dagger 2, Avaje Inject, HK2, Guice, Spring, and Micronaut (`micronaut-inject` only). Median of 10 cold JVM invocations, default JVM, default GC, Java 21, on a development laptop. **These numbers move on different hardware** — re-run locally before drawing conclusions.

| Framework                        | Wall-clock (ms) | `total_ns` (ms) | Style                      |
|----------------------------------|----------------:|----------------:|----------------------------|
| _jvm baseline (`java -version`)_ |             104 |               — | —                          |
| plain (no DI)                    |             172 |              36 | floor reference            |
| **dagger**                       |         **186** |          **44** | compile-time, lazy         |
| **tiko**                         |         **202** |          **61** | compile-time, lazy         |
| avaje                            |             228 |             105 | compile-time, eager        |
| hk2                              |             307 |             159 | runtime, reflection, lazy  |
| guice                            |             373 |             230 | runtime, reflection, lazy  |
| micronaut (inject-only)          |             459 |             308 | compile-time, eager + AOP  |
| spring                           |             529 |             368 | runtime, reflection, eager |

The `total_ns` column sums the four phases the bench measures (`create + first_get_a + first_get_b + close`) and is the apples-to-apples comparison: it accounts for both eager (Avaje, Spring, Micronaut) and lazy (Tiko, Dagger, Guice, HK2) initialisation strategies. See [`comparisons/README.md`](./comparisons/README.md) for full per-phase tables, methodology, caveats, and reproduction.

The honest reading: the dominant axis is **lazy vs eager init**, not "compile-time vs runtime." Four clusters emerge — lean compile-time-lazy (plain, Dagger, Tiko at 36–61 ms `total_ns`), compile-time-eager (Avaje at 105 ms), runtime-reflection-lazy (HK2, Guice at 159–230 ms), and eager-with-overhead (Micronaut, Spring at 308–368 ms). Within each laziness class the compile-time framework is cheaper (Tiko < Guice; Avaje < Spring), but Avaje (compile-time + eager) is slower than HK2 and Guice (runtime + lazy) — eagerness costs more than reflection saves at this scale.

## Modules

| Module                  | Purpose                                                                                                                                            |
|-------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| `tiko-api`              | Core annotations and interfaces. The only compile-time dependency your code needs.                                                                 |
| `tiko-processor`        | Annotation processor — runs at compile-time to validate dependencies and generate wiring code.                                                     |
| `tiko-runtime`          | Minimal runtime container. Zero dependencies beyond `tiko-api`. Ships the in-memory `LocalEventBus`.                                               |
| `tiko-config`           | YAML-backed configuration injection. The only module that depends on SnakeYAML. Required when your project uses `@Configuration`; not pulled otherwise. |
| `tiko-test`             | JUnit 5 extension + compile-time `@TestComponent` overrides + runtime `TikoOptions.override(...)` + `RecordingEventBus` spy. Test-scope dependency only. |
| `tiko-kafka` + `tiko-kafka-processor` | Kafka transport via the universal `TransportBootstrap` SPI — `@KafkaSource` / `@KafkaSink`, JSON serializer, per-record commit + seek-back. Opt-in. |
| `tiko-archetype`        | Maven archetype that scaffolds a runnable single-module Tiko project in seconds.                                                                   |
| `tiko-bom`              | Bill-of-materials for version-aligned dependency management.                                                                                       |

Event abstractions (`EventBus`, `EventCallback`, `Subscription`, `@EventHandler`, `@EventTrigger`, `Event<T>`) live in `tiko-api`; the in-memory implementation lives in `tiko-runtime`. Kafka ships as a separate, opt-in module pair (`tiko-kafka` + `tiko-kafka-processor`); further transports (RabbitMQ, JMS) are planned under Phase 8.

## Logging

Tiko logs through `java.lang.System.Logger` — the JDK-standard SPI introduced in
Java 9. There is no tiko-side configuration knob, no SPI to implement, no
adapter module.

**Default:** routes through `java.util.logging`. Nothing to configure for "just works."

**Routing to slf4j:** add `slf4j-jdk-platform-logging` + your slf4j backend
(logback, log4j-slf4j2-impl, slf4j-simple, etc.). See
[`tiko-examples/11_custom_logger`](./tiko-examples/11_custom_logger) for a
runnable example.

**Routing to log4j2:** add `log4j-jpl` + the log4j2 core. Same mechanism, different bridge.

**Routing to JBoss Logging:** JBoss Logging uses `JulLogManager` rather than a
`LoggerFinder` — set `-Djava.util.logging.manager=org.jboss.logmanager.LogManager`.

The single tiko-side knob remains `TikoOptions.errorHandler(...)` for handler-exception
policy — a different layer than framework logging.

## Documentation

| Document                                              | What's in it                                                                                |
|-------------------------------------------------------|---------------------------------------------------------------------------------------------|
| [docs/VISION.md](./docs/VISION.md)                    | Long-form pitch, design principles, three-layer architecture, event-pipeline trade-offs.    |
| [docs/di-and-scopes.md](./docs/di-and-scopes.md)      | Full DI reference — scopes, cross-scope proxies, lifecycle hooks, qualifiers, `@Produces`.  |
| [docs/configuration.md](./docs/configuration.md)      | `@Configuration` deep-dive — nested records, layered sources, module-baked defaults.        |
| [docs/events.md](./docs/events.md)                    | Event bus, error handling, async executor, lifecycle events, `@EventTrigger` chains.        |
| [docs/testing.md](./docs/testing.md)                  | `tiko-test` JUnit 5 extension — `@TikoTest`, parameter resolution, `RecordingEventBus`, scope helpers, boundary notes. |
| [docs/jdk-23-setup.md](./docs/jdk-23-setup.md)        | Annotation processing on JDK 23+ — Maven / Gradle / plain `javac`.                          |
| [docs/roadmap.md](./docs/roadmap.md)                  | What ships today and what's planned per phase.                                              |
| [docs/release-process.md](./docs/release-process.md)  | Release engineering notes (maintainers).                                                    |
| [docs/qa-playbook.md](./docs/qa-playbook.md)          | Structured QA passes over the framework + examples; issue body template; rules for what to file and what not to. |
| [docs/issue-fix-playbook.md](./docs/issue-fix-playbook.md) | Counterpart to the QA playbook — how to work a filed issue without anchoring on its framing. Four-phase fix workflow + common traps. |
| [comparisons/README.md](./comparisons/README.md)      | Side-by-side cold-start benchmarks across 8 DI frameworks.                                  |
| [docs/superpowers/specs/2026-05-12-kafka-event-bus-design.md](./docs/superpowers/specs/2026-05-12-kafka-event-bus-design.md) | Kafka event bus design — universal transport adapter pattern. |

## Roadmap (summary)

- **Phase 1 — Alpha completion.** ✅ Closed. Core DI, scopes, lifecycle events, `@EventTrigger` chains, multi-module aggregation.
- **Phase 2 — Configuration & distributed events.** ✅ Closed. `@Configuration` v1 + nested records + `Set<X>` + YAML source anchors, Kafka transport, `ErrorContext` hook, async event executor + shutdown timeout, `System.Logger` migration.
- **Phase 3 — Onboarding & tooling.** ✅ Closed (30/30 issues, a week ahead of due date). AI-assistant-aware archetype, `tiko-test` JUnit 5 module, machine-readable topology + nine-tool MCP server, plus the QA pass #1 follow-ups: examples README cleanup, slf4j routing fix, `@PreDestroy` LIFO contract honoured across `@Component` and `@Produces`, project-wide `maven-failsafe-plugin` wiring, dotted-prefix `@Configuration` now binds naturally-nested YAML. QA discipline codified in [qa-playbook.md](./docs/qa-playbook.md) + [issue-fix-playbook.md](./docs/issue-fix-playbook.md).
- **Phase 4 — Runtime hardening (in progress).** Structured `RuntimeException` subtypes, checked-exception propagation through `@Produces` / `@PostConstruct` (✅), framework-managed JVM shutdown hook, JaCoCo coverage + SonarCloud static analysis. AOP / metrics / GraalVM dropped from scope until a concrete driver appears.
- **Phase 5 — Publish to Maven Central.** POM metadata, GPG signing, sources/javadoc jars, `central-publishing-maven-plugin` + CI deploy, and the `@Named` vs `@Pick` API decision. Pulled early so a lean, validated core ships before the heavier feature work.
- **Phase 6 — MCP enrichment.** `get_generated_artifact`, a lifecycle-hook query tool, and richer proxy topology — deeper introspection of the compile-time graph.
- **Phase 7 — Resiliency layer.** Timeouts, retries, bounded-queue backpressure, executor pool knobs, DLQ for failed/timed-out events.
- **Phase 8 — Distributed transports.** RabbitMQ + JMS adapters, `TransportBootstrap` SPI audit, pluggable serializer SPI.
- **Phase 9 — Examples & docs polish.** Advanced-feature examples still to be written (`@EventTriggers`, scoped suppliers, origin chain, `TikoOptions`) plus public-docs tightening.

Full detail in [docs/roadmap.md](./docs/roadmap.md).

## Building from source

```bash
git clone https://github.com/tomas-samek/tiko-di.git
cd tiko-di
mvn clean install              # build all modules
mvn test                       # run tests
mvn clean install -DskipTests  # build without tests
mvn clean install -pl tiko-api # build specific module
```

Requires Java 21+ and Maven 3.8+.

## Philosophy

1. **Compile-time safety.** Wiring errors — unresolved dependencies, circular dependencies, scope violations — are compile-time errors and never survive the build. Runtime failures are reserved for what the compiler cannot see: requesting a component that is not in the graph, or touching an EVENT-scoped dependency outside an open unit of work.
2. **Simplicity.** Minimal concepts, intuitive API.
3. **Explicitness.** No magic, generated code is readable.
4. **Performance.** No reflection in wiring — generated code, fast startup, low memory.
5. **Modularity.** Use only what you need.
6. **Event-driven.** First-class support for decoupled communication.

## Contributing

Contributions are welcome. Open issues or pull requests on GitHub.

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes
4. Push to the branch
5. Open a Pull Request

Bug reports should include Java version, Maven version, and a minimal reproducer. State symptoms and observable facts only — no "probable cause" or "suggested fix" sections, those bias whoever picks the issue up. The full format and rationale are in [docs/qa-playbook.md](./docs/qa-playbook.md); the counterpart workflow for working a filed issue is [docs/issue-fix-playbook.md](./docs/issue-fix-playbook.md).

## License

MIT — see [LICENSE](LICENSE).

## Acknowledgments

Built on lessons learned from existing DI frameworks:

- **Dagger 2** — compile-time validation approach
- **Guice** — clean, type-safe API design
- **Spring** — comprehensive feature set and ecosystem thinking
- **Micronaut** — cloud-native optimization strategies

## Contact

Tomas Samek — [GitHub](https://github.com/tomas-samek)

Project: <https://github.com/tomas-samek/tiko-di>

---

**Tiko** — Compile-time dependency injection for Java 21+, with first-class event handling.
