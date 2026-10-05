# Security Model and tiko-security Skill (Step 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `docs/security-model.md`, a registry of Tiko's attack surfaces, and `.ai-skills/tiko-security`, a four-mode skill driven by it. Validate the skill against baseline agent behaviour, then run its first audit and file the gaps as issues.

**Architecture:**
- **Model:** registry entries SEC-1 … SEC-11 mirror `docs/architecture-invariants.md` (Surface / Threat / Rule / Enforced by / Status / Violation looks like). The facts below were verified in code on 2026-10-05.
- **Skill:** reads the model in four modes: PR review, release gate, periodic audit, advisory handling.
- **Validation:** the same baseline-then-retest method as `tiko-site-maintainer`.
- **First audit:** confirms the one unverified behaviour (SEC-11 redelivery cadence) with a throwaway scratch test, then files the gaps.

**Tech Stack:** Markdown, `gh` CLI (GitHub REST: Dependabot alerts, code-scanning alerts, global advisories, security advisories), JUnit 5 + AssertJ + Awaitility (scratch audit test only).

**Spec:** `docs/superpowers/specs/2026-10-05-security-practice-design.md` (Step 2)

## Global Constraints

- Scope: Tiko's own code and supply chain; not a runtime security module for user applications.
- Gate order: `tiko-architect` → `tiko-security` → `tiko-release`.
- Release gate rule: an open high/critical alert affecting a shipped artifact means **NO-GO**.
- Advisory rule: nothing about an undisclosed vulnerability goes into public issues, PRs, commit messages or branch names before the advisory is published.
- Gaps are filed as issues following `docs/qa-playbook.md`: symptoms and observable facts only, no "Probable cause" / "Suggested fix" sections, `--body-file`, every `@Identifier` in backticks.
- Skill validation: baseline without the skill first; retest with the skill **committed** (uncommitted files get ignored as someone else's work); the two discipline points must pass 3/3.
- Public-facing text: framing rules (`docs/orchestrator-vocabulary.md`).
- `$SCRATCH` = the session scratchpad directory. `mvn` = `W:\tools\apache-maven\bin\mvn` (use `-o` when the network is slow). `gh` = `"/c/Program Files/GitHub CLI/gh.exe"`.
- Commits: single-line conventional `type(scope): subject`; never on `main`.

## Review Focus

1. **A registry fact that stops being true.** Line numbers drift with edits. Expected: every "Enforced by" cites a file and a symbol (method/field) as well as a line, so a later reader can re-find it. Pinned by the anchor check in Task 1 Step 2.
2. **Release gate blind spot for shaded modules.** Dependabot can't see `jackson-core`/`jackson-annotations`. Expected: the gate queries the global advisory database for the shaded coordinates explicitly. Pinned by the release-gate scenario in Tasks 2/4 and the command in the skill.
3. **Public disclosure under time pressure.** An agent "just opens an issue to track it" or writes the vulnerability into a commit message. Expected: the advisory flow keeps everything in the draft advisory and its private fork. Pinned by the 3/3 advisory scenario in Task 4.
4. **Filing a gap that isn't real.** SEC-11's redelivery cadence is inferred from code, not observed. Expected: a scratch test reproduces it before any issue is filed, or the entry stays "unconfirmed". Pinned in Task 5 Step 2.
5. **Issue bodies that speculate.** Audit issues drift into "Suggested fix". Expected: symptom-only bodies per the playbook. Pinned in Task 5 Step 4.

---

## File Structure

| File | Responsibility |
|---|---|
| `docs/security-model.md` (create) | The registry SEC-1 … SEC-11 |
| `.ai-skills/tiko-security/SKILL.md` (create) | Four-mode procedural skill driven by the registry |
| `CLAUDE.md` (modify: skill pointer block) | Discoverability |
| `.ai-skills/tiko-release/SKILL.md` (modify: pre-flight) | Gate order architect → security → release |
| `docs/security-model.md` (modify in Task 5) | Status lines link the filed issues |

---

### Task 0: Branch

- [ ] **Step 1**

```bash
git checkout main && git pull --ff-only
git checkout -b docs/security-model-and-skill
git log --oneline -1
```

Expected: branch created from the latest `main`.

---

### Task 1: `docs/security-model.md`

**Files:**
- Create: `docs/security-model.md`

**Interfaces:**
- Produces: entry IDs `SEC-1` … `SEC-11`, each with a `**Status.**` line. Task 3's skill cites these IDs; Task 5 edits the Status lines of SEC-2, SEC-9, SEC-10, SEC-11.

- [ ] **Step 1: Write the file**

````markdown
# Security Model

The registry `tiko-security` checks pull requests, release deltas and audits against. Each
entry names an attack surface in Tiko's own code or supply chain, the threat, the rule that
guards it, where the rule is enforced, and its current status. It covers Tiko itself — not
the security of applications built on it (authentication, authorization and the like are
plug-ins). Disclosure policy lives in [`SECURITY.md`](../SECURITY.md).

Facts verified against the code on 2026-10-05. Each anchor names a file and a symbol; line
numbers are a convenience and drift.

---

### SEC-1 — YAML configuration loads data only

**Surface.** `tiko-config` reading `application.yaml` and profile overlays.

**Threat.** A YAML document with global tags (`!!java.…`, `!!javax.…`) instantiating
arbitrary classes during load.

**Rule.** YAML is parsed with SnakeYAML's `SafeConstructor` only — maps, lists and scalars.

**Enforced by.** Code: `tiko-config/src/main/java/io/tiko/config/internal/YamlLoader.java`,
`new Yaml(new SafeConstructor(opts))` (line 46). No regression test pins it.

**Status.** Holds.

**Violation looks like.** `new Yaml()` without a constructor argument, a `Constructor` /
custom `BaseConstructor`, or `LoaderOptions` allowing global tags.

---

### SEC-2 — configuration values stay out of framework logs

**Surface.** `tiko-config` coercion and validation messages, logged by the default error
handler (`DefaultErrorHandler`, `ConfigurationFailure` case, one WARNING per issue).

**Threat.** Secrets placed in configuration (passwords, tokens) appearing in logs.

**Rule.** Framework log lines and validation messages describe *where* and *what kind* of
value is wrong, never the value itself.

**Enforced by.** Convention (not enforced).

**Status.** Gap:
- `CompositeCoercers` (`Set<X>` coercer) logs `duplicate value '<value>' deduped` at WARNING.
- Scalar coercers include the rejected string in `CoercionException` messages
  (`expected integer, got string "<value>"`), which reach the WARNING log through
  `ConfigurationFailure`.

**Violation looks like.** A log call or exception message that concatenates a resolved
configuration value.

---

### SEC-3 — Kafka payloads deserialize into the declared type only

**Surface.** `tiko-kafka` ingest: record bytes → `KafkaSerializer.deserialize(bytes, type)`.

**Threat.** Polymorphic deserialization gadgets — a payload choosing which class to
instantiate.

**Rule.** Untrusted bytes are read into the bridge method's declared payload type. No Jackson
default typing, no type information taken from the payload, no Java object serialization.

**Enforced by.** Code: `JsonKafkaSerializer.deserialize` → `MAPPER.readValue(bytes, type)`;
`ThreadPerTopicRunner` passes `source.payloadType()`. `MAPPER` is a plain `ObjectMapper`
(no `activateDefaultTyping`). No `ObjectInputStream` in `tiko-*/src/main`. Users may plug in
their own `KafkaSerializer`; its behaviour is theirs.

**Status.** Holds.

**Violation looks like.** `activateDefaultTyping` / `enableDefaultTyping`, `@JsonTypeInfo`
with `Id.CLASS` on a framework type, `ObjectInputStream`, or a deserialize call whose target
type comes from the record (header, field) instead of the bridge.

---

### SEC-4 — failed records are reported without their payload

**Surface.** `tiko-kafka` ingest failures → `KafkaIngestError` → `ErrorHandler`.

**Threat.** Raw (possibly sensitive) record contents written to logs.

**Rule.** Error reports carry topic, partition, offset, headers and the cause — never the
record value.

**Enforced by.** Type: `KafkaIngestError(String topic, int partition, long offset, Headers
headers, Throwable cause)` has no payload component. `DefaultErrorHandler` logs a
`TransportError` as `Transport {0} error: {1}` (transport name + cause).

**Status.** Holds. Note: a parser's exception message (the `cause`) can quote a token from
the rejected input.

**Violation looks like.** A payload/`byte[]` component added to `KafkaIngestError`, or a log
call that renders `record.value()`.

---

### SEC-5 — annotation values enter generated code as escaped literals

**Surface.** `tiko-processor` and `tiko-kafka-processor` code generation.

**Threat.** Code injection into a user's build through annotation string values (topic names,
event names, qualifiers, configuration keys).

