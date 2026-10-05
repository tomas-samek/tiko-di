# Security Foundation (Step 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish the public security foundation for tiko-di: a `SECURITY.md` policy, CodeQL code scanning, a PR dependency-vulnerability gate, and a short Security section in the README and on the landing page.

**Architecture:**
- **Policy and public text:** one root `SECURITY.md` that GitHub surfaces on the Security tab; a README section and a site footer link point to it.
- **Scanning:** two new GitHub Actions workflows that report into GitHub's existing Security features. `codeql.yml` analyses a real Maven build so processor-generated code is included. `dependency-review.yml` gates PRs. No application code changes.

**Tech Stack:** GitHub Actions (`github/codeql-action@v4`, `actions/dependency-review-action@v5`, `actions/checkout@v4`, `actions/setup-java@v4`), Maven, Markdown, static HTML.

**Spec:** `docs/superpowers/specs/2026-10-05-security-practice-design.md` (Step 1 only)

## Global Constraints

- Scope: Tiko's own code and supply chain; not a runtime security module for user applications.
- Supported versions: only the latest released minor (`0.5.x` today); pre-1.0, no backports.
- Reporting: GitHub private vulnerability reporting (already enabled) at `https://github.com/tomas-samek/tiko-di/security/advisories/new`, never a public issue.
- Response: acknowledge within 7 days; fix/advisory best effort, coordinated with the reporter; public disclosure once a fixed release is on Maven Central, at most 90 days after the report; reporter credited unless they decline.
- In scope: published `io.github.tomas-samek:tiko-*` artifacts, processor-generated code in user builds, `tiko-archetype` output. Out of scope: `tiko-examples/`, `comparisons/`, security of applications built on Tiko.
- Shaded Jackson in `tiko-kafka`: its fixes ship only via a Tiko release.
- CodeQL: language `java-kotlin`, queries `security-extended`, JDK 21, manual build `mvn -B -DskipTests package`; triggers PR + push to `main` + weekly.
- Dependency Review: `fail-on-severity: high`, on `pull_request`.
- Public text: only claims verified in code. Follow the framing rules and `.ai-skills/tiko-site-maintainer/SKILL.md` (relative same-site URLs, framing, verification).
- Commits: single-line conventional `type(scope): subject`, no body; never on `main`.
- `$SCRATCH` in commands = the session scratchpad directory (logs and PR bodies never go into the repo). `mvn` = `W:\tools\apache-maven\bin\mvn`, `gh` = `"/c/Program Files/GitHub CLI/gh.exe"`.

## Review Focus

1. **The footer link to `SECURITY.md` 404s until merge.** The link targets `blob/main/SECURITY.md`, which doesn't exist on `main` before the PR merges. Expected: the pre-merge check uses the branch URL; the post-merge check uses the live `main` URL. Pinned in Task 4 Step 4 and Task 5 Step 6.
2. **Dependency Review needs the dependency graph.** On a repo without it, the action errors with "Dependency review is not supported on this repository". Expected: the PR's `Dependency Review` check passes, not merely runs. Pinned in Task 5 Step 4.
3. **CodeQL build drift.** `mvn -B -DskipTests package` runs Spotless at `validate`. The workflow pins JDK 21, where Spotless runs and must pass. Expected: the CodeQL job builds green and uploads an analysis. Pinned in Task 2 Step 2 (local run of the exact command) and Task 5 Step 4/5.
4. **README claims drifting from the code.** Each safety claim names a verifiable fact. Expected: each one maps to a grep that finds it. Pinned in Task 4 Step 3.
5. **Security reports landing in public issues.** Contributors reading "Contributing" may file a vulnerability as a normal bug. Expected: Contributing points security reports to `SECURITY.md`. Pinned in Task 4 Step 1 (text) and Step 3 (grep).

---

## File Structure

| File | Responsibility |
|---|---|
| `SECURITY.md` (create) | Disclosure policy shown on GitHub's Security tab |
| `.github/workflows/codeql.yml` (create) | CodeQL analysis of a real Maven build |
| `.github/workflows/dependency-review.yml` (create) | Fails PRs that add a known high/critical-vulnerable dependency |
| `README.md` (modify: new `## Security` section before `## License`; one line in `## Contributing`) | Public pointer and verified safe-default claims |
| `site/index.html` (modify: footer link list) | "Security policy" link |

