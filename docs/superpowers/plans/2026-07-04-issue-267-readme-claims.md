# Close #267 — README Claims + Kafka Partition-Key De-Reflection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close issue #267 by deleting the per-record reflection on the `@KafkaSink` partition-key path (replaced by a compile-time-generated extractor) and re-scoping the README's reflection / runtime-exception claims to what the code actually guarantees.

**Architecture:** `GeneratedSinkDescriptor` gains a `KeyExtractor` functional-interface component, mirroring the existing `SinkDispatcher` pattern. The kafka-processor generator emits one private static `key<i>(Object p)` method per keyed sink (typed cast + accessor call, validated at compile time by the existing `PartitionKeyValidator`) and passes `KafkaTransportBootstrap::key<i>` (or `p -> null` for keyless sinks) into the descriptor. `KafkaBootstrapSupport` calls the extractor and its reflective `resolvePartitionKey` is deleted. README edits are pure text.

**Tech Stack:** Java 21, Maven 3 (mvn at `W:\tools\apache-maven\bin\mvn.cmd`, not on PATH), JavaPoet (`com.palantir.javapoet`), Google compile-testing, JUnit 5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-07-04-issue-267-readme-claims-design.md`

## Global Constraints

- Branch: `fix/issue-267-readme-claims` (already created and checked out; spec committed on it).
- Spotless + Palantir format runs at `validate` — every `mvn` invocation fails on style. Run `& W:\tools\apache-maven\bin\mvn.cmd -pl '!tiko-bom' spotless:apply` from the repo root if a build fails with a spotless violation.
- Never judge a build by a piped exit code: run mvn redirecting to a log file (`> build.log 2>&1` in cmd, or PowerShell `& mvn.cmd ... *> build.log`), then check `$LASTEXITCODE` and grep the log. Never `mvn | grep`.
- Tests: JUnit 5 + AssertJ only. New test method names camelCase (no underscores; existing snake_case methods stay as-is unless the row itself is edited). No `Thread.sleep`, no `@Disabled`.
- Commits: single-line conventional `type(scope): subject` — **no body, no Co-Authored-By**.
- Exactly two product commits on top of the spec commit: one `refactor(kafka)` (Tasks 2+3 together — the modules only compile jointly), one `docs(readme)` (Task 4).
- Kafka e2e Testcontainers ITs skip on this machine (no Docker reachable from the test JVM) — `mvn test` does not run ITs anyway; do not chase Skipped IT output.
- Use the Read/Edit/Write tools for file changes, not PowerShell redirection (PowerShell 5.1 writes UTF-8 BOM, which breaks javac).

---

### Task 1: Delete stray build debris in the repo root

**Files:**
- Delete: `META-INF/` (repo root — contains only `tiko/defaults.yaml`)
- Delete: `io/` (repo root — contains only `tiko/kafka/test/FakeKafkaBroker*.class`)

Both directories are untracked jar-extraction debris from a benchmark run (confirmed contents: one YAML default + four `.class` files). Nothing else may be deleted.

- [ ] **Step 1: Verify contents match the description above**

Run: `Get-ChildItem -Recurse -Force META-INF, io | Select-Object FullName`
Expected: exactly `META-INF\tiko\defaults.yaml`, `io\tiko\kafka\test\FakeKafkaBroker*.class` (4 class files), and their parent dirs. If anything else appears, STOP and report instead of deleting.

- [ ] **Step 2: Delete both directories**

Run: `Remove-Item -Recurse -Force -Confirm:$false META-INF, io`

- [ ] **Step 3: Verify clean tree**

Run: `git status --short`
Expected: no `?? META-INF/`, no `?? io/`. (No commit — these were untracked.)

---

### Task 2: tiko-kafka runtime — `KeyExtractor` on the descriptor, reflection deleted

**Files:**
- Modify: `tiko-kafka/src/main/java/io/tiko/kafka/runtime/GeneratedSinkDescriptor.java`
- Modify: `tiko-kafka/src/main/java/io/tiko/kafka/runtime/KafkaBootstrapSupport.java` (lines 16-17 imports, 114-134 `wrapSinkCallback`, 151-159 `resolvePartitionKey`)
- Modify: `tiko-kafka/src/test/java/io/tiko/kafka/runtime/KafkaOutboundRoundTripTest.java`
- Modify: `tiko-kafka/src/test/java/io/tiko/kafka/runtime/KafkaEgressErrorTest.java` (line 27 construction)
- Modify: `tiko-kafka/src/test/java/io/tiko/kafka/runtime/KafkaEgressAsyncFailureTest.java` (lines 57 and 93 constructions)

**Interfaces:**
- Consumes: existing `GeneratedSinkDescriptor(String topic, String partitionKey, Class<?> eventType, Class<? extends KafkaSerializer> serializerClass, SinkDispatcher dispatcher)`; test fixture `record OrderPlaced(String orderId, int amount)`; test helper `TestKafkaBootstrap.start(container, broker, sources, sinks)`.
- Produces: `GeneratedSinkDescriptor` with a **sixth, trailing** component `KeyExtractor keyExtractor` where `@FunctionalInterface interface KeyExtractor { String extract(Object payload); }` is nested in `GeneratedSinkDescriptor`. Task 3's generator emits 6-arg constructor calls against exactly this signature. The extractor is always non-null; a null **return** means "no Kafka key".

**Compile-order note:** changing the record arity makes `tiko-kafka-processor` tests un-compilable until Task 3 updates the generator (its compile-tests build generated code against the real runtime record). That is expected — do not run the processor module's tests during this task, and do not commit until Task 3 is green.

- [ ] **Step 1: Add the `KeyExtractor` component to the record**

Replace the record in `GeneratedSinkDescriptor.java` (keep the existing package/imports):

```java
/**
 * Runtime descriptor for one {@code @KafkaSink} bridge method.
 *
 * @param topic              destination topic
 * @param partitionKey       accessor name from the annotation; empty string means null Kafka key
 * @param eventType          first parameter type of the sink method (the local event)
 * @param serializerClass    KafkaSerializer.Default.class means "use named YAML default"
 * @param dispatcher         invokes the bridge; returns the payload to send
 * @param keyExtractor       resolves the Kafka record key from the payload; generated at
 *                           compile time from the validated {@code partitionKey} accessor —
 *                           never null, returns null when the record has no key
 */
