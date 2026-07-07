# Archetype Context Slimming Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut the scaffolded project's default-read agent context from 38.5KB to ≤18KB by chunking the tiko-build skill into a spine + `reference/` files, slimming the archetype `CLAUDE.md` to a balanced spine, and dropping the cookbook-extension skill from scaffolds — with a directory-aware sync gate and zero content loss.

**Architecture:** Restructure-by-relocation: sections move verbatim between files (cut/paste by heading boundaries), with new navigation maps and scope headers written here in full. The #408 sync gate is upgraded from per-file to per-directory before the bundled copies are regenerated with `ArchetypeDocSync.main`. Every removed `CLAUDE.md` block has a named surviving home (audit table below).

**Tech Stack:** Markdown; Java (the sync tool/test in tiko-archetype test sources); Maven at `W:\tools\apache-maven\bin\mvn.cmd` (not on PATH).

**Spec:** `docs/superpowers/specs/2026-07-07-archetype-context-slimming-design.md`

## Global Constraints

- Branch: `docs/archetype-context-slimming` (checked out; spec committed).
- **Relocation over deletion:** moved sections are cut/pasted verbatim; permitted edits are only (a) fixing cross-references that became cross-file, (b) the new scope-header line atop each chunk, (c) dedup where the audit table names the surviving copy.
- Line numbers below refer to the files at branch creation (commit `519928b`); after the first edit they shift — always match on the quoted heading text.
- Canonical `.ai-skills/tiko-build/` is edited by hand; the bundled copy is **only ever produced by `ArchetypeDocSync.main`** — never hand-edit the bundled copy in this plan.
- Chunk cross-links inside the skill are relative within the skill directory (e.g. `reference/api-signatures.md`) — the `forArchetype` transformation only rewrites `](../../...)` links, so these pass through unchanged and resolve in both locations.
- Every `@Annotation` in prose in backticks (mention-harvest rule).
- Log discipline: `& W:\tools\apache-maven\bin\mvn.cmd <goals> *> W:\workspace\slim-<step>.log; $LASTEXITCODE`, check via PowerShell Select-String (UTF-16 logs).
- Use Read/Edit/Write tools for all file changes.
- Commits (three, per spec, single-line, no body/trailer):
  1. `docs(skill): split tiko-build into spine + reference chunks; directory-aware sync gate (#269)`
  2. `docs(archetype): slim CLAUDE.md to balanced spine with relocation audit (#269)`
  3. `docs(archetype): drop bundled cookbook-extension skill from scaffolds (#269)`

---

### Task 1: Chunk the canonical tiko-build skill

**Files:**
- Modify: `.ai-skills/tiko-build/SKILL.md` (currently 421 lines; headings at 17/30/45/77/114/166/184/298/385/406/415)
- Create: `.ai-skills/tiko-build/reference/api-signatures.md`
- Create: `.ai-skills/tiko-build/reference/kafka.md`
- Create: `.ai-skills/tiko-build/reference/config.md`
- Create: `.ai-skills/tiko-build/reference/events.md`

**Interfaces:**
- Produces: the four chunk paths above and the spine section set — Task 2's gate syncs them; Task 3's CLAUDE.md navigation map names them.

- [ ] **Step 1: Create the four chunk files from the current sections**

Cut these whole sections from `SKILL.md` (heading line through the line before the next `##` heading) and paste each into its chunk file **below** the scope header shown:

| chunk file | scope header (first lines of the new file) | section(s) moved (current lines) |
|---|---|---|
| `reference/events.md` | `# tiko-build reference — imperative publish & process lifetime`<br>`> Read this when: publishing events imperatively, or writing a headless/daemon main.` | `## Imperative publish & keeping the process alive` (77–113) |
| `reference/config.md` | `# tiko-build reference — typed configuration`<br>`> Read this when: declaring \`@Configuration\` records or writing override YAML.` | `## Typed config: keys are exact` (114–165) |
| `reference/api-signatures.md` | `# tiko-build reference — API signature sheet`<br>`> Read this when: writing any import, or unsure of a signature, annotation attribute, or config key.` | `## API signature sheet — exact imports and signatures` (184–297, all four `###` subsections) |
| `reference/kafka.md` | `# tiko-build reference — Kafka transport`<br>`> Read this when: consuming/producing Kafka, or writing the Kafka integration test.` | `## Kafka transport: write this shape first` incl. `### Testing Kafka bridges...` (298–384) |

