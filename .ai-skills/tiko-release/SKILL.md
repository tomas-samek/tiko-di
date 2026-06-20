---
name: tiko-release
description: Use when cutting a release of tiko-di to Maven Central. Procedural skill — pre-flight checks, two version inputs to ask the user about, workflow dispatch, the Sonatype Portal manual gate, post-publish verification, the README/install-doc version-pin bump, GitHub Release notes, common traps from prior releases.
---

# tiko-release

This file is the **operational distillation** of
[`docs/release-skill.md`](../../docs/release-skill.md), which in turn
sits alongside the comprehensive
[`docs/releasing.md`](../../docs/releasing.md) reference. The releasing
doc is the *what is published, and how it's wired*; this skill is
the *what to do, in what order, and what to ask before each step*.

## The rule — ask, don't fabricate the version inputs

> **Never pick `release_version` or `next_snapshot` silently.** Both
> are user decisions with consequences that can't be undone after the
> Central Portal Publish click.

Why this is load-bearing: the workflow takes both versions as inputs and
applies them to every POM in the reactor, tags `v<release_version>`,
and bumps main to `<next_snapshot>`. A wrong `next_snapshot` (patch
when the user meant minor, e.g. `0.2.3-SNAPSHOT` when they meant
`0.3.0-SNAPSHOT`) bakes the wrong cadence into git history. A wrong
`release_version` ships under a coordinate the user didn't intend.

You may **suggest** a value derived from main's current `*-SNAPSHOT`,
but you must confirm before dispatch. Use the actual ask:

- *"Main is currently at `0.3.0-SNAPSHOT`. Do you want to release
  `0.3.0` (next_snapshot `0.4.0-SNAPSHOT` or `0.3.1-SNAPSHOT`)?
  Or are you cutting `0.2.3` from this version?"*

If you can't see main's state, ask. Don't guess.

## When to use this skill

- The user wants to cut a Maven Central release of tiko-di.
- The user wants to verify the release pipeline is healthy before a
  release.
- Something failed in a prior release and the user is recovering.

Not for: releasing user projects (every project has its own conventions),
SNAPSHOT-only deploys, or framework-internal version bumps that don't
publish.

## Step 0 — architecture go/no-go (run first)

Before any pre-flight, run **`/tiko-architect`** against the release delta. Do **not** proceed
past a **NO-GO** without resolving the named architectural blockers. A **CONDITIONAL** means
file the listed follow-ups, then proceed. A **GO** clears this step. See
[`.ai-skills/tiko-architect/SKILL.md`](../tiko-architect/SKILL.md).

## Step 1 — pre-flight checks

Before asking for the version inputs, verify these. If any fail, fix
*then* proceed — never dispatch a release with a known-broken pre-flight.

### Repository state

- `git log origin/main` is green on CI. The last successful workflow
  run on `main` is **Build (JDK 21)** green (`gh run list --workflow=maven.yml`).
- No open PRs that the user wants in the release. Ask explicitly:
  *"Anything in flight you want to wait for?"*
- `main` is at a `*-SNAPSHOT` version (`grep -m 1 "<version>" pom.xml`).
  If it's at a release version, something earlier in the cycle failed
  and the recovery path is different — surface to the user.

### Repo secrets exist

Verify all four release secrets are configured:

```bash
gh secret list | grep -E "CENTRAL_(USERNAME|TOKEN)|GPG_(PRIVATE_KEY|PASSPHRASE)|RELEASE_PUSH_TOKEN"
```

All five names must appear. If any is missing — especially
`RELEASE_PUSH_TOKEN`, the one that bit `0.2.0` — stop and ask the user
to add it. The workflow fails at step "Checkout main with push-capable
token" with `Input required and not supplied: token` if
`RELEASE_PUSH_TOKEN` is missing. See `docs/releasing.md` for setup.

### Published-artifact audit

Open `docs/releasing.md` and confirm the **Published / Not published**
table matches reality:

```bash
grep -lE "<skipPublishing>true</skipPublishing>" tiko-*/pom.xml
```

Every module that appears in this list must be in the *Not published*
column of the table; every module not in the list must be in the
*Published* column. Drift here means an artifact will silently ship or
silently not ship — both bad.

