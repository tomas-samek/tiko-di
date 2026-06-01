package io.tiko.examples.http;

import io.javalin.http.Handler;
import io.tiko.Container;

/**
 * Tiny middleware bridge: wraps a Javalin {@link Handler} so each invocation
 * runs inside a Tiko request scope. Drop this in front of every route
 * registration to get request-scope semantics for the whole request lifecycle
 * — body parsing, business logic, event publishing, response serialization.
 *
 * <p>Why a helper instead of opening the scope inside each handler: ergonomics.
 * Every route would otherwise need an identical {@code runInEventScope}
 * wrapper, which is exactly the kind of boilerplate middleware exists to
 * eliminate.
 *
 * <p>This class is part of the example module, not framework code. If
 * something this thin proves valuable enough to live in a shared library,
 * it gets promoted to a {@code tiko-http-bridge} module in a follow-up — but
 * not before real users hit ergonomic friction.
 */
public final class TikoJavalin {

    private TikoJavalin() {}

    /**
     * Returns a new {@link Handler} that opens a Tiko request scope around
     * the delegate's {@link Handler#handle(io.javalin.http.Context)}.
     * Javalin's checked-exception declaration is wrapped in a
     * {@link RuntimeException}; Javalin's own exception mapper unwraps it on
     * its side and applies the user's configured exception handlers.
     */
    public static Handler scoped(Container container, Handler delegate) {
        return ctx -> container.runInEventScope(() -> {
            try {
                delegate.handle(ctx);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