Demote each moved `##` heading to `##` under the new `#` title (keep as `##`; subsections stay `###`).

- [ ] **Step 2: Fix cross-references that became cross-file**

In the moved text and the remaining spine, grep for references and fix:
- In `reference/kafka.md`: the sentence `see the key table in the API signature sheet above` → `see the key table in [\`reference/api-signatures.md\`](api-signatures.md)` (link relative to the `reference/` dir → plain `api-signatures.md`).
- In `reference/config.md`: if it references the signature sheet or key table, link to `api-signatures.md` the same way.
- In the spine: any `see the ... section below/above` sentence that pointed at a moved section now links to the chunk (path relative to `SKILL.md`: `reference/<name>.md`). Run `grep -n "above\|below\|§" .ai-skills/tiko-build/SKILL.md .ai-skills/tiko-build/reference/*.md` and fix every hit that refers to a moved/moving section.
- In the `## Cookbook table` rows: replace any pointer of the form "skill §N"/"this skill's <section>" that named a moved section with the chunk filename (e.g. `reference/kafka.md`).

- [ ] **Step 3: Insert the navigation map into the spine**

Immediately after the `## The rule` section (before `## When in doubt, ask`), insert:

```markdown
## Reference chunks — read on demand, not upfront

The depth lives in `reference/`. Open a chunk when its trigger fires; do
not read them all upfront.

| file | read when |
|---|---|
| [`reference/api-signatures.md`](reference/api-signatures.md) | writing any import, or unsure of a signature / annotation attribute / config key |
| [`reference/kafka.md`](reference/kafka.md) | consuming or producing Kafka, or writing the Kafka integration test |
| [`reference/config.md`](reference/config.md) | declaring `@Configuration` records or writing override YAML |
| [`reference/events.md`](reference/events.md) | publishing events imperatively, or a headless/daemon main |

A `cannot find symbol` on an import means a wrong package, not a missing
feature — `reference/api-signatures.md` first, then `javap`.
```

- [ ] **Step 4: Trim the closing pointer section**

Replace the body of `## Need a recipe the cookbook doesn't have?` (line ~415 to EOF) with exactly two lines: the existing first sentence pointing at asking the user, plus `To contribute the recipe upstream, see [the cookbook-extension skill](../../.ai-skills/tiko-cookbook-extension/SKILL.md).` (repo-relative — `forArchetype` turns it into a GitHub link in the bundled copy, which is correct since the bundled copy of that skill is being dropped).

- [ ] **Step 5: Verify spine size and content split**

Run (Git Bash): `wc -c .ai-skills/tiko-build/SKILL.md .ai-skills/tiko-build/reference/*.md`
Expected: `SKILL.md` ≤ 8192 bytes; the four chunks sum ≈ 13.5KB; total ≈ today's 20.4KB ± the nav map. Run `grep -c "^## " .ai-skills/tiko-build/SKILL.md` → expected 8 (rule, nav map, ask, scaffolding, cookbook, anti-pattern, not-cover, recipe-pointer). No commit yet — Task 2 commits jointly.

---

### Task 2: Directory-aware sync gate + regenerate the bundled copy

**Files:**
- Modify: `tiko-archetype/src/test/java/io/tiko/archetype/ArchetypeDocSync.java`
- Modify: `tiko-archetype/src/test/java/io/tiko/archetype/ArchetypeBundledSkillsInSyncTest.java`
- Regenerate (via tool, not by hand): `tiko-archetype/src/main/resources/archetype-resources/.ai-skills/tiko-build/**`