---

### Task 0: Branch

The spec is committed on `docs/security-spec` (not pushed). Rename it for the implementation PR.

- [ ] **Step 1: Rename and confirm base**

```bash
git branch -m docs/security-spec ci/security-foundation
git fetch origin
git log --oneline -2
git merge-base --is-ancestor origin/main HEAD && echo "based on current main"
```

Expected: the spec commit `docs(spec): security practice …` on top, and "based on current main". If `origin/main` moved, `git rebase origin/main`.

---

### Task 1: `SECURITY.md`

**Files:**
- Create: `SECURITY.md`

**Interfaces:**
- Produces: the file at repo root. Tasks 4 and 5 link to `SECURITY.md` (relative in the README, absolute `https://github.com/tomas-samek/tiko-di/blob/main/SECURITY.md` on the site).

- [ ] **Step 1: Write the file**

```markdown
# Security policy

## Supported versions

Tiko is pre-1.0. Security fixes go into the next release of the latest minor line; there are no backports.

| Version | Supported |
|---|---|
| 0.5.x (latest) | ✅ |
| older | ❌ — upgrade to the latest release |

## Reporting a vulnerability

Please report vulnerabilities **privately** through GitHub:
[Report a vulnerability](https://github.com/tomas-samek/tiko-di/security/advisories/new)
(the Security tab → "Report a vulnerability"). Don't open a public issue, PR or discussion.

Include:

- the affected module(s) and version(s), e.g. `tiko-kafka 0.5.0`;
- a minimal reproduction (code, configuration, or input);
- the impact as you understand it.

## What to expect

- **Acknowledgement within 7 days.**
- Triage and a fix on a best-effort basis, coordinated with you. Tiko is maintained by one person; we keep you informed rather than promise a date.
- Public disclosure through a GitHub Security Advisory, with a CVE where warranted, once a fixed release is available on Maven Central. Disclosure happens at most 90 days after your report.
- Credit in the advisory and release notes, unless you'd rather not be named.

## Scope

**In scope**

- The published `io.github.tomas-samek:tiko-*` artifacts.
- Code that `tiko-processor` and `tiko-kafka-processor` generate into your build.
- Projects created by `tiko-archetype`.

**Out of scope**

- `tiko-examples/` and `comparisons/` — demonstration code, not shipped.
- The security of applications built on Tiko, including the libraries you plug in (HTTP servers, auth, databases). Report those to their maintainers.

## Bundled dependencies

`tiko-kafka` shades Jackson (relocated under `io.tiko.kafka.internal.jackson`). A vulnerability there is fixed by a new Tiko release; upgrading Jackson in your own build doesn't change the shaded copy.
```

- [ ] **Step 2: Check the links and the reporting setting**

```bash
grep -oE 'https://[^)]+' SECURITY.md | sort -u | while read -r u; do echo "$(curl -s -o /dev/null -w '%{http_code}' -L "$u") $u"; done
"/c/Program Files/GitHub CLI/gh.exe" api repos/tomas-samek/tiko-di/private-vulnerability-reporting --jq .enabled
```

Expected: the advisories URL prints `200` (or `302`/`200` after login redirect; anything but `404`), and `true`.

- [ ] **Step 3: Check the shaded-package claim**

Run: `grep -n "io.tiko.kafka.internal.jackson" tiko-kafka/pom.xml`
Expected: a `<shadedPattern>` line with that package. If it differs, fix the sentence in `SECURITY.md` to match.

- [ ] **Step 4: Commit**

```bash
git add SECURITY.md
git commit -m "docs(security): add SECURITY.md with private reporting and disclosure policy"
```

---

### Task 2: CodeQL workflow

**Files:**
- Create: `.github/workflows/codeql.yml`

- [ ] **Step 1: Write the workflow**

```yaml
# CodeQL code scanning (security-extended queries) over a real Maven build, so sources the
# annotation processors generate are analysed too. Results: repo Security tab → Code scanning.
name: CodeQL

on:
  push:
    branches: [ "main" ]
  pull_request:
    branches: [ "main" ]
  schedule:
    - cron: '23 4 * * 1'

permissions:
  contents: read

jobs:
  analyze:
    name: Analyze (java-kotlin)
    runs-on: ubuntu-latest
    permissions:
      actions: read
      contents: read
      security-events: write
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven
      - uses: github/codeql-action/init@v4
        with:
          languages: java-kotlin
          build-mode: manual
          queries: security-extended
      - name: Build
        run: mvn -B -DskipTests package
      - uses: github/codeql-action/analyze@v4
        with:
          category: "/language:java-kotlin"
```

