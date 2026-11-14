# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Read the skill for the job first. Each one carries a **load-bearing rule** you must not skip.

- **Building a service on Tiko?** [`.ai-skills/tiko-build/SKILL.md`](./.ai-skills/tiko-build/SKILL.md),
  the operational distillation of [`docs/orchestrator-model.md`](./docs/orchestrator-model.md): decision
  tree, `@Produces` cookbook, and the anti-pattern redirects that stop agents from searching for
  "tiko's equivalent of Spring's X". This CLAUDE.md is for developing the repo; the skill is for
  **building with** the framework.
- **Adding a cookbook recipe?** [`.ai-skills/tiko-cookbook-extension/SKILL.md`](./.ai-skills/tiko-cookbook-extension/SKILL.md)
  (long form: [`docs/cookbook-extension.md`](./docs/cookbook-extension.md)). **Load-bearing rule: ask,
  don't fabricate.**
- **About to cut a release? Run the architecture gate first.** [`.ai-skills/tiko-architect/SKILL.md`](./.ai-skills/tiko-architect/SKILL.md),
  the GO / CONDITIONAL / NO-GO check of the release delta against the
  [architecture invariants registry](./docs/architecture-invariants.md), before `tiko-release`'s
  pre-flight. **Load-bearing rule: a NO-GO stops the release until the named blockers are resolved.**
- **Cutting a release of tiko-di?** [`.ai-skills/tiko-release/SKILL.md`](./.ai-skills/tiko-release/SKILL.md)
  (long form: [`docs/release-skill.md`](./docs/release-skill.md); reference:
  [`docs/releasing.md`](./docs/releasing.md)): pre-flight, the two version inputs, the Sonatype Portal
  manual gate, common traps. **Load-bearing rule: never pick `release_version` or `next_snapshot`
  silently.**
- **Touching the landing page (`site/`), adding a JDK, or a site deploy looks stale?**
  [`.ai-skills/tiko-site-maintainer/SKILL.md`](./.ai-skills/tiko-site-maintainer/SKILL.md): page-copy
  framing, every place the JDK list lives, verification, deploy troubleshooting. **Load-bearing rule:
  no "coming from Spring" mappings on the page — surface the conflict instead of writing one.**
- **Security-relevant change, a release, an audit, or a vulnerability report?**
  [`.ai-skills/tiko-security/SKILL.md`](./.ai-skills/tiko-security/SKILL.md): PR review against the
  [security model](./docs/security-model.md), the release gate (after `tiko-architect`), periodic
  audit, advisory handling. **Load-bearing rule: nothing about an undisclosed vulnerability goes
  public before its advisory is published.**

## Project Overview

**Tiko DI** is a compile-time dependency injection framework for Java 21+ with an event-driven
architecture. The annotation processor validates the wiring and generates it, so nothing at
runtime uses reflection or classpath scanning.

**Project Coordinates:**
- **Maven Group ID**: `io.github.tomas-samek` (Maven Central namespace; verified via GitHub).
  Java packages are `io.tiko.*` — independent of the Maven groupId and unchanged.
- **Artifact ID**: `tiko-*` (e.g. `tiko-api`, `tiko-runtime`)
- **Java Version**: 21+

## Build System

This project uses **Maven 3** as its build system.

### Common Commands

```bash
mvn compile                          # compile; runs annotation processing
mvn test                             # unit tests (*Test, surefire)
mvn test -Dtest=ClassName#methodName # one class or method
mvn clean install                    # full build incl. integration tests (*IT, failsafe)
mvn install -DskipTests              # install without tests
mvn compile -pl tiko-api             # one module
```

## Multi-Module Structure

