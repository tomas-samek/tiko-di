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
