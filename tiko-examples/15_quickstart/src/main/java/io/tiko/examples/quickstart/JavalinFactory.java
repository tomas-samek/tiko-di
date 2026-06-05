package io.tiko.examples.quickstart;

import io.javalin.Javalin;
import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PreDestroy;
import io.tiko.annotations.Produces;

/**
 * Canonical "register HTTP via {@code @Produces}" recipe — Javalin is plugged
 * in directly, with no wrapper between user code and the library. Lifecycle is
 * owned by the factory: it holds the instance after construction so
 * {@link #shutdown()} can stop the server at container teardown. Routes are
 * registered by {@link Main} against the produced instance.
 */
@Component(scope = Scope.SINGLETON)
public class JavalinFactory {

    private Javalin app;

    @Produces(scope = Scope.SINGLETON)
    public Javalin javalin() {
        this.app = Javalin.create();
        return app;
    }

    @PreDestroy
    public void shutdown() {
        if (app != null) app.stop();
    }
}
