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