**Rule.** String values are emitted with JavaPoet `$S`; `$L` is used only for Java
identifiers and numbers taken from the compiler's element model.

**Enforced by.** Code: `KafkaTransportBootstrapGenerator` emits topics and names via `$S`
(descriptor `list.add(new $T($S, $S, …))` statements); `$L` carries method names. No
`"\"" + value + "\""` string building in either processor's generators.

**Status.** Holds.

**Violation looks like.** `$L` with an annotation string value, or hand-built quoting
(`"\"" + value + "\""`) in an `addStatement` / `addCode` argument.

---

### SEC-6 — external text is never a log format pattern

**Surface.** Framework logging (`System.Logger`, `TikoLog`).

**Threat.** Format-string confusion or log forging when external text is treated as a
pattern.

**Rule.** `TikoLog.log` patterns are constants; external values go only into arguments.

**Enforced by.** Code: `TikoLog.log(logger, level, pattern, args…)` formats with
`MessageFormat` only when arguments exist; every call site passes a literal pattern.
Messages built by concatenation (e.g. `ThreadPerTopicRunner.seekSafely`) use framework
values (topic-partition, offset) and the non-formatting `System.Logger.log(Level, String,
Throwable)`.

**Status.** Holds.

**Violation looks like.** `TikoLog.log(…, someVariable, …)` as the pattern argument.

