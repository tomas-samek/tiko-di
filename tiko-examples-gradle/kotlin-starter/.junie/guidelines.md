# Junie guidelines

This project is a Kotlin service on Tiko DI, a compile-time dependency injection framework whose
processor runs on KSP. The canonical rules live in [`CLAUDE.md`](../CLAUDE.md); read it before
generating code. The bullets below are a refresher.

For building a new service or extending this one, also read
[`.ai-skills/tiko-build/SKILL.md`](../.ai-skills/tiko-build/SKILL.md) (decision tree, `@Produces`
cookbook, anti-pattern redirects) and
[`reference/kotlin.md`](../.ai-skills/tiko-build/reference/kotlin.md).

When the cookbook doesn't cover the library you need to integrate, ask the user for the facts you
need rather than fabricating an integration. To contribute the recipe upstream, follow
[the cookbook-extension skill on GitHub](https://github.com/tomas-samek/tiko-di/blob/main/.ai-skills/tiko-cookbook-extension/SKILL.md).

## Rules

- Constructor injection through the primary constructor; `@Inject` only to pick a secondary one.
- `@Component` scope: SINGLETON, EVENT, or PROTOTYPE (the default).
- Configuration uses `@Configuration` `@JvmRecord data class`es bound from YAML.
- Event handlers use `@EventHandler` on methods of `@Component` classes.
- Test fakes use `@TestComponent` from `tiko-test`; the class it replaces is `open`.

## Build commands

- `./gradlew build`: compiles and runs the tests
- `./gradlew run`: runs `main`
