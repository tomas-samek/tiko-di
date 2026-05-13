package io.tiko.kafka;

/**
 * Controls when a {@code @KafkaSource} consumer commits offsets to the broker.
 *
 * <p>MVP ships {@link #PER_RECORD} only. Future modes ({@code BATCH},
 * {@code AT_MOST_ONCE}, {@code MANUAL}) are explicit future work — see the spec
 * "Future extension points" table. Because this enum is referenced by annotation
 * value, expanding it is source-compatible for existing users.
 */
public enum CommitMode {

    /**
     * Commit each record's offset synchronously after the bridge invocation succeeds
     * and the deserialized event has been published to the local bus. Backs the
     * documented at-least-once + idempotent-handlers contract. Per-record commits are
     * slow at extreme throughput; MVP intentionally trades throughput for clarity.
     */
    PER_RECORD
}