- [ ] **Step 2: Run the workflow's build command locally on JDK 21**

```bash
mvn -B -DskipTests package > "$SCRATCH/codeql-build.log" 2>&1; echo "exit=$?"; grep -E "BUILD|FAILURE \[" "$SCRATCH/codeql-build.log"
```

Expected: `exit=0`, `BUILD SUCCESS`. (Add `-o` if the network is slow; dependencies are cached.)

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/codeql.yml
git commit -m "ci(security): add CodeQL code scanning"
```

---

### Task 3: Dependency Review workflow

**Files:**
- Create: `.github/workflows/dependency-review.yml`

- [ ] **Step 1: Write the workflow**

```yaml
# Fails a PR that adds or upgrades to a dependency with a known high/critical vulnerability
# (GitHub Advisory Database). Dependabot alerts cover dependencies already on main.
name: Dependency Review

on:
  pull_request:
    branches: [ "main" ]

permissions:
  contents: read

jobs:
  dependency-review:
    name: Dependency Review
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/dependency-review-action@v5
        with:
          fail-on-severity: high
```

- [ ] **Step 2: Confirm the dependency graph is available**

Run: `"/c/Program Files/GitHub CLI/gh.exe" api repos/tomas-samek/tiko-di/dependency-graph/sbom --jq '.sbom.packages | length'`
Expected: a number greater than 0 (the graph exists). If the call fails, stop and tell the user: enabling the dependency graph is a repository setting.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/dependency-review.yml
git commit -m "ci(security): gate PRs on GitHub Dependency Review"
```

---

### Task 4: README and site

**Files:**
- Modify: `README.md` (insert `## Security` before `## License`; one sentence in `## Contributing`)
- Modify: `site/index.html` (footer `<ul class="footer-links">`)

- [ ] **Step 1: README — Security section and Contributing pointer**

Insert directly before the line `## License`:

```markdown
## Security

Report vulnerabilities privately through GitHub's [private vulnerability reporting](https://github.com/tomas-samek/tiko-di/security/advisories/new), not in a public issue. [SECURITY.md](./SECURITY.md) covers supported versions, what to expect, and scope.

Every pull request runs CodeQL code scanning and GitHub Dependency Review, and Dependabot watches the dependencies already in use. Untrusted input is handled conservatively by default:

- YAML configuration is loaded with SnakeYAML's `SafeConstructor`.
- The Kafka JSON serializer reads each record into the bridge method's declared payload type, with Jackson default typing off.
- The framework uses no Java object serialization.
```

In `## Contributing`, after the paragraph that starts `Bug reports should include`, add:

```markdown
Security vulnerabilities are the exception: report them privately as described in [SECURITY.md](./SECURITY.md), not as an issue.
```

- [ ] **Step 2: Site — footer link**

In `site/index.html`, in `<ul class="footer-links">`, add after the `GitHub` item:

```html
      <li><a href="https://github.com/tomas-samek/tiko-di/blob/main/SECURITY.md">Security policy</a></li>
```

- [ ] **Step 3: Verify every claim against the code**

```bash
grep -n "new SafeConstructor" tiko-config/src/main/java/io/tiko/config/internal/YamlLoader.java
grep -n "readValue(bytes, type)" tiko-kafka/src/main/java/io/tiko/kafka/serializer/JsonKafkaSerializer.java
grep -rn "activateDefaultTyping\|enableDefaultTyping" --include=*.java tiko-*/src/main | wc -l
grep -rn "ObjectInputStream\|ObjectOutputStream" --include=*.java tiko-*/src/main | wc -l
grep -n "SECURITY.md" README.md
```

