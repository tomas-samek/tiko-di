# API signature sheet for the tiko-build skill (+ F5 closure)

**Date:** 2026-07-06
**Driver:** benchmark #269 post-#414 re-runs; finding F5
**Branch:** `docs/skill-api-signature-sheet`

## Context

Two independent benchmark observations motivate this change:

1. **Sonnet re-run (15/16):** the agent succeeded but ran `javap` against
   the `.m2` jars because no in-project doc gives exact method signatures
   (`TikoOptions.Builder`, `FakeKafkaBroker`, annotation attribute
   defaults). Pure token waste for a solved problem.
2. **Haiku re-run (7/16, `Done` unmet):** the first domino was importing
   the Kafka annotations from the wrong package
   (`io.tiko.annotations` instead of `io.tiko.kafka.annotations`),
   rationalized as "the annotations don't exist", triggering a hand-rolled
   Kafka pivot. Haiku read only `CLAUDE.md`, never the skill.

And one live falsehood (F5), resolved this session by reading the source:
`KafkaConfig` declares explicit `@Key("bootstrap-servers")`-style
annotations, so the real `tiko.kafka.*` override keys are **kebab-case**,
exactly as the shipped `META-INF/tiko/defaults.yaml` writes them. The
skill's claim (SKILL.md:233-234: "exact-key, camelCase — `bootstrapServers`,
not `bootstrap-servers`") is **wrong**. The general rule (exact keys =
record component names, camelCase, no kebab/snake aliasing — see
`tiko-config` `NearestKey` javadoc) applies only to records without
`@Key`.

## Decisions (from brainstorm)

- **Inline section** in the tiko-build skill, not a separate reference
  file — the Haiku run proved weak models don't take read-hops.
- **Plus a compact import-package table in the archetype `CLAUDE.md`** —
  the file weak models demonstrably do read.
- **No automated drift gate** now; the #408 canonical↔bundled sync gate
  and release review carry freshness. File a follow-up only if drift
  bites.

## Component 1 — "API signature sheet" section in the skill

File: `.ai-skills/tiko-build/SKILL.md` (canonical) and
`tiko-archetype/src/main/resources/archetype-resources/.ai-skills/tiko-build/SKILL.md`
(bundled copy; identical section text — the two files' only tolerated
differences remain the pre-existing link-style hunks).

Placement: new `## API signature sheet — exact imports and signatures`
section immediately after the `## Cookbook table` section, before
`## Kafka transport: write this shape first`.

Content, four blocks (~70 lines):

1. **Exact-package table.** Two-column table: type → package. Covers:
   `Component, Inject, Named, Pick, Produces, PostConstruct, PreDestroy,
   EventHandler, EventTrigger, EventTriggers, Configuration, Default, Key`
   (in their true packages under `io.tiko.annotations` / wherever the
   source says — transcribed, not recalled); `KafkaSource, KafkaSink`
   (`io.tiko.kafka.annotations`); `Container, EventBus, Scope, Provider,
   TransportBootstrap` (`io.tiko`); `Tiko, TikoOptions, TikoDaemon`
   (`io.tiko.runtime`); `ConfigSources` (`io.tiko.config`);
   `KafkaTransport, KafkaSerializer` (`io.tiko.kafka`);
   `JsonKafkaSerializer` (`io.tiko.kafka.serializer`); `FakeKafkaBroker,
   FakeKafkaTransport` (`io.tiko.kafka.test`).
2. **Signature one-liners** (javap-style), exactly the surface the
   benchmark agents discovered by hand:
   - `Tiko.create()`, `Tiko.create(TikoOptions)`, `Tiko.daemon(TikoOptions)`,
     `TikoDaemon.awaitShutdown()`
   - `TikoOptions.builder()`; Builder: `configSource(ConfigSource)`,
     `errorHandler(ErrorHandler)`, `override(Class<T>, Supplier<? extends T>)`,
     `replaceTransport(Class<T>, Function<T, TransportBootstrap>)`, `build()`
   - `ConfigSources.classpath(String)` plus whichever sibling factories the
     class actually ships (transcribed)
   - `EventBus.publish(Object)`, `EventBus.subscribe(Class<T>, EventCallback<T>)`
   - `FakeKafkaBroker`: `produce(String, byte[], String...)`,
     `produced(String)`, `findProduced(...)` (exact signature from source),
     `producerClient()`, `consumerClient(String)`
   - `FakeKafkaTransport.over(KafkaTransport, FakeKafkaBroker)`
   - `JsonKafkaSerializer.serialize(Object)` / `deserialize(byte[], Class<T>)`
   - Annotation attributes **with defaults**, transcribed from the
     annotation sources: `@Component(scope, name, profiles)`,
     `@Produces(scope, name, profiles)`,
     `@EventHandler(async, eventType, timeout, retries, backoff,
     backoffStrategy)`, `@EventTrigger(eventName, async, spread, guard)`,
     `@KafkaSource(topic, consumerGroup, serializer)`,
     `@KafkaSink(topic, partitionKey, serializer)`, `@Configuration(prefix)`,
     `@Default(value)`, `@Key(value)` — attribute lists and defaults must
     match the source exactly; drop or add attributes per what the
     annotations actually declare.
