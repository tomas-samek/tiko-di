Actual---
description: Implement a GitHub issue end-to-end — read it, branch, TDD, push, open PR.
argument-hint: <issue-number>
---

Implement GitHub issue #$ARGUMENTS in this repo. Run the loop below end-to-end without confirming between steps unless a stop condition fires.

The deliverable is a **green PR** ready for human review — branch pushed, PR opened, CI green. Merging and closing the issue are the repo owner's call: never merge the PR yourself, and never close the issue by hand (the `Closes #` line in the PR body does that on merge).

## Tooling locations

This workflow needs the GitHub CLI (`gh`) and Maven (`mvn`). Resolve each one before the loop starts:

1. **Try PATH first** — run `gh --version` and `mvn -v`. If they succeed, use the bare commands.
2. **Otherwise check memory** — skim `MEMORY.md` for `reference` entries pointing at the executables (e.g. `env_gh_cli_path.md`, `env_maven_path.md`) and verify the path still exists.
3. **Otherwise ask the user** — call `AskUserQuestion` for the exact path. Save it as a new `reference` memory (one file per tool, like `env_<tool>_path.md`) so future runs don't re-prompt.

When invoking from bash, quote paths with spaces (e.g. `"/c/Program Files/GitHub CLI/gh.exe"`).

## 1. Read the issue and the relevant memory

- Run `gh issue view $ARGUMENTS` to get title and body.
- Skim `MEMORY.md` for related notes. Especially check for:
  - **Descoped or narrowed designs** — if the issue is on a dropped list (e.g. items removed from a roadmap memory), STOP and surface the conflict to the user before doing any work.
  - **Validator-gap or known-bug memos** — the issue may be one of several already triaged together.
  - **Feedback memos** — commit-message style, testing preferences, etc.

If a memo is older than a couple of days, verify its claims against current code before relying on it.

## 2. Create the branch

- Make sure local `main` is current: `git checkout main && git pull --ff-only`.
- Branch as `<type>/issue-$ARGUMENTS-<slug>`:
  - `<type>` — one of `feat`, `fix`, `refactor`, `chore`, `ci`, `test`, `docs`, `build` (conventional commits).
  - `<slug>` — short kebab-case derived from the issue title.

## 3. TDD

- Write the failing test **first**. Run it and confirm it fails for the targeted reason. If extra failures show up (e.g. the fixture trips a different bug), isolate the fixture so the signal is clean — one bug per test.
- Then write the minimum production code to turn it green.
- Run the full reactor build (`mvn clean install`, using the `mvn` resolved in **Tooling locations**) to confirm no regressions before committing.

## 4. Commit (conventional, summary-only)

- Stage **only** files related to this issue. The working tree often has unrelated edits (pom version bumps, IDE-formatted whitespace) — leave them alone. Use explicit paths with `git add`, never `git add -A` / `git add .`.
- Commit message is a **single conventional-commits summary line**: `type(scope): subject (#$ARGUMENTS)`. No body. No `Co-Authored-By` trailer unless the user explicitly asks.

## 5. Push and open the PR

- `git push -u origin <branch>`.
- `gh pr create` with:
  - **Title** mirroring the commit subject.
  - **Body** containing `## Summary` (what changed and why), `## Test plan` (checklist of what was verified), and `Closes #$ARGUMENTS`.
- Return the PR URL.

## Stop conditions (flag and wait)

- The issue is on a dropped/narrowed list in memory.
- The fix needs a design decision the user hasn't made.
- The issue is ambiguous enough that no clean failing test can be written.
- The implementation would touch >1 issue worth of scope (split it).
