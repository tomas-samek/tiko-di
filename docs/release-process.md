# tiko-di release process

**Origin:** Written for v0.1.0. Subsequent releases reuse this checklist — substitute the version number throughout. The workflow is the same; the content of the release notes is not. Version-specific release notes live in [`RELEASE-NOTES.md`](../RELEASE-NOTES.md) at the repo root — this doc is the *workflow*, not the *content*.

**Scope:** Cut a versioned tag, push it, create the corresponding GitHub release. Until Maven Central publication lands (Phase 5), every release is a tagged GitHub-only release: no binary attachments, no central distribution, no publication automation.

**Prerequisites — verify before tagging:**

- The release's milestone on GitHub is fully closed (all issues merged or moved out).
- `mvn clean install` succeeds from a fresh checkout.
- All examples under `tiko-examples/` build and run correctly.
- `archetype:generate` from the local catalog produces a working scaffold.
- The `[Unreleased]` section of `RELEASE-NOTES.md` has been promoted to a versioned section with today's date and the new version number.

If any of the above is not yet true, **do not tag**. Wait until the state is clean.

---

## Tagging

Use `vX.Y.Z` (with `v` prefix). Most release-detection tooling and GitHub Actions defaults expect `v*`, and the prefix cleanly distinguishes tags from branch names in `git log`.

```bash
git checkout main
git pull --ff-only
git tag -a vX.Y.Z -m "tiko-di vX.Y.Z — short tagline"
git push origin vX.Y.Z
```

Annotated tag (`-a`) rather than lightweight tag — the message is preserved in `git log` and GitHub displays it on the tag page.

---

## GitHub release

Create the release with the `gh` CLI:

```bash
gh release create vX.Y.Z \
    --title "vX.Y.Z — short tagline" \
    --prerelease \
    --notes-file <path-to-notes>
```

Or use the GitHub UI (Releases → Draft a new release) if you prefer.

**Title format:** `vX.Y.Z — short tagline` (e.g. `v0.1.0 — first alpha release`).

**Mark as:** Pre-release while still in alpha. The "Pre-release" checkbox is important — it visually flags the release on the project page and in feeds, and prevents tools that auto-pick "latest stable" from picking this up as production. Drop the flag once the framework reaches a v1.x.

**Release notes:** copy the relevant version section verbatim from `RELEASE-NOTES.md` (everything between the version's heading and the next version's heading or the link footer). The release notes file at the repo root is the source of truth — never hand-write notes only on GitHub.

Tip: pipe the section out programmatically rather than copy-pasting:

```bash
# Extract the [X.Y.Z] section from RELEASE-NOTES.md (everything until the next ## heading)
awk '/^## \[X\.Y\.Z\]/{flag=1; next} /^## \[/{flag=0} flag' RELEASE-NOTES.md > /tmp/notes.md
gh release create vX.Y.Z --title "vX.Y.Z — ..." --prerelease --notes-file /tmp/notes.md
```

---

## Out of scope (do NOT do as part of this release)

- Maven Central publication (Phase 5).
- GPG signing of artifacts.
- Attaching binary jars to the GitHub release. If users ask for them later, attach retroactively or roll into the next patch.
- Javadoc or sources jars.
- BOM publication.
- A release announcement on LinkedIn, dev.to, or anywhere else. The release note **on GitHub** is sufficient for this version. Public announcement waits for a more meaningful milestone (article 1 publication, Maven Central, or v1.0.0).
- Updating the README to mention this release. The roadmap already reflects the work; the release page on GitHub is the canonical record.

---

## Verification after publishing

1. Visit https://github.com/tomas-samek/tiko-di/releases — confirm the new tag is listed and marked as Pre-release.
2. Click into the release and confirm the notes render correctly (especially the code blocks and links).
3. Confirm the tag is reachable: `git fetch --tags origin && git show vX.Y.Z` should work from a fresh clone.
4. Confirm a fresh clone of the tag builds: `git clone --branch vX.Y.Z https://github.com/tomas-samek/tiko-di.git /tmp/tiko-test && cd /tmp/tiko-test && mvn clean install`.

If any step fails, fix and consider whether to delete + retag or roll forward to a patch release.

---

## Notes

- **Annotated tag, not lightweight.** Use `git tag -a`. Lightweight tags have no message and look unfinished.
- **Pre-release flag matters until v1.x.** Without it, GitHub treats the new tag as "latest" stable, which is misleading for an alpha or beta.
- **`RELEASE-NOTES.md` is the source of truth.** Tags can be deleted; the file is the long-lived artifact, version-controlled, easier to review in PRs.
- **No retroactive changes.** Once a release is published, don't edit `RELEASE-NOTES.md` for that version substantively. Typo fixes are fine; rewrites are not. If something's wrong enough to need a rewrite, ship a patch.