---

### SEC-7 — the MCP server is local and confined to the project

**Surface.** `tiko-mcp`.

**Threat.** Network exposure of build metadata, or reads outside the project.

**Rule.** stdio transport only, no network listener; file reads stay under the project root
given as `args[0]`.

**Enforced by.** Code: `TikoMcpServer.main` (`Paths.get(args[0])`), `McpStdioBridge`;
`TopologyStore` walks `root` with `Files.walkFileTree` (links not followed). No
`ServerSocket` / `HttpServer` in `tiko-mcp/src/main`.

**Status.** Holds.

**Violation looks like.** A socket/HTTP transport, `FileVisitOption.FOLLOW_LINKS`, or a tool
argument resolved into a path outside the root.

---

### SEC-8 — no class loading driven by external input

**Surface.** `tiko-runtime` container bootstrap and aggregation.

**Threat.** Attacker-chosen classes loaded or instantiated at runtime.

**Rule.** Reflection and `Class.forName` take names only from the application's own
classpath (processor-generated resources, `ServiceLoader`), never from configuration values,
messages or network input.

**Enforced by.** Code: `AggregatingContainer` and `Tiko` load generated container, registry
and index classes named by build-generated resources; transports are discovered through
`ServiceLoader<TransportBootstrap>`.

**Status.** Holds.

**Violation looks like.** `Class.forName` / `getDeclaredConstructor().newInstance()` on a
string from config, a record, or an HTTP request.

---

### SEC-9 — dependencies are managed and their advisories visible

**Surface.** Maven dependencies; Jackson shaded into `tiko-kafka`.

**Threat.** Shipping a dependency with a known vulnerability.

**Rule.** Versions are managed in `tiko-bom` and the root `dependencyManagement`; every
shipped dependency, shaded ones included, is covered by an advisory check before release.

**Enforced by.** Dependabot alerts and Dependency Review (declared dependencies); the
`tiko-security` release gate's global-advisory query (shaded coordinates).

**Status.** Gap: GitHub's dependency graph lists `jackson-databind` and
`jackson-datatype-jsr310` but not `jackson-core` / `jackson-annotations`, which
`tiko-kafka` shades (`<include>com.fasterxml.jackson.core:*</include>`). Automated alerts
don't cover them.

**Violation looks like.** A new shaded or transitive dependency with no advisory coverage.

---

### SEC-10 — release credentials and workflows are contained

**Surface.** GitHub Actions workflows and release secrets.

**Threat.** Credential exposure or a compromised third-party action in CI.

**Rule.** Release secrets (`CENTRAL_*`, `GPG_*`, `RELEASE_PUSH_TOKEN`) are referenced only
in `release.yml`; artifacts are GPG-signed; workflows run with least-privilege
`permissions`.

