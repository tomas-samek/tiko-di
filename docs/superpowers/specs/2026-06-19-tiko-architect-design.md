# tiko-architect — design

A `tiko-architect` skill: an **architecture-scrutiny gate** run before `tiko-release` that
performs a delta-driven, multi-agent go/no-go review of the framework's architecture against a
curated registry of load-bearing invariants, and emits a tiered verdict (GO / CONDITIONAL /
NO-GO) for the human releaser to act on.

It is the formalisation of the discipline this project has applied ad-hoc — the multi-agent
review that caught the #111 `ROUTE_TO_DLQ` generator-bypass, and the VISION check that killed
the distributed-bus idea — so that architectural scrutiny happens on **every** release rather
than only when someone reaches for it.

## Decisions (locked during brainstorming)

| Axis | Decision |
|---|---|
| **Scope** | Delta-driven, escalating to boundaries it touches. Bounded by what changed since the last release tag; pulls up to audit a whole boundary (a sealed hierarchy, an SPI, tiko-api's zero-dep rule, the scope model) wherever the delta crosses it. |
| **Reference truth** | A curated **invariants registry** (`docs/architecture-invariants.md`) — an explicit, enumerated, anchored list of load-bearing rules. |
| **Verdict** | Tiered + advisory-with-teeth: **GO** (clean) / **CONDITIONAL** (ship with documented risks + named follow-ups) / **NO-GO** (named architectural blockers to resolve first). The human decides; a NO-GO is a strong, specific stop signal. |
| **Run model** | Triage the delta → the invariants it plausibly touches, then fan out one agent per touched invariant (deep scrutiny + adversarial verification of suspected violations), then roll up. |
| **Vehicle** | A skill + a standalone registry doc (mirrors `tiko-release` + `docs/releasing.md`). |

## Artifacts

1. **`docs/architecture-invariants.md`** — the registry. One entry per invariant:
   `ID` · one-line statement · rationale · **anchor** (where the rule is stated/enforced:
   VISION / CLAUDE.md / code path) · "what a violation looks like." Independently citable —
   contributors, CLAUDE.md, and other agents can reference it, not just the gate.
2. **`.ai-skills/tiko-architect/SKILL.md`** — the procedure (below). A long-form companion may
   live at `docs/architect-skill.md` if the procedure needs more prose than the skill should carry.

## Seed registry

Drawn from VISION.md, CLAUDE.md, and decisions made this session. Reviewable and extensible —
the skill proposes additions (see step 5).

- **ARCH-1** — `tiko-api` stays **zero-dependency**. *Anchor:* CLAUDE.md module chain; tiko-api pom.
- **ARCH-2** — annotations are **SOURCE** retention unless the runtime genuinely reads them
  (`@PostConstruct`/`@PreDestroy` are the documented RUNTIME exceptions). *Anchor:* CLAUDE.md "Annotation Retention".
- **ARCH-3** — event dispatch is **type-keyed, never name-keyed** (`@EventTrigger.eventName` is a
  trace label, not a routing key). *Anchor:* docs/events.md trade-offs.
- **ARCH-4** — `ErrorContext` is **sealed**; adding a permit is intentionally compile-loud. *Anchor:* ErrorContext.java javadoc.
- **ARCH-5** — the **three-scope** model (SINGLETON / EVENT / PROTOTYPE); EVENT is single-frame in
  `0.x`. *Anchor:* CLAUDE.md "Scope Management"; project_scope_model_unification.
- **ARCH-6** — **transports are entry points to the mesh; distributed orchestration across
  processes is out of scope** (use a service mesh). *Anchor:* VISION.md "Explicitly out of scope".
- **ARCH-7** — **compile-time safety / no runtime reflection** in framework internals; prefer typed dispatch. *Anchor:* CLAUDE.md design philosophy; feedback_typed_dispatch.
- **ARCH-8** — **interfaces + composition** over impls + inheritance; new features compose existing primitives. *Anchor:* feedback_interfaces_and_composition.
- **ARCH-9** — framework output goes only through **`System.Logger`** (no logging-framework
  dependency, lazy-holder pattern). *Anchor:* CLAUDE.md "Logging in Framework Code".
- **ARCH-10** — cross-scope injection (SINGLETON ← EVENT) requires the shorter-scoped bean to
  implement an **interface** for proxy generation. *Anchor:* CLAUDE.md cross-scope rules.
- **ARCH-11** — every generated top-level type carries **`@Generated`** via the shared helper. *Anchor:* CLAUDE.md "Generated Code Markings".
- **ARCH-12** — restriction-style features default **permissive** (benevolent defaults); tightening is opt-in. *Anchor:* feedback_benevolent_defaults.
- **ARCH-13** — **docs describe shipped reality.** Agent-facing and user docs (CLAUDE.md, the
  bundled skills, cookbooks, README, VISION) match the actual `0.x` API — no documenting
  non-existent features, examples valid, contracts described as they behave. *Anchor:* the
  #401–#406 drift postmortem; the #408 archetype-doc-sync gate (which covers the bundled subset
  mechanically — ARCH-13 covers the broader doc surface).

