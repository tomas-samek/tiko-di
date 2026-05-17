package io.tiko.examples.http;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;

/** Synchronous audit handler. Runs inline before {@code EventBus.publish} returns. */
@Component(scope = Scope.SINGLETON)
public class AuditLogger {

    private static final System.Logger LOG = System.getLogger("io.tiko.examples.http.audit");

    @EventHandler
    public void onTicketCreated(TicketCreated event) {
        LOG.log(
                System.Logger.Level.INFO,
                () -> "[AUDIT req=" + event.requestId() + "] ticket " + event.id() + " '" + event.title()
                        + "' created at " + event.createdAt());
    }
}
