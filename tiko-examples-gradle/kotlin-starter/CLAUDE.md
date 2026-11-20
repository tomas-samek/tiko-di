# Project context for Claude Code (and other coding agents)

This project is a Kotlin service on **[Tiko DI](https://github.com/tomas-samek/tiko-di)**, a
compile-time dependency injection framework for the JVM. Tiko's annotation processor runs on
KSP: it validates the wiring at build time and writes the container as plain Java. There is no
reflection and no classpath scanning in the wiring.

> **Building a service here?** Read
> [`.ai-skills/tiko-build/SKILL.md`](./.ai-skills/tiko-build/SKILL.md) first (decision tree,
> `@Produces` cookbook, anti-pattern redirects), then
> [`reference/kotlin.md`](./.ai-skills/tiko-build/reference/kotlin.md) for what changes in Kotlin.
>
> **Library the cookbook doesn't cover?** Ask the user for the facts you need rather than
> fabricating an integration. To contribute the recipe upstream, follow
> [the cookbook-extension skill on GitHub](https://github.com/tomas-samek/tiko-di/blob/main/.ai-skills/tiko-cookbook-extension/SKILL.md).

First half: Tiko in this project. Second half: a template for this project's own docs.

---

# Tiko in this project

## Scopes

| Scope | Lifetime | Typical use |
|---|---|---|
| `SINGLETON` | application lifetime | stateless services, repositories |
| `EVENT` | one unit of work: a request, a consumed message, a job, an async handler run | per-unit context (transaction, connection, correlation ID) |
| `PROTOTYPE` | a new instance per injection (default) | short-lived value objects |

**Default is `PROTOTYPE`**: declare `Scope.SINGLETON` explicitly for a longer lifetime. A
SINGLETON that takes an EVENT-scoped component gets a generated proxy, so the EVENT component
must implement an interface. "Event" in `Scope.EVENT`, `runInEventScope` and `EventStartedEvent`
means the unit of work, not something published on the event bus.

## Kotlin specifics

- **Constructor injection through the primary constructor.** `@Inject` is needed only to pick a
  secondary constructor instead. Default parameter values are never used: every parameter is
  injected.
- **`internal` classes and members work**: the generated Java calls them by their JVM names.
- **A `@TestComponent` that replaces a class subclasses it**, so the class and the members it
  overrides must be `open`. Replacing an interface-keyed component needs no `open`.
- **Configuration** is a `@Configuration` `@JvmRecord data class`. A Kotlin default value or a
  nullable type does not make a key optional; use `@Default("…")` or `Optional<T>`.
- **A basic type (`Int`, `Boolean`, …) is not injectable**: wrap it in a small class, or bind it
  through `@Configuration`.
- The full table of Kotlin constructs, what is supported and what is rejected:
  [`reference/kotlin.md`](./.ai-skills/tiko-build/reference/kotlin.md).

## Exact packages (import from here, not from memory)

| Type | Package |
|---|---|
| `@Component` `@Inject` `@Named` `@Pick` `@Produces` `@PostConstruct` `@PreDestroy` `@EventHandler` `@EventTrigger` `@Configuration` `@Default` `@Key` | `io.tiko.annotations` |
| `Container` `EventBus` `Scope` `Provider` `UnitOfWork` | `io.tiko` |
| `Tiko` `TikoOptions` `TikoDaemon` | `io.tiko.runtime` |
| `ConfigSources` | `io.tiko.config` (needs `tiko-config`) |
| `@TikoTest` `@TestComponent` `RecordingEventBus` | `io.tiko.test` (`tiko-test`, test scope) |
| `@KafkaSource` `@KafkaSink` | `io.tiko.kafka.annotations` (needs `tiko-kafka` + `ksp("…:tiko-kafka-processor")`) |

A `cannot find symbol` / `Unresolved reference` on an import means a wrong package: check this
table, then [`reference/api-signatures.md`](./.ai-skills/tiko-build/reference/api-signatures.md).

## Where the depth lives (read on demand)

| file | read when |
|---|---|
| [`.ai-skills/tiko-build/SKILL.md`](./.ai-skills/tiko-build/SKILL.md) | starting any new service work |
| [`reference/kotlin.md`](./.ai-skills/tiko-build/reference/kotlin.md) | any Kotlin construct, Gradle + KSP setup, coroutines (`tiko-coroutines`) |
| [`reference/api-signatures.md`](./.ai-skills/tiko-build/reference/api-signatures.md) | writing any import, or unsure of a signature or attribute |
| [`reference/config.md`](./.ai-skills/tiko-build/reference/config.md) | declaring `@Configuration` records or writing YAML |
| [`reference/events.md`](./.ai-skills/tiko-build/reference/events.md) | publishing and handling events, lifecycle hooks, a long-running `main` |
| [`reference/kafka.md`](./.ai-skills/tiko-build/reference/kafka.md) | consuming or producing Kafka |

## Build and run

```bash
./gradlew build    # compiles (KSP runs the processor), runs the tests
./gradlew test     # tests only
./gradlew run      # runs main (application { mainClass } in build.gradle.kts)
```

The generated container is under `build/generated/ksp/main/java/io/tiko/generated/`: read it when
in doubt, it is the whole wiring. A wiring mistake is a compile error that names the class and
the fix.

`tikoVersion` in `gradle.properties` is the Tiko version for every Tiko dependency.

## Common shapes

- **A long-running service** (an HTTP server, a Kafka consumer, a scheduler): `main` is
  `Tiko.daemon(…).awaitShutdown()`, and an HTTP server is a `@Component` the container starts
  and stops. [The HTTP cookbook](https://github.com/tomas-samek/tiko-di/blob/main/docs/cookbooks/http.md)
  has the shape.
- **Ktor routes** use `unitOfWork.inEventScope { … }` from `tiko-coroutines`:
  [the Ktor cookbook](https://github.com/tomas-samek/tiko-di/blob/main/docs/cookbooks/ktor.md).
- **Tests**: `@TikoTest` boots a container per test and resolves components as test-method
  parameters; `@TestComponent` replaces one.
  [Testing](https://github.com/tomas-samek/tiko-di/blob/main/docs/testing.md).

## MCP topology server

Every build writes the wiring as `topology.json` under `build/generated/ksp/main/resources/META-INF/tiko/`.
`.mcp.json` starts the `tiko-mcp` server for MCP-aware agents (it needs [jbang](https://www.jbang.dev/)),
so an agent can ask what is wired without reading the generated code.

---

# About this project

Replace this half with your project's own context.

## Project overview

What the service does, and for whom.

## Architecture

The main components and how a request or message flows through them.

## Conventions

Naming, packages, error handling, logging.

## External dependencies

Databases, brokers, services this project talks to, and how to run them locally.
