package io.tiko.examples.quickstart;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Event-driven workflow handler — the canonical orchestrator-model alternative
 * to attaching extra behaviour to a service method. Counts every
 * {@link NoteCreated} observed; exposed via {@link #count()} so the integration
 * test can prove the bus actually delivered.
 */
@Component(scope = Scope.SINGLETON)
public class NoteAuditor {

    private final AtomicInteger seen = new AtomicInteger();

    @EventHandler
    public void onNoteCreated(NoteCreated event) {
        seen.incrementAndGet();
    }

    public int count() {
        return seen.get();
    }
}