### BOM audit (critical — this is what `#298` caught)

Every artifact in the *Published* column except `tiko-archetype` must
have a `<dependency>` entry under `tiko-bom/pom.xml`'s
`<dependencyManagement>`. Check:

```bash
for m in $(grep -oE "tiko-[a-z-]+" docs/releasing.md | sort -u); do
    echo -n "$m: "
    grep -l "<artifactId>$m</artifactId>" tiko-bom/pom.xml > /dev/null \
        && echo "in BOM" || echo "NOT IN BOM"
done
```

`tiko-archetype` is the documented exception (archetypes resolve via
`-DarchetypeVersion=`, not as `<dependency>`). Everything else must
be in the BOM. If any artifact is published but missing from the BOM,
file an issue and fix it **before** releasing — otherwise consumers
who import the BOM get *"version is missing"* errors.

### Shaded-jar NOTICE audit

For every module that uses `maven-shade-plugin`:

```bash
grep -l "maven-shade-plugin" tiko-*/pom.xml
```

Verify each one has `ApacheNoticeResourceTransformer` configured
(Apache 2.0 §4(d) — required when bundling Jackson, ethlo.time,
FastDoubleParser, or any other Apache 2.0 dep). Missing this means
only one bundled dep's NOTICE survives the file collision; others'
attributions are lost. This is what `#294` (tiko-mcp) and `#295`
(tiko-kafka) fixed.

If any shaded module is missing the transformer, file an issue and
fix it before releasing.

## Step 2 — ask for the version inputs

Once pre-flight is clean, ask the user. Don't dispatch yet.

Required inputs:

1. **`release_version`** — semver `MAJOR.MINOR.PATCH`, no `-SNAPSHOT`
   suffix. The workflow validates this with a regex; bad input rejected
   at the *Validate version inputs* step.
2. **`next_snapshot`** — semver `MAJOR.MINOR.PATCH-SNAPSHOT`. The bump
   from `release_version`.

**Suggest, then confirm:**

> *"Main is at `<main version>`. Are we cutting `<release_version
> derived from main's snapshot>`? And do you want to bump to
> `<patch bump>-SNAPSHOT` or `<minor bump>-SNAPSHOT` for next?"*

The patch-vs-minor choice is the load-bearing ask. If the release is
a bugfix or BOM correction (e.g. `0.2.2`), patch bump is natural. If
the release introduces new public surface, minor bump is natural. Don't
pick.

## Step 3 — dispatch the workflow

```bash
gh workflow run release.yml \
    -f release_version=X.Y.Z \
    -f next_snapshot=X.Y.Z-SNAPSHOT
```

Then watch:

```bash
# A short loop until the new run id appears, then watch it:
until [ "$(gh run list --workflow=release.yml --limit 1 --json databaseId --jq '.[0].databaseId')" != "<prior id>" ]; do
    sleep 5
done
NEW_ID=$(gh run list --workflow=release.yml --limit 1 --json databaseId --jq '.[0].databaseId')
gh run watch "$NEW_ID" --exit-status
```

Expected duration: ~2m45s. The workflow:

1. Sets all POMs to `release_version` via `mvn versions:set`.
2. Deploys to **Central Portal staging** (not Central proper).
3. Commits `release: <X.Y.Z>` and tags `vX.Y.Z`.
4. Sets all POMs to `next_snapshot`.
5. Commits `chore: prepare <X.Y.Z>-SNAPSHOT`.
6. Pushes both commits + the tag.

After success: `git fetch --tags && git log origin/main --oneline -3`
should show the release commit, the next-snapshot commit, and the tag
pointing at the release commit.

## Step 4 — the Sonatype Portal manual gate (the trap)

**This is the step where `0.1.0` got stuck for three days.** The
workflow only uploads to *staging*. Maven Central is not yet serving
the artifacts.

Tell the user, explicitly:

> *"The workflow succeeded but artifacts are still in Central Portal
> **staging**. You need to open https://central.sonatype.com →
> Deployments, verify the staged bundle, and click **Publish**.
> Until you do, nothing is on Central."*

On the Portal Deployments page, the user should verify:

