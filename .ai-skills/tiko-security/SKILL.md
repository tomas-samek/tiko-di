---
name: tiko-security
description: Use when a change touches how tiko-di handles untrusted input, dependencies, generated code, logging or CI workflows; before cutting a release (after tiko-architect); for a periodic security audit; or when a vulnerability report, Dependabot alert or CodeQL alert arrives.
---

# tiko-security

Security for **Tiko's own code and supply chain** — not for applications built on Tiko.
Everything here is driven by the registry [`docs/security-model.md`](../../docs/security-model.md)
(SEC-1 … SEC-11). Disclosure policy: [`SECURITY.md`](../../SECURITY.md).

## Surface map

| Change touches | Check |
|---|---|
| `tiko-config/**` | SEC-1, SEC-2 |
| `tiko-kafka/**/serializer/**`, `KafkaSerializer`, `EventSerializer` | SEC-3 |
| `tiko-kafka/**/runtime/**`, `KafkaIngestError` | SEC-3, SEC-4, SEC-11 |
| `tiko-processor/**`, `tiko-kafka-processor/**` (generators, `CodeLiterals`) | SEC-5 |
| any log call, `TikoLog` | SEC-6 |
| `tiko-mcp/**` | SEC-7 |
| `tiko-runtime/**` (`Class.forName`, `ServiceLoader`, `TikoOptions`) | SEC-8, SEC-11 |
| `pom.xml`, `tiko-bom/pom.xml`, any `<dependency>` / shade config | SEC-9 |
| `.github/workflows/**` | SEC-10 |

## Mode 1 — PR review

1. `git diff --name-only main...HEAD` → map paths to SEC entries with the table.
2. For each entry: read its **Rule** and **Violation looks like**, inspect the diff, and decide
   *holds* / *violated* with file:line evidence.
3. A violated rule is **blocking**. Say which rule, where, and what an attacker gains.

## Mode 2 — Release gate (after `tiko-architect`, before `tiko-release`)

Collect, then decide:

```bash
last=$(git describe --tags --abbrev=0 --match 'v*')
git diff --name-only "$last"..HEAD                                   # → Mode 1 over the delta
gh api --paginate "repos/tomas-samek/tiko-di/dependabot/alerts?state=open&per_page=100" \
  --jq '.[] | "\(.security_advisory.severity) \(.dependency.package.name) \(.dependency.manifest_path)"'
gh api --paginate "repos/tomas-samek/tiko-di/code-scanning/alerts?state=open&ref=refs/heads/main&per_page=100" \
  --jq '.[] | "\(.rule.security_severity_level) \(.rule.id) \(.most_recent_instance.location.path)"'
# Shaded Jackson (SEC-9 cross-check): Dependabot covers what tiko-kafka declares (#476); this
# queries what it actually shades, at the resolved versions — they differ (e.g.
# jackson-annotations 2.22 vs jackson-core 2.22.3).
mvn -q -pl tiko-kafka dependency:list -DoutputFile=shaded-deps.txt \
  -DincludeGroupIds=com.fasterxml.jackson.core,com.fasterxml.jackson.datatype
grep -oE 'com\.fasterxml\.jackson\.[a-z]+:[a-z0-9-]+:jar:[^:]+' tiko-kafka/shaded-deps.txt \
  | sed -E 's/:jar:/@/' | while read -r coord; do
      gh api --paginate "/advisories?ecosystem=maven&affects=$coord&per_page=100" \
        --jq '.[] | "\(.severity) \(.ghsa_id) \(.summary)"' | sed "s|^|$coord |"
    done
rm tiko-kafka/shaded-deps.txt
```

| Verdict | When |
|---|---|
| **NO-GO** | an open **high/critical** alert (Dependabot, CodeQL, or a shaded-coordinate advisory) affecting a shipped artifact; or a SEC rule violated by the delta |
| **CONDITIONAL** | medium/low alerts on shipped artifacts; a SEC gap touched by the delta that is already tracked by an issue |
| **GO** | none of the above |

Only shipped code counts: alerts in `tiko-examples/`, `comparisons/` or any `src/test/**`
don't affect the verdict. Name every blocker; the release waits until NO-GO blockers are fixed.

An alert that genuinely doesn't apply is resolved **in GitHub first** — the maintainer dismisses
it with a reason — and only then is it no longer open. Dependabot reasons: `not used`,
`inaccurate`, `tolerable risk`, `no bandwidth`, `fix started`. Code-scanning reasons: `false
positive`, `won't fix`, `used in tests`. The gate reads alert state; it never waives an open
alert in its own verdict.

