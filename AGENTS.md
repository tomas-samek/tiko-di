# AGENTS.md

Agent-config pointer for tools that read `AGENTS.md` instead of
`CLAUDE.md`. Same content applies.

## Repo-development reference

[`CLAUDE.md`](./CLAUDE.md) covers conventions for working **on** the
Tiko DI codebase: build commands, code style, annotation processor
rules, testing strategy, generated-code markers, logging discipline.

## Building a service with Tiko

If you're scaffolding or extending a service that **uses** Tiko (rather
than developing the framework itself), read
[`.ai-skills/tiko-build/SKILL.md`](./.ai-skills/tiko-build/SKILL.md)
first. It's the operational distillation of
[`docs/orchestrator-model.md`](./docs/orchestrator-model.md):

- Decision tree for classifying needs into Core / Plug in / Open.
- `@Produces` cookbook table for the canonical libraries (HikariCP,
  Javalin, jOOQ, Flyway, Caffeine, FreeMarker, generic SDK clients).
- Anti-pattern redirect table (`@RestController`, `@Transactional`,
  `@Scheduled`, `@Async`, `@Retryable`, `@Value`, etc.) so an agent
  reaches for the tiko-native primitive instead of searching for a
  Spring equivalent that doesn't exist.

## Reference shape

[`tiko-examples/15_quickstart`](./tiko-examples/15_quickstart) is the
canonical small-service shape the skill cites. Every `@Produces`
factory and `@EventHandler` in it maps to a named recipe in the long
doc.

## Extending the cookbook

When the canonical recipes don't cover the library a user wants to
integrate, read
[`.ai-skills/tiko-cookbook-extension/SKILL.md`](./.ai-skills/tiko-cookbook-extension/SKILL.md) —
the procedural skill for adding a new recipe. **Load-bearing rule: ask,
don't fabricate.** A wrong recipe locks an opinionated bad default into
the project's apparent conventions; honest "I don't know which X you
want here" beats invented X. Long-form companion:
[`docs/cookbook-extension.md`](./docs/cookbook-extension.md).