```
tiko/
├── tiko-api/              # Core annotations & interfaces (~30KB target)
├── tiko-processor/        # Compile-time annotation processor (javac and KSP entry points)
├── tiko-processor-core/   # Frontend-neutral processor model, its javac and KSP frontends,
│                          # generator helpers — shared by both processors
├── tiko-runtime/          # Runtime container — `Tiko` bootstrap, `TikoContainerImpl`,
│                          # `AggregatingContainer`, `LocalEventBus`, `DefaultErrorHandler`
├── tiko-config/           # YAML-backed @Configuration injection (optional)
├── tiko-kafka/            # Kafka transport runtime — consumer runners, producer client,
│                          # `KafkaBootstrapSupport`, `FakeKafkaBroker` test support
├── tiko-kafka-processor/  # Compile-time processor for @KafkaSource / @KafkaSink bridges
├── tiko-kafka-it/         # Real-broker integration tests (Testcontainers; needs Docker)
├── tiko-test/             # @TikoTest JUnit extension, RecordingEventBus, @TestComponent
├── tiko-mcp/              # MCP introspection server over topology.json / wiring-errors.json
├── tiko-archetype/        # Maven archetype for new Tiko services
├── tiko-bom/              # Bill of materials (no parent — excluded from spotless aggregate)
├── tiko-coverage/         # JaCoCo aggregate module for Sonar
└── tiko-examples/         # Numbered example apps 01–16 (also serve as behavioral tests)
```

Event abstractions (`EventBus`, `@EventHandler`, `@EventTrigger`, `Event<T>`, etc.) live
in **tiko-api**; the in-memory implementation is in `tiko-runtime`. There is no separate
`tiko-event-api` / `tiko-event-local` split.

### Module Dependencies (core chain)

```
tiko-api            (no dependencies)
├── tiko-processor  (tiko-api, tiko-processor-core, tiko-config, javapoet, auto-service) — on the
│                    annotation-processor path; tiko-processor-core (javapoet) is the shared model
├── tiko-config     (tiko-api, snakeyaml)
└── tiko-runtime    (tiko-api; tiko-config as an OPTIONAL dependency — present only when the
                     app uses @Configuration or tiko.* YAML keys)
```

The Kafka pair mirrors the core split: `tiko-kafka` is the runtime (depends on
tiko-api + kafka-clients), `tiko-kafka-processor` the compile-time half. `tiko-test`
depends on tiko-runtime and JUnit 5.

## Core Architecture

### Design Philosophy

**"Compile-time safety with runtime simplicity"**

1. **Zero runtime DI exceptions** - All dependency errors caught at compile-time
2. **No global state** - Explicit Container instances
3. **Minimal boilerplate** - Simple patterns require zero configuration
4. **Clear error messages** - Suggest fixes, not just report problems
5. **No reflection** - Generated code is readable and debuggable

### Scope Management

Three scopes (via `Scope` enum) - from longest to shortest lifetime:
- `Scope.SINGLETON` - Application lifetime
- `Scope.EVENT` - One unit of work (HTTP request, consumed message, scheduled job, async dispatch)
- `Scope.PROTOTYPE` - Per injection (shortest, default)

**Unit of work:** The synchronous reach of an inbound stimulus, bounded at every async / transport hop. EVENT is single-frame in `0.x.0` — calling `runInEventScope` while a unit is already open throws `IllegalStateException`. Nestability is a deferred-but-additive future change.

**Cross-scope injection rules (3×3):**

| Consumer ↓ / Dependency → | SINGLETON | EVENT     | PROTOTYPE |
|---------------------------|-----------|-----------|-----------|
| SINGLETON                 | direct    | **proxy** | direct    |
| EVENT                     | direct    | direct    | direct    |
| PROTOTYPE                 | direct    | direct    | direct    |

**Proxy generation:** When an EVENT-scoped bean is injected into a SINGLETON, the annotation processor generates a proxy class so the SINGLETON resolves the current unit's instance on every call. The proxied bean must implement an interface. Resources bind to the current unit only — sharing one across units is a non-goal.

### Annotation Processing

`tiko-processor` scans `@Component` classes at compile time, validates the dependency graph
(missing deps, cycles, scope violations), generates the per-component factories,
`TikoContainerImpl` and the event-handler registration, and reports compile errors with
suggested fixes. Generated sources land in `target/generated-sources/annotations`.

