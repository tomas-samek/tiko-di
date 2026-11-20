# tiko-di skill (Kotlin)

This project is a Kotlin service on **tiko-di**, a compile-time dependency injection framework.
Tiko's processor runs on KSP.

## Building a new service or extending this one?

Read [`.ai-skills/tiko-build/SKILL.md`](../../.ai-skills/tiko-build/SKILL.md) first, then
[`reference/kotlin.md`](../../.ai-skills/tiko-build/reference/kotlin.md) for Kotlin.

## Hit a library the cookbook doesn't cover?

Ask the user for the facts you need rather than fabricating an integration
(**ask, don't fabricate**). To contribute the recipe upstream, follow
[the cookbook-extension skill on GitHub](https://github.com/tomas-samek/tiko-di/blob/main/.ai-skills/tiko-cookbook-extension/SKILL.md).

## Key things to know

- Constructor injection through the primary constructor; no field or setter injection.
- The wiring is validated and generated at compile time; a build failure names the problem.
- `@Component` declares a bean. The default scope is `PROTOTYPE`; pass `Scope.SINGLETON` for
  stateless services.
- A SINGLETON taking an EVENT-scoped component needs that component to implement an interface:
  the processor generates a proxy.
- Events: `@EventHandler` to receive; to send, take `EventBus` in the constructor and call
  `publish(...)`.
- [`CLAUDE.md`](../../CLAUDE.md) has the rest.

## When in doubt

Read the generated code under `build/generated/ksp/main/java/io/tiko/generated/`: the wiring is
all there, as plain Java.