**Interfaces:**
- Consumes: Task 1's canonical chunk layout.
- Produces: `ArchetypeDocSync.SYNCED_SKILLS == List.of("tiko-build")` (Task 4 relies on cookbook-extension no longer being synced); directory-sync semantics; regenerated bundled skill.

- [ ] **Step 1: Rewrite `ArchetypeDocSync` for directory semantics**

Replace `canonical(String)`/`bundled(String)` usage with directory walking (keep `forArchetype` unchanged; keep the class javadoc, updating the second paragraph to say "every `*.md` under the skill directory"):

```java
    /** Skills bundled by the archetype that are copies of a canonical repo-root skill. */
    public static final List<String> SYNCED_SKILLS = List.of("tiko-build");

    /** Canonical skill directory, relative to the {@code tiko-archetype} module directory. */
    public static Path canonicalDir(String skill) {
        return Path.of("..", ".ai-skills", skill);
    }

    /** Bundled skill directory, relative to the {@code tiko-archetype} module directory. */
    public static Path bundledDir(String skill) {
        return Path.of("src", "main", "resources", "archetype-resources", ".ai-skills", skill);
    }

    /** Every markdown file under {@code root}, as sorted paths relative to {@code root}. */
    public static List<Path> markdownFiles(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream.filter(p -> p.toString().endsWith(".md"))
                    .map(root::relativize)
                    .sorted()
                    .toList();
        }
    }

    /** Regenerates every bundled skill directory from its canonical source. Run from {@code tiko-archetype}. */
    public static void main(String[] args) throws IOException {
        for (String skill : SYNCED_SKILLS) {
            Path from = canonicalDir(skill);
            Path to = bundledDir(skill);
            // Remove bundled markdown with no canonical counterpart (renames/deletions propagate).
            for (Path stale : markdownFiles(to)) {
                if (!Files.exists(from.resolve(stale))) {
                    Files.delete(to.resolve(stale));
                    System.out.println("deleted stale " + to.resolve(stale));
                }
            }
            for (Path rel : markdownFiles(from)) {
                Path target = to.resolve(rel);
                Files.createDirectories(target.getParent());
                Files.writeString(target, forArchetype(Files.readString(from.resolve(rel))));
                System.out.println("regenerated " + target);
            }
        }
    }
```

(Delete the old single-file `canonical`/`bundled` methods after updating their callers in the test.)

- [ ] **Step 2: Rewrite the gate test for per-file directory comparison**

