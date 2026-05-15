package io.tiko.examples.persistence.batch;

import java.util.UUID;

/**
 * View of the current order being processed by the batch loop. Auto-proxied
 * into SINGLETON consumers so reads and writes route to the active EVENT
 * scope's {@link CurrentOrderContext}.
 */
public interface CurrentOrder {
    UUID orderId();

    void setOrderId(UUID orderId);
}
