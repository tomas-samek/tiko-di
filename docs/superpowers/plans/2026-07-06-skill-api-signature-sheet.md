# API Signature Sheet Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a transcribed-from-source API signature sheet to the tiko-build skill (canonical + archetype copies), fix the false `tiko.kafka.*` camelCase claim (F5), and add a compact exact-package table to the archetype `CLAUDE.md`.

**Architecture:** Docs-only. One new `## API signature sheet` section inserted into both skill copies; one corrected paragraph in both; one new subsection + one clarified sentence in the archetype `CLAUDE.md`. Every signature below was transcribed from source on 2026-07-06 — the implementer verifies each against the named source file before writing (transcribe, never recall).

**Tech Stack:** Markdown; Maven (`W:\tools\apache-maven\bin\mvn.cmd`, not on PATH) for the verification build.

**Spec:** `docs/superpowers/specs/2026-07-06-skill-api-signature-sheet-design.md`

## Global Constraints

- Branch: `docs/skill-api-signature-sheet` (checked out; spec committed).
- The canonical skill (`.ai-skills/tiko-build/SKILL.md`) and the archetype bundled copy (`tiko-archetype/src/main/resources/archetype-resources/.ai-skills/tiko-build/SKILL.md`) must receive **byte-identical** new/changed section text; their only tolerated differences remain the pre-existing link-style hunks (relative vs absolute GitHub links) — the archetype's #408 sync-gate test enforces this and must stay green.
- Every `@Annotation` name in prose wrapped in backtick code spans (mention-harvest rule).
- Every signature in the sheet must match the named source file exactly — before writing each block, Read the source file listed for it and correct any drift from this plan.
- Full reactor `mvn test` must pass (spotless + #408 gate). Log-file discipline: `& W:\tools\apache-maven\bin\mvn.cmd test *> W:\workspace\sheet-verify.log; $LASTEXITCODE`, check with PowerShell Select-String (the log is UTF-16 — Git-Bash grep won't match).
- Use Read/Edit tools for all file changes.
- Single commit at the end (spec delivery rule): `docs(skill): API signature sheet + kebab-case key-table fix across agent-facing docs (#269)` — single line, no body, no Co-Authored-By.

---

### Task 1: The signature sheet + F5 fix in both skill copies

**Files:**
- Modify: `.ai-skills/tiko-build/SKILL.md` (insert new section before `## Kafka transport: write this shape first`, currently line ~184; fix the false claim at lines ~233-234, which after the insertion will have shifted — match on text)
- Modify: `tiko-archetype/src/main/resources/archetype-resources/.ai-skills/tiko-build/SKILL.md` (identical edits)

**Interfaces:**
- Consumes: source files named per block below (verification reads).
- Produces: the section heading `## API signature sheet — exact imports and signatures` that Task 2's CLAUDE.md table references by name.

- [ ] **Step 1: Verify the transcriptions below against source**

Read each source file and diff against the sheet text in Step 2; fix the sheet text if the source disagrees (the source wins):
- `tiko-api/src/main/java/io/tiko/annotations/{Component,Produces,EventHandler,EventTrigger,EventTriggers,Configuration,Default,Key,Named,Pick,Inject,PostConstruct,PreDestroy,BackoffStrategy}.java`
- `tiko-api/src/main/java/io/tiko/EventBus.java`
- `tiko-runtime/src/main/java/io/tiko/runtime/Tiko.java` (public factory methods), `TikoOptions.java` (Builder methods), `TikoDaemon.java` (`awaitShutdown`)
- `tiko-config/src/main/java/io/tiko/config/ConfigSources.java`
- `tiko-kafka/src/main/java/io/tiko/kafka/annotations/{KafkaSource,KafkaSink}.java`
- `tiko-kafka/src/main/java/io/tiko/kafka/{KafkaTransport,KafkaConfig}.java`, `serializer/JsonKafkaSerializer.java`, `test/{FakeKafkaBroker,FakeKafkaTransport}.java`
- `tiko-kafka/src/main/resources/META-INF/tiko/defaults.yaml`

- [ ] **Step 2: Insert the new section into the canonical skill**

Insert immediately before the line `## Kafka transport: write this shape first` (i.e. after the paragraph ending `...full code snippets and lifecycle notes per recipe.`):

```markdown
## API signature sheet — exact imports and signatures

Transcribed from source. Import from this table — never from memory.

### Exact packages

| Type | Package |
|---|---|
| `@Component` `@Inject` `@Named` `@Pick` `@Produces` `@PostConstruct` `@PreDestroy` `@EventHandler` `@EventTrigger` `@EventTriggers` `@Configuration` `@Default` `@Key` `BackoffStrategy` | `io.tiko.annotations` |
| `@KafkaSource` `@KafkaSink` | `io.tiko.kafka.annotations` — **NOT** `io.tiko.annotations` |
| `Container` `EventBus` `EventCallback` `Subscription` `Scope` `Provider` `TransportBootstrap` `ErrorHandler` `ConfigSource` | `io.tiko` |
| `Tiko` `TikoOptions` `TikoDaemon` | `io.tiko.runtime` |
| `ConfigSources` | `io.tiko.config` |
| `KafkaTransport` `KafkaSerializer` `KafkaConfig` | `io.tiko.kafka` |
| `JsonKafkaSerializer` | `io.tiko.kafka.serializer` |
| `FakeKafkaBroker` `FakeKafkaTransport` | `io.tiko.kafka.test` |

**The rule:** a `cannot find symbol` on an import means a wrong package,
not a missing feature — check this table first, then `javap` the resolved
jar. Never conclude an annotation or class does not exist because one
import guess failed. Kafka types additionally require the `tiko-kafka`
dependency and the `tiko-kafka-processor` annotation-processor path —
both ship **commented out** in the scaffolded pom; enable them first.

### Signatures you will call

```java
// Bootstrap (io.tiko.runtime)
static Container Tiko.create()
static Container Tiko.create(TikoOptions options)
static TikoDaemon Tiko.daemon(TikoOptions options)
void TikoDaemon.awaitShutdown()

// Options (io.tiko.runtime) — all builder methods return Builder
static TikoOptions.Builder TikoOptions.builder()
Builder configSource(ConfigSource source)
Builder errorHandler(ErrorHandler handler)
<T> Builder override(Class<T> type, Supplier<? extends T> supplier)
<T extends TransportBootstrap> Builder replaceTransport(Class<T> transport, Function<T, TransportBootstrap> replacement)
TikoOptions build()

// Config sources (io.tiko.config.ConfigSources)
static ConfigSource classpath(String resourcePath)
static ConfigSource classpathAll(String resourcePath)
static ConfigSource file(Path path)
static ConfigSource fromMap(Map<String, Object> data)
static ConfigSource layered(ConfigSource... sources)

// Event bus (io.tiko.EventBus)
<T> void publish(T event)
<T> Subscription subscribe(Class<T> eventType, EventCallback<T> callback)

// Fake broker (io.tiko.kafka.test) — in-process test seam
void FakeKafkaBroker.produce(String topic, byte[] payload, String... headerKv)
List<ProducerRecord<String, byte[]>> FakeKafkaBroker.produced(String topic)
Optional<ProducerRecord<String, byte[]>> FakeKafkaBroker.findProduced(String topic, String headerKey, String headerValue)
static FakeKafkaTransport FakeKafkaTransport.over(KafkaTransport original, FakeKafkaBroker broker)

// JSON serializer (io.tiko.kafka.serializer)
byte[] JsonKafkaSerializer.serialize(Object value)
<T> T JsonKafkaSerializer.deserialize(byte[] bytes, Class<T> type)
```

### Annotation attributes (with defaults)

```java
@Component(Scope scope = Scope.PROTOTYPE, String name = "", String[] profiles = {},
           Class<?>[] expose = {}, boolean exposeSelf = true)
@Produces(Scope scope = Scope.PROTOTYPE, String name = "", String[] profiles = {})
@EventHandler(boolean async = false, Class<?> eventType = Object.class, String timeout = "",
              int retries = 0, String backoff = "", BackoffStrategy backoffStrategy = BackoffStrategy.FIXED)
@EventTrigger(String eventName = "", boolean async = false, boolean spread = false,
              Class<? extends EventTriggerGuard>[] guard = EventTriggerGuard.AlwaysAllow.class)
@Configuration(String prefix)            // required
@Default(String value)                   // required
@Key(String value)                       // required — overrides the YAML key for one record component
@Named(String value)                     // required
@Pick(Class<?> value)                    // required
@KafkaSource(String topic,               // required
             String consumerGroup = "", Class<? extends KafkaSerializer> serializer = KafkaSerializer.Default.class,
             CommitMode commitMode = CommitMode.PER_RECORD)
@KafkaSink(String topic,                 // required
           String partitionKey = "", Class<? extends KafkaSerializer> serializer = KafkaSerializer.Default.class)
```

`timeout` / `backoff` take ISO-8601 durations (`"PT5S"`); `timeout` and
`retries` require `async = true`.

### Config keys — the two rules and the `tiko.kafka.*` table

1. **Your `@Configuration` records:** YAML keys bind to record component
   names **exactly** — camelCase as declared (`poolSize`, never
   `pool-size`). No kebab-case or snake_case aliasing, by design.
2. **`@Key("...")` overrides that** for a single component. Modules use it
   for kebab-case public keys; `tiko-kafka`'s `KafkaConfig` does exactly
   that, so the real broker keys are:

| `tiko.kafka.*` key | shipped default |
|---|---|
| `bootstrap-servers` | `localhost:9092` |
| `consumer-group` | `tiko-app` |
| `serializer` | `json` |
| `auto-offset-reset` | `earliest` |
| `poll-timeout` | `PT0.5S` |
| `shutdown-timeout` | `PT5S` |
| `producer-properties` | `{}` |
| `consumer-properties` | `{}` |
| `poison-record-policy` | `SEEK` (`SKIP` opt-in) |

Write these keys kebab-case exactly as above (they are `@Key`-declared;
`serializer` is the one plain camelCase-free field name). A key that
matches neither a component name nor a `@Key` value fails validation at
`Tiko.create(...)` with a nearest-key suggestion.
```

- [ ] **Step 3: Fix the F5 claim in the canonical skill**

Replace (currently lines ~233-234, after insertion shifted — match on text):

```markdown
Broker config binds to `tiko.kafka.*` (exact-key, camelCase —
`bootstrapServers`, not `bootstrap-servers`). Full contract, configuration, and
```

with:

```markdown
Broker config binds to `tiko.kafka.*` with **kebab-case** keys
(`bootstrap-servers`, not `bootstrapServers`) — they are `@Key`-declared;
see the key table in the API signature sheet above. Full contract, configuration, and
```

- [ ] **Step 4: Apply the identical Step 2 + Step 3 edits to the archetype copy**

Same insertion point (before `## Kafka transport: write this shape first`) and same claim replacement in
`tiko-archetype/src/main/resources/archetype-resources/.ai-skills/tiko-build/SKILL.md`.
Byte-identical section text. Then verify the only remaining differences between the two files are the pre-existing link-style hunks:

Run (Git Bash): `diff .ai-skills/tiko-build/SKILL.md tiko-archetype/src/main/resources/archetype-resources/.ai-skills/tiko-build/SKILL.md`
Expected: exactly 5 hunks, all `../../docs/...` ↔ `https://github.com/tomas-samek/tiko-di/...` link substitutions, none inside the new section. If anything else differs, fix before proceeding.

---

### Task 2: Archetype `CLAUDE.md` import table + rule clarification, then commit

**Files:**
- Modify: `tiko-archetype/src/main/resources/archetype-resources/CLAUDE.md` (new subsection after the `### Test (optional, from `tiko-test`)` block ending ~line 76, before `## Rules`; clarify the sentence at ~line 214)

**Interfaces:**
- Consumes: Task 1's section heading `## API signature sheet — exact imports and signatures` (referenced by name).
- Produces: the final docs state; Task 3 verifies and ships it.

- [ ] **Step 1: Insert the exact-packages subsection**

Insert before `## Rules` (after the Test-annotations block):

```markdown
### Exact packages (import from here, not from memory)

| Type | Package |
|---|---|
| `@Component` `@Inject` `@Named` `@Pick` `@Produces` `@PostConstruct` `@PreDestroy` `@EventHandler` `@EventTrigger` `@Configuration` `@Default` `@Key` | `io.tiko.annotations` |
| `@KafkaSource` `@KafkaSink` | `io.tiko.kafka.annotations` — **NOT** `io.tiko.annotations` |
| `Container` `EventBus` `Scope` `Provider` | `io.tiko` |
| `Tiko` `TikoOptions` `TikoDaemon` | `io.tiko.runtime` |
| `ConfigSources` | `io.tiko.config` |
| `KafkaTransport` / `JsonKafkaSerializer` / `FakeKafkaBroker` `FakeKafkaTransport` | `io.tiko.kafka` / `io.tiko.kafka.serializer` / `io.tiko.kafka.test` |

A `cannot find symbol` on an import means a wrong package, not a missing
feature — check this table, then `javap` the resolved jar; never conclude
an annotation does not exist because one import guess failed. Kafka types
need the `tiko-kafka` dependency + `tiko-kafka-processor` processor path —
both ship commented out in this pom; enable them first. Full signatures:
the API signature sheet in
[`.ai-skills/tiko-build/SKILL.md`](.ai-skills/tiko-build/SKILL.md).
```

- [ ] **Step 2: Clarify the config-keys sentence (~line 214)**

In the sentence `Keys bind **exact** (camelCase as declared — \`poolSize\`, never \`pool-size\`).`, append immediately after it:

```markdown
Module-shipped keys may differ: a record component annotated `@Key("...")` binds that literal key instead — `tiko.kafka.*` keys are kebab-case for exactly this reason (`bootstrap-servers`, see the key table in the tiko-build skill).
```

(Keep the rest of the paragraph unchanged.)

- [ ] **Step 3: Confirm no other false claims remain**

Run (Git Bash): `grep -rn "bootstrapServers" .ai-skills tiko-archetype/src/main/resources/archetype-resources docs/testing.md`
Expected: hits only in the corrected sentences (as the negative example: "not `bootstrapServers`") — no line asserting camelCase as the correct `tiko.kafka.*` form. Also check `.cursor/rules/tiko.md`, `.junie/guidelines.md`, `.github/copilot-instructions.md` under archetype resources came back clean (they are pointer files; the grep above covers them).

- [ ] **Step 4: Full reactor verification**

Run: `& W:\tools\apache-maven\bin\mvn.cmd test *> W:\workspace\sheet-verify.log; $LASTEXITCODE`
Expected: `0`; `Select-String -Path W:\workspace\sheet-verify.log -Pattern "BUILD (SUCCESS|FAILURE)"` shows `BUILD SUCCESS` (includes spotless and the archetype #408 skill-sync gate).

- [ ] **Step 5: Commit (single commit for the whole change, per spec)**

```powershell
git add .ai-skills/tiko-build/SKILL.md tiko-archetype/src/main/resources/archetype-resources/.ai-skills/tiko-build/SKILL.md tiko-archetype/src/main/resources/archetype-resources/CLAUDE.md
git commit -m "docs(skill): API signature sheet + kebab-case key-table fix across agent-facing docs (#269)"
```

---

### Task 3: Push + PR

- [ ] **Step 1: Push and create the PR** (body via file; annotations in backticks):

`pr-body-sheet.md`:

```markdown
Part of #269 (findings F5 + the two post-#414 re-run observations).

Adds a transcribed-from-source **API signature sheet** to the `tiko-build` skill (canonical + archetype bundle): exact packages, callable signatures, annotation attributes with defaults, and the real `tiko.kafka.*` key table. Evidence: the Sonnet re-run succeeded but burned a `javap` discovery loop on exactly these signatures; the Haiku re-run's first domino was importing `@KafkaSource` from the wrong package and concluding the annotations "don't exist".

Also:
- **Closes F5**: the skill claimed `tiko.kafka.*` keys are camelCase (`bootstrapServers`); the source says they are `@Key`-declared **kebab-case** (`bootstrap-servers`, matching the shipped `defaults.yaml`). Corrected in both skill copies; the archetype `CLAUDE.md` config rule gains the `@Key` exception clause.
- Archetype `CLAUDE.md` gains a compact exact-packages table + the wrong-import rule ("`cannot find symbol` means wrong package, not a missing feature") — placed in the one file the weak-model benchmark run actually read.

Docs-only; no runtime changes.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

```powershell
git push -u origin docs/skill-api-signature-sheet
& "C:\Program Files\GitHub CLI\gh.exe" pr create --title "docs(skill): API signature sheet + kebab-case key fix (F5) for agent-facing docs (#269)" --body-file pr-body-sheet.md
Remove-Item pr-body-sheet.md -Confirm:$false
```

- [ ] **Step 2: Post-CI:** `gh pr checks <N> --watch`, then the SonarCloud open-issues query; report PR URL + status. User merges.

---

## Self-Review Notes

- Spec coverage: Component 1 → Task 1 Step 2 (all four blocks present with real content); Component 2 → Task 1 Step 3 + Task 2 Steps 2-3 (AGENTS.md/pointer files verified clean by the Step 3 grep — the spec's assumption that AGENTS.md carries the claim turned out false during plan research; CLAUDE.md:214's general rule is true and gets the exception clause instead); Component 3 → Task 2 Step 1; Implementation rules → Global Constraints + Task 1 Step 1; Verification → Task 2 Step 4 + Task 3; Delivery → single commit + PR.
- Type consistency: section heading text identical in Task 1 Step 2 and Task 2 Step 1's reference; key table matches `KafkaConfig` + `defaults.yaml` transcriptions.
- Deviation from spec, recorded: the spec's "javap cross-check pass against installed jars" is realized as Task 1 Step 1's source-file verification (stronger: source is the ground truth the jars are built from; the jars in `.m2` are the same commit).
