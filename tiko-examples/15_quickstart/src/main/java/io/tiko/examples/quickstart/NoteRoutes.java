package io.tiko.examples.quickstart;

import io.javalin.config.RoutesConfig;
import io.javalin.http.BadRequestResponse;
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
 * {@link HttpServer} calls inside {@code Javalin.create(...)} — Javalin 7
 * accepts routes only there. Each handler runs in a unit of work and a database
 * transaction of its own ({@link RequestTransactions}), so every request gets
 * its own {@link RequestId}, and its writes are committed or rolled back together.
 */
@Component(scope = Scope.SINGLETON)
public class NoteRoutes {

    private final RequestTransactions transactions;
    private final NoteRepository repo;
    private final EventBus eventBus;
    private final RequestId requestId;

    @Inject
    public NoteRoutes(
            RequestTransactions transactions, NoteRepository repo, EventBus eventBus, RequestId requestId) {
        this.transactions = transactions;
        this.repo = repo;
        this.eventBus = eventBus;
        this.requestId = requestId;
    }

    /** Registers this group's paths, each handler in a unit of work and a transaction of its own. */
    public void register(RoutesConfig routes) {
        routes.post("/notes", transactions.inTransaction(this::handleCreate));
        routes.get("/notes/{id}", transactions.inTransaction(this::handleGet));
    }

    public void handleCreate(Context ctx) {
        ctx.header("X-Request-Id", requestId.value());
        var req = ctx.bodyAsClass(CreateNoteRequest.class);
        if (req.text() == null || req.text().isBlank()) {
            ctx.status(400).json(java.util.Map.of("error", "text must not be blank"));
            return;
        }
        var note = new Note(UUID.randomUUID(), req.text(), Instant.now(), req.tags());
        try {
            // The note row goes in first, then each tag: a blank tag fails the request after a
            // write, and the request's transaction rolls the note back with it.
            repo.insert(note);
            for (String tag : note.tags()) {
                if (tag == null || tag.isBlank()) throw new BadRequestResponse("tags must not be blank");
                repo.insertTag(note.id(), tag);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Note not stored", e);
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
