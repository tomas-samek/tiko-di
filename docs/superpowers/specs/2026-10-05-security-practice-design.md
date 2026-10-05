# Security practice: public foundation and the tiko-security skill

**Status:** approved (design)
**Relates to:** `docs/architecture-invariants.md` + `.ai-skills/tiko-architect` (the registry-plus-gate pattern this mirrors), `.ai-skills/tiko-release`, the public-framing rules (milestone #11)

## Goal

Make care for security visible to people evaluating Tiko, and back it with a real practice.

This covers the security of **Tiko's own code and supply chain**: how the framework handles untrusted input, its dependencies, and its releases. It is **not** a runtime security module for users' applications. Authentication, authorization and similar stay in the "plug in" bucket, as already decided.

Keep it proportionate to a small, solo-maintained, pre-1.0 framework.

## Decisions (from brainstorming)

| Topic | Decision |
|---|---|
| Audience | Both: a public signal for adopters, backed by an internal practice |
| Approach | Threat-model-anchored: one `docs/security-model.md` registry drives every mode of the skill |
| Skill modes | Release gate, PR review on risky areas, periodic audit, advisory handling |
| Delivery | Two steps, two PRs: Step 1 public foundation, Step 2 model + skill |
| Report response | Acknowledge within 7 days; fix/advisory best effort, coordinated with the reporter, 90-day disclosure as the outer bound |
| PR dependency gate | GitHub Dependency Review (`actions/dependency-review-action`); Dependabot alerts (already on) cover existing dependencies |
| Badge | None for now (OpenSSF Scorecard possible later) |

## Current state (verified 2026-10-05)

- **Repo settings:** Dependabot security updates, secret scanning and push protection are enabled. GitHub private vulnerability reporting is **enabled**.
- **Missing:** no `SECURITY.md`, no code scanning, no PR dependency gate.
- **Code facts the public text may rely on:**
  - YAML config is loaded with `SafeConstructor` (`tiko-config/.../YamlLoader.java`).
  - The Kafka JSON mapper (`JsonKafkaSerializer`) uses a plain `ObjectMapper`, with no default typing.
  - No `ObjectInputStream`, `ProcessBuilder` or `Runtime.exec` in main code.
  - `tiko-mcp` is stdio-only (`args[0]` = project root, no network listener).
  - Releases are GPG-signed (`maven-gpg-plugin`).
  - Jackson is shaded and relocated inside `tiko-kafka`, so a Jackson fix reaches users only through a Tiko release.
- **Known gaps** (Step 2's first audit files them as issues):
  - `tiko-config/.../CompositeCoercers.java` logs a deduplicated `Set` config value at WARNING (`duplicate value '…' deduped`). Config values may be secrets.
  - GitHub Actions are pinned by version tag (`@v4`), not commit SHA.

## Step 1: public foundation

### `SECURITY.md` (repo root)

Sections:

1. **Supported versions.** Only the latest released minor (`0.5.x` today). Pre-1.0, fixes go into the next release; there are no backports.
2. **Reporting a vulnerability.** Use GitHub's private reporting ("Report a vulnerability" on the Security tab), never a public issue. Include the affected module and version, a reproduction, and the impact.
3. **What to expect:**
   - acknowledgement within 7 days;
   - triage and fix best effort, coordinated with the reporter;
   - public disclosure through a GitHub Security Advisory (with a CVE where warranted) once a fixed release is on Maven Central, at most 90 days after the report;
   - credit to the reporter unless they decline.
4. **Scope.**
   - **In:** the published `io.github.tomas-samek:tiko-*` artifacts, the code the processors generate into user builds, and projects created by `tiko-archetype`.
   - **Out:** `tiko-examples/`, `comparisons/`, and the security of applications built on Tiko (for example the auth libraries users plug in).
5. **Bundled dependencies.** `tiko-kafka` shades Jackson, so a vulnerability in it is fixed by a Tiko release. Upgrading Jackson in your own build doesn't change the shaded copy.

### Code scanning: `.github/workflows/codeql.yml`

- **Triggers:** `pull_request` and `push` to `main`, plus a weekly `schedule`.
- **Permissions:** `security-events: write`, `contents: read`, `actions: read`.
- **Analysis:** language `java-kotlin`, queries `security-extended`. JDK 21 via `actions/setup-java`. Manual build `mvn -B -DskipTests package`, so annotation-processor-generated sources are part of the analysis.
- Findings appear in the Security tab. No branch-protection change is part of this design.

### PR dependency gate: `.github/workflows/dependency-review.yml`

- **Trigger:** `pull_request`. Uses `actions/dependency-review-action` with `fail-on-severity: high`.
- It fails a PR that adds or upgrades to a dependency with a known high or critical vulnerability in the GitHub Advisory Database.

### Public text

- **`README.md`:** a short "Security" section. Report privately (link to `SECURITY.md`), what is scanned (CodeQL, dependency review, Dependabot), and the safe defaults listed under "Code facts" above. Only claims verified in code.
- **`site/index.html`:** a "Security policy" link in the footer to `SECURITY.md` on GitHub (absolute `https://` URL).
- Both follow the public-framing rules and the `tiko-site-maintainer` skill.

### Step 1 verification

- Both workflows run on the Step 1 PR itself and pass. CodeQL results appear in the Security tab.
- Dependency Review runs on a PR. Its blocking behaviour is the action's documented contract; no deliberately vulnerable dependency is introduced to prove it.
- `SiteInSyncTest` / `SiteCompileErrorDemoTest` and `check-site.sh` pass with the footer link; the new link returns 200.
- Every safety claim in the README/site text maps to a verified code fact above.

## Step 2: `docs/security-model.md` and the `tiko-security` skill

### `docs/security-model.md`

A registry in the style of `architecture-invariants.md`. Each entry has:
- an ID (`SEC-n`);
- the attack surface (module and paths);
- the threat;
- the rule;
- **Enforced by:** a test, a code location, or "convention (not enforced)";
- **Status:** holds / gap (issue link).

Initial entries:

| ID | Surface | Rule |
|---|---|---|
| SEC-1 | YAML config (`tiko-config`) | Load with `SafeConstructor` only. |
| SEC-2 | Config values in logs (`tiko-config`) | Framework logs never contain resolved config values. Known gap: `CompositeCoercers` duplicate-value warning. |
| SEC-3 | Kafka ingest (`tiko-kafka`) | Untrusted bytes deserialize only to the bridge's fixed target type: no Jackson default typing, no polymorphic type info from the payload, no Java serialization. |
| SEC-4 | Kafka ingest failures (`tiko-kafka`) | Poison records go to the configured policy; the framework never logs raw record payloads. |
| SEC-5 | Generated code (`tiko-processor`, `tiko-kafka-processor`) | Annotation string values (topics, event names, qualifiers, config keys) enter generated code only as escaped literals (JavaPoet `$S`), never concatenated into code. |
| SEC-6 | Framework logging (all) | External text is never used as a log format string; payloads and config values are not logged. |
| SEC-7 | `tiko-mcp` | Stdio transport only, no network listener; reads only under the project root it was given. |
| SEC-8 | Runtime wiring (`tiko-runtime`) | No reflection or class loading driven by external input; transport discovery is ServiceLoader on the application's own classpath. |
| SEC-9 | Supply chain: dependencies | Versions managed in `tiko-bom` and the root `dependencyManagement`; shaded Jackson is tracked so its CVEs trigger a Tiko release. |
| SEC-10 | Supply chain: release | Artifacts GPG-signed; release secrets (`CENTRAL_*`, `GPG_*`, `RELEASE_PUSH_TOKEN`) referenced only in `release.yml`. Known gap: Actions pinned by tag, not SHA. |
| SEC-11 | Availability (`tiko-runtime`, `tiko-kafka`) | The async queue is bounded with an overflow policy; ingest failures can't produce an unbounded tight retry loop. |

Step 2 fills **Enforced by** and **Status** for each entry by reading the code. Gaps become issues following `docs/qa-playbook.md`: symptoms only.

### `.ai-skills/tiko-security/SKILL.md`

One skill, four modes, all driven by `docs/security-model.md`:

1. **PR review.** A path-to-entry map (e.g. `tiko-kafka/**/serializer/**` → SEC-3, SEC-4; `**/generator/**` → SEC-5). For a diff, list the entries touched and whether each rule still holds, with file:line evidence.
2. **Release gate.** Run after `tiko-architect` and before `tiko-release`. Input: the delta since the last `vX.Y.Z` tag, open Dependabot/CodeQL alerts, and shaded dependency versions. Output: **GO / CONDITIONAL / NO-GO** with named blockers. An open high/critical alert affecting a shipped artifact means NO-GO.
3. **Periodic audit.** Walk every entry, confirm its enforcement still exists, and file gaps as issues (symptoms only).
4. **Advisory handling.** For a private report or an alert:
   - triage severity;
   - fix in a GitHub temporary private fork attached to a draft advisory;
   - release;
   - publish the advisory (CVE if warranted);
   - credit the reporter.
   Nothing about the vulnerability goes into public issues, PRs or commits before disclosure.

**Wiring:**
- a pointer block in `CLAUDE.md`;
- `tiko-release`'s pre-flight states the gate order: `tiko-architect` → `tiko-security` → `tiko-release`.

### Step 2 verification

The same baseline-then-retest method as `tiko-site-maintainer`:
- Fresh agents run realistic scenarios without the skill: a PR adding a polymorphic Kafka payload, a release with an open high alert, a private vulnerability report.
- The skill is written against the observed failures.
- The scenarios rerun with the skill committed: each needs ≥ 3/3 passes for the discipline points (no public disclosure before the fix; NO-GO on open high/critical alerts).

## Out of scope

- A runtime security module or auth features for user applications.
- Branch-protection changes, OpenSSF Scorecard, SBOM publishing, SHA-pinning of Actions (recorded as a gap; a follow-up decision).
- OWASP Dependency-Check / NVD scanning (Dependency Review chosen instead).
- Fixing the known gaps. Step 2's audit files them as issues.
