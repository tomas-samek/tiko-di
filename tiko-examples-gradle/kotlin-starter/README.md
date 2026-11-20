# Tiko Kotlin starter

A Kotlin + Gradle + KSP project on [Tiko DI](https://github.com/tomas-samek/tiko-di): one
component, a `main` that boots the container, and a `@TikoTest`. It is the starting point for a
Kotlin service, as the `tiko-archetype` is for a Maven one.

Inside the tiko-di repository this directory is the source of the
[`tiko-kotlin-starter`](https://github.com/tomas-samek/tiko-kotlin-starter) template repository,
and builds against the repository's snapshot (run `mvn install -DskipTests` from the root first).

## Start a project from it

On GitHub, **Use this template** on `tiko-kotlin-starter`, or copy this directory out of a
[release tag](https://github.com/tomas-samek/tiko-di/tags). `tikoVersion` in `gradle.properties`
is the Tiko release it builds against.

```bash
./gradlew build   # compiles (KSP runs Tiko's processor) and runs the test
./gradlew run     # prints: Hello, world!
```

Then rename `com.example` and `rootProject.name` in `settings.gradle.kts`.

## What is in it

- `src/main/kotlin/com/example/`: `Greeter`, a `@Component`, and `Main.kt`, which boots the
  container with `Tiko.create()`.
- `src/test/kotlin/com/example/GreeterTest.kt`: a `@TikoTest` that takes the component as a
  parameter.
- Agent context: `CLAUDE.md` (canonical), `AGENTS.md`, `.cursor/rules/tiko.md`,
  `.github/copilot-instructions.md`, `.junie/guidelines.md`, `.ai-skills/tiko-build/` (the
  build skill, with its Kotlin reference) and `.mcp.json` (the `tiko-mcp` topology server).

## Where next

- [Kotlin reference](https://github.com/tomas-samek/tiko-di/blob/main/.ai-skills/tiko-build/reference/kotlin.md):
  Gradle setup and every Kotlin construct.
- [Your first service](https://github.com/tomas-samek/tiko-di/blob/main/docs/getting-started.md),
  and the Kotlin services under
  [`tiko-examples-gradle`](https://github.com/tomas-samek/tiko-di/tree/main/tiko-examples-gradle):
  Ktor + Exposed, a Kafka consumer, a transactional outbox.
