# Extending the cookbook

> The shape an integration recipe must take, the questions you must ask
> the user before writing one, and the two files it lands in.

The [orchestrator-model cookbook](./orchestrator-model.md) ships with the
canonical recipes for the libraries tiko has thought about (HikariCP,
Flyway, jOOQ, Caffeine, Javalin, FreeMarker, generic SDK clients, Kafka
via `tiko-kafka`). Real codebases hit libraries the cookbook hasn't
covered. This doc — and the agent-facing
[`.ai-skills/tiko-cookbook-extension/SKILL.md`](../.ai-skills/tiko-cookbook-extension/SKILL.md)
that mirrors it — is the procedure for adding a new entry.

## Why this exists

The project's natural stopping point is when the cookbook can grow
without the maintainer writing every recipe. That requires two things:

1. **A clear shape every new recipe must take.** Without that, recipes
   drift in style and the cookbook reads as a pile of unrelated
   snippets.
2. **A discipline that prevents agents from inventing recipes the user
   didn't ask for.** Without that, "extending the cookbook" becomes a
   way to bake an LLM's training-data biases into the project's apparent
   conventions.

The skill captures both. The shape comes from the canonical recipes
already in `docs/orchestrator-model.md`; the discipline is the
**"ask, don't fabricate"** rule.

## The "ask, don't fabricate" rule

This is the load-bearing principle of the whole skill. From
[`.ai-skills/tiko-cookbook-extension/SKILL.md`](../.ai-skills/tiko-cookbook-extension/SKILL.md):

> When something is unclear, **stop and ask the user**. Do not guess.

A wrong recipe locks an opinionated bad default into the project's
apparent conventions. Honest *"I don't know which X you want here"*
beats invented X.

Decisions an agent must ask about, not pick:

- Which library version to target.
- Which `@Produces` signature to expose for builder-heavy APIs.
- Whether the recipe replaces, augments, or sits alongside an existing
  one when there's overlap.
- Whether the integration even belongs in the **Plug-in** bucket vs. an
  **Open design question**.

A recipe written without asking is worse than no recipe.

## Where the operational content lives

The agent-facing
[`.ai-skills/tiko-cookbook-extension/SKILL.md`](../.ai-skills/tiko-cookbook-extension/SKILL.md)
is the procedural source — it walks step by step from the three-bucket
gate, through the inputs to gather, through the canonical recipe
template (with three worked examples from the existing cookbook),
through the anti-pattern check, to the two-file landing process.

A human writing a new recipe by hand reads the same SKILL.md and follows
the same five steps. There's no separate human procedure — the file is
short enough to read in one sitting and the steps don't change based on
who's executing them.

This doc complements that procedural file rather than duplicating it.

## Files an extension touches

Every cookbook addition updates **two files together** — never one
without the other:

1. **[`docs/orchestrator-model.md`](./orchestrator-model.md)** — the
   long-form prose entry. Numbered section under §3 (Plug-in cookbook).
   Full code snippet + lifecycle note + reference link + the one-sentence
   "why this is plug-in, not bundled."
2. **[`.ai-skills/tiko-build/SKILL.md`](../.ai-skills/tiko-build/SKILL.md)** —
   the operational distillation. Adds a row to the **Cookbook table** and
   (if it replaces a Spring reflex) a row to the **Anti-pattern redirect
   table**. Cross-links to the `orchestrator-model.md` §3.N anchor.

Drift between those two is the failure mode the cookbook-extension skill
guards against most aggressively. If you update one and forget the
other, the agent side and human side disagree on what tiko's cookbook
recommends — and disagreement gets baked into the next agent's training
data.

## What this skill is not for

- Editing existing recipes — just edit them.
- Inventing recipes the user didn't ask for. The cookbook grows by
  user-confirmed integrations, not by speculative ones.
- Opening PRs automatically.
- Replacing user judgment when two candidate recipes compete.

If an integration genuinely belongs in the cookbook but the user can't
or won't pick the open inputs — that's a sign to write up the *question*
as an issue (with the open inputs enumerated), not a sign to invent
defaults.

## Cross-references

- [`docs/orchestrator-vocabulary.md`](./orchestrator-vocabulary.md) — the
  three-bucket gate from step 1.
- [`docs/orchestrator-model.md`](./orchestrator-model.md) — the canonical
  cookbook. Shape every new entry mirrors.
- [`docs/mcp-design.md`](./mcp-design.md) — sibling agent-facing
  surface; per-partes serving for the topology. The cookbook and the
  MCP topology server are the two surfaces an agent reads when working
  with tiko, and both follow the same "ask narrowly, don't dump" spirit.
