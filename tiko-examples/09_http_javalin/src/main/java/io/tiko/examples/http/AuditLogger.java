package io.tiko.examples.http;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.util.logging.Logger;

/** Synchronous audit handler. Runs inline before {@code EventBus.publish} returns. */
@Component(scope = Scope.SINGLETON)
public class AuditLogger {

    private static final Logger LOG = Logger.getLogger("io.tiko.examples.http.audit");

    @EventHandler
    public void onTicketCreated(TicketCreated event) {
        LOG.info(() -> "[AUDIT req=" + event.requestId() + "] ticket " + event.id() + " '" + event.title()
                + "' created at " + event.createdAt());
    }
}
