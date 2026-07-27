# Kafka Ingest Error-Decision Hook Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a programmatic per-error Kafka ingest hook that inspects each `KafkaIngestError` and returns `SEEK`/`SKIP`/`DEAD_LETTER`/`FAIL`, overriding the static `poison-record-policy` when registered.

**Architecture:** A user registers one `@Component` implementing `KafkaIngestErrorDecider`. `KafkaBootstrapSupport` resolves it via `container.getAll(...)` and threads it into each `ThreadPerTopicRunner`. The runner tracks a per-partition consecutive-failure count and applies the returned decision at its existing ingest-failure site. When no decider is registered the current #313 static path runs unchanged.

**Tech Stack:** Java 21, Maven, JUnit 5 + AssertJ + Awaitility, `com.google.testing.compile` (not needed here), Apache Kafka clients. Module: `tiko-kafka`.

## Global Constraints

- Java 21+; 4-space indent, K&R braces, Palantir format via `mvn spotless:apply` (run before every commit; `spotless:check` is bound to `validate` and fails the build otherwise).
- Framework logging only via `System.Logger` through the existing `ThreadPerTopicRunner.LoggerHolder`; never `System.err`/`printStackTrace`.
- New tests: JUnit 5, AssertJ, `xxxTest` naming, camelCase method names, no `Thread.sleep` (use Awaitility `await()`), no `@Disabled`.
- Purely additive: with no decider registered, behavior is byte-for-byte the #313 static `poison-record-policy` path. Existing `ThreadPerTopicRunner` 7-arg construction and tests must keep compiling and passing.
- Maven runs from `W:\tools\apache-maven\bin\mvn` (not on PATH). Build a single module with `-pl tiko-kafka`.
- `IngestDecision` (programmatic, 4 values) stays a separate type from the YAML-bound `IngestErrorPolicy` (`SEEK`/`SKIP`); do not make `DEAD_LETTER`/`FAIL` YAML-selectable.

---

### Task 1: Public API types

**Files:**
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/IngestDecision.java`
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/KafkaIngestErrorDecider.java`
- Create: `tiko-kafka/src/main/java/io/tiko/kafka/KafkaRecordDeadLettered.java`
- Test: `tiko-kafka/src/test/java/io/tiko/kafka/KafkaRecordDeadLetteredTest.java`

**Interfaces:**
- Produces:
  - `enum IngestDecision { SEEK, SKIP, DEAD_LETTER, FAIL }`
  - `interface KafkaIngestErrorDecider { IngestDecision decide(KafkaIngestError error, int attempt); }`
  - `record KafkaRecordDeadLettered(String topic, int partition, long offset, org.apache.kafka.common.header.Headers headers, Throwable cause, int attempts) implements io.tiko.TransportError` with `transport()` returning `"kafka"`.

- [ ] **Step 1: Write the failing test**

Create `tiko-kafka/src/test/java/io/tiko/kafka/KafkaRecordDeadLetteredTest.java`:

```java
package io.tiko.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.ErrorContext;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

class KafkaRecordDeadLetteredTest {

    @Test
    void carriesRecordCoordinatesAndIsAKafkaTransportError() {
        Throwable cause = new IllegalStateException("bad shape");
        KafkaRecordDeadLettered dl =
                new KafkaRecordDeadLettered("orders", 2, 42L, new RecordHeaders(), cause, 3);

        assertThat(dl.topic()).isEqualTo("orders");
        assertThat(dl.partition()).isEqualTo(2);
        assertThat(dl.offset()).isEqualTo(42L);
        assertThat(dl.attempts()).isEqualTo(3);
        assertThat(dl.cause()).isSameAs(cause);
        assertThat(dl.transport()).isEqualTo("kafka");
        assertThat(dl).isInstanceOf(ErrorContext.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `/w/tools/apache-maven/bin/mvn -pl tiko-kafka test -Dtest=KafkaRecordDeadLetteredTest`
Expected: FAIL — compilation error, `KafkaRecordDeadLettered` does not exist.

- [ ] **Step 3: Write the three types**

Create `tiko-kafka/src/main/java/io/tiko/kafka/IngestDecision.java`:

```java
package io.tiko.kafka;

