---
name: tiko-architect
description: Architecture go/no-go gate to run BEFORE cutting a tiko-di release. Checks the release delta (since the last vX.Y.Z tag) against the curated invariants registry (docs/architecture-invariants.md) and returns GO / CONDITIONAL / NO-GO with named blockers and follow-ups. Use before tiko-release; not for per-PR review (that's code-review).
---

# tiko-architect

The architecture altitude of release scrutiny. `code-review` finds line-level bugs;
`security-review` audits the diff for security issues; `tiko-release` pre-flight checks release
mechanics (BOM, secrets, NOTICE, versions); **this** asks whether the release is
architecturally sound to ship — do the abstractions still cohere, are the invariants and VISION
upheld, is the public surface consistent, do the docs describe shipped reality. Advisory, but a
NO-GO is a hard, specific stop.

| Skill | Altitude |
|---|---|
| `code-review` | line-level correctness bugs in a diff |
| `security-review` | security of the diff |
| `tiko-release` pre-flight | release mechanics — BOM entries, secrets, NOTICE, version inputs |
| **`tiko-architect`** | **architecture** — abstraction coherence, invariant/VISION upholding, public-surface consistency, doc coherence |

The invariants registry is `docs/architecture-invariants.md` (ARCH-1 … ARCH-13). This skill
never duplicates invariant content — it references invariants by ID and directs agents to read
their entry from the registry.

**Out of scope.** This skill does not replace `code-review`, `security-review`, or the
`tiko-release` mechanics pre-flight. It does not auto-fix violations or auto-file issues — the
report names them; the human acts. It is a **release** gate, not a per-PR review.

---

## Procedure

### Step 1 — Establish the delta

Resolve the last release tag and produce the changed-file list plus a high-level summary of
what the release does:

```bash
LAST=$(git describe --tags --abbrev=0)   # or: gh release list --limit 1
git diff "$LAST"..HEAD --stat            # changed files
git log "$LAST"..HEAD --oneline          # what the release does
```

Output from this step: a list of changed files grouped by module, and a one-paragraph summary
of the release's purpose (derived from the commit log).

### Step 2 — Triage

A single cheap pass: map the delta to the subset of invariants in `docs/architecture-invariants.md`
that the delta plausibly touches, based on file area, package, keyword, and which boundaries
the delta crosses (e.g. a change in `tiko-api/pom.xml` touches ARCH-1; a new `@Retention`
touches ARCH-2; new logging code touches ARCH-9; new generated type touches ARCH-11).

Output:

- **In-scope invariants**: the ARCH-IDs to fan out to (e.g. ARCH-1, ARCH-7, ARCH-11).
- **Triaged-out invariants**: every ARCH-ID not in scope, with a one-line reason (e.g.
  "ARCH-6 — no new transport modules; ARCH-4 — ErrorContext unchanged"). Nothing is silently
  skipped — every invariant must appear in exactly one list.

### Step 3 — Fan out

Launch one agent per touched invariant. Each agent:

1. Reads the invariant's full entry from `docs/architecture-invariants.md` (by ARCH-ID).
2. Reads the relevant portion of the delta (the files the invariant's anchor covers).
3. Reads the invariant's anchor source (the CLAUDE.md section, code path, or doc cited in
   the registry entry).
4. Judges: **clean** (no evidence of violation), **eroded** (weakened but not broken), or
   **violated** (clearly broken).
5. **Adversarially verifies any suspected violation**: tries to refute the finding before
   asserting it (e.g. checks whether an apparent reflection call is in a test-only path, or
   whether a new annotation has generated-code consumers that justify RUNTIME retention).
6. Returns a structured finding:

```
{
  invariant:   "ARCH-N",
  verdict:     "clean" | "eroded" | "violated",
  severity:    "blocker" | "concern" | "nit",
  evidence:    "file:line — one-line description",
  resolution:  "what must change (for blocker/concern) or suggested follow-up (for nit)"
}
```

Severity mapping: `violated` → `blocker`; `eroded` → `concern`; `clean` findings may carry
`nit` if a minor drift was observed but does not affect the verdict.

### Step 4 — Roll up

Aggregate all findings into a tiered verdict:

- **NO-GO** — one or more findings with `severity: blocker`. List each blocker and its
  required resolution. Do not proceed with the release until these are resolved.
- **CONDITIONAL** — no blockers, but one or more `concern`-severity findings. The release
  may ship; list the exact follow-up issues to file (title + one-line scope) before or
  immediately after the release.
- **GO** — all findings are `clean` or `nit`. List nits as non-blocking observations.

### Step 5 — Completeness and registry self-audit

Two questions to answer before emitting the report:

**(a) Completeness check.** Did triage miss an invariant the delta plausibly touches? Re-read
the triaged-out list and the delta summary together. Flag any invariant that was triaged out
but now appears relevant given the full picture.

**(b) Registry freshness.** Does each touched invariant's **Anchor** still resolve to a real
source? Did this release introduce a new architectural rule — a new design decision, a new
constraint codified in code or docs — that the registry should capture as a new ARCH-N entry?
Surface proposed additions or edits as part of the verdict report so the registry self-evolves
rather than silently aging.

### Step 6 — Emit the verdict report

Produce the verdict report using the template below. The releaser reads it and acts:

- On **NO-GO**: resolve the named blockers, then re-run `tiko-architect`.
- On **CONDITIONAL**: file the named follow-up issues, then proceed to `tiko-release`.
- On **GO**: proceed to `tiko-release`.

---

## Verdict report shape

```
**VERDICT: GO | CONDITIONAL | NO-GO**

Release delta: vX.Y.Z-prev..HEAD
Invariants in scope: ARCH-N, ARCH-M, …
Invariants triaged out: ARCH-P (reason), ARCH-Q (reason), …

| Invariant | Verdict   | Severity | Evidence                         |
|-----------|-----------|----------|----------------------------------|
| ARCH-N    | clean     | —        | —                                |
| ARCH-M    | eroded    | concern  | path/to/File.java:42 — one line  |
| ARCH-P    | violated  | blocker  | tiko-api/pom.xml:17 — one line   |

**Blockers (NO-GO):** resolve before release
- ARCH-P: <what must change>

**Follow-ups to file (CONDITIONAL):** title + one-line scope
- "<Issue title>" — <one-line scope>

**Registry changes proposed:**
- Add ARCH-N+1: <statement> — <anchor>
- Edit ARCH-N: update anchor to <new reference>
```
