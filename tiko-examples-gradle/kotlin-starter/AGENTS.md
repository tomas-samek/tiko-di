# Tiko DI project (Kotlin)

This project is a Kotlin service on [Tiko DI](https://github.com/tomas-samek/tiko-di), a
compile-time dependency injection framework. Tiko's processor runs on KSP.

## Building a service on this starter?

Read [`.ai-skills/tiko-build/SKILL.md`](./.ai-skills/tiko-build/SKILL.md) first (decision tree,
`@Produces` cookbook, anti-pattern redirects), then
[`reference/kotlin.md`](./.ai-skills/tiko-build/reference/kotlin.md) for Kotlin.

## Hit a library the cookbook doesn't cover?

Ask the user for the facts you need rather than fabricating an integration
(**ask, don't fabricate**). To contribute the recipe upstream, follow
[the cookbook-extension skill on GitHub](https://github.com/tomas-samek/tiko-di/blob/main/.ai-skills/tiko-cookbook-extension/SKILL.md).

## Read this first

The canonical conventions live in [`CLAUDE.md`](./CLAUDE.md): scopes, Kotlin specifics, exact
packages, build commands, common shapes.

## Quick rules

- Constructor injection through the primary constructor; `@Inject` only to pick a secondary one.
- Components declare their scope: `@Component(scope = Scope.SINGLETON)`; the default is `PROTOTYPE`.
- A class a `@TestComponent` replaces, and its overridden members, are `open`.

## Build commands

- `./gradlew build`: compiles (KSP runs the processor) and runs the tests
- `./gradlew test`: tests only
- `./gradlew run`: runs `main`