```java
class ArchetypeBundledSkillsInSyncTest {

    static List<String> syncedSkills() {
        return ArchetypeDocSync.SYNCED_SKILLS;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("syncedSkills")
    void bundledSkillDirectoryIsInSyncWithCanonical(String skill) throws Exception {
        Path canonicalDir = ArchetypeDocSync.canonicalDir(skill);
        Path bundledDir = ArchetypeDocSync.bundledDir(skill);
        List<Path> canonicalFiles = ArchetypeDocSync.markdownFiles(canonicalDir);
        List<Path> bundledFiles = ArchetypeDocSync.markdownFiles(bundledDir);

        assertThat(bundledFiles)
                .as("bundled %s markdown set must mirror canonical (regenerate via ArchetypeDocSync.main)", skill)
                .containsExactlyElementsOf(canonicalFiles);

        for (Path rel : canonicalFiles) {
            String expected =
                    ArchetypeDocSync.forArchetype(Files.readString(canonicalDir.resolve(rel)));
            String actual = Files.readString(bundledDir.resolve(rel));
            assertThat(normalize(actual))
                    .as(
                            "Bundled .ai-skills/%s/%s has drifted from canonical. Regenerate from the"
                                    + " tiko-archetype/ directory: `mvn -q test-compile` then `java -cp"
                                    + " target/test-classes io.tiko.archetype.ArchetypeDocSync`.",
                            skill, rel)
                    .isEqualTo(normalize(expected));
        }
    }

    private static String normalize(String markdown) {
        return markdown.replace("\r\n", "\n");
    }
}
```

- [ ] **Step 3: Red run — the gate must fail before regeneration**

Run: `& W:\tools\apache-maven\bin\mvn.cmd test -pl tiko-archetype -am "-Dtest=ArchetypeBundledSkillsInSyncTest" *> W:\workspace\slim-gate-red.log; $LASTEXITCODE`
Expected: non-zero — the bundled `tiko-build` still has the old monolithic `SKILL.md` and no `reference/` files (file-set mismatch). This proves the gate detects desync in both shape and content.

- [ ] **Step 4: Regenerate the bundled copy with the tool**

```powershell
Set-Location W:\workspace\tiko-di\tiko-archetype
& W:\tools\apache-maven\bin\mvn.cmd -q test-compile *> W:\workspace\slim-regen-compile.log
java -cp target/test-classes io.tiko.archetype.ArchetypeDocSync
Set-Location W:\workspace\tiko-di
```

Expected output: one `regenerated ...` line per markdown file (spine + 4 chunks), plus no stale deletions on first run except none.

- [ ] **Step 5: Green run + link-transformation spot check**

Run: `& W:\tools\apache-maven\bin\mvn.cmd test -pl tiko-archetype -am *> W:\workspace\slim-gate-green.log; $LASTEXITCODE`
Expected: `0`, BUILD SUCCESS.
Spot check (Git Bash): `grep -n "](reference/" tiko-archetype/src/main/resources/archetype-resources/.ai-skills/tiko-build/SKILL.md | head -4` → intra-skill links unchanged (still relative); `grep -n "github.com" tiko-archetype/src/main/resources/archetype-resources/.ai-skills/tiko-build/SKILL.md | head -3` → repo-relative links became GitHub URLs, as before.

- [ ] **Step 6: Commit 1**

```powershell
git add .ai-skills/tiko-build tiko-archetype/src/test/java/io/tiko/archetype tiko-archetype/src/main/resources/archetype-resources/.ai-skills/tiko-build
git commit -m "docs(skill): split tiko-build into spine + reference chunks; directory-aware sync gate (#269)"
```

---

### Task 3: Slim the archetype CLAUDE.md to the balanced spine

**Files:**
- Modify: `tiko-archetype/src/main/resources/archetype-resources/CLAUDE.md` (currently ~420 lines; headings listed in the audit table)
- Modify (receiving relocations): `.ai-skills/tiko-build/reference/config.md`, `.ai-skills/tiko-build/reference/events.md` — **canonical copies**, then re-run the Task 2 regeneration so the bundled copies follow.

**Interfaces:**
- Consumes: Task 1's chunk files and Task 2's regeneration tool.
- Produces: the slimmed CLAUDE.md; the relocation audit implemented exactly as tabled.

- [ ] **Step 1: Apply the relocation audit table**

For each row: verify the "already covered" claim by reading the named target; if the target does NOT cover the content, move the block there instead of dropping it (and say so in the report).

| CLAUDE.md block (current lines) | action → surviving home |
|---|---|
| `### Constructor injection` (107–129) | **KEEP** in CLAUDE.md (the one canonical pattern) |
| `### Disambiguating with `@Named`` (130–145) | drop — already covered by the cheat-sheet row (`@Named`/`@Pick`, line ~48-60) + GitHub `docs/di-and-scopes.md` (linked under `## Where to dig deeper`); add `(worked example: docs/di-and-scopes.md)` to the cheat-sheet row |
| `### Factory methods with `@Produces`` (146–158) | drop — verify the spine's `## Scaffolding shape` shows a `@Produces` factory; if it does not, move this block into that section (canonical skill) |
| `### Lifecycle hooks` (159–175) | move → `reference/events.md` (fits startup/shutdown hooks; dedupe with any existing lifecycle text there) |
| `### Cross-scope proxy` (176–195) | drop — verify `## Scopes` (27–45) states the proxy rule in ≥2 lines; if not, add a two-line summary to `## Scopes`; the worked example is covered by GitHub `docs/di-and-scopes.md` |
| `### YAML configuration (requires `tiko-config`)` (196–234) | move → `reference/config.md` (dedupe: the chunk already has the exact-keys walkthrough — keep the fuller of overlapping fragments, once) |
| `### Events` (235–265) + `### Declarative chains with `@EventTrigger`` (266–275) | move → `reference/events.md` (dedupe with imperative-publish content) |
| `### Testing with `@TikoTest`` (276–307) | **KEEP** in CLAUDE.md — plan-level clarification of the spec: the `Done` criterion depends on tests and weak models read only this file |
| `## MCP topology server` (361–381) | condense in place to 3 lines: what it is, the jbang auto-connect note, and a link `https://github.com/tomas-samek/tiko-di/tree/main/tiko-examples/13_mcp_introspection`; the removed detail is covered by that example's README |
| everything else (`## Scopes`, cheat-sheet + `### Exact packages`, `## Rules`, `## Common pitfalls`, `## Build and run` + daemon subsection, `## Optional Tiko modules`, `## Where to dig deeper`, the scaffolded-app placeholder sections 395–end) | **KEEP** |