public record GeneratedSinkDescriptor(
        String topic,
        String partitionKey,
        Class<?> eventType,
        Class<? extends KafkaSerializer> serializerClass,
        SinkDispatcher dispatcher,
        KeyExtractor keyExtractor) {

    @FunctionalInterface
    public interface SinkDispatcher {
        Object dispatch(Container container, Object event);
    }

    @FunctionalInterface
    public interface KeyExtractor {
        String extract(Object payload);
    }
}
```

- [ ] **Step 2: Write the behavioral test for extractor-driven keys**

In `KafkaOutboundRoundTripTest.java`, update the existing construction (line 23-28) to pass an extractor, and add a parameterized test covering the three key shapes. Full updated file:

```java
package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.runtime.GeneratedSinkDescriptor.KeyExtractor;
import io.tiko.kafka.runtime.fixtures.OrderKafkaPublisher;
import io.tiko.kafka.runtime.fixtures.OrderPlaced;
import io.tiko.kafka.serializer.JsonKafkaSerializer;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.runtime.Tiko;
import java.util.List;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class KafkaOutboundRoundTripTest {

    @Test
    @SuppressWarnings("unchecked")
    void publishing_locally_sends_a_kafka_record_with_partition_key() {
        FakeKafkaBroker broker = new FakeKafkaBroker();

        List<GeneratedSinkDescriptor> sinks = List.of(descriptor(p -> {
            var v = ((OrderPlaced) p).orderId();
            return v == null ? null : String.valueOf(v);
        }));

        try (Container container = Tiko.create();
                TestKafkaBootstrap bootstrap = TestKafkaBootstrap.start(container, broker, List.of(), sinks)) {

            container.getEventBus().publish(new OrderPlaced("o-5", 21));

            List<ProducerRecord<String, byte[]>> produced = broker.produced("orders-out");
            assertThat(produced).hasSize(1);
            ProducerRecord<String, byte[]> rec = produced.get(0);
            assertThat(rec.key()).isEqualTo("o-5");

            OrderPlaced roundTripped = new JsonKafkaSerializer().deserialize(rec.value(), OrderPlaced.class);
            assertThat(roundTripped).isEqualTo(new OrderPlaced("o-5", 21));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keyShapes")
    void partitionKeyComesFromTheDescriptorExtractor(String name, KeyExtractor extractor, String expectedKey) {
        FakeKafkaBroker broker = new FakeKafkaBroker();

        List<GeneratedSinkDescriptor> sinks = List.of(descriptor(extractor));

        try (Container container = Tiko.create();
                TestKafkaBootstrap bootstrap = TestKafkaBootstrap.start(container, broker, List.of(), sinks)) {

            container.getEventBus().publish(new OrderPlaced("o-5", 21));

            List<ProducerRecord<String, byte[]>> produced = broker.produced("orders-out");
            assertThat(produced).hasSize(1);
            assertThat(produced.get(0).key()).isEqualTo(expectedKey);
        }
    }

    private static Stream<Arguments> keyShapes() {
        return Stream.of(
                Arguments.of("string accessor", (KeyExtractor) p -> String.valueOf(((OrderPlaced) p).orderId()), "o-5"),
                Arguments.of("numeric accessor", (KeyExtractor) p -> String.valueOf(((OrderPlaced) p).amount()), "21"),
                Arguments.of("null key", (KeyExtractor) p -> null, null));
    }

    private static GeneratedSinkDescriptor descriptor(KeyExtractor extractor) {
        return new GeneratedSinkDescriptor(
                "orders-out",
                "orderId",
                OrderPlaced.class,
                KafkaSerializer.Default.class,
                (container, event) -> container.get(OrderKafkaPublisher.class).toKafka((OrderPlaced) event),
                extractor);
    }
}
```

- [ ] **Step 3: Run the module's tests — expect compile failure (the "red" state)**

Run (PowerShell): `& W:\tools\apache-maven\bin\mvn.cmd test -pl tiko-kafka -am *> ..\kafka-red.log; $LASTEXITCODE`
Expected: non-zero — `KafkaBootstrapSupport` doesn't compile against the new record yet, and the other two test files still use the 5-arg constructor.

- [ ] **Step 4: Use the extractor in `KafkaBootstrapSupport`; delete the reflection**

In `KafkaBootstrapSupport.java`:

1. Delete imports `java.lang.reflect.InvocationTargetException` and `java.lang.reflect.Method` (lines 16-17).
2. In `wrapSinkCallback`, replace

```java
String key = sink.partitionKey().isEmpty() ? null : resolvePartitionKey(payload, sink.partitionKey());
```

with

```java
String key = sink.keyExtractor().extract(payload);
```

3. Delete the whole `resolvePartitionKey` method (lines 151-159).

- [ ] **Step 5: Update the remaining 5-arg constructions to pass `p -> null`**

`KafkaEgressErrorTest.java` line 27-32 →

```java
List<GeneratedSinkDescriptor> sinks = List.of(new GeneratedSinkDescriptor(
        "fail-out",
        "",
        OrderPlaced.class,
        KafkaSerializer.Default.class,
        (container, event) -> container.get(ThrowingPublisher.class).toKafka((OrderPlaced) event),
        p -> null));
```

`KafkaEgressAsyncFailureTest.java` line 57-58 →

```java
List<GeneratedSinkDescriptor> sinks = List.of(new GeneratedSinkDescriptor(
        "async-fail-out",
        "",
        OrderPlaced.class,
        KafkaSerializer.Default.class,
        (container, event) -> event,
        p -> null));
```

`KafkaEgressAsyncFailureTest.java` line 93-94 →

```java
List<GeneratedSinkDescriptor> sinks = List.of(new GeneratedSinkDescriptor(
        "handler-throws",
        "",
        OrderPlaced.class,
        KafkaSerializer.Default.class,
        (container, event) -> event,
        p -> null));
```

- [ ] **Step 6: Run the module's tests — expect green**

Run: `& W:\tools\apache-maven\bin\mvn.cmd test -pl tiko-kafka -am *> ..\kafka-green.log; $LASTEXITCODE`
Expected: `0`, `BUILD SUCCESS` in the log, all tiko-kafka tests pass including the new `partitionKeyComesFromTheDescriptorExtractor` rows. **Do not commit yet** — tiko-kafka-processor is intentionally broken until Task 3.

---

### Task 3: tiko-kafka-processor — generate the extractor, then the joint kafka commit

**Files:**
- Modify: `tiko-kafka-processor/src/main/java/io/tiko/kafka/processor/generator/KafkaTransportBootstrapGenerator.java` (`buildSinksProvider` lines 147-173, new `buildSinkKeyExtractor`, generate-loop lines 78-81)
- Test: `tiko-kafka-processor/src/test/java/io/tiko/kafka/processor/generator/KafkaTransportBootstrapGeneratorTest.java`

**Interfaces:**
- Consumes: Task 2's 6-arg `GeneratedSinkDescriptor(topic, partitionKey, eventType, serializerClass, dispatcher, keyExtractor)`; compile-time model `KafkaSinkDescriptor` (already has `producedPayloadType()` and `partitionKey()`); the existing generated-class constant `CLASS_NAME = "KafkaTransportBootstrap"`.
- Produces: generated bootstrap containing, per keyed sink `i`, a `private static String key<i>(Object p)` method and a 6th constructor argument `KafkaTransportBootstrap::key<i>`; keyless sinks pass `p -> null`.

- [ ] **Step 1: Write the failing generator tests**

Add two tests to `KafkaTransportBootstrapGeneratorTest.java` (keep the two existing tests; add these imports: `java.io.IOException`, `java.nio.charset.StandardCharsets`, `javax.tools.JavaFileObject`, and `static org.assertj.core.api.Assertions.assertThat` aliased — use the AssertJ import with a distinct name or fully-qualified call, since the compile-testing `assertThat` is already statically imported; simplest is `org.assertj.core.api.Assertions.assertThat(...)` fully qualified inside the new methods):

```java
@Test
void keyedSinkGeneratesStaticKeyExtractorInsteadOfReflection() throws IOException {
    Compilation compilation = compileOrderFixtures();

    String normalized = bootstrapSource(compilation).replaceAll("\\s", "");
    org.assertj.core.api.Assertions.assertThat(normalized)
            .as("keyed sink resolves the partition key via a generated static method")
            .contains("KafkaTransportBootstrap::key0")
            .contains("privatestaticStringkey0(Objectp)")
            .contains("Objectv=((OrderPlaced)p).orderId()")
            .contains("returnv==null?null:String.valueOf(v)");
}

@Test
void keylessSinkGeneratesNullKeyLambda() throws IOException {
    Compilation compilation = Compiler.javac()
            .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
            .compile(
                    JavaFileObjects.forSourceString(
                            "demo.OrderPlaced", "package demo; public record OrderPlaced(String orderId) {}"),
                    JavaFileObjects.forSourceString("demo.OrderPublisher", """
                            package demo;
                            import io.tiko.annotations.Component;
                            import io.tiko.kafka.annotations.KafkaSink;
                            import io.tiko.Scope;
                            @Component(scope = Scope.SINGLETON)
                            public class OrderPublisher {
                                @KafkaSink(topic = "orders")
                                public OrderPlaced toKafka(OrderPlaced e) { return e; }
                            }
                            """));

    assertThat(compilation).succeeded();
    String normalized = bootstrapSource(compilation).replaceAll("\\s", "");
    org.assertj.core.api.Assertions.assertThat(normalized)
            .as("keyless sink passes a null-returning extractor and generates no key method")
            .contains("this::sink0,p->null")
            .doesNotContain("key0(");
}

private Compilation compileOrderFixtures() {
    Compilation compilation = Compiler.javac()
            .withProcessors(new TikoAnnotationProcessor(), new KafkaAnnotationProcessor())
            .compile(
                    JavaFileObjects.forSourceString(
                            "demo.OrderPlaced", "package demo; public record OrderPlaced(String orderId) {}"),
                    JavaFileObjects.forSourceString("demo.OrderPublisher", """
                            package demo;
                            import io.tiko.annotations.Component;
                            import io.tiko.kafka.annotations.KafkaSink;
                            import io.tiko.Scope;
                            @Component(scope = Scope.SINGLETON)
                            public class OrderPublisher {
                                @KafkaSink(topic = "orders", partitionKey = "orderId")
                                public OrderPlaced toKafka(OrderPlaced e) { return e; }
                            }
                            """));
    assertThat(compilation).succeeded();
    return compilation;
}

private static String bootstrapSource(Compilation compilation) throws IOException {
    JavaFileObject bootstrap = compilation.generatedSourceFiles().stream()
            .filter(f -> f.getName().contains("KafkaTransportBootstrap"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("KafkaTransportBootstrap was not generated"));
    return new String(bootstrap.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
}
```

Note: `Object v = ...` (autoboxing) rather than `var` in the generated key method is deliberate — the accessor may return a primitive (e.g. `int amount()`), and `var v` + `v == null` would not compile for primitives.

- [ ] **Step 2: Run the processor tests — expect failure**

Run: `& W:\tools\apache-maven\bin\mvn.cmd test -pl tiko-kafka-processor -am *> ..\proc-red.log; $LASTEXITCODE`
Expected: non-zero. The two new tests fail; the pre-existing compile-tests (`source_and_sink_generate_bootstrap_and_service_entry`, `KafkaTransportBootstrapContractTest`, `KafkaTopologyFragmentTest`) also fail because generated code still calls the 5-arg constructor. This is the joint red state Task 2 predicted.

- [ ] **Step 3: Update the generator**

In `KafkaTransportBootstrapGenerator.java`:

1. In `generate(...)`, after the sink-dispatcher loop (line 79-81), add key-extractor methods for keyed sinks:

```java
// Per-sink key extractor: compile-time replacement for the former runtime reflection
// on @KafkaSink(partitionKey) — the accessor was already validated by PartitionKeyValidator.
for (int i = 0; i < sinks.size(); i++) {
    if (!sinks.get(i).partitionKey().isEmpty()) {
        cls.addMethod(buildSinkKeyExtractor(sinks.get(i), i));
    }
}
```

2. In `buildSinksProvider`, replace the `list.add(...)` statement (lines 162-169) with:

```java
for (int i = 0; i < sinks.size(); i++) {
    KafkaSinkDescriptor s = sinks.get(i);
    if (s.partitionKey().isEmpty()) {
        b.addStatement(
                "list.add(new $T($S, $S, $T.class, $T.class, this::sink$L, p -> null))",
                KAFKA_SINK_DESCRIPTOR,
                s.topic(),
                s.partitionKey(),
                TypeName.get(s.eventType()),
                TypeName.get(s.serializerClass()),
                i);
    } else {
        b.addStatement(
                "list.add(new $T($S, $S, $T.class, $T.class, this::sink$L, $L::key$L))",
                KAFKA_SINK_DESCRIPTOR,
                s.topic(),
                s.partitionKey(),
                TypeName.get(s.eventType()),
                TypeName.get(s.serializerClass()),
                i,
                CLASS_NAME,
                i);
    }
}
```

3. Add the new method builder (next to `buildSinkDispatcher`):

```java
private MethodSpec buildSinkKeyExtractor(KafkaSinkDescriptor s, int index) {
    TypeName payloadName = TypeName.get(s.producedPayloadType());
    return MethodSpec.methodBuilder("key" + index)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(String.class)
            .addParameter(Object.class, "p")
            // Object (not var): the accessor may return a primitive, which must box
            // before the null check.
            .addStatement("$T v = (($T) p).$L()", Object.class, payloadName, s.partitionKey())
            .addStatement("return v == null ? null : $T.valueOf(v)", String.class)
            .build();
}
```

- [ ] **Step 4: Run both kafka modules — expect green**

Run: `& W:\tools\apache-maven\bin\mvn.cmd test -pl tiko-kafka,tiko-kafka-processor -am *> ..\kafka-all-green.log; $LASTEXITCODE`
Expected: `0`, `BUILD SUCCESS`, all tests in both modules pass.

- [ ] **Step 5: Commit the joint kafka change**

```powershell
git add tiko-kafka/src tiko-kafka-processor/src
git status --short   # verify ONLY the five expected files are staged
git commit -m "refactor(kafka): resolve @KafkaSink partitionKey via generated extractor, drop runtime reflection (#267)"
```

---

### Task 4: README truth + acceptance closure

**Files:**
- Modify: `README.md` (line numbers below refer to the current file at commit `dade00c`-based branch state)

**Interfaces:**
- Consumes: nothing from other tasks (text-only).
- Produces: the final README; Task 5 verifies its acceptance criteria.

- [ ] **Step 1: Move the AI-benchmark blurb below the buckets**

Delete line 14 (the paragraph starting `**Benchmarked for AI-friendliness** → [llm-framework-benchmark]...`) and the blank line that follows it, so the status line (line 12) is followed directly by `## What Tiko is`.

Then insert a new section between the end of the "What you plug in" table (line with `| Metrics / tracing | ... | — |`) and `## Start building`:

```markdown
## Benchmarked for AI-friendliness

[llm-framework-benchmark](https://github.com/tomas-samek/llm-framework-benchmark): on an external-oracle-graded build task (a Kafka → H2 → merged-notification service), run across three models (Claude Sonnet 4.6 / Fable 5 / Opus 4.8, n=5). tiko — *absent from the models' training data* — reaches **86–100% median compliance**, on par with Spring on versions the models know, and clears the brand-new Spring Boot 4.0.6 wall that broke Sonnet 4.6 (median 0%). See the benchmark for the full picture, per-build token cost, and caveats.
```

(The body text is the original blurb verbatim, minus the leading `**Benchmarked for AI-friendliness** → ` prefix, which becomes the heading.)

- [ ] **Step 2: Re-scope the reflection claim under the quick example**

Replace (line 103):

```markdown
The annotation processor validates all dependencies at compile-time and generates the wiring code. Nothing runs by reflection.
```

with:

```markdown
The annotation processor validates all dependencies at compile-time and generates the wiring code — plain Java you can read and step through. No reflection, no classpath scanning in your wiring.
```

- [ ] **Step 3: Re-scope Philosophy #1 (runtime exceptions)**

Replace (line 339):

```markdown
1. **Compile-time safety.** Catch all errors the compiler can see. The only runtime exceptions Tiko throws fire at container startup — never during `container.get(...)` in a running application.
```

with:

```markdown
1. **Compile-time safety.** Wiring errors — missing dependencies, circular dependencies, scope violations — are compile-time errors and never survive the build. Runtime failures are reserved for what the compiler cannot see: requesting a component that is not in the graph, or touching an EVENT-scoped dependency outside an open unit of work.
```

- [ ] **Step 4: Re-scope Philosophy #4 (zero reflection)**

Replace (line 342):

```markdown
4. **Performance.** Zero reflection, fast startup, low memory.
```

with:

```markdown
4. **Performance.** No reflection in wiring — generated code, fast startup, low memory.
```

- [ ] **Step 5: Banned-vocab rewordings (three edits)**

Documentation table, testing.md row (line 301): replace the cell text

`...`RecordingEventBus`, scope helpers, known limitations.` → `...`RecordingEventBus`, scope helpers, boundary notes.`

Documentation table, roadmap.md row (line 303):

`What ships today, what's planned per phase, known limitations.` → `What ships today and what's planned per phase.`

Roadmap summary, Phase 9 (line 320):

`Advanced-feature example gaps (`@EventTriggers`, scoped suppliers, origin chain, `TikoOptions`) plus public-docs tightening.` → `Advanced-feature examples still to be written (`@EventTriggers`, scoped suppliers, origin chain, `TikoOptions`) plus public-docs tightening.`

- [ ] **Step 6: Verify acceptance criteria**

Run: `rg -n "(gap|missing|not yet supported|limitation|tiko's equivalent)" README.md`
Expected: **zero hits** (exit code 1).

Run: `rg -n "Spring" README.md`
Expected: first hit is at or below the new `## Benchmarked for AI-friendliness` section (i.e. no hit before the "Start building" heading; the cold-start table, roadmap, and acknowledgments hits are fine). Visually confirm the first ~30 rendered lines contain no "Spring".

- [ ] **Step 7: Commit**

```powershell
git add README.md
git commit -m "docs(readme): re-scope reflection and runtime-exception claims; move AI benchmark below buckets (#267)"
```

---

### Task 5: Full verification + PR

**Files:** none created (verification + delivery only).

- [ ] **Step 1: Full reactor test run**

Run: `& W:\tools\apache-maven\bin\mvn.cmd test *> ..\full-test.log; $LASTEXITCODE`
Expected: `0` and `BUILD SUCCESS` in `..\full-test.log` (check with `rg "BUILD (SUCCESS|FAILURE)" ..\full-test.log`). This covers the spotless gate and compiles example `08_kafka_order_warehouse` against the regenerated bootstrap. If spotless fails: `& W:\tools\apache-maven\bin\mvn.cmd -pl '!tiko-bom' spotless:apply`, re-run, and amend nothing — fold formatting into whichever of the two commits owns the file via `git commit --fixup` is NOT used; instead commit formatting as part of a re-run only if it appears (expected: none, since all code in this plan is hand-formatted to house style).

- [ ] **Step 2: Push and open the PR**

Write the PR body to a file first (mention-harvest rule: every annotation in backticks; `--body-file`, never heredoc):

`pr-body-267.md`:

```markdown
Closes #267.

The structural three-bucket rewrite landed in #290; this PR closes out the remaining acceptance criteria and the claim corrections from the follow-up review comment.

**Kafka (`refactor`):** `@KafkaSink(partitionKey = "...")` no longer resolves the accessor by per-record reflection in `KafkaBootstrapSupport`. The kafka-processor now generates a static key-extractor method per keyed sink (the accessor is already compile-time-validated by `PartitionKeyValidator`) and passes it through a new `KeyExtractor` component on `GeneratedSinkDescriptor`. The runtime `IllegalStateException` fallback path is gone — a bad key is now a compile error only.

**README (`docs`):**
- Runtime-exception claim re-scoped to wiring: `NoSuchComponentException` / `NoActiveEventScopeException` exist by design and the old "never during `container.get(...)`" wording overclaimed.
- "Nothing runs by reflection" / "Zero reflection" re-scoped to "no reflection in your wiring" (bootstrap-time module bridges remain, deliberately).
- AI-friendliness benchmark blurb moved below the three buckets so the first screen carries no Spring mention (#267 acceptance).
- Last banned-vocab hits reworded ("known limitations" ×2, "example gaps").

Acceptance re-verified: `rg "(gap|missing|not yet supported|limitation|tiko's equivalent)" README.md` → zero hits; three-bucket headings unchanged.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

Then:

```powershell
git push -u origin fix/issue-267-readme-claims
& "C:\Program Files\GitHub CLI\gh.exe" pr create --title "Close #267: README claim re-scoping + Kafka partition-key de-reflection" --body-file pr-body-267.md
Remove-Item pr-body-267.md -Confirm:$false
```

- [ ] **Step 3: Post-CI checks**

After CI runs: check the Sonar open-issues API for new issues on the PR (green gate is not enough — query open issues before calling it ready). Report PR URL + CI/Sonar state to the user; the user merges in the UI (branch protection — never `--admin`).

---

## Self-Review Notes

- Spec coverage: Part 1 → Tasks 2-3; Part 2 → Task 4 (all six edits present verbatim from spec); Part 3 → Task 1; Delivery → Tasks 3/4 commits + Task 5 PR. No gaps.
- Type consistency: `KeyExtractor.extract(Object) : String` used identically in Task 2 (record + tests) and Task 3 (generated `key<i>` + method-reference emit). Constructor arity 6 everywhere.
- Deviation from spec, intentional: the generator emits a private static `key<i>` method + method reference instead of an inline block lambda — same generated-extractor design, but avoids multi-statement lambdas inside a single JavaPoet `addStatement` (fragile formatting) and mirrors the existing `sink<i>` dispatcher pattern. Keyless sinks still get the spec's `p -> null`.
- Deviation from spec, intentional: generated key method uses `Object v = ...` rather than `var v` because the validated accessor may return a primitive (`var` would infer the primitive and break the null check). House `var` rule yields to correctness here; the comment in the generated-code builder records why.
