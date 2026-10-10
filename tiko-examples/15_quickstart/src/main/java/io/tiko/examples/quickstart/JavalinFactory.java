package io.tiko.examples.quickstart;

import io.javalin.Javalin;
import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PreDestroy;
import io.tiko.annotations.Produces;

/**
 * Canonical "register HTTP via {@code @Produces}" recipe — Javalin is plugged
 * in directly, with no wrapper between user code and the library. Javalin 7
 * accepts routes only inside {@code Javalin.create(...)}, so each route group
 * is a parameter of the producer and registers its own paths there. Lifecycle
 * is owned by the factory: it holds the instance after construction so
 * {@link #shutdown()} can stop the server at container teardown. {@link Main}
 * only starts it.
 */
@Component(scope = Scope.SINGLETON)
public class JavalinFactory {

    private Javalin app;

    @Produces(scope = Scope.SINGLETON)
    public Javalin javalin(NoteRoutes notes) {
        this.app = Javalin.create(cfg -> notes.register(cfg.routes));
        return app;
    }

    @PreDestroy
    public void shutdown() {
        if (app != null) app.stop();
    }
}
