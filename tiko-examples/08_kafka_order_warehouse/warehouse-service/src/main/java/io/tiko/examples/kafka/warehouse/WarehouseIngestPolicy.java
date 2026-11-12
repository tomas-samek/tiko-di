package io.tiko.examples.kafka.warehouse;

import io.tiko.Scope;
import io.tiko.UndecodablePayloadException;
import io.tiko.annotations.Component;
import io.tiko.kafka.IngestDecision;
import io.tiko.kafka.KafkaIngestError;
import io.tiko.kafka.KafkaIngestErrorDecider;

/**
 * Sorts ingest failures into the ones retrying can fix and the ones it can't. A rejected order or
 * undecodable bytes fail the same way on every redelivery: retrying them would block the partition for
 * good, so they are dead-lettered. Anything else, such as a database that is briefly down, is sought
 * back and redelivered, as with the default {@code SEEK} policy.
 */
@Component(scope = Scope.SINGLETON)
public class WarehouseIngestPolicy implements KafkaIngestErrorDecider {

    @Override
    public IngestDecision decide(KafkaIngestError error, int attempt) {
        return switch (error.cause()) {
            case RejectedOrderException rejected -> IngestDecision.DEAD_LETTER;
            case UndecodablePayloadException undecodable -> IngestDecision.DEAD_LETTER;
            default -> IngestDecision.SEEK;
        };
    }
}
