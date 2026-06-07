# Junie guidelines

This project uses Tiko DI — a compile-time DI framework for Java 21+. The
canonical rules live in [`CLAUDE.md`](../CLAUDE.md); read it before
generating code. The bullets below are a refresher.

For building a new service or extending this one, also read
[`.ai-skills/tiko-build/SKILL.md`](../.ai-skills/tiko-build/SKILL.md) —
decision tree, `@Produces` cookbook, anti-pattern redirects.

When the cookbook doesn't cover the library you need to integrate, read
[`.ai-skills/tiko-cookbook-extension/SKILL.md`](../.ai-skills/tiko-cookbook-extension/SKILL.md) —
the procedural skill for adding a new recipe. Load-bearing rule:
**ask, don't fabricate.**

## Rules

- Constructor injection only — `@Inject` on the constructor.
- Every `@Component` declares a scope: SINGLETON, REQUEST, EVENT, or PROTOTYPE.
- Configuration uses `@Configuration` records bound from YAML.
- Event handlers use `@EventHandler` on methods of `@Component` classes.
- Test fakes use `@TestComponent` from `tiko-test`.

## Build commands

- `mvn compile` — annotation processing runs here
- `mvn test` — full test run
- `mvn exec:java -Dexec.mainClass=${package}.Main` — run example
