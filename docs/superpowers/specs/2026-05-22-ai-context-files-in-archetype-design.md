# AI-context files in the existing `tiko-archetype`

**Status:** Design approved 2026-05-22. Implementation plan to follow.
**Tracker:** [#21](https://github.com/tomas-samek/tiko-di/issues/21)
**Milestone:** Phase 3 — Onboarding & tooling
**Predecessors:**
- [#20](https://github.com/tomas-samek/tiko-di/issues/20) — the `tiko-archetype` module being extended (already ships)

## Goal

A user who scaffolds a new Tiko DI project via `mvn archetype:generate
-DarchetypeArtifactId=tiko-archetype` gets a project that already has
the AI-context files most common coding agents look for:
`CLAUDE.md`, `AGENTS.md`, `.cursor/rules/tiko.md`,
`.github/copilot-instructions.md`, `.junie/guidelines.md`, plus the
existing `.ai-skills/SKILL.md`.

When the user opens the generated project in their AI-enabled editor,
the agent picks up the conventions for Tiko DI immediately — without
the user having to write them down.

## Non-goals

- A separate `tiko-archetype-ai-quickstart` module. The existing
  archetype already ships `CLAUDE.md`, `.ai-skills/SKILL.md`, and
  `.cursor/rules/tiko.md`; the value of two archetypes is weak. We
  extend the existing one instead.
- Refreshing the *content* of the existing AI files
  (`CLAUDE.md`, `.ai-skills/SKILL.md`, `.cursor/rules/tiko.md`). They
  may be stale; that's a separate concern. This spec only adds the
  missing three files.
- A build-time fanout pipeline that generates per-tool files from a
  single canonical Markdown snippet. The pointer pattern (see below)
  achieves the "single source of truth" goal more cheaply.
- Auto-syncing the archetype's `CLAUDE.md` with the root project's
  `CLAUDE.md`. Manual snapshot per the original #21 framing.

## Design decisions

Two foundational calls made during brainstorming:

1. **Extend the existing archetype, do not fork a new one.** The
   existing `tiko-archetype` already has CLAUDE.md, .ai-skills, and
   Cursor rules. Adding the three missing files (AGENTS.md, Copilot,
   Junie) is a smaller diff than maintaining two modules.
2. **Pointer pattern: CLAUDE.md is the single source of truth.** The
   three new files are short pointer documents (~25 lines each) telling
   their respective tools to read CLAUDE.md for the canonical
   conventions. One file to maintain when guidelines change. Tools that
   read additional files in the workspace pick up CLAUDE.md; tools that
   only read their dedicated file get a clear directive to look there.

## Architecture

### Files added under `tiko-archetype/src/main/resources/archetype-resources/`

```
archetype-resources/
├── AGENTS.md                                    ← NEW (~25 lines)
├── CLAUDE.md                                    ← unchanged (canonical)
├── .ai-skills/SKILL.md                          ← unchanged
├── .cursor/rules/tiko.md                        ← unchanged
├── .github/copilot-instructions.md              ← NEW (~25 lines)
├── .junie/guidelines.md                         ← NEW (~25 lines)
├── gitignore                                    ← unchanged
├── pom.xml                                      ← unchanged
└── src/main/java/{Main,Greeter}.java            ← unchanged
```

### `archetype-metadata.xml` changes

Add three new `<fileSet>` entries — one per new file — to
`tiko-archetype/src/main/resources/META-INF/maven/archetype-metadata.xml`.
Match the existing pattern (each fileset names a directory + an include
glob, `filtered="true"`, `packaged="false"`).

Suggested form:

```xml
<fileSet filtered="true" packaged="false">
    <directory></directory>
    <includes>
        <include>AGENTS.md</include>
    </includes>
</fileSet>
<fileSet filtered="true" packaged="false">
    <directory>.github</directory>
    <includes>
        <include>copilot-instructions.md</include>
    </includes>
</fileSet>
<fileSet filtered="true" packaged="false">
    <directory>.junie</directory>
    <includes>
        <include>guidelines.md</include>
    </includes>
</fileSet>
```

The implementer may choose to consolidate with existing AI-related
filesets if Maven's archetype-plugin permits broader include globs.

### `archetype-post-generate.groovy` — no change needed

The existing script renames `gitignore` → `.gitignore` because Maven's
archetype-plugin filters out files starting with `.`. The three new
files have their final names (`AGENTS.md`, `copilot-instructions.md`,
`guidelines.md`) at archetype-resource time, so they pass through the
plugin's default filtering. The directories `.github` and `.junie` may
need the same rename treatment if the plugin filters them; verify
during implementation. If yes, extend the Groovy script.

### Smoke test

The existing integration test
(`tiko-archetype/src/test/resources/projects/basic/`) runs
`archetype:generate` + `mvn compile` on the generated project. The new
files are documentation, not code, so the existing smoke continues to
validate the archetype. No new IT needed.

## Content shape — all three new files

All three files share the same skeleton, with tool-specific framing.
Total per-file size: ~25 lines.

### `AGENTS.md`

Vendor-neutral. Follows the [agents.md](https://agents.md) convention
of a brief project orientation doc.

```markdown
# Tiko DI project

This project uses [Tiko DI](https://github.com/tomas-samek/tiko-di) —
a compile-time dependency injection framework for Java 21+.

## Read this first

The canonical conventions live in [`CLAUDE.md`](./CLAUDE.md) at the
project root. It covers:

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

### `.github/copilot-instructions.md`

Copilot-specific. Same content; framed as instructions.

```markdown
# Copilot instructions

This project uses Tiko DI — a compile-time dependency injection
framework for Java 21+. **Read [`CLAUDE.md`](../CLAUDE.md) for the full
conventions before suggesting code.** The summary below is a refresher;
CLAUDE.md is authoritative.

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

### `.junie/guidelines.md`

JetBrains AI / Junie format. Action-oriented bullets.

```markdown
# Junie guidelines

This project uses Tiko DI — a compile-time DI framework for Java 21+.
The canonical rules live in [`CLAUDE.md`](../CLAUDE.md); read it
before generating code. The bullets below are a refresher.

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

### Template variables

All three files are processed by Maven's archetype-plugin with
`filtered="true"`, so `${package}` and other archetype properties
expand at generation time. Only `${package}` is needed (in the
`mvn exec:java` example line).

## README update

In `README.md`, the existing "Scaffold a new project (archetype)"
section already documents `tiko-archetype`. Add a one-line list of the
AI-context files included:

```markdown
The generated project ships with AI-context files for the major coding
agents — `CLAUDE.md` (canonical), `AGENTS.md`, `.cursor/rules/tiko.md`,
`.github/copilot-instructions.md`, `.junie/guidelines.md`,
`.ai-skills/SKILL.md`. Each tool-specific file points at `CLAUDE.md` as
the source of truth; edit one file when conventions change.
```

No new section needed.

## Acceptance

- [ ] Three new files (`AGENTS.md`, `.github/copilot-instructions.md`,
  `.junie/guidelines.md`) exist in
  `tiko-archetype/src/main/resources/archetype-resources/`.
- [ ] Each new file is ~25 lines, points at `CLAUDE.md`, and follows the
  per-tool tone described above.
- [ ] `archetype-metadata.xml` includes filesets for all three new files.
- [ ] `archetype-post-generate.groovy` adjusts the `.github` / `.junie`
  directory inclusion if the archetype-plugin filters them out.
- [ ] `mvn -pl tiko-archetype integration-test` passes (existing smoke
  validates archetype:generate + compile).
- [ ] Manually verify a generated project includes all six AI-context
  files (`mvn archetype:generate` against the locally-built archetype,
  inspect the output directory).
- [ ] README updated to list all AI-context files included in the
  generated project.

## Out of scope

- Separate `tiko-archetype-ai-quickstart` module.
- Build-time fanout pipeline.
- Refresh of existing `CLAUDE.md`, `.ai-skills/SKILL.md`,
  `.cursor/rules/tiko.md` content. Their content is what it is; this
  spec only adds the missing three files.
- Auto-syncing the archetype's `CLAUDE.md` with the root project's
  `CLAUDE.md`.

## References

- `tiko-archetype/src/main/resources/META-INF/maven/archetype-metadata.xml`
  — current fileset declarations
- `tiko-archetype/src/main/resources/archetype-resources/CLAUDE.md`
  — the canonical doc the new files point at
- `tiko-archetype/src/main/resources/META-INF/archetype-post-generate.groovy`
  — post-generate hook for filename adjustments
- [#21](https://github.com/tomas-samek/tiko-di/issues/21) — tracker
- [agents.md](https://agents.md) — AGENTS.md convention
