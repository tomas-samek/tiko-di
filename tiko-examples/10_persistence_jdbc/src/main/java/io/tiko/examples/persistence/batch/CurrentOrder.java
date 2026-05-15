package io.tiko.examples.persistence.batch;

import java.util.UUID;

/** Read-only view of the current order being processed by the batch loop. */
public interface CurrentOrder {
    UUID orderId();
}
