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