/**
 * What a registered {@link KafkaIngestErrorDecider} tells the consumer runner to do with a
 * record whose ingest failed (#385). Programmatic superset of the static, YAML-bound
 * {@link IngestErrorPolicy} ({@code SEEK}/{@code SKIP}); {@link #DEAD_LETTER} and {@link #FAIL}
 * only make sense with a decider in scope, so they are deliberately not YAML-selectable.
 */
public enum IngestDecision {

    /** Seek back and redeliver the record on the next poll (retry). No offset committed. */
    SEEK,

    /** Route the {@link KafkaIngestError} and commit past the record so the partition advances. */
    SKIP,

    /**
     * Route a {@link KafkaRecordDeadLettered} (distinct from {@link KafkaIngestError}) and commit
     * past the record. Lets an operator's {@code ErrorHandler} forward a deliberate dead-letter to
     * their own sink, distinct from a skip or a transient blip.
     */
    DEAD_LETTER,

    /** Route the {@link KafkaIngestError} and stop this topic's consumer; the record is left uncommitted. */
    FAIL
}
```

Create `tiko-kafka/src/main/java/io/tiko/kafka/KafkaIngestErrorDecider.java`:

```java
package io.tiko.kafka;

/**
 * Programmatic per-error ingest decision hook (#385). Register at most one as a
 * {@code @Component(scope = Scope.SINGLETON)}; when present it overrides the static
 * {@code tiko.kafka.poison-record-policy} for every ingest failure (deserialize, bridge
 * dispatch, or publish).
 */
@FunctionalInterface
public interface KafkaIngestErrorDecider {

    /**
     * Chooses the outcome for a failed record.
     *
     * @param error   the ingest failure (topic, partition, offset, headers, cause)
     * @param attempt consecutive failure count for this record's offset, starting at 1
     * @return the outcome the runner applies; a {@code null} return is treated as {@link IngestDecision#SEEK}
     */
    IngestDecision decide(KafkaIngestError error, int attempt);
}
```

Create `tiko-kafka/src/main/java/io/tiko/kafka/KafkaRecordDeadLettered.java`:

```java
package io.tiko.kafka;

import io.tiko.TransportError;
import org.apache.kafka.common.header.Headers;

/**
 * Routed via the configured {@code ErrorHandler} when a {@link KafkaIngestErrorDecider} returns
 * {@link IngestDecision#DEAD_LETTER} (#385). Distinct from {@link KafkaIngestError} so an operator
 * can tell a deliberate dead-letter (after {@link #attempts()} tries) from a skip or a transient
 * blip and forward it to their own sink. The record is committed past after routing.
 *
 * @param topic     source topic
 * @param partition partition number
 * @param offset    record offset within the partition
 * @param headers   record headers (never {@code null})
 * @param cause     the underlying throwable
 * @param attempts  consecutive failure count reached before dead-lettering (>= 1)
 */
public record KafkaRecordDeadLettered(
        String topic, int partition, long offset, Headers headers, Throwable cause, int attempts)
        implements TransportError {

    @Override
    public String transport() {
        return "kafka";
    }
}
```

- [ ] **Step 4: Format and run the test to verify it passes**

Run: `/w/tools/apache-maven/bin/mvn -pl tiko-kafka spotless:apply` then `/w/tools/apache-maven/bin/mvn -pl tiko-kafka test -Dtest=KafkaRecordDeadLetteredTest`
Expected: PASS — `Tests run: 1, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add tiko-kafka/src/main/java/io/tiko/kafka/IngestDecision.java \
        tiko-kafka/src/main/java/io/tiko/kafka/KafkaIngestErrorDecider.java \
        tiko-kafka/src/main/java/io/tiko/kafka/KafkaRecordDeadLettered.java \
        tiko-kafka/src/test/java/io/tiko/kafka/KafkaRecordDeadLetteredTest.java
