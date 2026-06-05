package io.tiko.examples.quickstart;

import io.javalin.http.Context;
import io.tiko.EventBus;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * Bridge between Javalin and the persistence + event-bus layers. Not a
 * {@code @Component}: it depends on {@link EventBus}, which Tiko exposes
 * off the {@link io.tiko.Container} rather than via DI. {@link Main}
 * constructs it once after container bootstrap.
 */
public final class NoteRoutes {

    private final NoteRepository repo;
    private final EventBus eventBus;

    public NoteRoutes(NoteRepository repo, EventBus eventBus) {
        this.repo = repo;
        this.eventBus = eventBus;
    }

    public void handleCreate(Context ctx) {
        var req = ctx.bodyAsClass(CreateNoteRequest.class);
        if (req.text() == null || req.text().isBlank()) {
            ctx.status(400).json(java.util.Map.of("error", "text must not be blank"));
            return;
        }
        var note = new Note(UUID.randomUUID(), req.text(), Instant.now());
        try {
            repo.insert(note);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        eventBus.publish(new NoteCreated(note.id(), note.createdAt()));
        ctx.status(201).json(note);
    }

    public void handleGet(Context ctx) {
        var id = UUID.fromString(ctx.pathParam("id"));
        try {
            repo.findById(id).ifPresentOrElse(n -> ctx.status(200).json(n), () -> ctx.status(404));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
