# tiko-architect Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a `tiko-architect` skill — an architecture-scrutiny gate run before `tiko-release` that checks the release delta against a curated invariants registry and emits a tiered GO / CONDITIONAL / NO-GO verdict.

**Architecture:** Two artifacts plus wiring. `docs/architecture-invariants.md` is the curated registry (the reference truth). `.ai-skills/tiko-architect/SKILL.md` is the procedure: resolve the delta since the last release tag → triage it against the registry → fan out one sub-agent per touched invariant (deep scrutiny + adversarial verify) → roll up to a tiered verdict. `tiko-release` gets a pointer so the gate runs first. Two dry-runs validate the skill end-to-end and become its worked examples.

**Tech Stack:** Markdown (skill + registry docs), `git`/`gh` for the delta and dry-runs, the Agent tool for fan-out. No compiled code.

## Global Constraints

- Full design is the source of truth: `docs/superpowers/specs/2026-06-19-tiko-architect-design.md`. Copy invariant statements and procedure steps from it verbatim; do not reinvent.
- Registry anchors must be **real** — every `Anchor:` must resolve to an existing VISION.md section, CLAUDE.md section, code path, or named memory. Verify each before committing.
- The skill must not absorb `code-review` (line bugs), `security-review`, or `tiko-release` **mechanics** (BOM/secrets/NOTICE/versions). It is the **architecture** altitude only.
- Verdict is **advisory-with-teeth**: GO / CONDITIONAL (ship + file named follow-ups) / NO-GO (named blockers). The skill **names** follow-ups/blockers; it never auto-fixes or auto-files.
- Skill paths are exact: registry at `docs/architecture-invariants.md`; skill at `.ai-skills/tiko-architect/SKILL.md`.
- Commit style: single-line conventional-commits subject, no body (per repo convention).
- Branch: `docs/tiko-architect-spec` (already holds the spec). Stay on it.

---

### Task 1: The invariants registry

**Files:**
- Create: `docs/architecture-invariants.md`

**Interfaces:**
- Produces: invariant IDs `ARCH-1` … `ARCH-13`, each addressable by ID. Task 2's skill and the fan-out agents reference these IDs.

- [ ] **Step 1: Define the acceptance check (what "done" means for this file)**

The file must contain exactly the 13 invariants from spec §"Seed registry", each as an entry with five fields: `ID` · **statement** · **rationale** · **Anchor:** (real source) · **Violation looks like:** (one concrete example). A short intro paragraph states the registry's purpose and that `tiko-architect` checks the release delta against it.

- [ ] **Step 2: Write the registry**

Use this entry format for each invariant; fill statement from spec §"Seed registry" verbatim:

```markdown
### ARCH-1 — tiko-api stays zero-dependency

**Statement.** Nothing in `tiko-api` may add a third-party (or cross-module) runtime dependency.

**Rationale.** tiko-api is the zero-dep core every other module and consumer depends on; a dep here propagates to everyone and breaks the cold-start/compile-time-safety pitch.

**Anchor.** CLAUDE.md "Module Dependencies (core chain)"; `tiko-api/pom.xml`.

**Violation looks like.** A `<dependency>` added to `tiko-api/pom.xml`, or an `import` of a non-JDK / non-`io.tiko` type in `tiko-api/src/main`.
```

Author all 13 (`ARCH-1`…`ARCH-13`) with statements copied from the spec. `ARCH-13` (docs describe shipped reality) must note it complements the #408 archetype-doc-sync gate (which covers the bundled subset mechanically).

- [ ] **Step 3: Verify every anchor resolves**

For each invariant, confirm its `Anchor:` exists:

```bash
grep -rn "Explicitly out of scope" docs/VISION.md          # ARCH-6
grep -n "Annotation Retention" CLAUDE.md                    # ARCH-2
grep -n "Generated Code Markings" CLAUDE.md                 # ARCH-11
ls tiko-api/pom.xml                                         # ARCH-1
```
Expected: each returns a hit / the path exists. Fix any anchor that doesn't resolve (correct the reference, don't invent one).

- [ ] **Step 4: Commit**

```bash
git add docs/architecture-invariants.md
git commit -m "docs(architecture): add curated invariants registry (ARCH-1..13)"
```

---

### Task 2: The tiko-architect skill

**Files:**
- Create: `.ai-skills/tiko-architect/SKILL.md`

**Interfaces:**
- Consumes: the `ARCH-*` IDs from `docs/architecture-invariants.md` (Task 1).
- Produces: the `/tiko-architect` skill, discoverable by its frontmatter `description`; emits a verdict report (GO / CONDITIONAL / NO-GO) that Task 3's `tiko-release` pointer references.

- [ ] **Step 1: Define the acceptance check**

