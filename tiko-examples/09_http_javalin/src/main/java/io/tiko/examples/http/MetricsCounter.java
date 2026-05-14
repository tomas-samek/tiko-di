package io.tiko.examples.http;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.util.concurrent.atomic.AtomicLong;

/** Synchronous metrics handler. Exposes {@link #count()} for tests. */
@Component(scope = Scope.SINGLETON)
public class MetricsCounter {

    private final AtomicLong ticketsCreated = new AtomicLong();

    @EventHandler
    public void onTicketCreated(TicketCreated event) {
        ticketsCreated.incrementAndGet();
    }

    public long count() {
        return ticketsCreated.get();
    }
}