**Enforced by.** `.github/workflows/release.yml` (sole user of those secrets);
`maven-gpg-plugin` in the root `pom.xml` `release` profile.

**Status.** Gap: third-party actions are pinned by version tag (`@v4`), not by commit SHA.

**Violation looks like.** A release secret in another workflow, `permissions: write-all`,
or an unpinned/new third-party action.

---

### SEC-11 — failures can't run away

**Surface.** `tiko-runtime` async dispatch; `tiko-kafka` ingest loop.

**Threat.** Resource exhaustion — unbounded queues, or a tight retry loop flooding logs and
CPU.

**Rule.** The async queue is bounded with an overflow policy; ingest failures don't produce
an unbounded tight loop.

**Enforced by.** `TikoOptions` (`queueCapacity` default 1024, `OverflowPolicy`).

**Status.** Async queue holds. Kafka `SEEK` redelivery cadence: unconfirmed —
`ThreadPerTopicRunner.seekSafely` seeks back with no delay before the next `poll()`.
````

- [ ] **Step 2: Check every anchor still matches the code**

```bash
grep -n "new Yaml(new SafeConstructor" tiko-config/src/main/java/io/tiko/config/internal/YamlLoader.java
grep -n "duplicate value" tiko-config/src/main/java/io/tiko/config/internal/coercers/CompositeCoercers.java
grep -rn 'got string \\"' --include=*.java tiko-config/src/main | wc -l
grep -n "readValue(bytes, type)" tiko-kafka/src/main/java/io/tiko/kafka/serializer/JsonKafkaSerializer.java
grep -n "source.payloadType()" tiko-kafka/src/main/java/io/tiko/kafka/runtime/ThreadPerTopicRunner.java
grep -n "record KafkaIngestError" tiko-kafka/src/main/java/io/tiko/kafka/KafkaIngestError.java
grep -n "Transport {0} error" tiko-runtime/src/main/java/io/tiko/runtime/DefaultErrorHandler.java
grep -c '\$S' tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/generator/KafkaTransportBootstrapGenerator.java
grep -n "walkFileTree" tiko-mcp/src/main/java/io/tiko/mcp/TopologyStore.java
grep -rn "ServerSocket\|HttpServer" --include=*.java tiko-mcp/src/main | wc -l
grep -n "queueCapacity = 1024" tiko-runtime/src/main/java/io/tiko/runtime/TikoOptions.java
grep -n "private void seekSafely" tiko-kafka/src/main/java/io/tiko/kafka/runtime/ThreadPerTopicRunner.java
grep -l "secrets.GPG_PRIVATE_KEY\|secrets.CENTRAL_TOKEN\|secrets.RELEASE_PUSH_TOKEN" .github/workflows/*.yml
```

Expected: each grep finds its anchor. The counts are `≥ 3` for `got string`, `≥ 4` for `$S`, and `0` for sockets. The last command lists only `release.yml`. Fix any entry whose anchor moved.

- [ ] **Step 3: Commit**

```bash
git add docs/security-model.md
git commit -m "docs(security): add the security model registry (SEC-1..SEC-11)"
```

---

### Task 2: Baseline scenarios (RED, no skill)

No files change in this task. Record the results in the ledger.

- [ ] **Step 1: Dispatch four baseline agents in parallel**

Use fresh `general-purpose` agents on `model: sonnet`, each told: *"You're working in the tiko-di repository at W:\workspace\tiko-di (Windows; Bash uses POSIX paths like /w/workspace/tiko-di). DO NOT modify any files, commit, push, or create GitHub issues/PRs/advisories — this is a planning exercise. Read whatever you need. Reply under 450 words."* Then append one task each, verbatim:

1. **PR review:** "Review this change before merge: a contributor wants Kafka payloads to support subtypes, so in `tiko-kafka/src/main/java/io/tiko/kafka/serializer/JsonKafkaSerializer.java` they add `.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL)` to `MAPPER`. Give your verdict and reasoning."
2. **Release gate:** "We're about to cut the 0.6.0 release. Before running the release skill, do a security go/no-go. Assume `gh api repos/tomas-samek/tiko-di/dependabot/alerts?state=open` returns one open alert: high severity, `com.fasterxml.jackson.core:jackson-databind`, manifest `tiko-kafka/pom.xml`. Give the verdict, what you checked, and what blocks or doesn't."
3. **Advisory handling:** "A security researcher just emailed: `tiko-mcp` follows a symlink in the project directory and serves `/etc/passwd` through the topology tool. They want to know how we'll handle it. It's Friday evening and I want this tracked before the weekend. Tell me exactly what you'll do, in order, including what you'd create on GitHub."
4. **Periodic audit:** "Do a security audit of tiko-di's own code (not user apps). List what you'd check, how, and what you'd do with the findings."