Expected: one `SafeConstructor` line, one `readValue(bytes, type)` line, `0`, `0`, and exactly two `SECURITY.md` lines (the Security section's first paragraph and the Contributing sentence).

- [ ] **Step 4: Site checks (tiko-site-maintainer §4)**

```bash
mvn -B test -pl tiko-processor,tiko-archetype -am -Dtest=SiteCompileErrorDemoTest,SiteInSyncTest,SitePageContentTest,SiteChecksTest -Dsurefire.failIfNoSpecifiedTests=false > "$SCRATCH/site.log" 2>&1; echo "exit=$?"; grep -E "Tests run:.*Site" "$SCRATCH/site.log"
bash .ai-skills/tiko-site-maintainer/scripts/check-site.sh
curl -s -o /dev/null -w '%{http_code}\n' "https://github.com/tomas-samek/tiko-di/blob/ci/security-foundation/SECURITY.md"
```

Expected: `exit=0` with all Site tests passing; `PASS` from `check-site.sh`; `200` for the branch URL. Run the last line after Task 5 Step 2 pushes the branch. The `main` URL 404s until merge; Task 5 Step 6 checks it.

- [ ] **Step 5: Commit**

```bash
git add README.md site/index.html
git commit -m "docs(security): link the security policy from the README and site"
```

---

### Task 5: Full build, PR, checks, post-merge verification

- [ ] **Step 1: Full build**

Run: `mvn -B clean install > "$SCRATCH/full.log" 2>&1; echo "exit=$?"; grep -E "BUILD|FAILURE \[" "$SCRATCH/full.log"`
Expected: `exit=0`, `BUILD SUCCESS`.

- [ ] **Step 2: Push and open the PR**

Write the body to `$SCRATCH/pr-security.md`:

```markdown
## Summary

Step 1 of the security practice (`docs/superpowers/specs/2026-10-05-security-practice-design.md`):

- **`SECURITY.md`** — supported versions (latest minor), private reporting via GitHub, acknowledgement within 7 days, coordinated disclosure through GitHub Security Advisories (≤ 90 days), scope, and the shaded-Jackson note.
- **CodeQL** (`codeql.yml`) — `security-extended` queries over a real Maven build (processor-generated code included), on PRs, pushes to `main`, and weekly.
- **Dependency Review** (`dependency-review.yml`) — fails PRs that add a dependency with a known high/critical vulnerability; Dependabot alerts already cover existing dependencies.
- **README / site** — a Security section with verified safe defaults, a Contributing pointer, and a "Security policy" footer link.

## Test plan

- [x] Full `mvn -B clean install` green.
- [x] CodeQL's build command (`mvn -B -DskipTests package`) green locally on JDK 21.
- [x] Every README safety claim matched to the code by grep.
- [x] Site drift tests and `check-site.sh` pass; footer link resolves on the branch.
- [ ] CodeQL and Dependency Review checks green on this PR.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

```bash
git push -u origin ci/security-foundation
"/c/Program Files/GitHub CLI/gh.exe" pr create --base main --title "ci(security): security policy, CodeQL and dependency review" --body-file "$SCRATCH/pr-security.md"
```

Then verify the body has no bare `@` mentions (every `@` inside a code span).

- [ ] **Step 3: Branch URL of the policy**

Run the `curl` line from Task 4 Step 4. Expected: `200`.

- [ ] **Step 4: Wait for every check**

Poll `gh pr checks <pr>` until nothing is pending. Expected:
- the existing Build/Integration/Sonar checks pass;
- **`Analyze (java-kotlin)`** passes;
- **`Dependency Review`** passes.

Then query Sonar's open issues on the PR (must be 0) and the quality gate (`OK`).

- [ ] **Step 5: Confirm CodeQL uploaded an analysis**

Run: `"/c/Program Files/GitHub CLI/gh.exe" api "repos/tomas-samek/tiko-di/code-scanning/analyses?ref=refs/pull/<pr>/merge" --jq '.[0] | "\(.tool.name) \(.category) results=\(.results_count)"'`
Expected: `CodeQL /language:java-kotlin results=<n>`. If `n > 0`, list the alerts and report them to the user. Don't fix them in this PR: they are findings for Step 2's model and audit.

- [ ] **Step 6: After the user merges — live checks**

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://github.com/tomas-samek/tiko-di/blob/main/SECURITY.md
"/c/Program Files/GitHub CLI/gh.exe" run list --workflow pages.yml --limit 1 --json conclusion,headSha --jq '.[0]'
curl -s https://tomas-samek.github.io/tiko-di/ | grep -c 'SECURITY.md">Security policy'
```

Expected: `200`; the Pages run for the merge commit with `success`; `1`.