Each processor is a frontend-neutral pipeline behind thin frontend entries: `TikoPipeline`
(tiko-processor) and `KafkaPipeline` (tiko-kafka-processor) collect, validate and generate on
`tiko-processor-core`'s model (`ProcessingEnv`, `ClassDecl`, `TypeRef`, …), while the javac entries
`TikoAnnotationProcessor` / `KafkaAnnotationProcessor` only wrap each round in a
`JavacProcessingEnv`, claim annotations, and decide when to call `process` / `finish`. Logic lives
only in core-model code: no `javax.lang.model` / `javax.annotation.processing` import outside
`io.tiko.processor.core.javac` and the entry classes (JavaPoet's `Modifier` in generators excepted),
pinned by each module's `FrontendBoundaryTest`.

### Event System

The local in-memory bus is the default. The Kafka transport is opt-in via the
`tiko-kafka` + `tiko-kafka-processor` modules: `@KafkaSource` / `@KafkaSink` annotations
generate a `KafkaTransportBootstrap` that `Tiko.create()` discovers through the
`TransportBootstrap` ServiceLoader SPI. There is **no** `tiko.event.bus` property —
transport selection is dependency + annotation driven, not a config switch. Broker
settings live under `tiko.kafka.*` (see `tiko-kafka`'s `defaults.yaml`).

The `Tiko` bootstrap (`Tiko.create()` / `Tiko.daemon()`) is a concrete class in
**tiko-runtime** (`io.tiko.runtime.Tiko`), not a tiko-api interface — apps need the
runtime artifact on the classpath to boot.

## Framework reference

How the framework behaves for its users lives in the docs, not here. Read the one you need:

| Topic | Where |
|---|---|
| Every annotation, attribute and signature | [`.ai-skills/tiko-build/reference/api-signatures.md`](./.ai-skills/tiko-build/reference/api-signatures.md), sources in `tiko-api/src/main/java/io/tiko/annotations` |
| Constructor injection, scopes, unit of work, `@Produces`, `@Named` | [`docs/di-and-scopes.md`](./docs/di-and-scopes.md) |
| `@EventHandler`, lifecycle events, `@EventTrigger` chains and guards, `Event<?>`, async resilience | [`docs/events.md`](./docs/events.md) |
| `@Configuration` and YAML | [`docs/configuration.md`](./docs/configuration.md) |
| `@TikoTest`, `@TestComponent`, test containers | [`docs/testing.md`](./docs/testing.md) |

## Development Guidelines

### Coding Style

The project uses **Spotless + Palantir Java Format** (configured in the root `pom.xml`).
The `spotless:check` goal is bound to the **validate** phase, so any Maven invocation —
including `mvn compile`, `mvn test`, `mvn package` (the CI command), and `mvn install` —
fails fast on style violations.

- **Format before committing:** `mvn spotless:apply` (excludes `tiko-bom` because it
  intentionally has no parent: `mvn -pl '!tiko-bom' spotless:apply` from the root).
- **Style facts** (handled automatically by the formatter — don't fight them by hand):
  4-space indent, K&R braces, deterministic import ordering, no unused imports, no
  trailing whitespace, files end with a newline.
- **IDE integration:** the [Palantir Java Format](https://github.com/palantir/palantir-java-format)
  IntelliJ plugin formats on save with the same rules; with that installed, the gate
  should never fire on local commits.
- **Don't bypass the gate.** If the formatter rewrites something in a way that hurts
  readability, fix it via small structural changes (extract a variable, split an
  expression) rather than disabling Spotless. The whole point is a single mechanical
  source of truth — local exceptions defeat that.

### Path-scoped rules

The rest of the house rules apply to part of the tree. They live in `.claude/rules/` and load
when you read or edit a matching file. Read the file first when you start new code there.

| Rule file | Loads for | Covers |
|---|---|---|
| [`java-idioms.md`](./.claude/rules/java-idioms.md) | any `.java` | Java 21+ features, `var`, enum comparison, loops vs streams, method length |
| [`framework-code.md`](./.claude/rules/framework-code.md) | `**/src/main/java/**` | `System.Logger` only, the lazy logger holder, processor stack traces |
| [`api-surface.md`](./.claude/rules/api-surface.md) | `tiko-api`, `**/annotations/**`, `tiko-test` sources | `SOURCE` vs `RUNTIME` retention, one-line interface default methods |
| [`annotation-processors.md`](./.claude/rules/annotation-processors.md) | `tiko-processor`, `tiko-kafka-processor`, `tiko-processor-core` | the generated package, `@Generated` markings, the compile-error format |
| [`yaml.md`](./.claude/rules/yaml.md) | `tiko-config` | explicit `SafeConstructor` |
| [`testing.md`](./.claude/rules/testing.md) | `**/src/test/**` | JUnit 5 + AssertJ, `*Test` / `*IT`, naming, Awaitility, parameterized tests, compile-testing |
| [`dependencies.md`](./.claude/rules/dependencies.md) | `**/pom.xml` | latest stable version, BOM + root pins, licenses, versions plugin |

### Issue Writing

Issue bodies: scope + concrete file list + acceptance + out-of-scope. Rationale lives in linked predecessor issues, not duplicated. **No "## Probable cause" or "## Suggested fix" sections** — symptoms and observable facts only. Speculation in the body biases whoever picks the issue up; let them analyse the evidence with a clean head. Acceptance describes the user-visible outcome, not the proposed mechanism.

**Group similar findings.** The issue and PR text around a small fix must not outweigh the fix.

- **Filing:** findings of the same kind in the same area (doc drift, Javadoc, small fixes of one shape) go into one issue as a checklist, one line per finding: the file and what is observed. A finding gets its own issue only when it needs its own design decision or its own test strategy.
- **Fixing:** one PR may close several small issues that touch the same files or the same area (`Closes #a`, `Closes #b`, each on its own line). Do not mix a behaviour change with a pure documentation fix in one PR.
- **Partial work:** when only part of a grouped issue gets done, merge that part and file the rest as a follow-up issue. Do not hold the finished part back, and do not split up front for the sake of re-planning.

- Use the [QA playbook](./docs/qa-playbook.md) to find, verify, and report defects.
- Use the [issue-fix playbook](./docs/issue-fix-playbook.md) when investigating,
  fixing, or publishing work on a filed issue. It defines the deliverables for
  each kind of request.

### GitHub Markdown formatting

> **Wrap every `@Identifier` in proper backtick code spans when writing GitHub issue, PR, or release bodies.** GitHub `@username` mentions are case-insensitive, and common Java-annotation names map to real dormant accounts — `@Named` resolves to GitHub user `nAmed` (2011-era, zero public activity, almost certainly a username squatted to harvest mentions from any project documenting `jakarta.inject` / `javax.inject`). Same risk applies to `@Component`, `@Pick`, `@Produces`, etc.

How to keep it from happening:

- For `gh release create` / `gh issue create` / `gh pr create`, always use `--notes-file` / `--body-file <path>` with a plain `.md` written by hand. **Do not** use `<<'EOF'` heredoc with escaped backticks (`\`@Named\``) — single-quoted heredoc passes the literal `\` + `` ` `` to the API, breaking the code span and re-exposing the `@`.
- When crediting humans, write `[name](https://github.com/name)` rather than `[@name](https://github.com/name)`. The `@` in link text doesn't change the link target but does fire a mention notification.
- After publish, verify with `gh api .../releases/tags/<tag> --jq .body | xxd | grep <annotation>` — the byte immediately before `@` should be `0x60` (backtick) or `0x20` (space inside a code span), never `0x5c` (backslash).

Postmortem of the v0.1.0 release notes that triggered this rule lives in the memory entry `feedback_github_markdown_no_mention_harvest`.
