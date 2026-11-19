package io.tiko.examples.quickstart;

import static io.tiko.examples.quickstart.RequestUnits.inUnit;

import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.tiko.EventBus;
import io.tiko.Scope;
import io.tiko.UnitOfWork;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * Bridge between Javalin and the persistence + event-bus layers. A plain
 * component: its paths live next to their handlers in {@link #register}, which
 * {@link HttpServer} calls inside {@code Javalin.create(...)} — Javalin 7
 * accepts routes only there. Each handler runs in a unit of work of its own,
 * so every request gets its own {@link RequestId}.
 */
@Component(scope = Scope.SINGLETON)
public class NoteRoutes {

    private final UnitOfWork unitOfWork;
    private final NoteRepository repo;
    private final EventBus eventBus;
    private final RequestId requestId;

    @Inject
    public NoteRoutes(UnitOfWork unitOfWork, NoteRepository repo, EventBus eventBus, RequestId requestId) {
        this.unitOfWork = unitOfWork;
        this.repo = repo;
        this.eventBus = eventBus;
        this.requestId = requestId;
    }

    /** Registers this group's paths, each handler in a unit of work of its own. */
    public void register(RoutesConfig routes) {
        routes.post("/notes", inUnit(unitOfWork, this::handleCreate));
        routes.get("/notes/{id}", inUnit(unitOfWork, this::handleGet));
    }

    public void handleCreate(Context ctx) {
        ctx.header("X-Request-Id", requestId.value());
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