## Procedure (the skill)

1. **Establish the delta.** `git diff <last vX.Y.Z tag>..HEAD` (resolve the tag via
   `git describe --tags --abbrev=0` or `gh release list`). Produce the changed-file list + a
   high-level summary of what the release does.
2. **Triage.** A cheap single pass mapping the delta → the registry invariants it plausibly
   touches (by file area, package, keyword) and the boundaries it crosses. Output: the in-scope
   invariant subset + crossed boundaries. Log what was triaged OUT (so nothing is silently skipped).
3. **Fan out.** One agent per touched invariant/boundary. Each agent reads its invariant + the
   relevant delta + the invariant's anchor (doc/code), judges **clean / eroded / violated**, and
   **adversarially verifies** any suspected violation (try to refute it before asserting it).
   Returns a structured finding: `{invariant, verdict, severity (blocker | concern | nit),
   evidence, suggested resolution}`.
4. **Roll up** to the tiered verdict:
   - **NO-GO** — any blocker-severity violation. List the blockers + required resolutions.
   - **CONDITIONAL** — concerns/erosions that are shippable with follow-ups. List the exact
     follow-up issues to file (title + one-line scope).
   - **GO** — clean (nits, if any, listed as non-blocking).
5. **Completeness + registry self-audit.** Two questions: (a) did triage miss an invariant the
   delta plausibly touches? (b) does each invariant still match its **anchor**, and did this
   release introduce a *new* architectural rule the registry should add? Surface registry
   additions/edits as part of the report so the registry self-evolves rather than silently aging.
6. **Emit the verdict report.** Verdict line + per-invariant findings + (for CONDITIONAL) the
   follow-ups to file + (from step 5) any proposed registry changes. The releaser acts on it.

## Where it sits

Invoked as the **first step of the release flow**, before the `tiko-release` Step-1 pre-flight.
`tiko-release`'s SKILL.md gets a pointer at the top: *"Run `/tiko-architect` first. Do not
proceed past a NO-GO without resolving the named architectural blockers; CONDITIONAL means file
the listed follow-ups, then proceed."*

**Distinct altitude from existing skills** (no overlap):

| Skill | Altitude |
|---|---|
| `code-review` | line-level correctness bugs in a diff |
| `security-review` | security of the diff |
| `tiko-release` pre-flight | release **mechanics** — BOM entries, secrets, NOTICE, version inputs |
| **`tiko-architect`** | **architecture** — abstraction coherence, invariant/VISION upholding, public-surface consistency, doc coherence |

## Validation (how we prove the skill works)

Two documented dry-runs, kept in the skill as worked examples:

1. **Retrospective on the 0.3.0 delta** (`v0.2.2..v0.3.0`): should cleanly clear what shipped
   while demonstrating a CONDITIONAL-style flag on a real subtlety (e.g. the #111 generator-bypass
   that the multi-agent review caught, or #395's THROW-resubmit residual).
2. **Seeded violation:** a throwaway commit adding a dependency to `tiko-api` must produce a
   **NO-GO** on **ARCH-1** with the offending pom line as evidence.

## Out of scope

- Replacing `code-review` / `security-review` / the `tiko-release` mechanics pre-flight — the
  architect sits above them, it doesn't absorb them.
- Auto-fixing violations or auto-filing the follow-up issues — the report names them; the human acts.
- Gating non-release work (PR-time). It is a **release** gate; per-PR architectural drift is the
  registry's job to make visible, not this skill's to enforce on every branch.