- **Artifact count matches the `docs/releasing.md` Published column.**
  At the time of writing: 11 artifacts (`tiko-parent`, `tiko-bom`,
  `tiko-api`, `tiko-runtime`, `tiko-processor`, `tiko-config`,
  `tiko-mcp`, `tiko-test`, `tiko-archetype`, `tiko-kafka`,
  `tiko-kafka-processor`). Sonatype's component summary shows duplicate
  entries for `?type=pom` and `?type=maven-archetype` variants — that's
  normal; the unique-artifact count is what matters.
- **No surprise additions** (e.g. `tiko-kafka-it`, `tiko-coverage`,
  examples — all should be skipped per `docs/releasing.md`).
- **NOTICE content in shaded jars** is the merged version. For
  `tiko-mcp`: download the staged jar, inspect `META-INF/NOTICE` —
  should be ~2 KB with merged Tiko + Jackson + FastDoubleParser
  attribution. If it's ~738 B, the
  `ApacheNoticeResourceTransformer` wasn't applied. Same check for
  `tiko-kafka`.

Then **Publish**. The `autoPublish=false` setting in the workflow is
deliberate — this is the human verification gate.

If anything looks off: **Drop**, fix on a follow-up branch, cut a
patch release. Don't publish a release with known defects.

## Step 5 — wait for `repo1.maven.org` sync

After Publish, Central Portal's promise *"artifacts appear on Central
a short while after publishing"* is 15 min – 4 hours in practice. Set
up a watcher:

```bash
until curl -sf -o /dev/null \
    https://repo1.maven.org/maven2/io/github/tomas-samek/tiko-bom/<X.Y.Z>/tiko-bom-<X.Y.Z>.pom; do
    sleep 120
done
echo "<X.Y.Z> reachable at $(date -Iseconds)"

for a in tiko-bom tiko-api tiko-runtime tiko-mcp tiko-kafka tiko-kafka-processor tiko-archetype; do
    code=$(curl -sI -o /dev/null -w "%{http_code}" \
        https://repo1.maven.org/maven2/io/github/tomas-samek/$a/<X.Y.Z>/$a-<X.Y.Z>.pom)
    echo "  $a: HTTP $code"
done
```

Run in background; you'll be notified on completion. The poll cadence
is 2 minutes — long enough to keep the cache warm, short enough to
react when sync lands.

## Step 6 — bump the README + install-doc version pins

The release workflow sets every **POM** to the new version, but it does
**not** touch the prose docs. These advertise the install coordinate as
literal text and go stale silently — exactly what left the README saying
`Status: 0.1.0` while Central was already at `0.2.2` across three releases
(fixed in `#316`). Do this once Step 5 confirms the version resolves on
`repo1.maven.org`, so the docs never advertise a coordinate that isn't
downloadable yet.

Spots to bump to `<X.Y.Z>`:

- `README.md` — the `**Status: <ver> on Maven Central.**` line.
- `README.md` — the `tiko-bom` `<version>` in the `## Installation`
  BOM-import snippet. This is now the **single** place the module and
  annotation-processor versions resolve from (`#316`), so the dep blocks
  and the `annotationProcessorPaths` path carry no version — don't
  re-add one.
- `README.md` — `-DarchetypeVersion=<ver>` in the archetype scaffold
  command.
- `docs/jdk-23-setup.md` — the Maven `<version>`, the three Gradle
  coordinates, and the plain-`javac` jar names (still explicit pins, not
  BOM-managed).

Then sweep for stragglers (substitute the *prior* version):

```bash
grep -n "<prior X.Y.Z>" README.md docs/jdk-23-setup.md
```

**Do not** bump version strings in point-in-time records — they pin an
old version on purpose: `docs/skill-benchmark/*`, `docs/release-process.md`,
`docs/release-skill.md`, `docs/superpowers/plans/*`. Only the live install
docs move.

Commit on a branch and PR (never edit `main` directly):

```bash
git checkout -b docs/bump-<X.Y.Z>
git commit -am "docs(readme): bump version pins to <X.Y.Z>"
```

The canonical verification is Step 8 below — a fresh-`m2` `mvn compile`
against the copy-pasted snippet proves the bumped coordinate actually
resolves before the PR merges.

## Step 7 — GitHub Release notes

Once Central is live, draft the release notes:

