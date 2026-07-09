# Tiko DI project

This project uses [Tiko DI](https://github.com/tomas-samek/tiko-di) — a
compile-time dependency injection framework for Java 21+.

## Building a service on this scaffold?

Read [`.ai-skills/tiko-build/SKILL.md`](./.ai-skills/tiko-build/SKILL.md)
first — decision tree, `@Produces` cookbook, anti-pattern redirects. The
skill is the procedure for building with the framework; the files below
are the reference.

## Hit a library the cookbook doesn't cover?

Ask the user for the missing facts rather than fabricating an integration
(**ask, don't fabricate**). To contribute the recipe upstream, follow
[the cookbook-extension skill on GitHub](https://github.com/tomas-samek/tiko-di/blob/main/.ai-skills/tiko-cookbook-extension/SKILL.md).

## Read this first

The canonical conventions live in [`CLAUDE.md`](./CLAUDE.md) at the project
root. It covers:

- Component scopes (SINGLETON / EVENT / PROTOTYPE)
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
