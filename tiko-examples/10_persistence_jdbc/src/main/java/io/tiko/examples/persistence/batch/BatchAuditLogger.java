package io.tiko.examples.persistence.batch;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.Inject;
import io.tiko.events.EventStartedEvent;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Sync subscriber to {@link EventStartedEvent} that records the EVENT-scoped
 * {@link CurrentOrder}'s id on each iteration. Proves two things at once:
 * (a) Tiko's auto-proxy works for EVENT-scoped beans injected into SINGLETONs,
 * (b) the batch loop actually opens N distinct EVENT scopes inside one REQUEST.
 *
 * <p>Lives in main sources (not test) so the annotation processor wires it
 * like any other subscriber. {@link #captured()} returns a defensive snapshot
 * for tests to assert against.
 */
@Component(scope = Scope.SINGLETON)
public class BatchAuditLogger {

    private static final Logger LOG = Logger.getLogger("io.tiko.examples.persistence.batch");

    private final CurrentOrder current; // auto-proxied to the current EVENT scope's CurrentOrderContext
    private final List<UUID> seen = new CopyOnWriteArrayList<>();

    @Inject
    public BatchAuditLogger(CurrentOrder current) {
        this.current = current;
    }

    @EventHandler
    public void onEventStarted(EventStartedEvent event) {
        UUID id = current.orderId();
        if (id != null) {
            seen.add(id);
            LOG.info(() -> "[batch-audit] processing order " + id);
        }
    }

    /** Defensive snapshot. */
    public List<UUID> captured() {
        return List.copyOf(seen);
    }
}