A **shaded-coordinate advisory** can't be dismissed (it isn't a repository alert). Resolve it by
bumping the shaded version (`mvn versions:set-property -Dproperty=jackson.version …`, plus
`jackson-annotations.version` to the same major.minor) and
re-running the gate. If no fixed upstream version exists, the release stays NO-GO until the
maintainer decides; record that decision in a comment on the release PR, not in release notes.

| Rationalization | Reality |
|---|---|
| "CONDITIONAL until it's triaged" | Untriaged is still open. Open high/critical on a shipped artifact = NO-GO. |
| "SEC-3 makes it unexploitable for us" | Maybe — then dismiss the alert in GitHub with that reason. Until then it's open. |
| "Ship with an accepted-risk note in the release notes" | Risk acceptance is a dismissal decision for the maintainer, recorded on the alert, not a line in the notes. |
| "Dependabot shows nothing, so Jackson is clean" | Dependabot sees what `tiko-kafka` declares, not what it shades (SEC-9). Run the advisory query. |

## Mode 3 — Periodic audit

1. For every SEC entry, re-run its anchors (the "Enforced by" file + symbol) and its
   "Violation looks like" greps across `tiko-*/src/main`.
2. A behaviour inferred from reading code is **unconfirmed** until a scratch test reproduces
   it (`docs/qa-playbook.md`, scratch QA test pattern; delete the scratch file after).
3. File each confirmed gap as an issue: symptoms only, per `docs/qa-playbook.md`. Then link
   it from the entry's **Status**. Gaps that could hurt users if published (an exploitable
   flaw, not a hardening item) go through Mode 4 instead.
4. Propose new SEC entries when the code has grown a surface the registry doesn't cover.

The audit is the registry walk above — not a generic checklist. New tooling (fuzzing,
secret scanners, extra static analysis) is a separate proposal to the maintainer, never a
substitute for checking each SEC entry. Reading code is how SEC-7 was first recorded
wrong ("links not followed"): an anchor is confirmed only when you've looked at **every** call
site of the surface, not the first one you found.

**Red flags — stop:** an audit report that never names a SEC entry; a finding filed with
"may", "could" or "likely" and no reproduction; a Status changed to "Holds" from one grep.

## Mode 4 — Advisory handling

A private report (GitHub "Report a vulnerability"), an alert, or your own finding of an
exploitable flaw:

1. **Acknowledge within 7 days** — reply in the advisory thread (or create a draft advisory
   for alerts/own findings: repo **Security → Advisories → New draft**).
2. **Triage** in the draft: affected modules/versions, reproduction, severity (CVSS).
   Rate the impact the **code** allows, not the impact the report claims — read what the
   affected path actually returns, logs or executes, and reproduce it. A report's worst case
   and a first reading both tend to overstate. If the confirmed impact is a hardening gap
   rather than an exploitable flaw, the **maintainer decides** whether it moves to a public
   issue. For an external report, tell the reporter in the advisory thread and close the draft
   advisory before anything is filed publicly (SECURITY.md promises coordinated handling).
3. **Fix privately**: from the draft advisory, *Start a temporary private fork*; fix there,
   with tests. Commit messages and branch names stay neutral (`fix(kafka): harden …`).
4. **Release**: merge the private fork's PR from the advisory, then run `tiko-architect` →
   `tiko-security` (Mode 2) → `tiko-release`.
5. **Publish** the advisory once the fixed version resolves on Maven Central (request a CVE
   in the advisory if warranted); credit the reporter unless they decline. Then add the advisory
   link and the credit to the fixed version's GitHub Release notes (SECURITY.md promises credit
   there too) — only after publication, never before.

**Nothing about the vulnerability goes into a public issue, PR, discussion, commit message,
branch name or release note before step 5.**

| Rationalization | Reality |
|---|---|
| "I'll open an issue so it's tracked" | The draft advisory *is* the tracker, and it's private. A public issue discloses the vuln. |
| "The fix is tiny, a normal PR is fine" | The diff and its description disclose the vuln on every fork and mirror. Use the private fork. |
| "I'll describe it in the commit message for history" | The advisory is the history; commit messages are public the moment they're pushed. |
| "The maintainer asked me to track it before the weekend" | Track it in the draft advisory — that satisfies the ask without disclosing. |

**Red flags — stop:** `gh issue create` mentioning the vuln, a PR on the public repo with the
fix before publication, words like "vulnerability", "CVE", "exploit", "injection" in a commit
message or branch name for an undisclosed issue.