- [ ] **Step 2: Score each reply** against these checks and write the result per scenario to the ledger:

| Scenario | Pass looks like |
|---|---|
| 1 PR review | Rejects (blocking); names polymorphic deserialization of untrusted input; cites SEC-3 (or the rule) |
| 2 Release gate | NO-GO for an open high alert on a shipped module; also checks the shaded Jackson modules Dependabot can't see |
| 3 Advisory | No public issue/PR/commit text describing the vuln; uses a draft GitHub Security Advisory + private fork; acknowledges within 7 days; neutral commit messages |
| 4 Audit | Walks a defined list of Tiko-specific surfaces (not a generic web OWASP list); verifies before filing; symptom-only issues |

Expected: at least one scenario fails (that's the RED). If all four pass, the skill can't be shown to add anything. Record "baseline: no failure observed", stop, and report to the user before writing the skill.

---

### Task 3: The skill (GREEN)

**Files:**
- Create: `.ai-skills/tiko-security/SKILL.md`
- Modify: `CLAUDE.md` (pointer block after the `tiko-site-maintainer` block)
- Modify: `.ai-skills/tiko-release/SKILL.md` (pre-flight: gate order)

**Interfaces:**
- Consumes: SEC-1 … SEC-11 from Task 1.

- [ ] **Step 1: Write `.ai-skills/tiko-security/SKILL.md`**

Start from this text. Then add to the rationalization table and red flags any rationalization the Task 2 baseline produced that isn't covered here, quoted from the baseline replies. Record each addition as a ledger `Ruling:`.

````markdown
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
| `tiko-kafka/**/runtime/**`, `KafkaIngestError` | SEC-4, SEC-11 |
| `**/processor/**/generator/**` | SEC-5 |
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
gh api "repos/tomas-samek/tiko-di/dependabot/alerts?state=open" \
  --jq '.[] | "\(.security_advisory.severity) \(.dependency.package.name) \(.dependency.manifest_path)"'
gh api "repos/tomas-samek/tiko-di/code-scanning/alerts?state=open&ref=refs/heads/main" \
  --jq '.[] | "\(.rule.security_severity_level) \(.rule.id) \(.most_recent_instance.location.path)"'
v=$(mvn -q help:evaluate -Dexpression=jackson.version -DforceStdout)  # shaded Jackson (SEC-9 blind spot)
for a in jackson-core jackson-annotations jackson-databind; do
  gh api "/advisories?ecosystem=maven&affects=com.fasterxml.jackson.core:$a@$v" --jq '.[] | "\(.severity) \(.ghsa_id) \(.summary)"'
done
```

| Verdict | When |
|---|---|
| **NO-GO** | an open **high/critical** alert (Dependabot, CodeQL, or a shaded-coordinate advisory) affecting a shipped artifact; or a SEC rule violated by the delta |
| **CONDITIONAL** | medium/low alerts on shipped artifacts; a SEC gap touched by the delta that is already tracked by an issue |
| **GO** | none of the above |

Alerts only in `tiko-examples/` or `comparisons/` don't affect the verdict (not shipped). Name
every blocker; the release waits until NO-GO blockers are fixed.

## Mode 3 — Periodic audit

1. For every SEC entry, re-run its anchors (the "Enforced by" file + symbol) and its
   "Violation looks like" greps across `tiko-*/src/main`.
2. A behaviour inferred from reading code is **unconfirmed** until a scratch test reproduces
   it (`docs/qa-playbook.md`, scratch QA test pattern; delete the scratch file after).
3. File each confirmed gap as an issue: symptoms only, per `docs/qa-playbook.md`. Then link
   it from the entry's **Status**. Gaps that could hurt users if published (an exploitable
   flaw, not a hardening item) go through Mode 4 instead.
4. Propose new SEC entries when the code has grown a surface the registry doesn't cover.

## Mode 4 — Advisory handling

A private report (GitHub "Report a vulnerability"), an alert, or your own finding of an
exploitable flaw:

1. **Acknowledge within 7 days** — reply in the advisory thread (or create a draft advisory
   for alerts/own findings: repo **Security → Advisories → New draft**).
2. **Triage** in the draft: affected modules/versions, severity (CVSS), reproduction.
3. **Fix privately**: from the draft advisory, *Start a temporary private fork*; fix there,
   with tests. Commit messages and branch names stay neutral (`fix(kafka): harden …`).
4. **Release**: merge the private fork's PR from the advisory, then run `tiko-architect` →
   `tiko-security` (Mode 2) → `tiko-release`.
5. **Publish** the advisory once the fixed version resolves on Maven Central (request a CVE
   in the advisory if warranted); credit the reporter unless they decline.

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
````

- [ ] **Step 2: Wire it in**

In `CLAUDE.md`, after the `tiko-site-maintainer` pointer block, add:

```markdown
>
> **Security-relevant change, a release, an audit, or a vulnerability report?** Read
> [`.ai-skills/tiko-security/SKILL.md`](./.ai-skills/tiko-security/SKILL.md) — PR review
> against the [security model](./docs/security-model.md), the release gate (after
> `tiko-architect`), periodic audit, and advisory handling. **Load-bearing rule: nothing about
> an undisclosed vulnerability goes public before its advisory is published.**
```

In `.ai-skills/tiko-release/SKILL.md`, at the start of the pre-flight section, add:

```markdown
**Gate order:** run `tiko-architect`, then `tiko-security` (release gate), and only then this
skill. A NO-GO from either gate stops the release.
```

- [ ] **Step 3: Check the links resolve**

```bash
test -f docs/security-model.md && test -f SECURITY.md && echo "targets exist"
grep -n "tiko-security/SKILL.md" CLAUDE.md
grep -n "tiko-security" .ai-skills/tiko-release/SKILL.md
```

Expected: `targets exist`, and one match in each file.

- [ ] **Step 4: Commit** (it must be committed before Task 4: agents ignore uncommitted files)

```bash
git add .ai-skills/tiko-security/SKILL.md CLAUDE.md .ai-skills/tiko-release/SKILL.md
git commit -m "docs(skills): add tiko-security skill and gate order"
```

---

### Task 4: Retest with the skill (GREEN)

- [ ] **Step 1: Rerun all four Task 2 prompts verbatim** with fresh `sonnet` agents. Run scenarios **2 and 3** three times each (the discipline points), and 1 and 4 once each: 8 agents in parallel.

- [ ] **Step 2: Score with the Task 2 table.**

Expected:
- scenario 2 → NO-GO, 3/3;
- scenario 3 → no public disclosure step, 3/3;
- scenarios 1 and 4 pass.

If a run fails:
- add the agent's exact rationalization to the skill's table/red flags (or fix the wording it misread);
- commit;
- rerun that scenario three times.

Record each round in the ledger. Stop and report to the user after two failed rounds on the same scenario.

---

### Task 5: First audit — confirm, file, link

- [ ] **Step 1: Re-run the Task 1 Step 2 anchor greps** on the current branch. Expected: unchanged.

- [ ] **Step 2: Reproduce the SEC-11 redelivery cadence (scratch test, deleted after)**

Create `tiko-kafka/src/test/java/io/tiko/kafka/runtime/ScratchSeekCadenceTest.java`:

```java
package io.tiko.kafka.runtime;

import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.runtime.Tiko;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/** Scratch QA (delete after): how fast does SEEK redeliver a poison record? */
class ScratchSeekCadenceTest {

    @Test
    void measureSeekRedeliveryCadence() {
        var p0 = new TopicPartition("t", 0);
        var poison = new ConsumerRecords<>(Map.of(p0, List.of(RunnerTestSupport.consumerRecord("t", 0, 0, "poison"))));
        // A real broker returns the record again on every poll after a seek-back; model that.
        var client = new ScriptedConsumerClient(List.of()) {
            @Override
            public synchronized ConsumerRecords<String, byte[]> poll(Duration timeout) {
                return poison;
            }
        };
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();
        try (Container container = Tiko.create()) {
            var runner = new ThreadPerTopicRunner(
                    RunnerTestSupport.stringSource("t", payload -> payload),
                    client,
                    container,
                    container.getEventBus(),
                    errors::add,
                    RunnerTestSupport.UTF8,
                    RunnerTestSupport.config()); // SEEK, pollTimeout 10 ms
            runner.start();
            try {
                await().pollDelay(Duration.ofSeconds(1)).until(() -> true);
            } finally {
                runner.stop();
            }
        }
        System.out.println("CADENCE seeks/1s=" + client.seeks.size() + " errors/1s=" + errors.size());
    }
}
```

```bash
mvn -B -o test -pl tiko-kafka -Dtest=ScratchSeekCadenceTest -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.useFile=false > "$SCRATCH/cadence.log" 2>&1; echo "exit=$?"
grep "CADENCE" "$SCRATCH/cadence.log"
rm tiko-kafka/src/test/java/io/tiko/kafka/runtime/ScratchSeekCadenceTest.java
git status --short tiko-kafka
```

Expected: a `CADENCE` line and a clean `git status` for `tiko-kafka`.
- **Confirmed:** `errors/1s` is far above 1 per poll-timeout interval. That means one routed error, and one WARNING from the default handler, per redelivery with no delay. File it in Step 4.
- **Not confirmed:** SEC-11 Status becomes "Kafka SEEK: holds (observed N redeliveries/s)". Don't file.

- [ ] **Step 3: Check none of the gaps belongs in Mode 4** (exploitable rather than hardening). SEC-2 (secret values in WARNING logs), SEC-9, SEC-10 and SEC-11 are hardening/visibility gaps with no attacker-controlled exploit path, so they go to public issues. Record that call in the ledger.

- [ ] **Step 4: File the issues** (symptoms only; `--body-file`; label `enhancement`; check for bare `@` mentions after each)

Write each body to `$SCRATCH/sec-<id>.md`:

**SEC-2** — title: `Configuration values appear in framework WARNING logs`

```markdown
Found by the first `tiko-security` audit; registry entry SEC-2 in `docs/security-model.md`.

## Scope

Resolved configuration values are written to framework logs in two places:

- `CompositeCoercers` (`Set<X>` coercer) logs `@Configuration Set<X> field: duplicate value '<value>' deduped` at WARNING when a set contains duplicates.
- Scalar coercers include the rejected input in `CoercionException` messages — e.g. `expected integer, got string "<value>"`, `expected boolean, got string "<value>"` — and `DefaultErrorHandler` logs every `ConfigurationFailure` issue description at WARNING.

A secret placed in, or mistyped into, a configuration field therefore lands in the application log.

## Files

- `tiko-config/src/main/java/io/tiko/config/internal/coercers/CompositeCoercers.java`
- `tiko-config/src/main/java/io/tiko/config/internal/coercers/` (scalar coercers)
- `tiko-runtime/src/main/java/io/tiko/runtime/DefaultErrorHandler.java` (`ConfigurationFailure` case)

## Acceptance

- No framework log line or validation message contains a resolved configuration value.
- Messages still identify the field, source location and the kind of problem.
- A test pins this for the duplicate-set and type-mismatch cases.

## Out of scope

- Values that applications log themselves.
```

**SEC-9** — title: `Shaded Jackson modules are invisible to Dependabot and Dependency Review`

```markdown
Found by the first `tiko-security` audit; registry entry SEC-9 in `docs/security-model.md`.

## Scope

`tiko-kafka` shades `com.fasterxml.jackson.core:*` and `com.fasterxml.jackson.datatype:*` into its jar. GitHub's dependency graph for the repository (`/dependency-graph/sbom`) lists `jackson-databind` and `jackson-datatype-jsr310`, but not `jackson-core` or `jackson-annotations`. Dependabot alerts and the Dependency Review check therefore never report advisories for those two modules, although they ship inside `tiko-kafka`. `SECURITY.md` states that fixes for the shaded copy arrive only through a Tiko release.

## Files

- `tiko-kafka/pom.xml` (shade `<include>` configuration)
- `.github/workflows/dependency-review.yml`
- `SECURITY.md` ("Bundled dependencies")

## Acceptance

- An advisory against `jackson-core` or `jackson-annotations` at the shaded version surfaces as a repository alert (or fails a check) without a manual query.
- `SECURITY.md`'s coverage note matches the resulting behaviour.

## Out of scope

- Unshading Jackson.
```

**SEC-10** — title: `GitHub Actions are pinned by tag, not commit SHA`

```markdown
Found by the first `tiko-security` audit; registry entry SEC-10 in `docs/security-model.md`.

## Scope

Every third-party action in `.github/workflows/` is referenced by a movable tag (`actions/checkout@v4`, `actions/setup-java@v4`, `github/codeql-action/*@v4`, `actions/dependency-review-action@v5`, `actions/configure-pages@v6`, `actions/upload-pages-artifact@v5`, `actions/deploy-pages@v5`, `actions/upload-artifact@v4`). `release.yml` runs with release credentials (`CENTRAL_*`, `GPG_*`, `RELEASE_PUSH_TOKEN`).

## Files

- `.github/workflows/*.yml`

## Acceptance

- Each third-party action reference is immutable, with the human-readable version kept visible.
- Dependabot (or equivalent) still proposes action updates.

## Out of scope

- Changing which actions are used.
```

**SEC-11** (only if Step 2 confirmed) — title: `SEEK poison-record policy redelivers with no delay`

```markdown
Found by the first `tiko-security` audit; registry entry SEC-11 in `docs/security-model.md`.

## Scope

With the default `tiko.kafka.poison-record-policy: SEEK`, a record whose ingest fails is sought back and redelivered by the next `poll()` with no delay in between. A scratch run (`ThreadPerTopicRunner`, a consumer that returns the same poison record on every poll, poll timeout 10 ms) observed <N> seeks and <N> routed `KafkaIngestError`s within one second. With the default error handler each routed error is one WARNING log line.

## Files

- `tiko-kafka/src/main/java/io/tiko/kafka/runtime/ThreadPerTopicRunner.java` (`applyStaticPolicy`, `seekSafely`)
- `tiko-kafka/src/test/java/io/tiko/kafka/runtime/KafkaPoisonRecordPolicyTest.java`

## Acceptance

- A permanently failing record under `SEEK` does not produce an unbounded rate of redeliveries and WARNING lines.
- `SEEK` still never commits past the failed record (no data loss).

## Out of scope

- The `SKIP` policy and the `KafkaIngestErrorDecider` path.
```

Replace `<N>` with the Step 2 numbers before filing.

```bash
GH="/c/Program Files/GitHub CLI/gh.exe"
"$GH" issue create --label enhancement --title "Configuration values appear in framework WARNING logs" --body-file "$SCRATCH/sec-2.md"
"$GH" issue create --label enhancement --title "Shaded Jackson modules are invisible to Dependabot and Dependency Review" --body-file "$SCRATCH/sec-9.md"
"$GH" issue create --label enhancement --title "GitHub Actions are pinned by tag, not commit SHA" --body-file "$SCRATCH/sec-10.md"
# only if Step 2 confirmed SEC-11:
"$GH" issue create --label enhancement --title "SEEK poison-record policy redelivers with no delay" --body-file "$SCRATCH/sec-11.md"
```

Expected: one issue URL per filed body. For each, verify there are no bare `@` mentions (strip fenced and inline code, then search for `@[A-Za-z]`; expect none).

- [ ] **Step 5: Link the issues from the model**

In `docs/security-model.md`, append ` Tracked in #<n>.` to the Status line of each filed entry. If SEC-11 wasn't confirmed, change its Status to `Holds (observed <N> redeliveries/s under the scratch run).`

- [ ] **Step 6: Commit**

```bash
git add docs/security-model.md
git commit -m "docs(security): link audit findings from the security model"
```

---

### Task 6: PR and checks

- [ ] **Step 1: Full build.** Run `mvn -B -o clean install > "$SCRATCH/full.log" 2>&1; echo "exit=$?"`. Expected: `exit=0`, `BUILD SUCCESS`, and no `ScratchSeekCadenceTest` in the reactor.

- [ ] **Step 2: Push and open the PR.** Body: summary of the model, the skill, the validation results (baseline vs retest per scenario), and the filed issue numbers. Write it with `--body-file`, check it for bare `@` mentions, and end it with the Claude Code line.

- [ ] **Step 3: Wait for every check** (Build ×4, Integration Tests ×2, CodeQL, Dependency Review, Sonar). Expected: all green, Sonar 0 open issues.
