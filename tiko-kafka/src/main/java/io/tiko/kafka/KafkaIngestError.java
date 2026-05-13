package io.tiko.kafka;

import io.tiko.TransportError;
import org.apache.kafka.common.header.Headers;

/**
 * Raised when the inbound bridge for a Kafka record throws (deserialisation failure,
 * bridge method throw, or downstream {@code eventBus.publish} failure). The consumer
 * seeks back to {@link #offset()} on the next loop iteration; the same record is
 * redelivered. Routed via the configured {@code ErrorHandler}.
 *
 * @param topic     source topic
 * @param partition partition number
 * @param offset    record offset within the partition
 * @param headers   record headers (never {@code null})
 * @param cause     the underlying throwable
 */
public record KafkaIngestError(String topic, int partition, long offset, Headers headers, Throwable cause)
        implements TransportError {

    @Override
    public String transport() {
        return "kafka";
    }
}
