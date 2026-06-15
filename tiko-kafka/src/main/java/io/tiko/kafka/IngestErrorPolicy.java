package io.tiko.kafka;

/**
 * What a consumer runner does with a record whose ingest fails — deserialize, bridge
 * dispatch, or publish (#313). Configured by {@code tiko.kafka.poison-record-policy}.
 *
 * <p>The policy is uniform across all ingest-failure stages on purpose. The tempting
 * refinement — "a deserialize failure is permanent poison, so always safe to skip" — is
 * unsound: a schema-registry-backed deserializer does a network call mid-deserialize, and
 * a rolling deployment or {@code ClassNotFound} can make the very same bytes fail now and
 * succeed minutes later. You cannot tell permanent poison from a transient blip at the
 * failure site, at any stage, so the choice is left to the operator rather than guessed.
 */
public enum IngestErrorPolicy {

    /**
     * Default. Seek back to the failed offset and redeliver — the record is retried until
     * it succeeds. No data is lost across a transient outage (a brief broker/registry/DB
     * blip rides through), at the cost of a poison record blocking its partition until it
     * is removed or its consumer is reconfigured. Existing behaviour before #313.
     */
    SEEK,

    /**
     * Opt-in. Route the {@link KafkaIngestError} to the {@code ErrorHandler} (the "log"),
     * then commit past the record so the partition advances — a first-class "log and skip
     * a poison record" without a {@code null}-returning serializer workaround. Trade-off:
     * a record that failed for a <em>transient</em> reason is also dropped, so enable this
     * only for streams where occasional loss on a blip is acceptable. Safe auto-skip that
     * rides out transient failures first needs bounded retry (tracked separately).
     */
    SKIP;

    /**
     * Parses the configured value case-insensitively. Throws {@link IllegalArgumentException}
     * with the valid set on an unknown value, so a typo fails fast at startup rather than
     * silently falling back to a default the operator did not choose.
     */
    public static IngestErrorPolicy parse(String raw) {
        if (raw != null) {
            for (IngestErrorPolicy p : values()) {
                if (p.name().equalsIgnoreCase(raw.trim())) {
                    return p;
                }
            }
        }
        throw new IllegalArgumentException(
                "Unknown tiko.kafka.poison-record-policy '" + raw + "'. Valid values: SEEK, SKIP.");
    }
}
