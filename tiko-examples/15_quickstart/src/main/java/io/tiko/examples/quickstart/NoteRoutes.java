package io.tiko.examples.quickstart;

import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.tiko.EventBus;
import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * Bridge between Javalin and the persistence + event-bus layers. A plain
 * component: its paths live next to their handlers in {@link #register}, which
 * {@link JavalinFactory} calls inside {@code Javalin.create(...)} — Javalin 7
 * accepts routes only there.
 */
@Component(scope = Scope.SINGLETON)
public class NoteRoutes {

    private final NoteRepository repo;
    private final EventBus eventBus;

    @Inject
    public NoteRoutes(NoteRepository repo, EventBus eventBus) {
        this.repo = repo;
        this.eventBus = eventBus;
    }

    /** Registers this group's paths. */
    public void register(RoutesConfig routes) {
        routes.post("/notes", this::handleCreate);
        routes.get("/notes/{id}", this::handleGet);
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
