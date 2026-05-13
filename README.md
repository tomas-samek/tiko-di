# Tiko DI

> A modern, compile-time dependency injection framework for Java 17+ with event-driven architecture

[![Build](https://github.com/tomas-samek/tiko-di/actions/workflows/maven.yml/badge.svg?branch=main)](https://github.com/tomas-samek/tiko-di/actions/workflows/maven.yml)
[![Java](https://img.shields.io/badge/Java-17%20%7C%2021%20%7C%2025%20%7C%2026-blue.svg)](https://www.oracle.com/java/)
[![Maven](https://img.shields.io/badge/Maven-3.8+-red.svg)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

**Status: Alpha — Phase 2 in progress.** Suitable for early-adopter experimentation. See [docs/roadmap.md](./docs/roadmap.md) for what ships today and what's next.

## Why Tiko?

Tiko combines compile-time validation with a small surface area, and treats the event bus as a first-class part of the container — the same `@EventHandler` and `@EventTrigger` code is designed to work against either in-memory or distributed buses.

**Measured cold start: 202 ms** on a small four-singleton workload — +16 ms over Dagger 2, ~330 ms faster than Spring Boot, comparable to a no-DI baseline. Eight side-by-side framework comparisons under [`comparisons/`](./comparisons/README.md) (`mvn` to reproduce).

For the longer pitch — design principles, three-layer architecture, event-pipeline trade-offs — see [docs/VISION.md](./docs/VISION.md).

### Three principles

1. **Compile-time over runtime.** What can be validated at build, is. Missing deps, circular deps, scope violations, ambiguous providers — all caught by `javac`, not at startup. No `ApplicationContext` to query, no classpath scan at startup, no "bean not found" surprises in production.
2. **Explicit over implicit.** Services declare what they provide and require. Wiring is generated from declarations, not inferred from annotations scattered across the classpath. The build produces a topology you can read.
3. **Proportional over total.** DI where it solves a real problem (alternative implementations, test doubles, lifecycle, cross-cutting concerns). Plain `new`, static methods, and records where it doesn't. Not every utility needs to be a bean.

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

The annotation processor validates all dependencies at compile-time and generates the wiring code. Nothing runs by reflection.

## Installation

Tiko is not yet on Maven Central — publication is tracked as Phase 5. For now, build from source (see [Building from source](#building-from-source)) and the artifacts will be available in your local Maven repository.

```xml
<dependencies>
    <dependency>
        <groupId>io.tiko</groupId>
        <artifactId>tiko-api</artifactId>
        <version>0.1.0</version>
    </dependency>
    <dependency>
        <groupId>io.tiko</groupId>
        <artifactId>tiko-runtime</artifactId>
        <version>0.1.0</version>
    </dependency>
    <!-- Optional, only if you use @Configuration -->
    <dependency>
        <groupId>io.tiko</groupId>
        <artifactId>tiko-config</artifactId>
        <version>0.1.0</version>
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
                        <groupId>io.tiko</groupId>
                        <artifactId>tiko-processor</artifactId>
                        <version>0.1.0</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

> **On JDK 23+?** `javac` no longer runs annotation processing implicitly — the snippet above is already correct (it requires `maven-compiler-plugin` ≥ 3.13.0). For Gradle, plain `javac`, and the legacy `<proc>full</proc>` opt-in, see [docs/jdk-23-setup.md](./docs/jdk-23-setup.md).

### Scaffold a new project (archetype)

The fastest way to start a fresh project — generates a runnable single-module Tiko DI project with a minimal `Main` + one `@Component`, plus AI-assistant context files:

```bash
mvn archetype:generate \
    -DarchetypeGroupId=io.tiko \
    -DarchetypeArtifactId=tiko-archetype \
    -DarchetypeVersion=0.1.0 \
    -DarchetypeCatalog=local \
    -DgroupId=com.example \
    -DartifactId=my-app \
    -DinteractiveMode=false

cd my-app
mvn exec:java   # prints: Hello, world!
```

`-DarchetypeCatalog=local` is required until Tiko publishes to Maven Central. Build the parent project once with `mvn install` to populate your local catalog.

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

Scopes: `SINGLETON` > `REQUEST` > `EVENT` > `PROTOTYPE` (longest to shortest lifetime). Shorter-lived beans injected into longer-lived scopes are automatically proxied — see [docs/di-and-scopes.md](./docs/di-and-scopes.md#cross-scope-injection).

## Runnable examples

Seven worked examples ship under [`tiko-examples/`](./tiko-examples/README.md), each a self-contained Maven project:

| #  | Module                                                              | Demonstrates                                                                                            |
|----|---------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| 01 | [`01_basic_di`](./tiko-examples/01_basic_di)                        | `@Component`, scopes, cross-scope proxies, `@Produces`, `@Named`, `@Pick`, `Provider<T>`, `Picker<T>`, `pick()` |
| 02 | [`02_config`](./tiko-examples/02_config)                            | `@Configuration` records, layered `ConfigSources`, `${VAR}` interpolation                               |
| 03 | [`03_events`](./tiko-examples/03_events)                            | Lifecycle events, `@EventTrigger` chains with guards/spread/async, `Event<?>` origin tracking           |
| 04 | [`04_api_impl`](./tiko-examples/04_api_impl)                        | API/impl split — app compiles against an interface jar, impl supplied at runtime                        |
| 05 | [`05_multi_module`](./tiko-examples/05_multi_module)                | Multi-module aggregation via `AggregatingContainer`                                                     |
| 06 | [`06_config_multi_module`](./tiko-examples/06_config_multi_module)  | Module-baked `META-INF/tiko/defaults.yaml` discovery + user override                                    |
| 08 | [`08_kafka_order_warehouse`](./tiko-examples/08_kafka_order_warehouse) | Cross-JVM Kafka demo — `@KafkaSource` / `@KafkaSink`, shared event class, Testcontainers e2e         |

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

| Module          | Purpose                                                                                                                                            |
|-----------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| `tiko-api`      | Core annotations and interfaces. The only compile-time dependency your code needs.                                                                 |
| `tiko-processor`| Annotation processor — runs at compile-time to validate dependencies and generate wiring code.                                                     |
| `tiko-runtime`  | Minimal runtime container. Zero dependencies beyond `tiko-api`. Ships the in-memory `LocalEventBus`.                                               |
| `tiko-config`   | YAML-backed configuration injection. The only module that depends on SnakeYAML. Required when your project uses `@Configuration`; not pulled otherwise. |

Event abstractions (`EventBus`, `EventCallback`, `Subscription`, `@EventHandler`, `@EventTrigger`, `Event<T>`) live in `tiko-api`; the in-memory implementation lives in `tiko-runtime`. A future Kafka-backed bus would arrive as its own module.

## Documentation

| Document                                              | What's in it                                                                                |
|-------------------------------------------------------|---------------------------------------------------------------------------------------------|
| [docs/VISION.md](./docs/VISION.md)                    | Long-form pitch, design principles, three-layer architecture, event-pipeline trade-offs.    |
| [docs/di-and-scopes.md](./docs/di-and-scopes.md)      | Full DI reference — scopes, cross-scope proxies, lifecycle hooks, qualifiers, `@Produces`.  |
| [docs/configuration.md](./docs/configuration.md)      | `@Configuration` deep-dive — nested records, layered sources, module-baked defaults.        |
| [docs/events.md](./docs/events.md)                    | Event bus, error handling, async executor, lifecycle events, `@EventTrigger` chains.        |
| [docs/jdk-23-setup.md](./docs/jdk-23-setup.md)        | Annotation processing on JDK 23+ — Maven / Gradle / plain `javac`.                          |
| [docs/roadmap.md](./docs/roadmap.md)                  | What ships today, what's planned per phase, known limitations.                              |
| [docs/release-process.md](./docs/release-process.md)  | Release engineering notes (maintainers).                                                    |
| [comparisons/README.md](./comparisons/README.md)      | Side-by-side cold-start benchmarks across 8 DI frameworks.                                  |
| [docs/superpowers/specs/2026-05-12-kafka-event-bus-design.md](./docs/superpowers/specs/2026-05-12-kafka-event-bus-design.md) | Kafka event bus design — universal transport adapter pattern. |

## Roadmap (summary)

- **Phase 2 (current)** — Kafka event bus, configuration follow-ups, conditional beans, profile isolation.
- **Phase 3 (next)** — Onboarding & tooling: Maven archetype variants, machine-readable topology, MCP server for AI agents.
- **Phase 4 (future)** — Runtime hardening: AOP/interceptors, metrics hooks, GraalVM native image.
- **Phase 5 (future)** — Publish to Maven Central.

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

Requires Java 17+ and Maven 3.8+.

## Philosophy

1. **Compile-time safety.** Catch all errors the compiler can see. The only runtime exceptions Tiko throws fire at container startup — never during `container.get(...)` in a running application.
2. **Simplicity.** Minimal concepts, intuitive API.
3. **Explicitness.** No magic, generated code is readable.
4. **Performance.** Zero reflection, fast startup, low memory.
5. **Modularity.** Use only what you need.
6. **Event-driven.** First-class support for decoupled communication.

## Contributing

Contributions are welcome. Open issues or pull requests on GitHub.

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes
4. Push to the branch
5. Open a Pull Request

Bug reports should include Java version, Maven version, and a minimal reproducer.

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

**Tiko** — Compile-time dependency injection for Java 17+, with first-class event handling.
