package io.tiko.examples.persistence.batch;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SINGLETON sink invoked per order during a batch transaction. The batch
 * driver calls {@link #record(UUID)} explicitly inside the loop — the
 * recorder is part of the batch's own observability surface, not a Tiko
 * lifecycle subscriber.
 *
 * <p>Under the unified scope model, a batch is one unit of work (one
 * transaction, one EVENT scope) with an internal loop. There is no
 * per-iteration EVENT scope to hook into, so observation happens via
 * direct invocation rather than via {@code EventEndingEvent}. For
 * genuinely independent events (each its own unit), use the cross-event
 * outbox/saga pattern above the DI layer.
 */
@Component(scope = Scope.SINGLETON)
public class BatchAuditLogger {

    private static final System.Logger LOG = System.getLogger("io.tiko.examples.persistence.batch");

    private final List<UUID> seen = new CopyOnWriteArrayList<>();

    public void record(UUID orderId) {
        seen.add(orderId);
        LOG.log(System.Logger.Level.INFO, () -> "[batch-audit] processing order " + orderId);
    }

    /** Defensive snapshot. */
    public List<UUID> captured() {
        return List.copyOf(seen);
    }
}
