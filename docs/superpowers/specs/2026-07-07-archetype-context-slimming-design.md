# Archetype context slimming + navigable skill chunks

**Date:** 2026-07-07
**Driver:** the 2026-07-04 token/price analysis (context bytes multiply
across cache-read turns) + the #269 re-run evidence on how models actually
navigate docs.
**Branch:** `docs/archetype-context-slimming`

## Context

Every scaffolded tiko project ships ~60KB of agent-facing markdown; the
default-read surface (archetype `CLAUDE.md` + `tiko-build` `SKILL.md`) is
~38.5KB and grew with the #422 signature sheet. Benchmark evidence:

- Agents re-read context across 30–100 tool-call turns at cache-read
  prices — static payload is a cost multiplier, not a one-time cost.
- `tiko-cookbook-extension/SKILL.md` (12.5KB) ships in every scaffold but
  teaches contributing recipes to *tiko's own repo* — dead weight for app
  builders.
- Haiku (7/16 run) read only `CLAUDE.md` and never followed the generic
  skill pointer; Sonnet (15/16) read whatever the task needed. Chunking
  must therefore keep weak-model essentials inline and make every hop
  *named and motivated*, not generic.

Decisions from brainstorm: balanced ~10KB `CLAUDE.md` spine (patterns
mostly relocate, constructor injection stays); chunking applies to
`tiko-build` + archetype `CLAUDE.md` only (repo-only skills are not a
cost lever); relocation over deletion, with an audit table.

## Component 1 — `tiko-build` skill: spine + `reference/` chunks

Applies identically to the canonical `.ai-skills/tiko-build/` and the
archetype-bundled copy (the sync gate keeps them equal; see Component 4).

```
.ai-skills/tiko-build/
  SKILL.md                 # spine, target ≤ 8KB
  reference/
    api-signatures.md      # the entire "API signature sheet" section (#422)
    kafka.md               # "Kafka transport: write this shape first" incl.
                           # the "Testing Kafka bridges" recipe
    config.md              # "Typed config: keys are exact" (incl. key rules;
                           # the tiko.kafka.* key table stays with the sheet
                           # in api-signatures.md — one home, kafka.md and
                           # config.md link to it)
    events.md              # "Imperative publish & keeping the process alive"
```

**Spine keeps, verbatim from today's file:** `## The rule`,
`## When in doubt, ask`, `## Scaffolding shape`, `## Cookbook table`,
`## Anti-pattern redirect table`, `## What this skill does not cover`,
`## Need a recipe the cookbook doesn't have?` (trimmed to a two-line
upstream pointer).

**Spine gains a navigation map** — a short table directly after
`## The rule`:

| file | read when |
|---|---|
| `reference/api-signatures.md` | writing any import or unsure of a signature/attribute/config key |
| `reference/kafka.md` | consuming/producing Kafka, or writing the Kafka integration test |
| `reference/config.md` | declaring `@Configuration` records or override YAML |
| `reference/events.md` | publishing events imperatively, or a headless/daemon main |

**Chunks:** each begins with a one-line scope header ("Read this when …")
and contains its section moved verbatim (content edits limited to: fixing
now-internal cross-references, and absorbing relocated `CLAUDE.md`
patterns per Component 2). Cookbook-table rows that referenced skill
sections (e.g. "skill §4.2") now name the chunk files.

## Component 2 — Archetype `CLAUDE.md`: balanced spine (~10KB)

