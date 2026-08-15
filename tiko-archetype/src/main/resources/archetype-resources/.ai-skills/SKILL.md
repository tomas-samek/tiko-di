# tiko-di skill

This project uses **tiko-di**, a compile-time dependency injection framework for Java 21+.

## Building a new service or extending this one?

Read [`tiko-build/SKILL.md`](./tiko-build/SKILL.md) first. It's the
operational distillation of the orchestrator-model doc: the decision tree
for Core / Plug in / Open, the `@Produces` cookbook table, and the
anti-pattern redirect table so you reach for the tiko-native primitive
instead of searching for a Spring equivalent.
Depth (API signatures, Kafka, config, events) lives in `tiko-build/reference/` — the skill's navigation map says when to open each file.

## Hit a library the cookbook doesn't cover?

Ask the user for the missing facts rather than fabricating an integration
(**ask, don't fabricate**). To contribute the recipe upstream, follow
[the cookbook-extension skill on GitHub](https://github.com/tomas-samek/tiko-di/blob/main/.ai-skills/tiko-cookbook-extension/SKILL.md).

## Where to find framework documentation

- README: https://github.com/tomas-samek/tiko-di/blob/main/README.md
- Worked examples: https://github.com/tomas-samek/tiko-di/tree/main/tiko-examples
- Issue tracker: https://github.com/tomas-samek/tiko-di/issues

## Key things to know

- Constructor injection only — no field injection, no setter injection.
- All dependency wiring is validated and generated at compile time. Build failures point at exact problems.
- `@Component` declares a bean. Default scope is `PROTOTYPE` — pass `Scope.SINGLETON` for stateless services.
- Cross-scope injection (e.g. SINGLETON depending on EVENT) requires the shorter-scoped bean to implement an interface — the framework generates a proxy.
- Events: `@EventHandler` to receive; to send, inject `EventBus` into a component and call `publish(...)` (or `container.getEventBus().publish(...)` outside a component). `@EventTrigger` chains handlers declaratively.
- See README "Core Concepts" and "Usage Examples" sections for full details.

## When in doubt

Read the generated code under `target/generated-sources/annotations/io/tiko/generated/`. The framework's behaviour is fully visible in source — there is no reflection or classpath scanning in your wiring.
