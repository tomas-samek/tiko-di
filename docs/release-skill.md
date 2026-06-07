# Releasing — the procedure

> The pre-flight checks, the two version inputs to ask the user about,
> the Sonatype Portal manual gate, the post-publish verification, and
> the common traps. Lives alongside the reference doc
> [`docs/releasing.md`](./releasing.md), which is *what is published
> and how it's wired*; this doc and its agent-facing mirror are *what
> to do, in what order, and what to ask before each step*.

## Why this exists

The release pipeline has three categories of friction:

1. **Structural checks that surface late.** A new module gets added
   without a BOM entry; a shaded jar ships without the Apache NOTICE
   merge. The release succeeds at the workflow level but produces a
   half-broken artifact set.
2. **A manual human gate that's easy to forget.** The workflow uploads
   to Sonatype Portal **staging**. Until a human clicks **Publish**,
   Maven Central serves nothing. `0.1.0` sat in staging for three days
   in 2026-06 before anyone noticed.
3. **Two version inputs that an LLM agent might pick silently.** The
   workflow needs `release_version` and `next_snapshot`. Patch vs minor
   bump for the snapshot is the load-bearing decision; getting it
   wrong bakes the wrong cadence into git history.

The release skill captures all three. Pre-flight catches structural
gaps before dispatch; explicit step-4 narration prevents the staging
trap; the **"ask, don't fabricate"** rule prevents an agent from
picking versions without confirmation.

## The "ask, don't fabricate" rule

The load-bearing principle from
[`.ai-skills/tiko-release/SKILL.md`](../.ai-skills/tiko-release/SKILL.md):

> Never pick `release_version` or `next_snapshot` silently.

Both are user decisions with consequences that can't be undone after
the Central Portal Publish click. A skill that suggests a value
derived from main's current `*-SNAPSHOT` is fine — but the confirm step
is mandatory. The patch-vs-minor bump for `next_snapshot` is the most
common ask: `0.2.3-SNAPSHOT` vs `0.3.0-SNAPSHOT` look almost the same
in a diff but mean very different things for downstream users tracking
the project's cadence.

## Where the operational content lives

The agent-facing
[`.ai-skills/tiko-release/SKILL.md`](../.ai-skills/tiko-release/SKILL.md)
is the procedural source — seven numbered steps from pre-flight
through GitHub Release publication, with the specific verification
commands and the common-traps list. A human releasing manually follows
the same procedure.

This doc complements that procedural file. The reference for *what*
is published and *how* the workflow is wired stays in
[`docs/releasing.md`](./releasing.md).

## Three docs, three purposes

- **`docs/releasing.md`** — reference. What artifacts are published,
  what secrets the workflow needs, what each phase of the workflow
  does. The first place to read when something is unfamiliar.
- **`docs/release-skill.md`** (this file) — framing. Why the skill
  exists, what the load-bearing rule is, where to find the procedural
  source.
- **`.ai-skills/tiko-release/SKILL.md`** — procedural. The seven
  steps in order, with the verification commands inline. The file an
  agent reads to act.

Drift between any two of those is the failure mode worth guarding
against. If you change the release workflow, the reference doc updates
first; the skill follows; this doc only changes if the framing itself
moved.

## What this skill is not for

- Releasing user projects built on top of tiko. Every project has its
  own release conventions; the skill is tiko-di-specific.
- SNAPSHOT-only deploys (no Central upload, no version bump, no manual
  gate).
- Framework-internal version manipulation that doesn't publish.

## Cross-references

- [`docs/releasing.md`](./releasing.md) — comprehensive reference.
- [`.ai-skills/tiko-release/SKILL.md`](../.ai-skills/tiko-release/SKILL.md) —
  procedural source.
- [`.ai-skills/tiko-cookbook-extension/SKILL.md`](../.ai-skills/tiko-cookbook-extension/SKILL.md) —
  sibling "ask, don't fabricate" skill on a different surface.
