# Kafka ingest: programmatic per-error decision hook (#385)

**Status:** approved (design)
**Issue:** [#385](https://github.com/tomas-samek/tiko-di/issues/385)
**Milestone:** 0.5.0 — async scope completion & ingest resilience
**Depends on / relates to:** #313 (static `poison-record-policy`), #108 (retry/backoff), #111 (dead-letter surface)

## Problem

`tiko.kafka.poison-record-policy` (#313) is a uniform, config-level `SEEK`/`SKIP`
switch applied to every ingest failure (deserialize, bridge dispatch, or
publish). It cannot branch on *what* failed. Operators want to express, per
failure:

> if the cause is transient/retryable → retry N times; else if it is a known-bad
> shape → dead-letter; else skip (or stop the consumer).

This spec adds a programmatic hook that inspects the `KafkaIngestError` and
returns an outcome, overriding the static policy when registered.

## Findings that shaped the design

Two assumptions in the issue did not survive contact with the code:

1. **There is no reusable retry/backoff primitive to dispatch to.** #108's retry
   engine (`EventChainContext.runAsyncWithRetry` + a `private static
   backoffDelayNanos`) is welded to `CompletableFuture` async-handler
   re-invocation. Only the `BackoffStrategy` enum and `RetryPolicy` record are
   separable. Ingest "retry" is fundamentally *seek-back-and-re-poll* — Kafka-local
   by nature; it cannot route through an in-process async handler.
2. **There is no dead-letter *sink*.** #111's "DLQ" is `ErrorHandler.onError(ErrorContext)`
   plus `OverflowPolicy.ROUTE_TO_DLQ` for queue overflow. There is no dead-letter
   topic and no producer in the consumer runner. The ingest path already routes
   `KafkaIngestError` through `ErrorHandler` on every failure.

Consequences: ingest retry is seek-based (not "shared machinery"); `DEAD_LETTER`
is a distinct *signal* to the shared `ErrorHandler`, not a republish.

## Decisions (from brainstorming)

- **Decision model:** attempt-count passed to the hook + a flat enum. The hook
  bounds retries itself and branches on the cause. Bounding lives in user code —
  the point of a *programmatic* hook.
- **`DEAD_LETTER`:** commit past the record (partition advances) and route a
  *distinct* `KafkaRecordDeadLettered` `ErrorContext` (not the plain
  `KafkaIngestError`), so an operator's `ErrorHandler` can tell deliberate
  dead-lettering from a skip or a transient blip and forward it to whatever sink
  they run. No Kafka-local DLQ reimplementation.
- **Registration:** a DI-registered `@Component` implementing the decider
  interface, resolved via `container.getAll(...)`. No new global/config surface;
  mirrors how components and `ErrorHandler` are already registered.
- **`FAIL` scope:** stops only *this topic's* runner, not the whole application.
- **No backoff between `SEEK` retries in v1** (rationale below).

## Public API (`io.tiko.kafka`)

```java
/** What a registered decider tells the consumer runner to do with a failed record (#385). */
public enum IngestDecision { SEEK, SKIP, DEAD_LETTER, FAIL }

/**
 * Programmatic per-error ingest decision hook. Register exactly one as a
 * {@code @Component(scope = SINGLETON)}; when present it overrides the static
 * {@code tiko.kafka.poison-record-policy} for every ingest failure.
 */
@FunctionalInterface
public interface KafkaIngestErrorDecider {
    /**
     * @param error   the ingest failure (topic, partition, offset, headers, cause)
     * @param attempt consecutive failure count for this record's offset, starting at 1
     * @return the outcome the runner applies
     */
    IngestDecision decide(KafkaIngestError error, int attempt);
}

/**
 * Routed (via {@code ErrorHandler}) when a decider returns {@link IngestDecision#DEAD_LETTER}.
 * Distinct from {@link KafkaIngestError} so an operator can forward deliberate dead-letters
 * to their own sink. The record is committed past after routing.
 */
public record KafkaRecordDeadLettered(
        String topic, int partition, long offset, Headers headers, Throwable cause, int attempts)
        implements TransportError {
    @Override public String transport() { return "kafka"; }
}
```

`IngestDecision` is intentionally **separate** from the YAML-bound
`IngestErrorPolicy` (`SEEK`/`SKIP`): the config enum stays a 2-value zero-config
knob, and `DEAD_LETTER`/`FAIL` must not be YAML-selectable because they only make
sense with a decider present.

## Wiring (`KafkaBootstrapSupport`)

At bootstrap, resolve the optional decider:

```java
List<KafkaIngestErrorDecider> deciders = container.getAll(KafkaIngestErrorDecider.class);
KafkaIngestErrorDecider decider = switch (deciders.size()) {
    case 0 -> null;                       // additive: static poison-record-policy path unchanged
    case 1 -> deciders.get(0);
    default -> throw new IllegalStateException(
        "Multiple KafkaIngestErrorDecider components found (" + deciders.size()
        + "); register at most one.");    // fail fast at startup
};
```

`decider` is threaded into every `ThreadPerTopicRunner` as a new **nullable**
constructor parameter. `null` selects the legacy static path, so existing runner
tests and the `KafkaConfig`-only construction keep working.

## Runner behavior (`ThreadPerTopicRunner`)

### Attempt tracking

Per `TopicPartition`, track `(offset, consecutiveFailures)` in a plain `HashMap`
— the run loop is single-threaded (one thread per topic), so no synchronization
is needed. On a failure at offset `O`:

- if the tracked offset for the partition equals `O` → increment the count;
- else → start a new `(O, 1)`.

The entry is cleared whenever the offset advances past `O` (any successful commit,
or a `SKIP`/`DEAD_LETTER` commit-past). `attempt` passed to the hook is this count
(starts at 1 on the first failure).

*Known limitation (documented, acceptable for v1):* a rebalance that revokes then
reassigns a partition to the same consumer can carry a stale count. Rare;
noted, not handled in v1.

### Decision application

When a decider is present, replace the binary `SEEK`/`SKIP` branch with:

| Decision      | Routed context           | Offset action              | Loop        |
|---------------|--------------------------|----------------------------|-------------|
| `SEEK`        | `KafkaIngestError`       | `seek(offset)` (redeliver) | `return`    |
| `SKIP`        | `KafkaIngestError`       | `commit(offset + 1)`       | continue    |
| `DEAD_LETTER` | `KafkaRecordDeadLettered`| `commit(offset + 1)`       | continue    |
| `FAIL`        | `KafkaIngestError`       | none (leave uncommitted)   | stop runner |

- **Hook-throws guard:** the decider is invoked through a guard that catches any
  throwable, logs a WARNING, and falls back to `SEEK` (safest — no data loss). A
  throwing decider never kills the consumer thread.
- **`FAIL`** sets the runner's `running` flag false and exits the run loop for
  this topic only; the failed record is left uncommitted, so it redelivers if the
  consumer is restarted. Other topics' runners are unaffected. Consumer close
  stays owned by `stop()` (called at container shutdown); its existing
  `compareAndSet` guard makes the eventual close a no-op-safe single close.
- When `decider == null`, the exact #313 static path runs (route
  `KafkaIngestError`, then `SEEK`/`SKIP`).

### Why no backoff between `SEEK` retries (v1)

The topic thread services *all* partitions of its topic. Parking it to back off
between retries would stall unrelated partitions on the same topic. Retry cadence
is therefore the natural poll loop; the attempt counter bounds it. Backoff is a
purely additive future change (and would require lifting #108's `backoffDelayNanos`
out of `EventChainContext` first). Operators needing to ride out a long transient
outage use unbounded `SEEK` (the zero-config default) or a high N.

## Testing (no broker — `ScriptedConsumerClient` style)

New `ThreadPerTopicRunner` tests, each scripting poll batches and asserting on the
client's `seeks`/`commits` and the routed `ErrorContext` list:

1. Bounded retry then dead-letter: decider returns `SEEK` while `attempt < N`,
   then `DEAD_LETTER`; assert N seeks at the offset, then a `KafkaRecordDeadLettered`
   routed with `attempts == N` and a commit past.
2. `SKIP` decision commits past and routes `KafkaIngestError` (parity with the
   static `SKIP`).
3. `FAIL` stops the runner and leaves the offset uncommitted.
4. Decider throws → falls back to `SEEK`, thread survives, WARNING path.
5. Attempt counter resets after a successful record between failures.
6. `KafkaBootstrapSupport`: `>1` decider → startup `IllegalStateException`; `0`
   → static path; `1` → wired.

## Documentation

- Document the registration surface and a worked example (retry transient causes
  N times, then dead-letter) in the Kafka docs and the Kafka
  cookbook/example under `tiko-examples`.
- Cross-reference #313's `poison-record-policy` as the zero-config default the
  decider overrides.

## Out of scope

- DLQ-topic republish (a producer-in-runner, topic naming, raw-byte passthrough).
- Any runtime behavior change when no decider is registered — purely additive.
- Relaxing what `poison-record-policy` does; it remains the default.
- Backoff between ingest retries (additive future change).

## Affected files

- **New:** `tiko-kafka/.../kafka/IngestDecision.java`,
  `KafkaIngestErrorDecider.java`, `KafkaRecordDeadLettered.java`.
- **Changed:** `tiko-kafka/.../kafka/runtime/ThreadPerTopicRunner.java`
  (decider param, attempt tracking, decision switch),
  `.../kafka/runtime/KafkaBootstrapSupport.java` (resolve + thread the decider).
- **Tests:** new runner tests + a bootstrap resolution test in `tiko-kafka/src/test`.
- **Docs:** Kafka docs + example.
