package io.tiko.kafka;

import io.tiko.TransportError;

/**
 * Raised when an outbound Kafka sink fails to serialise or send a record. Local handlers
 * for the same event have already run before the sink callback fires, so a throw here
 * does not block local processing. Routed via the configured {@code ErrorHandler}.
 *
 * @param topic destination topic
 * @param event the local event whose translation/send failed
 * @param cause the underlying throwable
 */
public record KafkaEgressError(String topic, Object event, Throwable cause) implements TransportError {

    @Override
    public String transport() {
        return "kafka";
    }
}
