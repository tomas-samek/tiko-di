# Tiko DI project

This project uses [Tiko DI](https://github.com/tomas-samek/tiko-di) — a
compile-time dependency injection framework for Java 21+.

## Read this first

The canonical conventions live in [`CLAUDE.md`](./CLAUDE.md) at the project
root. It covers:

- Component scopes (SINGLETON / REQUEST / EVENT / PROTOTYPE)
- Annotation cheat-sheet (`@Component`, `@Inject`, `@Produces`,
  `@Configuration`, `@EventHandler`, `@EventTrigger`)
- Constructor-injection rule (no field injection)
- Build commands and common pitfalls

## Quick rules

- Constructor injection only. `@Inject` on the constructor, never on fields.
- Components declare scope: `@Component(scope = Scope.SINGLETON)`.
- Annotation processing runs in `mvn compile`.

## Build commands

- `mvn compile` — runs annotation processing
- `mvn test` — runs tests
- `mvn exec:java -Dexec.mainClass=${package}.Main` — runs the example