- [ ] **Step 2: Insert the navigation map into CLAUDE.md**

Immediately after the `### Exact packages...` block (before `## Rules`), insert:

```markdown
### Where the depth lives (read on demand)

| file | read when |
|---|---|
| [`.ai-skills/tiko-build/SKILL.md`](./.ai-skills/tiko-build/SKILL.md) | starting any new service work — decision tree, cookbook, anti-patterns |
| [`reference/api-signatures.md`](./.ai-skills/tiko-build/reference/api-signatures.md) | writing any import, or unsure of a signature / attribute / config key |
| [`reference/kafka.md`](./.ai-skills/tiko-build/reference/kafka.md) | consuming or producing Kafka, or the Kafka integration test |
| [`reference/config.md`](./.ai-skills/tiko-build/reference/config.md) | `@Configuration` records or override YAML |
| [`reference/events.md`](./.ai-skills/tiko-build/reference/events.md) | imperative publish, lifecycle hooks, daemon keep-alive |
```

- [ ] **Step 3: Re-run regeneration + verify sizes**

Re-run Task 2 Step 4's regeneration (the canonical chunks changed in Step 1). Then:
`wc -c tiko-archetype/src/main/resources/archetype-resources/CLAUDE.md` → expected ≤ 10,500 bytes.
`wc -c tiko-archetype/src/main/resources/archetype-resources/.ai-skills/tiko-build/SKILL.md` → still ≤ 8,192.
Sum of the two ≤ 18,432 (the spec's ≤18KB acceptance).
`grep -rn "bootstrapServers\|@KafkaSource" tiko-archetype/src/main/resources/archetype-resources/CLAUDE.md | head` → the packages table and kebab-case clause survived intact.

- [ ] **Step 4: Run the archetype tests green, then Commit 2**

Run: `& W:\tools\apache-maven\bin\mvn.cmd test -pl tiko-archetype -am *> W:\workspace\slim-claude-green.log; $LASTEXITCODE` → 0, BUILD SUCCESS.

```powershell
git add tiko-archetype/src/main/resources/archetype-resources/CLAUDE.md .ai-skills/tiko-build tiko-archetype/src/main/resources/archetype-resources/.ai-skills/tiko-build
git commit -m "docs(archetype): slim CLAUDE.md to balanced spine with relocation audit (#269)"
```

---

### Task 4: Drop the bundled cookbook-extension skill

**Files:**
- Delete: `tiko-archetype/src/main/resources/archetype-resources/.ai-skills/tiko-cookbook-extension/` (whole directory)
- Modify: `tiko-archetype/src/main/resources/archetype-resources/.ai-skills/SKILL.md` (the index)
- Check-only: `tiko-archetype/src/main/resources/META-INF/maven/archetype-metadata.xml` (the `.ai-skills` fileSet ships the directory wholesale — confirm no per-file include lists the deleted skill), and the pointer files (`AGENTS.md`, `.cursor/rules/tiko.md`, `.junie/guidelines.md`, `.github/copilot-instructions.md`) for references to the deleted skill.

**Interfaces:**
- Consumes: Task 2's `SYNCED_SKILLS` already excludes `tiko-cookbook-extension` (the gate no longer requires the bundled copy).
- Produces: final archetype payload.

- [ ] **Step 1: Delete the directory and update the index**

`Remove-Item -Recurse -Force tiko-archetype/src/main/resources/archetype-resources/.ai-skills/tiko-cookbook-extension -Confirm:$false`

In the index `.ai-skills/SKILL.md`, replace the whole `## Hit a library the cookbook doesn't cover?` section body with:

```markdown
Ask the user for the missing facts rather than fabricating an integration
(**ask, don't fabricate**). To contribute the recipe upstream, follow
[the cookbook-extension skill on GitHub](https://github.com/tomas-samek/tiko-di/blob/main/.ai-skills/tiko-cookbook-extension/SKILL.md).
```

Also in the index's `## Building a new service...` paragraph, append one sentence: `Depth (API signatures, Kafka, config, events) lives in \`tiko-build/reference/\` — the skill's navigation map says when to open each file.`

- [ ] **Step 2: Sweep for stale references**

Run (Git Bash): `grep -rn "cookbook-extension" tiko-archetype/src/main/resources/archetype-resources/`
Expected: only the new GitHub-URL line in the index. Fix any other hit (pointer files, CLAUDE.md) to either drop the mention or use the GitHub URL. Confirm `archetype-metadata.xml` has no per-file include naming the deleted path.

- [ ] **Step 3: Full reactor green + payload report + Commit 3**

Run: `& W:\tools\apache-maven\bin\mvn.cmd test *> W:\workspace\slim-full.log; $LASTEXITCODE` → 0, BUILD SUCCESS (spotless + directory gate + all modules).

Byte report for the PR body (Git Bash):
`find tiko-archetype/src/main/resources/archetype-resources -name "*.md" | while read f; do printf "%7d  %s\n" "$(wc -c < "$f")" "$f"; done | sort -rn`
Record: CLAUDE.md + tiko-build/SKILL.md sum (target ≤ 18,432; before: 38,553), and the total (before: ~60,337).

```powershell
git add -A tiko-archetype/src/main/resources/archetype-resources/.ai-skills
git commit -m "docs(archetype): drop bundled cookbook-extension skill from scaffolds (#269)"
```

---

### Task 5: Push + PR

- [ ] **Step 1:** PR body to a scratch file: summary of the three commits, the byte-count table (before/after for default payload and total), the relocation audit summary (rows + outcomes), the risk note ("named-hop bet, measured by the next benchmark cell, cheap to revert"), footer `🤖 Generated with [Claude Code](https://claude.com/claude-code)`. Then:

```powershell
git push -u origin docs/archetype-context-slimming
& "C:\Program Files\GitHub CLI\gh.exe" pr create --title "docs: archetype context slimming - tiko-build spine + reference chunks, CLAUDE.md balanced spine, drop bundled cookbook-extension (#269)" --body-file <scratch>\pr-body-slim.md
```

- [ ] **Step 2:** `gh pr checks --watch`, SonarCloud open-issues query, report. User merges.

---

## Self-Review Notes

- Spec coverage: Component 1 → Tasks 1–2; Component 2 → Task 3 (audit table implemented; two spec ambiguities resolved and labeled: `@TikoTest` stays, `@Named`/cross-scope drop-with-verification); Component 3 → Task 4; Component 4 → Task 2; Acceptance → Task 3 Step 3 + Task 4 Step 3 byte checks, pointer sweep in Task 4 Step 2, README/repo-CLAUDE.md pointers unaffected (path `.ai-skills/tiko-build/SKILL.md` unchanged — no task needed); Risk/measurement → PR body note.
- Type consistency: `SYNCED_SKILLS`, `canonicalDir`, `bundledDir`, `markdownFiles` used identically in Task 2's tool and test; chunk filenames identical across Tasks 1, 2, 3 and both navigation maps.
- Deliberate sequencing: gate red-run (Task 2 Step 3) happens against Task 1's real restructure — the red state doubles as proof the new gate catches both missing files and stale content.
