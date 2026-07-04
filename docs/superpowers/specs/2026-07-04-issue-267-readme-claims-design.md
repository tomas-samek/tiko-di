# Close #267 — README claim re-scoping + Kafka partition-key de-reflection

**Date:** 2026-07-04
**Issue:** #267 (epic #262, milestone "Public framing reset")
**Branch:** `fix/issue-267-readme-claims`

## Context

The structural README rewrite for #267 already landed in PR #290 (commit
`9e3ae12`): identity sentence, verbatim one-line pitch, three-bucket headings,
"What you plug in" table, and skill/reference-app links are all in place. The
issue stayed open because of a follow-up code-review comment and residual
acceptance failures:

1. **Overclaim — runtime exceptions.** README Philosophy #1 says "The only
   runtime exceptions Tiko throws fire at container startup — never during
   `container.get(...)`". Contradicted by `NoSuchComponentException` (thrown
   from `get` / `getProvider` / `pick().resolve()` at any time) and
   `NoActiveEventScopeException` (generated EVENT proxies, outside an open
   unit of work).
2. **Overclaim — reflection.** "Nothing runs by reflection" (Quick example
   epilogue) and "Zero reflection" (Philosophy #4). Live counter-example:
   `KafkaBootstrapSupport.resolvePartitionKey` does uncached
   `getClass().getMethod(accessor).invoke(payload)` **per published record**,
   even though `PartitionKeyValidator` already proves at compile time that the
   accessor is a public zero-arg method on the sink's return type. The issue
   comment classifies this one as *fixable rather than re-wordable*.
   Bootstrap-time reflection (optional-module `Class.forName` bridges in
   `Tiko` / `AggregatingContainer`, custom-serializer instantiation) remains,
   so the claim must also be re-scoped to wiring.
3. **Banned-vocab regex still hits:** "known limitations" ×2 (Documentation
   table) and "example gaps" (Roadmap summary, Phase 9).
4. **Spring appears in the first screen** — the AI-friendliness benchmark
   blurb (added later by #315) names Spring twice directly under the badges;
   #267 acceptance forbids Spring in the first screen.

Decisions taken during brainstorming: fix the Kafka reflection in this task
(not reword-only); move the benchmark blurb below the buckets verbatim rather
than de-naming Spring; carry the accessor to runtime via an extractor lambda
on the descriptor (mirroring the existing `SinkDispatcher` pattern), not by
changing the dispatcher contract.

## Part 1 — Kafka: partition key via generated extractor

Deletes the per-record reflection; the failure mode becomes a compile error.

### Changes

- **`GeneratedSinkDescriptor`** (tiko-kafka, `io.tiko.kafka.runtime`): add a
  nested `KeyExtractor` functional interface —

  ```java
  @FunctionalInterface
  public interface KeyExtractor {
      String extract(Object payload);
  }
  ```

  — and a trailing `KeyExtractor keyExtractor` record component. The
  `partitionKey` string component stays (introspection / diagnostics). The
  extractor is always non-null: sinks without a partition key get `p -> null`.

- **`KafkaTransportBootstrapGenerator`** (tiko-kafka-processor): per sink,
  emit a typed lambda as the new final constructor argument:
  - empty `partitionKey` → `p -> null`;
  - non-empty → cast to the compile-time-known payload type
    (`producedPayloadType`), call the validated accessor, null-safe
    `String.valueOf`:

    ```java
    p -> { var v = ((OrderShipped) p).orderId();
           return v == null ? null : String.valueOf(v); }
    ```

- **`KafkaBootstrapSupport.wrapSinkCallback`**: replace the
  `partitionKey().isEmpty()` branch + `resolvePartitionKey(...)` call with
  `String key = sink.keyExtractor().extract(payload);`. Delete
  `resolvePartitionKey` and the `java.lang.reflect.Method` /
  `InvocationTargetException` imports. The runtime
  `IllegalStateException("partitionKey ... could not be resolved")` path
  disappears entirely.

### Tests

- **tiko-kafka-processor** (compile-testing): update generated-output
  assertions to expect the extractor lambda; cover both the empty-key
  (`p -> null`) and named-key (typed cast + accessor) shapes.
- **tiko-kafka** (runtime): update every test that constructs
  `GeneratedSinkDescriptor` manually (new constructor arg); assert the sink
  path uses the extractor — cases: accessor value non-null String, non-String
  accessor (e.g. `long` id → `String.valueOf`), accessor returns null → null
  Kafka key, empty partition key → null Kafka key.
- Locate all `GeneratedSinkDescriptor` construction sites (tests, examples,
  FakeKafkaBroker fixtures) before editing; example
  `08_kafka_order_warehouse` uses generated code only and must compile with
  the regenerated bootstrap.

TDD applies during implementation (failing test first for the generator
output and the runtime path).

## Part 2 — README truth + acceptance closure

1. **Move the AI-benchmark blurb** (currently directly under the badges)
   verbatim into its own section after "What you plug in", before "Start
   building" — heading: `## Benchmarked for AI-friendliness`. The
   status line ("0.3.0 on Maven Central …") stays at the top. Spring thereby
   leaves the first screen; the blurb text itself is not reworded
   (per memory: preserve benchmark claims; Spring as illustration is allowed
   below the fold).
2. **Reflection claims:**
   - Quick-example epilogue → "The annotation processor validates all
     dependencies at compile-time and generates the wiring code — plain Java
     you can read and step through. No reflection, no classpath scanning in
     your wiring."
   - Philosophy #4 → "No reflection in wiring — generated code, fast
     startup, low memory."
   - An absolute "nothing runs by reflection" stays indefensible even after
     Part 1 (bootstrap-time module bridges, serializer instantiation), so the
     re-scope to *wiring* is required, not optional.
3. **Runtime-exception claim** (Philosophy #1) → re-scope to wiring: wiring
   errors (missing dependencies, circular dependencies, scope violations) are
   compile-time errors and never survive the build; runtime failures are
   reserved for what the compiler cannot see — requesting a component that is
   not in the graph (`NoSuchComponentException`), or touching an EVENT-scoped
   dependency outside an open unit of work (`NoActiveEventScopeException`).
4. **Banned vocab** (exact rewordings):
   - Documentation table, testing.md row: "… scope helpers, known
     limitations." → "… scope helpers, boundary notes."
   - Documentation table, roadmap.md row: "What ships today, what's planned
     per phase, known limitations." → "What ships today and what's planned
     per phase."
   - Roadmap summary, Phase 9: "Advanced-feature example gaps
     (`@EventTriggers`, scoped suppliers, origin chain, `TikoOptions`) plus
     public-docs tightening." → "Advanced-feature examples still to be
     written (`@EventTriggers`, scoped suppliers, origin chain,
     `TikoOptions`) plus public-docs tightening."

### Acceptance (from #267, re-verified at the end)

- `rg "(gap|missing|not yet supported|limitation|tiko's equivalent)" README.md`
  returns zero hits.
- Spring is not mentioned in the first screen of rendered content on
  github.com.
- Three-bucket structure visible from headings alone (already true — must
  stay true).
- "How do I add a database / HTTP layer?" findable in under 30 seconds with a
  `@Produces` answer (already true — must stay true).

## Part 3 — housekeeping

Inspect and remove the stray untracked `META-INF/` and `io/` directories in
the repo root (build debris from a benchmark run). Confirm contents are
generated artifacts before deleting.

## Delivery

- One PR onto `main`, two commits:
  1. `refactor(kafka): resolve @KafkaSink partitionKey via generated extractor, drop runtime reflection (#267)`
  2. `docs(readme): re-scope reflection and runtime-exception claims; move AI benchmark below buckets (#267)`
- Verification before PR: full `mvn test` from the root (spotless gate
  included), the acceptance `rg` check, and a compile of
  `08_kafka_order_warehouse`.

## Out of scope

- Other docs cleanup (#268 closed as no-op; legitimate technical vocabulary
  elsewhere stays).
- Cookbook content (#264) and skill packaging (#266).
- Bootstrap-time reflection removal (optional-module `Class.forName` bridges,
  serializer instantiation) — deliberate design, re-scoped in prose instead.