git commit -m "feat(kafka): IngestDecision + KafkaIngestErrorDecider + KafkaRecordDeadLettered types (#385)"
```

---

### Task 2: Runner decider handling (attempt tracking + decision switch)

**Files:**
- Modify: `tiko-kafka/src/main/java/io/tiko/kafka/runtime/ThreadPerTopicRunner.java`
- Test: `tiko-kafka/src/test/java/io/tiko/kafka/runtime/KafkaIngestErrorDeciderTest.java` (create)

**Interfaces:**
- Consumes: `IngestDecision`, `KafkaIngestErrorDecider`, `KafkaRecordDeadLettered` (Task 1); existing `ThreadPerTopicRunner` 7-arg constructor; `ScriptedConsumerClient`, `RunnerTestSupport` (test support).
- Produces: new **8-arg** `ThreadPerTopicRunner(GeneratedSourceDescriptor, KafkaConsumerClient, Container, EventBus, ErrorHandler, KafkaSerializer, KafkaConfig, KafkaIngestErrorDecider)` constructor; the existing 7-arg constructor delegates to it with a `null` decider.

- [ ] **Step 1: Write the first failing test (bounded retry then dead-letter)**

Create `tiko-kafka/src/test/java/io/tiko/kafka/runtime/KafkaIngestErrorDeciderTest.java`:

```java
package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.kafka.IngestDecision;
import io.tiko.kafka.KafkaIngestError;
import io.tiko.kafka.KafkaIngestErrorDecider;
import io.tiko.kafka.KafkaRecordDeadLettered;
import io.tiko.runtime.Tiko;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class KafkaIngestErrorDeciderTest {

    private static final TopicPartition P0 = new TopicPartition("t", 0);

    private static ConsumerRecords<String, byte[]> poisonBatch(long offset) {
        return new ConsumerRecords<>(
                Map.of(P0, List.of(RunnerTestSupport.consumerRecord("t", 0, offset, "poison"))));
    }

    private static ThreadPerTopicRunner runner(
            ScriptedConsumerClient client, Container container, List<ErrorContext> routed, KafkaIngestErrorDecider d) {
        return new ThreadPerTopicRunner(
                RunnerTestSupport.stringSource("t", payload -> payload),
                client,
                container,
                container.getEventBus(),
                routed::add,
                RunnerTestSupport.UTF8,
                RunnerTestSupport.config(),
                d);
    }

    @Test
    void retriesWhileTransientThenDeadLettersWithTheAttemptCount() {
        ScriptedConsumerClient client =
                new ScriptedConsumerClient(List.of(poisonBatch(0), poisonBatch(0), poisonBatch(0)));
        List<ErrorContext> routed = new CopyOnWriteArrayList<>();
        KafkaIngestErrorDecider decider =
                (error, attempt) -> attempt < 3 ? IngestDecision.SEEK : IngestDecision.DEAD_LETTER;

        try (Container container = Tiko.create()) {
            ThreadPerTopicRunner runner = runner(client, container, routed, decider);
            runner.start();
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> client.commits.contains(Map.entry(P0, 1L)));
            } finally {
                runner.stop();
            }
        }

        assertThat(client.seeks)
                .as("attempts 1 and 2 seek back at the failed offset")
                .containsExactly(Map.entry(P0, 0L), Map.entry(P0, 0L));
        assertThat(client.commits).as("dead-letter commits past the record").contains(Map.entry(P0, 1L));
        KafkaRecordDeadLettered dl = routed.stream()
                .filter(KafkaRecordDeadLettered.class::isInstance)
                .map(KafkaRecordDeadLettered.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no KafkaRecordDeadLettered routed: " + routed));
        assertThat(dl.attempts()).isEqualTo(3);
        assertThat(dl.offset()).isZero();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `/w/tools/apache-maven/bin/mvn -pl tiko-kafka test -Dtest=KafkaIngestErrorDeciderTest`
Expected: FAIL — compilation error, no 8-arg `ThreadPerTopicRunner` constructor.

- [ ] **Step 3: Implement the runner changes**

In `tiko-kafka/src/main/java/io/tiko/kafka/runtime/ThreadPerTopicRunner.java`:

Add imports (with the existing imports):

```java
import io.tiko.ErrorContext;
import io.tiko.kafka.IngestDecision;
import io.tiko.kafka.KafkaIngestErrorDecider;
import io.tiko.kafka.KafkaRecordDeadLettered;
import java.util.HashMap;
```

Add the field (after `private final IngestErrorPolicy poisonRecordPolicy;`):

```java
    // Null when no @Component decider is registered — the static poison-record-policy path runs (#313).
    private final KafkaIngestErrorDecider decider;

    // Per-partition consecutive-failure tracking for the decider's `attempt` argument (#385).
    // Only ever touched from the single run() thread, so a plain HashMap is safe.
    private final Map<TopicPartition, Attempt> attempts = new HashMap<>();

    private record Attempt(long offset, int count) {}
```

Replace the existing 7-arg constructor with a delegating 7-arg plus the new 8-arg constructor:

```java
    public ThreadPerTopicRunner(
            GeneratedSourceDescriptor source,
            KafkaConsumerClient consumer,
            Container container,
            EventBus eventBus,
            ErrorHandler errorHandler,
            KafkaSerializer serializer,
            KafkaConfig config) {
        this(source, consumer, container, eventBus, errorHandler, serializer, config, null);
    }

    public ThreadPerTopicRunner(
            GeneratedSourceDescriptor source,
            KafkaConsumerClient consumer,
            Container container,
            EventBus eventBus,
            ErrorHandler errorHandler,
            KafkaSerializer serializer,
            KafkaConfig config,
            KafkaIngestErrorDecider decider) {
        this.source = source;
        this.consumer = consumer;
        this.container = container;
        this.eventBus = eventBus;
        this.errorHandler = errorHandler;
        this.serializer = serializer;
        this.config = config;
        this.decider = decider;
        // Parse eagerly so a typo'd policy fails fast at start() rather than per record.
        this.poisonRecordPolicy = IngestErrorPolicy.parse(config.poisonRecordPolicy());
    }
```

In `processPartition`, after the successful `consumer.commitSync(...)` line, clear any attempt tracking for the advanced partition:

```java
                consumer.commitSync(Map.of(tp, new OffsetAndMetadata(r.offset() + 1)));
                attempts.remove(tp);
```

Replace the `catch (Exception ex)` block body with:

```java
            } catch (Exception ex) {
                KafkaIngestError error =
                        new KafkaIngestError(r.topic(), r.partition(), r.offset(), r.headers(), ex);
                if (decider == null) {
                    // Static poison-record-policy path (#313), unchanged.
                    routeError(error);
                    if (poisonRecordPolicy == IngestErrorPolicy.SKIP) {
                        commitSafely(tp, r.offset() + 1);
                    } else {
                        seekSafely(tp, r.offset());
                        return;
                    }
                } else {
                    int attempt = nextAttempt(tp, r.offset());
                    switch (decide(error, attempt)) {
                        case SEEK -> {
                            routeError(error);
                            seekSafely(tp, r.offset());
                            return;
                        }
                        case SKIP -> {
                            routeError(error);
                            attempts.remove(tp);
                            commitSafely(tp, r.offset() + 1);
                        }
                        case DEAD_LETTER -> {
                            routeError(new KafkaRecordDeadLettered(
                                    r.topic(), r.partition(), r.offset(), r.headers(), ex, attempt));
                            attempts.remove(tp);
                            commitSafely(tp, r.offset() + 1);
                        }
                        case FAIL -> {
                            routeError(error);
                            // Stop this topic's runner; the record is left uncommitted and
                            // redelivers if the consumer is restarted. Other topics are unaffected.
                            running.set(false);
                            return;
                        }
                    }
                }
            }
```

Rename the existing `routeIngestError(KafkaIngestError)` method to `routeError(ErrorContext)`. It currently has two callers: the one inside `processPartition`'s catch block is already rewritten above; update the remaining one in `run()` (the infrastructure-failure path). The renamed method:

```java
    /** Routes an error context without letting a throwing ErrorHandler kill the consumer thread. */
    private void routeError(ErrorContext error) {
        try {
            errorHandler.onError(error);
        } catch (Exception handlerFailure) {
            LoggerHolder.LOG.log(
                    System.Logger.Level.WARNING,
                    "ErrorHandler threw while handling a Kafka ingest error",
                    handlerFailure);
        }
    }
```

(In `run()`, change `routeIngestError(new KafkaIngestError(...))` to `routeError(new KafkaIngestError(...))`.)

Add the attempt-tracking and guarded-decide helpers (near `routeError`):

```java
    /** Consecutive failure count for {@code offset} on {@code tp}, starting at 1; resets when the offset changes. */
    private int nextAttempt(TopicPartition tp, long offset) {
        Attempt prior = attempts.get(tp);
        int count = (prior != null && prior.offset() == offset) ? prior.count() + 1 : 1;
        attempts.put(tp, new Attempt(offset, count));
        return count;
    }

    /** Invokes the decider under a guard: a throw or null falls back to SEEK (safest — no data loss). */
    private IngestDecision decide(KafkaIngestError error, int attempt) {
        try {
            IngestDecision decision = decider.decide(error, attempt);
            return decision != null ? decision : IngestDecision.SEEK;
        } catch (Exception deciderFailure) {
            LoggerHolder.LOG.log(
                    System.Logger.Level.WARNING,
                    "KafkaIngestErrorDecider threw; falling back to SEEK",
                    deciderFailure);
            return IngestDecision.SEEK;
        }
    }
```

- [ ] **Step 4: Format and run the first test to verify it passes**

Run: `/w/tools/apache-maven/bin/mvn -pl tiko-kafka spotless:apply` then `/w/tools/apache-maven/bin/mvn -pl tiko-kafka test -Dtest=KafkaIngestErrorDeciderTest`
Expected: PASS — `Tests run: 1, Failures: 0`.

- [ ] **Step 5: Add the branch-pinning tests**

Append these methods to `KafkaIngestErrorDeciderTest`:

```java
    @Test
    void skipDecisionCommitsPastAndRoutesTheIngestError() {
        ScriptedConsumerClient client = new ScriptedConsumerClient(List.of(poisonBatch(0)));
        List<ErrorContext> routed = new CopyOnWriteArrayList<>();

        try (Container container = Tiko.create()) {
            ThreadPerTopicRunner runner = runner(client, container, routed, (error, attempt) -> IngestDecision.SKIP);
            runner.start();
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> client.commits.contains(Map.entry(P0, 1L)));
            } finally {
                runner.stop();
            }
        }

        assertThat(client.seeks).as("SKIP must not seek").isEmpty();
        assertThat(routed).hasSize(1).allSatisfy(e -> assertThat(e).isInstanceOf(KafkaIngestError.class));
    }

    @Test
    void failDecisionStopsTheRunnerAndLeavesTheOffsetUncommitted() {
        ScriptedConsumerClient client = new ScriptedConsumerClient(List.of(poisonBatch(0)));
        List<ErrorContext> routed = new CopyOnWriteArrayList<>();

        try (Container container = Tiko.create()) {
            ThreadPerTopicRunner runner = runner(client, container, routed, (error, attempt) -> IngestDecision.FAIL);
            runner.start();
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> !routed.isEmpty());
            } finally {
                runner.stop();
            }
        }

        assertThat(client.commits).as("FAIL leaves the record uncommitted").isEmpty();
        assertThat(client.seeks).as("FAIL does not seek").isEmpty();
        assertThat(routed).allSatisfy(e -> assertThat(e).isInstanceOf(KafkaIngestError.class));
    }

    @Test
    void aThrowingDeciderFallsBackToSeekAndKeepsTheThreadAlive() {
        ScriptedConsumerClient client = new ScriptedConsumerClient(List.of(poisonBatch(0)));
        List<ErrorContext> routed = new CopyOnWriteArrayList<>();

        try (Container container = Tiko.create()) {
            ThreadPerTopicRunner runner = runner(client, container, routed, (error, attempt) -> {
                throw new RuntimeException("decider boom");
            });
            runner.start();
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> client.seeks.contains(Map.entry(P0, 0L)));
            } finally {
                runner.stop();
            }
        }

        assertThat(client.commits).as("SEEK fallback must not commit").isEmpty();
    }

    @Test
    void attemptCountIsPerOffsetAndResetsWhenTheOffsetChanges() {
        ScriptedConsumerClient client =
                new ScriptedConsumerClient(List.of(poisonBatch(0), poisonBatch(0), poisonBatch(1)));
        List<ErrorContext> routed = new CopyOnWriteArrayList<>();
        List<Integer> attemptsSeen = new CopyOnWriteArrayList<>();

        try (Container container = Tiko.create()) {
            ThreadPerTopicRunner runner = runner(client, container, routed, (error, attempt) -> {
                attemptsSeen.add(attempt);
                return IngestDecision.SEEK;
            });
            runner.start();
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> attemptsSeen.size() >= 3);
            } finally {
                runner.stop();
            }
        }

        assertThat(attemptsSeen)
                .as("offset 0 fails twice (1,2); a new offset restarts the count at 1")
                .startsWith(1, 2, 1);
    }
```

- [ ] **Step 6: Run the full test class to verify all pass**

Run: `/w/tools/apache-maven/bin/mvn -pl tiko-kafka test -Dtest=KafkaIngestErrorDeciderTest`
Expected: PASS — `Tests run: 5, Failures: 0`.

- [ ] **Step 7: Run the existing runner tests to confirm no regression (static path unchanged)**

Run: `/w/tools/apache-maven/bin/mvn -pl tiko-kafka test -Dtest=KafkaPoisonRecordPolicyTest,KafkaRunnerFailureHandlingTest,KafkaHandlerCommitSemanticsTest`
Expected: PASS — all green (they use the 7-arg constructor / static path).

- [ ] **Step 8: Commit**

```bash
git add tiko-kafka/src/main/java/io/tiko/kafka/runtime/ThreadPerTopicRunner.java \
        tiko-kafka/src/test/java/io/tiko/kafka/runtime/KafkaIngestErrorDeciderTest.java
git commit -m "feat(kafka): apply KafkaIngestErrorDecider decisions in ThreadPerTopicRunner (#385)"
```

---

### Task 3: Resolve and wire the decider in KafkaBootstrapSupport

**Files:**
- Modify: `tiko-kafka/src/main/java/io/tiko/kafka/runtime/KafkaBootstrapSupport.java`
- Test: `tiko-kafka/src/test/java/io/tiko/kafka/runtime/KafkaBootstrapDeciderResolutionTest.java` (create)

**Interfaces:**
- Consumes: `KafkaIngestErrorDecider`, `IngestDecision` (Task 1); the 8-arg `ThreadPerTopicRunner` constructor (Task 2); `Container.getAll(Class)`.
- Produces: `static KafkaIngestErrorDecider resolveDecider(java.util.List<KafkaIngestErrorDecider> deciders)` — package-private, testable in isolation.

- [ ] **Step 1: Write the failing test**

Create `tiko-kafka/src/test/java/io/tiko/kafka/runtime/KafkaBootstrapDeciderResolutionTest.java`:

```java
package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.kafka.IngestDecision;
import io.tiko.kafka.KafkaIngestErrorDecider;
import java.util.List;
import org.junit.jupiter.api.Test;

class KafkaBootstrapDeciderResolutionTest {

    @Test
    void noDecidersResolvesToNullSoTheStaticPolicyRuns() {
        assertThat(KafkaBootstrapSupport.resolveDecider(List.of())).isNull();
    }

    @Test
    void exactlyOneDeciderIsUsed() {
        KafkaIngestErrorDecider only = (error, attempt) -> IngestDecision.SKIP;
        assertThat(KafkaBootstrapSupport.resolveDecider(List.of(only))).isSameAs(only);
    }

    @Test
    void multipleDecidersFailFast() {
        KafkaIngestErrorDecider a = (error, attempt) -> IngestDecision.SKIP;
        KafkaIngestErrorDecider b = (error, attempt) -> IngestDecision.SEEK;
        assertThatThrownBy(() -> KafkaBootstrapSupport.resolveDecider(List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at most one");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `/w/tools/apache-maven/bin/mvn -pl tiko-kafka test -Dtest=KafkaBootstrapDeciderResolutionTest`
Expected: FAIL — compilation error, `KafkaBootstrapSupport.resolveDecider` does not exist.

- [ ] **Step 3: Implement resolution and wiring**

In `tiko-kafka/src/main/java/io/tiko/kafka/runtime/KafkaBootstrapSupport.java`, add the import:

```java
import io.tiko.kafka.KafkaIngestErrorDecider;
```

Add the resolver method (package-private static, anywhere among the private helpers):

```java
    /**
     * Resolves the optional ingest-error decider (#385). Zero registered components → {@code null}
     * (the static {@code poison-record-policy} path runs); exactly one → that decider; more than one
     * → fail fast, since the runner can honour only one.
     */
    static KafkaIngestErrorDecider resolveDecider(java.util.List<KafkaIngestErrorDecider> deciders) {
        return switch (deciders.size()) {
            case 0 -> null;
            case 1 -> deciders.get(0);
            default -> throw new IllegalStateException("Multiple KafkaIngestErrorDecider components registered ("
                    + deciders.size() + "); register at most one.");
        };
    }
```

In `bootstrap(...)`, resolve the decider once (near where `ErrorHandler errorHandler = resolveErrorHandler(container);` is) and pass it into the runner construction. Change:

```java
                    new ThreadPerTopicRunner(source, client, container, eventBus, errorHandler, serializer, config);
```

to:

```java
                    new ThreadPerTopicRunner(
                            source, client, container, eventBus, errorHandler, serializer, config, decider);
```

and add, alongside the other resolved locals:

```java
        KafkaIngestErrorDecider decider = resolveDecider(container.getAll(KafkaIngestErrorDecider.class));
```

- [ ] **Step 4: Format and run to verify it passes**

Run: `/w/tools/apache-maven/bin/mvn -pl tiko-kafka spotless:apply` then `/w/tools/apache-maven/bin/mvn -pl tiko-kafka test -Dtest=KafkaBootstrapDeciderResolutionTest`
Expected: PASS — `Tests run: 3, Failures: 0`.

- [ ] **Step 5: Run the whole tiko-kafka module test suite**

Run: `/w/tools/apache-maven/bin/mvn -pl tiko-kafka test`
Expected: PASS — BUILD SUCCESS, 0 failures / 0 errors.

- [ ] **Step 6: Commit**

```bash
git add tiko-kafka/src/main/java/io/tiko/kafka/runtime/KafkaBootstrapSupport.java \
        tiko-kafka/src/test/java/io/tiko/kafka/runtime/KafkaBootstrapDeciderResolutionTest.java
git commit -m "feat(kafka): resolve and wire the KafkaIngestErrorDecider component (#385)"
```

---

### Task 4: Document the registration surface

**Files:**
- Modify: `docs/cookbooks/kafka.md`

**Interfaces:**
- Consumes: the public types and behavior from Tasks 1–3. No code, no test.

- [ ] **Step 1: Add a documentation section**

Append a section to `docs/cookbooks/kafka.md` (place it after the existing poison-record-policy coverage; keep the surrounding heading level consistent with the file):

```markdown
## Programmatic ingest-error decisions (`KafkaIngestErrorDecider`)

`tiko.kafka.poison-record-policy` (`SEEK`/`SKIP`) is a uniform, zero-config
switch: every ingest failure is either sought back or skipped. When you need to
branch on *what* failed — retry a transient blip a few times, dead-letter a
known-bad shape, skip the rest — register a `KafkaIngestErrorDecider`.

Register **at most one** as a singleton component. When present, it overrides the
static policy for every ingest failure; when absent, the static
`poison-record-policy` runs unchanged (this feature is purely additive).

```java
@Component(scope = Scope.SINGLETON)
public class OrderIngestPolicy implements KafkaIngestErrorDecider {
    @Override
    public IngestDecision decide(KafkaIngestError error, int attempt) {
        if (attempt < 3 && error.cause() instanceof java.io.IOException) {
            return IngestDecision.SEEK;        // transient — retry (redelivered next poll)
        }
        if (error.cause() instanceof com.example.SchemaMismatch) {
            return IngestDecision.DEAD_LETTER; // known-bad shape — hand off, then advance
        }
        return IngestDecision.SKIP;            // log and move on
    }
}
```

`attempt` is the consecutive failure count for the record's offset, starting at
`1`; bounding the retry is your code's job. The outcomes:

| Outcome       | Effect |
|---------------|--------|
| `SEEK`        | Seek back; the record is redelivered on the next poll. No offset committed. |
| `SKIP`        | Route the `KafkaIngestError`; commit past the record. |
| `DEAD_LETTER` | Route a `KafkaRecordDeadLettered` (carrying `attempts`) instead of `KafkaIngestError`; commit past. Forward it from your `ErrorHandler` to whatever dead-letter sink you run. |
| `FAIL`        | Route the `KafkaIngestError`; stop **this topic's** consumer (the record is left uncommitted and redelivers if the consumer restarts). |

There is no dead-letter *topic* — `DEAD_LETTER` routes a distinct
`ErrorContext` through your `ErrorHandler`, which is where you decide what to do
with it:

```java
TikoOptions.builder().errorHandler(ctx -> {
    switch (ctx) {
        case KafkaRecordDeadLettered dl -> dlqSink.send(dl);
        case KafkaIngestError e -> log.warn("ingest failure on {}", e.topic());
        default -> { /* other contexts */ }
    }
}).build();
```

A decider that throws falls back to `SEEK` (no data loss) and logs a warning; it
never kills the consumer thread.
```

- [ ] **Step 2: Commit**

```bash
git add docs/cookbooks/kafka.md
git commit -m "docs(kafka): document the KafkaIngestErrorDecider ingest hook (#385)"
```

---

## Final verification

- [ ] **Full reactor build** — Run: `/w/tools/apache-maven/bin/mvn test` (log to a file; a piped exit code lies). Expected: BUILD SUCCESS, 0 failures / 0 errors. Confirms the additive change breaks no other module.
- [ ] **Spotless** — the reactor build runs `spotless:check` at `validate`; a green build means formatting is clean.

## Self-review notes (coverage against the spec)

- Public types (`IngestDecision`, `KafkaIngestErrorDecider`, `KafkaRecordDeadLettered`) → Task 1.
- Attempt-count model + flat enum → Task 2 (`nextAttempt`, `decide` guard, decision switch).
- `DEAD_LETTER` = distinct context + commit past → Task 2 (`DEAD_LETTER` case).
- `FAIL` stops only this topic → Task 2 (`running.set(false)`).
- No backoff between `SEEK` retries → Task 2 (SEEK just seeks + returns; poll-loop cadence).
- DI registration via `getAll`, 0/1/>1 semantics → Task 3 (`resolveDecider`).
- Additive fallback (no decider → #313 path) → Task 2 (`decider == null` branch) + Task 3 (`case 0 -> null`).
- Hook-throws guard → Task 2 (`decide`), tested in Task 2 Step 5.
- Broker-less tests in `ScriptedConsumerClient` style → Tasks 2 and 3.
- Documentation of the registration surface → Task 4.
- Out-of-scope items (DLQ topic, backoff) are intentionally excluded.
```