3. **Config key rules + the real `tiko.kafka.*` table.** General rule
   (exact keys = component names, camelCase, no aliasing), the `@Key`
   override mechanism, then a table of every `tiko.kafka.*` key in
   kebab-case with its shipped default — transcribed from
   `tiko-kafka/src/main/resources/META-INF/tiko/defaults.yaml`.
4. **The behavioral rule** (verbatim): "A `cannot find symbol` on an
   import means a wrong package, not a missing feature — check this table
   first, then `javap` the resolved jar. Never conclude an annotation or
   class does not exist because one import guess failed."

## Component 2 — F5 falsehood sweep

Fix the camelCase claim everywhere agent-facing docs state it:

- `.ai-skills/tiko-build/SKILL.md` (~line 233) + archetype bundled copy:
  rewrite to the kebab-case truth, pointing at the sheet's key table.
- Archetype `CLAUDE.md` and `AGENTS.md` (the Sonnet run report observed
  the camelCase override contract stated there): `grep` the archetype
  resources for `bootstrapServers` / "camelCase" claims about
  `tiko.kafka.*` and fix each instance. The general camelCase rule for
  *user-defined* `@Configuration` records stays — only the `tiko.kafka.*`
  claims change (those keys are `@Key`-overridden kebab-case).
- Also sweep `.cursor/rules/tiko.md`, `.junie/guidelines.md`,
  `.github/copilot-instructions.md` in archetype resources for the same
  claim (they are pointer files, likely clean — verify).

## Component 3 — Import table in archetype `CLAUDE.md`

File: `tiko-archetype/src/main/resources/archetype-resources/CLAUDE.md`.
Add a ~12-line "Exact packages (import from here, not from memory)" block:
the package table from Component 1 in condensed form, the behavioral rule
(one line), and one operational line: "Kafka annotations require the
`tiko-kafka` dependency + `tiko-kafka-processor` processor path — both
ship commented in this pom; uncomment before use." Placement: with the
existing annotation cheat-sheet section.

## Implementation rules

- **Every signature transcribed from current source, never from memory.**
  Source files: `tiko-api/src/main/java/io/tiko/annotations/*.java`,
  `io/tiko/{Container,EventBus,Scope,Provider,TransportBootstrap}.java`,
  `tiko-runtime/src/main/java/io/tiko/runtime/{Tiko,TikoOptions,TikoDaemon}.java`,
  `tiko-config/src/main/java/io/tiko/config/ConfigSources.java`,
  `tiko-kafka/src/main/java/io/tiko/kafka/**` (annotations, KafkaTransport,
  serializer, test types, KafkaConfig), and `defaults.yaml`.
- Annotation names in markdown always in backtick code spans
  (mention-harvest rule).
- Canonical and bundled skill get the identical new section; the #408
  gate must stay green.

## Verification

- Full reactor `mvn test` (spotless + archetype #408 sync gate).
- Cross-check pass: for each signature block, `javap` the installed
  `0.4.0-SNAPSHOT` jar and diff against the sheet (spot errors before the
  PR, not after).
- Acceptance: an agent reading only the skill (or only archetype
  `CLAUDE.md`) can write every import in the benchmark app without one
  `javap` call; `grep -rn "bootstrapServers" <archetype resources +
  .ai-skills>` returns zero false claims.

## Out of scope

- Automated compile drift-gate (follow-up if drift ever bites).
- Archetype context slimming (separate lever; this change deliberately
  ADDS ~12 lines to CLAUDE.md).
- Changing any binder/runtime behavior — docs only.

## Delivery

One docs-only PR off `main`, single commit
`docs(skill): API signature sheet + kebab-case key-table fix across agent-facing docs (#269)`,
referencing finding F5 and the two re-run observations.