**Keeps:** identity/intro + skill pointer block, `## Scopes`,
`## Annotations cheat-sheet` (all three subsections),
`### Exact packages` table (#422), `## Rules`, the
`### Constructor injection` pattern (the one canonical worked pattern),
build-commands section, a condensed MCP note (2–3 lines + pointer to the
existing `docs` on GitHub), and a **navigation map** listing the four
skill chunks with the same "read when" lines as the spine.

**Relocates (audit table required in the plan — every moved block gets a
target):** the worked patterns other than constructor injection
(`@Named` disambiguation, `@Produces` factory, lifecycle hooks,
cross-scope proxy, events) and the YAML configuration walkthrough. Rule:
if the receiving chunk (or the spine's cookbook table) already covers the
content, the `CLAUDE.md` copy is dropped as duplication; if not, the
block moves into the matching chunk (`config.md`, `events.md`, or the
spine's scaffolding section). Nothing is deleted without a
named surviving home; the plan's audit table lists
`block → destination (moved | already-covered-by <anchor>)`.

## Component 3 — Drop `tiko-cookbook-extension` from the archetype

- Delete `tiko-archetype/src/main/resources/archetype-resources/.ai-skills/tiko-cookbook-extension/`.
- Remove its entry from the sync mechanism (Component 4) and from the
  archetype's `.ai-skills/SKILL.md` index; the index gains one line:
  contributing a recipe upstream → the canonical skill on GitHub
  (absolute URL).
- The canonical `.ai-skills/tiko-cookbook-extension/` in the repo is
  untouched.
- Check `archetype-metadata.xml` (or equivalent fileset config) so the
  deleted directory is not referenced.

## Component 4 — Sync gate: file → directory

`ArchetypeDocSync` + `ArchetypeBundledSkillsInSyncTest`
(tiko-archetype test sources) currently sync/compare one `SKILL.md` per
entry in `SYNCED_SKILLS`. Change to per-skill **directory** semantics:

- For each synced skill, discover every `*.md` under the canonical skill
  directory (recursively — `reference/` included) and require the bundled
  counterpart to equal `forArchetype(canonical)` per file.
- A canonical file with no bundled counterpart (or vice versa) fails the
  gate with a message naming the missing path.
- `SYNCED_SKILLS` drops `tiko-cookbook-extension`.
- `ArchetypeDocSync.main` regeneration writes the whole directory.
- The existing `forArchetype` link transformation applies per file
  unchanged; new relative links *between* skill files (spine → chunk) are
  relative within the skill directory and must survive the
  transformation untouched (they resolve in both locations).

## Acceptance

- Default-read payload (archetype `CLAUDE.md` + `tiko-build/SKILL.md`
  spine) **≤ 18KB** total; report exact before/after byte counts in the
  PR body (before: 17,888 + 20,665 = 38,553 bytes).
- Archetype `.ai-skills` total shrinks by ≥ the 12,487-byte
  cookbook-extension deletion.
- Zero content loss: every removed block appears in the audit table with
  a surviving home.
- Full reactor `mvn test` green — including the extended directory sync
  gate and spotless.
- All pointer files (`AGENTS.md`, `.cursor/rules/tiko.md`,
  `.junie/guidelines.md`, `.github/copilot-instructions.md`) checked for
  references to moved sections; stale references fixed.
- Repo `README.md` and repo `CLAUDE.md` skill pointers still resolve
  (path `.ai-skills/tiko-build/SKILL.md` unchanged).
- The benchmark methodology's "with-skill (auto-loaded)" definition
  (`.ai-skills/tiko-build/SKILL.md` present) still holds unchanged.

## Risk, stated

Chunking bets that models follow **named** hops ("writing an import →
read `reference/api-signatures.md`") even though the Haiku run ignored a
generic pointer. Mitigation: the balanced spines keep weak-model
essentials (packages table, cheat-sheet, scopes, rules, cookbook +
anti-pattern tables) inline. The bet is measured by the next benchmark
cell run — if with-skill scores drop, the revert is cheap (chunks
re-concatenate).

## Out of scope

- Chunking repo-only skills (`tiko-architect`, `tiko-release`, canonical
  `tiko-cookbook-extension`).
- Repo-root `CLAUDE.md` (repo-development context, not shipped).
- Any content rewrites beyond relocation + cross-reference fixes.
- Benchmark re-run (follow-up measurement, separate task).

## Delivery

One PR off `main`, commits split by concern (skill chunking + gate;
CLAUDE.md slim; cookbook-extension drop), conventional single-line
messages. PR body carries the byte-count table and the relocation audit
summary.
