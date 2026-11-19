package io.tiko.examples.quickstart;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.Inject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Event-driven workflow handler — the canonical orchestrator-model alternative
 * to attaching extra behaviour to a service method. Records the request of every
 * {@link NoteCreated} observed, so the integration test can prove the bus
 * delivered, and delivered inside the request's unit of work: a synchronous
 * handler reads the same {@link RequestId} the route read.
 */
@Component(scope = Scope.SINGLETON)
public class NoteAuditor {

    private final RequestId requestId;
    private final List<String> requestIds = new CopyOnWriteArrayList<>();

    @Inject
    public NoteAuditor(RequestId requestId) {
        this.requestId = requestId;
    }

    @EventHandler
    public void onNoteCreated(NoteCreated event) {
        requestIds.add(requestId.value());
    }

    public int count() {
        return requestIds.size();
    }

    /** The request id of each note seen, in order. */
    public List<String> requestIds() {
        return List.copyOf(requestIds);
    }
}