The skill must: (a) carry YAML frontmatter `name: tiko-architect` + a `description` that makes it the obvious choice "before cutting a release, run an architecture go/no-go" (so it's surfaced like the other skills); (b) document the 6-step procedure from spec §"Procedure"; (c) define the tiered verdict + the report shape; (d) state the altitude boundary vs `code-review` / `security-review` / `tiko-release` mechanics; (e) reference `docs/architecture-invariants.md` as the registry. No invariant content is duplicated into the skill — it points at the registry by ID.

- [ ] **Step 2: Write the skill frontmatter + intro**

```markdown
---
name: tiko-architect
description: Architecture go/no-go gate to run BEFORE cutting a tiko-di release. Checks the release delta (since the last vX.Y.Z tag) against the curated invariants registry (docs/architecture-invariants.md) and returns GO / CONDITIONAL / NO-GO with named blockers and follow-ups. Use before tiko-release; not for per-PR review (that's code-review).
---

# tiko-architect

The architecture altitude of release scrutiny. `code-review` finds line-level bugs;
`tiko-release` pre-flight checks release mechanics (BOM, secrets, NOTICE, versions);
**this** asks whether the release is architecturally sound to ship — do the abstractions still
cohere, are the invariants and VISION upheld, is the public surface consistent, do the docs
describe shipped reality. Advisory, but a NO-GO is a hard, specific stop.
```

- [ ] **Step 3: Write the procedure (the 6 steps)**

Transcribe spec §"Procedure" into concrete, runnable steps. Step 1 (delta) must give the exact commands:

```markdown
## 1. Establish the delta
LAST=$(git describe --tags --abbrev=0)         # or: gh release list --limit 1
git diff "$LAST"..HEAD --stat                  # changed files
git log "$LAST"..HEAD --oneline                # what the release does
```

Then §2 Triage (map delta → touched `ARCH-*`; log what was triaged OUT), §3 Fan out (one
Agent per touched invariant; each returns `{invariant, verdict: clean|eroded|violated,
severity: blocker|concern|nit, evidence, resolution}`; adversarially verify suspected
violations), §4 Roll up (NO-GO if any blocker; CONDITIONAL if shippable concerns + list
follow-ups; GO if clean), §5 Completeness + registry self-audit (missed invariant? anchors
still valid? new rule to add to the registry?), §6 Verdict report.

- [ ] **Step 4: Write the verdict-report template**

Include a copy-paste report shape so every run looks the same:

```markdown
## Verdict report shape
**VERDICT: GO | CONDITIONAL | NO-GO**

| Invariant | Verdict | Severity | Evidence |
|---|---|---|---|
| ARCH-N | clean/eroded/violated | blocker/concern/nit | file:line + one line |

- **Blockers (NO-GO):** …resolve before release
- **Follow-ups to file (CONDITIONAL):** …title + one-line scope
- **Registry changes proposed:** …add/edit ARCH-N
```

- [ ] **Step 5: Verify internal consistency**

```bash
# Every ARCH-id the skill mentions must exist in the registry:
grep -oE "ARCH-[0-9]+" .ai-skills/tiko-architect/SKILL.md | sort -u
grep -oE "^### ARCH-[0-9]+" docs/architecture-invariants.md | grep -oE "ARCH-[0-9]+" | sort -u
```
Expected: the skill's referenced IDs are a subset of the registry's. Fix mismatches.

- [ ] **Step 6: Commit**

```bash
git add .ai-skills/tiko-architect/SKILL.md
git commit -m "docs(skill): add tiko-architect architecture go/no-go gate"
```

---

### Task 3: Wire it into the release flow + discovery

**Files:**
- Modify: `.ai-skills/tiko-release/SKILL.md` (add the pointer near the top, before "Step 1 — pre-flight")
- Modify: `.ai-skills/SKILL.md` (the skill router/index — add a tiko-architect row if it enumerates skills)

**Interfaces:**
- Consumes: the `/tiko-architect` skill (Task 2).

- [ ] **Step 1: Add the gate pointer to tiko-release**

After the front-matter intro of `.ai-skills/tiko-release/SKILL.md`, add:

```markdown
## Step 0 — architecture go/no-go (run first)

Before any pre-flight, run **`/tiko-architect`** against the release delta. Do **not** proceed
past a **NO-GO** without resolving the named architectural blockers. A **CONDITIONAL** means
file the listed follow-ups, then proceed. A **GO** clears this step. See
[`.ai-skills/tiko-architect/SKILL.md`](../tiko-architect/SKILL.md).
```

- [ ] **Step 2: List the skill in the router (if applicable)**

Check whether `.ai-skills/SKILL.md` enumerates the available skills:

```bash
grep -n "tiko-release\|tiko-build" .ai-skills/SKILL.md
```
If it lists them, add a `tiko-architect` line in the same format. If it does not enumerate skills, skip this step (note it in the commit).

- [ ] **Step 3: Verify the cross-references resolve**

```bash
grep -n "tiko-architect" .ai-skills/tiko-release/SKILL.md   # pointer present
ls .ai-skills/tiko-architect/SKILL.md                       # target exists
```
Expected: both succeed.

- [ ] **Step 4: Commit**

```bash
git add .ai-skills/tiko-release/SKILL.md .ai-skills/SKILL.md
git commit -m "docs(skill): gate tiko-release behind tiko-architect go/no-go"
```

---

### Task 4: Validation dry-run #1 — 0.3.0 retrospective

**Files:**
- Modify: `.ai-skills/tiko-architect/SKILL.md` (append a "Worked example: 0.3.0" section)

- [ ] **Step 1: Run the skill against the 0.3.0 delta**

Execute the procedure with `LAST=v0.2.2`, `HEAD=v0.3.0`:

```bash
git diff v0.2.2..v0.3.0 --stat
git log v0.2.2..v0.3.0 --oneline
```
Triage → fan out → roll up exactly as the skill prescribes.

- [ ] **Step 2: Confirm the expected verdict**

Expected: **GO or CONDITIONAL** — it must *clear what actually shipped* (0.3.0 is released and sound) while demonstrating the skill's value by surfacing at least one real subtlety as a CONDITIONAL-level note (e.g. the #111 `ROUTE_TO_DLQ` generator-bypass class, or #395's THROW-resubmit residual). A NO-GO here would mean the skill is mis-calibrated (too strict) — recheck severity rules.

