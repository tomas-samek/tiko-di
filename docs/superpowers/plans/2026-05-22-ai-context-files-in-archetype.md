# AI-Context Files in Archetype Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three AI-context pointer files (`AGENTS.md`, `.github/copilot-instructions.md`, `.junie/guidelines.md`) to the existing `tiko-archetype` so a generated project comes with full agent coverage out of the box.

**Architecture:** Pure content addition to `tiko-archetype/src/main/resources/archetype-resources/`. The three new files are ~25-line pointer docs that direct each tool to the existing `CLAUDE.md` for the canonical conventions. `archetype-metadata.xml` gets three new `<fileSet>` entries. If Maven's archetype-plugin filters out `.github/` or `.junie/` (as it does with `.gitignore`), `archetype-post-generate.groovy` is extended to rename them.

**Tech Stack:** Maven archetype-plugin, Markdown, Groovy (post-generate hook).

**Spec:** `docs/superpowers/specs/2026-05-22-ai-context-files-in-archetype-design.md`
**Tracker:** [#21](https://github.com/tomas-samek/tiko-di/issues/21)
**Branch:** continue on `spec/21-ai-context-files-in-archetype`.

---

## Task 1: Add the three AI-context pointer files

**Files:**
- Create: `tiko-archetype/src/main/resources/archetype-resources/AGENTS.md`
- Create: `tiko-archetype/src/main/resources/archetype-resources/.github/copilot-instructions.md`
- Create: `tiko-archetype/src/main/resources/archetype-resources/.junie/guidelines.md`

- [ ] **Step 1: Create AGENTS.md**

Write `tiko-archetype/src/main/resources/archetype-resources/AGENTS.md` with the following exact content:

```markdown
# Tiko DI project

This project uses [Tiko DI](https://github.com/tomas-samek/tiko-di) — a
compile-time dependency injection framework for Java 21+.

## Read this first

The canonical conventions live in [`CLAUDE.md`](./CLAUDE.md) at the project
root. It covers:

- Component scopes (SINGLETON / REQUEST / EVENT / PROTOTYPE)
- Annotation cheat-sheet (`@Component`, `@Inject`, `@Produces`,
  `@Configuration`, `@EventHandler`, `@EventTrigger`)
- Constructor-injection rule (no field injection)
- Build commands and common pitfalls

## Quick rules

- Constructor injection only. `@Inject` on the constructor, never on fields.
- Components declare scope: `@Component(scope = Scope.SINGLETON)`.
- Annotation processing runs in `mvn compile`.

## Build commands

- `mvn compile` — runs annotation processing
- `mvn test` — runs tests
- `mvn exec:java -Dexec.mainClass=${package}.Main` — runs the example
```

(Note the `${package}` template variable in the last line — Maven's archetype-plugin will substitute it at `archetype:generate` time.)

- [ ] **Step 2: Create .github/copilot-instructions.md**

Create the `.github/` directory under `archetype-resources/` if needed, then write `tiko-archetype/src/main/resources/archetype-resources/.github/copilot-instructions.md`:

```markdown
# Copilot instructions

This project uses Tiko DI — a compile-time dependency injection framework
for Java 21+. **Read [`CLAUDE.md`](../CLAUDE.md) for the full conventions
before suggesting code.** The summary below is a refresher; CLAUDE.md is
authoritative.

## Patterns to follow

```java
@Component(scope = Scope.SINGLETON)
public class FooService {
    @Inject
    public FooService(BarRepository repo) { /* ... */ }
}
```

- Constructor injection only — never field injection.
- Components must declare a scope.
- `@TestComponent` for test fixtures (from `tiko-test`).

## Build commands

- `mvn compile` — runs annotation processing
- `mvn test` — runs tests
```

- [ ] **Step 3: Create .junie/guidelines.md**

Create the `.junie/` directory under `archetype-resources/` if needed, then write `tiko-archetype/src/main/resources/archetype-resources/.junie/guidelines.md`:

```markdown
# Junie guidelines

This project uses Tiko DI — a compile-time DI framework for Java 21+. The
canonical rules live in [`CLAUDE.md`](../CLAUDE.md); read it before
generating code. The bullets below are a refresher.

## Rules

- Constructor injection only — `@Inject` on the constructor.
- Every `@Component` declares a scope: SINGLETON, REQUEST, EVENT, or PROTOTYPE.
- Configuration uses `@Configuration` records bound from YAML.
- Event handlers use `@EventHandler` on methods of `@Component` classes.
- Test fakes use `@TestComponent` from `tiko-test`.

## Build commands

- `mvn compile` — annotation processing runs here
- `mvn test` — full test run
- `mvn exec:java -Dexec.mainClass=${package}.Main` — run example
```

- [ ] **Step 4: Verify files exist with correct content**

Run:
```
W:\tools\apache-maven\bin\mvn -pl tiko-archetype validate
```
Expected: BUILD SUCCESS (no compile step; just validates the POM). Spotless may or may not touch Markdown — if it does, accept the formatting.

Confirm via file listing:
```
ls tiko-archetype/src/main/resources/archetype-resources/AGENTS.md
ls tiko-archetype/src/main/resources/archetype-resources/.github/copilot-instructions.md
ls tiko-archetype/src/main/resources/archetype-resources/.junie/guidelines.md
```
All three should exist.

- [ ] **Step 5: Commit**

```bash
git add tiko-archetype/src/main/resources/archetype-resources/AGENTS.md \
        tiko-archetype/src/main/resources/archetype-resources/.github/copilot-instructions.md \
        tiko-archetype/src/main/resources/archetype-resources/.junie/guidelines.md
git commit -m "feat(archetype): add AGENTS.md + Copilot + Junie pointer files (#21)"
```

(Spotless apply if needed: `mvn -pl tiko-archetype spotless:apply`.)

---

## Task 2: Wire the new files into archetype-metadata.xml

**Files:**
- Modify: `tiko-archetype/src/main/resources/META-INF/maven/archetype-metadata.xml`

The current `archetype-metadata.xml` has filesets for `.ai-skills/**/*.md`, `.cursor/**/*.md`, and the top-level files (`gitignore`, `CLAUDE.md`). It needs entries for the three new files.

- [ ] **Step 1: Read the current file**

Open `W:\workspace\tiko-di\tiko-archetype\src\main\resources\META-INF\maven\archetype-metadata.xml` to see the existing `<fileSets>` structure. The existing pattern for AI files (`.ai-skills`, `.cursor`) uses one `<fileSet>` per top-level directory.

- [ ] **Step 2: Add the AGENTS.md include**

The top-level `<fileSet>` currently includes `gitignore` and `CLAUDE.md`. Add `AGENTS.md` to its `<includes>`:

```xml
<!-- Top-level project files. `gitignore` is renamed to `.gitignore` by the
     post-generate Groovy script (META-INF/archetype-post-generate.groovy);
     the archetype-plugin filters dotfiles out of the bundled archetype jar
     by default. -->
<fileSet filtered="true" encoding="UTF-8">
    <directory></directory>
    <includes>
        <include>gitignore</include>
        <include>CLAUDE.md</include>
        <include>AGENTS.md</include>
    </includes>
</fileSet>
```

- [ ] **Step 3: Add the .github and .junie filesets**

After the existing `.cursor` fileset, add two new filesets matching the same style:

```xml
<fileSet filtered="true" encoding="UTF-8">
    <directory>.github</directory>
    <includes>
        <include>**/*.md</include>
    </includes>
</fileSet>
<fileSet filtered="true" encoding="UTF-8">
    <directory>.junie</directory>
    <includes>
        <include>**/*.md</include>
    </includes>
</fileSet>
```

- [ ] **Step 4: Verify the file parses**

Run:
```
W:\tools\apache-maven\bin\mvn -pl tiko-archetype validate
```
Expected: BUILD SUCCESS. The archetype-plugin validates the metadata XML during the `validate` phase.

- [ ] **Step 5: Commit**

```bash
git add tiko-archetype/src/main/resources/META-INF/maven/archetype-metadata.xml
git commit -m "feat(archetype): register AGENTS.md + .github + .junie filesets in archetype-metadata.xml (#21)"
```

---

## Task 3: Run the integration test + manual smoke

**Files:**
- (None — verification only.)

The archetype's existing integration test (`tiko-archetype/src/test/resources/projects/basic/`) runs `mvn archetype:generate` against the bundled archetype, then `mvn compile` on the generated project. If the three new files end up in the generated project, we're done. If the `.github/` or `.junie/` directories get filtered out by maven-archetype-plugin's default-excludes, the IT will still pass (the new files are docs, not code) BUT a manual smoke will reveal they're missing — that's what Task 4 conditionally fixes.

- [ ] **Step 1: Run the archetype IT**

Run:
```
W:\tools\apache-maven\bin\mvn -pl tiko-archetype clean integration-test
```
Expected: BUILD SUCCESS. The `archetype:integration-test` goal generates a project from the archetype to `target/test-classes/projects/basic/project/`, then runs `mvn compile` (per the `goal.txt`) on that project.

- [ ] **Step 2: Inspect the generated project's file tree**

After the IT runs, the generated project lives under:
```
tiko-archetype/target/test-classes/projects/basic/project/basic-it/
```

List the files:
```
ls -la tiko-archetype/target/test-classes/projects/basic/project/basic-it
ls -la tiko-archetype/target/test-classes/projects/basic/project/basic-it/.github 2>/dev/null
ls -la tiko-archetype/target/test-classes/projects/basic/project/basic-it/.junie 2>/dev/null
```

(On PowerShell use `Get-ChildItem -Force` to see dotfiles.)

Confirm:
- `AGENTS.md` is at the project root.
- `.github/copilot-instructions.md` is at `.github/`.
- `.junie/guidelines.md` is at `.junie/`.
- `CLAUDE.md`, `.gitignore`, `.ai-skills/SKILL.md`, `.cursor/rules/tiko.md`, `pom.xml`, `src/main/java/com/example/basicit/{Main,Greeter}.java` still exist (existing behavior preserved).

- [ ] **Step 3: If `.github` and `.junie` ARE present, mark this task done**

If all three new files are in the generated project, skip Task 4 and proceed to Task 5. Note in the report that no Groovy script changes were needed.

Commit no changes for this verification task.

- [ ] **Step 4: If `.github` or `.junie` directories are MISSING, proceed to Task 4**

Note which directory(ies) got filtered out. The archetype-plugin's default-excludes typically targets `.gitignore`, `.svn/`, `CVS/`, `.git/`. `.github/` and `.junie/` may or may not be in the default list — depends on the plugin version. Task 4 adjusts the post-generate Groovy hook to compensate.

---

## Task 4 (conditional): Extend `archetype-post-generate.groovy` to handle filtered directories

**Files:**
- Modify (conditional): `tiko-archetype/src/main/resources/META-INF/archetype-post-generate.groovy`
- Modify (conditional): `tiko-archetype/src/main/resources/META-INF/maven/archetype-metadata.xml`
- Modify (conditional): `tiko-archetype/src/main/resources/archetype-resources/` (rename directories)

> **Only do this task if Task 3 Step 4 revealed filtering issues.** If Task 3 confirmed all files end up in the generated project, skip directly to Task 5.

The strategy mirrors the `gitignore` → `.gitignore` rename: ship the files under non-dotfile directory names (`github/`, `junie/`), then rename to `.github/` and `.junie/` in the post-generate hook.

- [ ] **Step 1: Rename archetype-resource directories**

```bash
git mv tiko-archetype/src/main/resources/archetype-resources/.github \
       tiko-archetype/src/main/resources/archetype-resources/github
git mv tiko-archetype/src/main/resources/archetype-resources/.junie \
       tiko-archetype/src/main/resources/archetype-resources/junie
```

(Only do the rename(s) for directories that were filtered. If only `.github` was filtered, rename only it.)

- [ ] **Step 2: Update archetype-metadata.xml**

Change the filesets to reference the non-dotfile names. For each renamed directory, change `<directory>.github</directory>` to `<directory>github</directory>` (and similarly for `.junie` → `junie`).

- [ ] **Step 3: Extend the Groovy post-generate hook**

In `tiko-archetype/src/main/resources/META-INF/archetype-post-generate.groovy`, add a directory rename for each affected directory. Mirror the existing `gitignore` → `.gitignore` pattern:

```groovy
// Post-generate hook: renames non-dotfile names → dotfile names in the generated project.
// Workaround for maven-archetype-plugin's default-excludes filtering out files/dirs
// matching dotfile patterns from the bundled archetype jar — we ship them under
// non-dotfile names and rename them back here.

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

def projectDir = Paths.get(request.outputDirectory, request.artifactId)

def renames = [
    ["gitignore",  ".gitignore"],
    ["github",     ".github"],
    ["junie",      ".junie"],
]

for (rename in renames) {
    def src = projectDir.resolve(rename[0])
    def dst = projectDir.resolve(rename[1])
    if (Files.exists(src)) {
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
    }
}
```

(If only one directory needed renaming, include only that one. Don't add no-op entries for directories that worked without the workaround.)

- [ ] **Step 4: Re-run the IT and inspect**

```
W:\tools\apache-maven\bin\mvn -pl tiko-archetype clean integration-test
ls -la tiko-archetype/target/test-classes/projects/basic/project/basic-it/.github
ls -la tiko-archetype/target/test-classes/projects/basic/project/basic-it/.junie
```

Expected: directories present in the generated project at their correct dotfile names.

- [ ] **Step 5: Commit**

```bash
git add tiko-archetype/src/main/resources/archetype-resources \
        tiko-archetype/src/main/resources/META-INF/maven/archetype-metadata.xml \
        tiko-archetype/src/main/resources/META-INF/archetype-post-generate.groovy
git commit -m "feat(archetype): rename .github + .junie via post-generate (workaround for plugin default-excludes) (#21)"
```

---

## Task 5: Update README.md

**Files:**
- Modify: `README.md`

The README's "Scaffold a new project (archetype)" section currently documents the existing archetype. Add a brief sentence listing the AI-context files included.

- [ ] **Step 1: Locate the archetype section**

Open `W:\workspace\tiko-di\README.md`, find "Scaffold a new project (archetype)" (around line 108 per the prior exploration). It currently shows the `mvn archetype:generate` command and a brief description.

- [ ] **Step 2: Add the AI-context note**

Insert a paragraph immediately after the `mvn archetype:generate` example (before the `-DarchetypeCatalog=local` explanation):

```markdown
The generated project ships with AI-context files for the major coding
agents — `CLAUDE.md` (canonical), `AGENTS.md`, `.cursor/rules/tiko.md`,
`.github/copilot-instructions.md`, `.junie/guidelines.md`,
`.ai-skills/SKILL.md`. Each tool-specific file points at `CLAUDE.md` as
the source of truth; edit one file when conventions change.
```

Adjust wording / placement to fit the surrounding tone. Don't overhaul the section.

- [ ] **Step 3: Verify Spotless still passes**

Run:
```
W:\tools\apache-maven\bin\mvn -pl '!tiko-bom' spotless:check
```
Expected: clean. (Spotless probably doesn't touch the README, but verify.)

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: README mentions the AI-context files included in archetype output (#21)"
```

---

## Task 6: Update `docs/roadmap.md` — mark #21 shipped

**Files:**
- Modify: `docs/roadmap.md`

- [ ] **Step 1: Move #21 from Phase 3 Open to Shipped**

In `docs/roadmap.md`, find Phase 3. After #122, #127, #128, #129 shipped, the counter should be `4/6 closed`. Move #21 to Shipped, update to `5/6 closed`.

Add the Shipped bullet:

```markdown
- ✅ tiko-archetype: ships AGENTS.md + `.github/copilot-instructions.md` + `.junie/guidelines.md` pointer files alongside the existing `CLAUDE.md` / `.cursor/rules/tiko.md` / `.ai-skills/SKILL.md` — generated projects come fully AI-aware, every tool's file points at `CLAUDE.md` as the single source of truth ([#21](https://github.com/tomas-samek/tiko-di/issues/21)).
```

Remove the corresponding Open entry. Update the counter.

- [ ] **Step 2: Commit**

```bash
git add docs/roadmap.md
git commit -m "docs(roadmap): mark #21 shipped under Phase 3"
```

---

## Task 7: Final smoke + PR

- [ ] **Step 1: Full clean build**

```
W:\tools\apache-maven\bin\mvn clean install
```
Expected: BUILD SUCCESS across all modules.

- [ ] **Step 2: Manual smoke — generate a fresh project from the archetype**

After `mvn clean install` populates the local Maven repository with the new archetype version, generate a fresh project in a scratch directory:

```bash
cd %TEMP%
W:\tools\apache-maven\bin\mvn archetype:generate \
    -DarchetypeGroupId=io.tiko \
    -DarchetypeArtifactId=tiko-archetype \
    -DarchetypeVersion=0.1.0 \
    -DarchetypeCatalog=local \
    -DgroupId=com.example \
    -DartifactId=ai-archetype-smoke \
    -DinteractiveMode=false
```

(PowerShell variant: `cd $env:TEMP`.)

Inspect the generated `ai-archetype-smoke/` directory. Confirm all six AI-context files are present:

- `CLAUDE.md`
- `AGENTS.md`
- `.ai-skills/SKILL.md`
- `.cursor/rules/tiko.md`
- `.github/copilot-instructions.md`
- `.junie/guidelines.md`

Also confirm:
- `.gitignore` exists (renamed from `gitignore` by post-generate).
- `pom.xml`, `src/main/java/com/example/aiarchetypesmoke/Main.java`, `src/main/java/com/example/aiarchetypesmoke/Greeter.java` exist.
- `mvn -f ai-archetype-smoke/pom.xml compile` succeeds.

Document the findings in the PR description (especially any deviations from expectation).

- [ ] **Step 3: Push the branch**

```bash
git push -u origin spec/21-ai-context-files-in-archetype
```

- [ ] **Step 4: Open the PR**

```bash
gh pr create --title "feat: AI-context pointer files in tiko-archetype (#21)" --body "Closes #21. Implements docs/superpowers/specs/2026-05-22-ai-context-files-in-archetype-design.md."
```

Include the manual smoke results in the PR body.

---

## Self-review notes

**Spec coverage:**
- Three new pointer files (AGENTS.md, .github/copilot-instructions.md, .junie/guidelines.md): Task 1 ✓
- archetype-metadata.xml filesets: Task 2 ✓
- Post-generate Groovy adjustment (conditional): Task 4 ✓
- IT smoke validates compile: Task 3 ✓
- Manual smoke validates file presence: Task 7 ✓
- README update: Task 5 ✓
- Roadmap update: Task 6 ✓

**Type / name consistency:**
- All three new files: same skeleton, tool-specific framing. Pointer pattern consistent.
- `${package}` template variable used in two places (AGENTS.md and .junie/guidelines.md `mvn exec:java` examples). The archetype-plugin filters all three new files (`filtered="true"`) so the substitution fires.

**Known risks:**
- Task 4 is conditional on Maven plugin behavior I can't verify without running the IT. The implementer must run Task 3 and decide whether Task 4 is needed. The plan covers both branches explicitly.
- The plan doesn't refresh the EXISTING AI files (CLAUDE.md, .ai-skills/SKILL.md, .cursor/rules/tiko.md) — out of scope per the spec. They may be stale relative to recent framework changes (#127, #128, #129); a separate housekeeping issue could pick that up.
