# Junie guidelines

This project uses Tiko DI — a compile-time DI framework for Java 21+. The
canonical rules live in [`CLAUDE.md`](../CLAUDE.md); read it before
generating code. The bullets below are a refresher.

For building a new service or extending this one, also read
[`.ai-skills/tiko-build/SKILL.md`](../.ai-skills/tiko-build/SKILL.md) —
decision tree, `@Produces` cookbook, anti-pattern redirects.

When the cookbook doesn't cover the library you need to integrate, ask the
user for the missing facts rather than fabricating an integration. To contribute
the recipe upstream, follow
[the cookbook-extension skill on GitHub](https://github.com/tomas-samek/tiko-di/blob/main/.ai-skills/tiko-cookbook-extension/SKILL.md).

## Rules

- Constructor injection only — `@Inject` on the constructor.
- `@Component` scope is optional: SINGLETON, EVENT, or PROTOTYPE (the default).
- Configuration uses `@Configuration` records bound from YAML.
- Event handlers use `@EventHandler` on methods of `@Component` classes.
- Test fakes use `@TestComponent` from `tiko-test`.

## Build commands

- `mvn compile` — annotation processing runs here
- `mvn test` — full test run
- `mvn exec:java -Dexec.mainClass=${package}.Main` — run example