- [ ] **Step 3: Capture the run as a worked example**

Append a condensed transcript (the verdict report shape) under a "Worked example: 0.3.0 retrospective" heading in the skill, so future runs have a calibration reference.

- [ ] **Step 4: Commit**

```bash
git add .ai-skills/tiko-architect/SKILL.md
git commit -m "docs(skill): tiko-architect worked example — 0.3.0 retrospective (GO/CONDITIONAL)"
```

---

### Task 5: Validation dry-run #2 — seeded violation → NO-GO

**Files:**
- Modify: `.ai-skills/tiko-architect/SKILL.md` (append a "Worked example: seeded violation" section)

- [ ] **Step 1: Seed a throwaway violation**

On a scratch commit, add a dependency to `tiko-api` (violating ARCH-1):

```bash
git checkout -b scratch/arch-seed
# add a <dependency> block (e.g. commons-lang3) to tiko-api/pom.xml
git commit -am "scratch: seed ARCH-1 violation (throwaway)"
```

- [ ] **Step 2: Run the skill against the seeded delta**

Run the procedure with the delta = this scratch commit. Triage must route to ARCH-1; the ARCH-1 agent must flag it.

- [ ] **Step 3: Confirm NO-GO on ARCH-1**

Expected: **NO-GO**, with ARCH-1 marked `violated` / `blocker` and the added `<dependency>` pom line as evidence. If it does not produce NO-GO, the skill's ARCH-1 detection or the triage is wrong — fix the skill, not the seed.

- [ ] **Step 4: Tear down the seed; capture the example**

```bash
git checkout docs/tiko-architect-spec
git branch -D scratch/arch-seed
```
Append the condensed NO-GO report under a "Worked example: seeded ARCH-1 violation" heading in the skill.

- [ ] **Step 5: Commit**

```bash
git add .ai-skills/tiko-architect/SKILL.md
git commit -m "docs(skill): tiko-architect worked example — seeded ARCH-1 violation (NO-GO)"
```

---

## Self-Review

**Spec coverage:** registry (Task 1) ✓ · skill procedure + verdict + altitude boundary (Task 2) ✓ · tiko-release pointer + discovery (Task 3) ✓ · two validation dry-runs, incl. ARCH-13 doc-coherence exercised via the 0.3.0 retrospective (Tasks 4–5) ✓ · "out of scope" (no auto-fix/auto-file, not a PR gate) encoded in Global Constraints + Task 2 frontmatter ✓.

**Placeholder scan:** no "TBD"/"handle edge cases"/"write tests for the above" — each task names exact files, real verification commands, and copy-paste content shapes; full invariant/procedure prose is sourced verbatim from the cited spec (DRY) rather than re-invented.

**Type consistency:** the `ARCH-1`…`ARCH-13` IDs and the finding shape `{invariant, verdict, severity, evidence, resolution}` are used identically in Tasks 1, 2, 4, 5; verdict tiers GO/CONDITIONAL/NO-GO consistent throughout.
