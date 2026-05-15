package io.tiko.examples.http;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only-but-always-present recorder of {@link TicketCreated} events. Lives
 * in main sources so the annotation processor wires it as a normal sync
 * subscriber. Cost in production: a single CopyOnWriteArrayList growing on
 * each POST. Negligible; the example is not a benchmark and this keeps the
 * test surface honest (it observes the same event everyone else sees).
 */
@Component(scope = Scope.SINGLETON)
public class TicketCreatedRecorder {

    private final List<TicketCreated> events = new CopyOnWriteArrayList<>();

    @EventHandler
    public void onTicketCreated(TicketCreated event) {
        events.add(event);
    }

    public List<TicketCreated> events() {
        return List.copyOf(events);
    }
}
