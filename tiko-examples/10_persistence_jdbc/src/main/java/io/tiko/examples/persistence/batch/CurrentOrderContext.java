package io.tiko.examples.persistence.batch;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import java.util.UUID;

/**
 * Per-event state for the batch flow: the order id being processed in
 * the current iteration. EVENT-scoped, so each {@code runInEventScope}
 * gets its own instance. A SINGLETON consumer (see {@code BatchAuditLogger})
 * can inject {@link CurrentOrder} directly via constructor — Tiko's
 * annotation processor generates an auto-proxy that resolves to the
 * current EVENT scope's instance on every method call.
 */
@Component(scope = Scope.EVENT)
public class CurrentOrderContext implements CurrentOrder {

    private UUID orderId;

    public void setOrderId(UUID orderId) {
        this.orderId = orderId;
    }

    @Override
    public UUID orderId() {
        return orderId;
    }
}