1. **Compute the changelog**:
   ```bash
   git log v<prior>..v<X.Y.Z> --oneline
   ```
2. **Draft the body** as `.release-notes-<X.Y.Z>.md` (untracked file).
   Structure: Summary → What's new (PRs by theme) → What stays the same
   → Coordinates (BOM import + a representative dep snippet) → Full
   changelog pointer. Keep it short for patches; longer for feature
   releases.
3. **Mention-harvest pre-check** (per
   `feedback_github_markdown_no_mention_harvest` rule in memory):
   ```bash
   grep -oE '[^`"a-zA-Z0-9_/-]@[A-Za-z][A-Za-z0-9_-]*' .release-notes-<X.Y.Z>.md \
       && echo "FAIL — wrap annotations in backticks" \
       || echo "PASS"
   ```
4. **Publish**:
   ```bash
   gh release create v<X.Y.Z> \
       --title "v<X.Y.Z> — <one-line summary>" \
       --notes-file .release-notes-<X.Y.Z>.md
   ```
5. **Post-publish byte check**:
   ```bash
   gh api repos/tomas-samek/tiko-di/releases/tags/v<X.Y.Z> --jq .body \
       | xxd -c 1 \
       | awk '/^[0-9a-f]+: 40 / {print prev_offset, prev_byte, "->", $0} {prev_offset=$1; prev_byte=$2}' \
       | grep -vE "^[^ ]+ 60 ->|^[^ ]+ 20 ->"
   ```
   No output = every `@` is preceded by a backtick or a space (safe).
   Any line in output = a mention-harvest hazard slipped through; fix
   immediately by editing the release body.

## Step 8 — validate from an external-user POV (optional but recommended)

If a skill-benchmark cell exists (e.g. `docs/skill-benchmark/runs/.../`),
update its `tiko.version` to `<X.Y.Z>` and run `mvn compile` against a
fresh local Maven repo:

```bash
mvn -Dmaven.repo.local=/tmp/fresh-m2 compile
```

This is what closed `F3` in the cell-1 benchmark: a compile-only
validation that the published artifacts resolve in the canonical
*"import BOM, no version on dep"* shape an external user would write.

If this fails, you've shipped a coordinate that doesn't quite work
for users — file an issue, cut a patch release. The cell-1 cleanup
to canonical `0.2.2` BOM shape is the worked example.

## Common traps from prior releases

The pre-flight section above already gates the structural ones. These
are the operational gotchas:

- **"Workflow succeeded" ≠ "on Maven Central."** Drilled into Step 4
  because `0.1.0` sat in staging unpublished for three days before
  anyone noticed.
- **Re-running a workflow that previously failed re-uses the run id.**
  GitHub UI re-run doesn't create a new run; the watcher loop in
  Step 3 that polls for *"new run id"* will spin forever if you used
  the UI re-run. Use `gh workflow run` for retries — that produces
  a fresh id.
- **Force-push to main is almost never the right answer.** If a
  release attempt left orphan `release:` and `chore:` commits on main,
  prefer additive history (cut the next release with a higher version)
  over force-push. The orphan commits are harmless.
- **A tag that points at the wrong commit must be deleted before
  re-running.** `git push origin :refs/tags/vX.Y.Z`. The workflow will
  fail at the tag-push step if the tag exists.
- **Don't publish a release with a known shaded-jar defect.** Drop
  staging, fix on a PR, retry. The `0.2.0` → `0.2.1` cycle did this
  for the `tiko-mcp` NOTICE merge.

## What this skill does NOT do

- Decide the release version. That's the user's call (the load-bearing
  ask).
- Click Publish on Central Portal. Manual human action; the skill
  prompts the user to do it but cannot do it itself.
- Patch broken pre-flight conditions silently. Surface every failure
  to the user.

## Reading list

- [`docs/releasing.md`](../../docs/releasing.md) — the comprehensive
  release reference. *What* is published; the skill is *how*.
- [`docs/release-skill.md`](../../docs/release-skill.md) — the
  human-facing companion to this file.
- Prior release postmortems live in the relevant PR bodies: `#294`
  (`tiko-mcp` NOTICE + `tiko-archetype` publish), `#295` (Kafka
  publish), `#298`/`#299` (BOM kafka entries).
