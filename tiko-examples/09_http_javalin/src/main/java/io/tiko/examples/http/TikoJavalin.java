package io.tiko.examples.http;

import io.javalin.http.Handler;
import io.tiko.UnitOfWork;

/**
 * Tiny middleware bridge: wraps a Javalin {@link Handler} so each invocation
 * runs inside one Tiko unit of work (an EVENT scope). Drop this in front of every
 * route registration to get one unit per request across the whole request lifecycle
 * — body parsing, business logic, event publishing, response serialization.
 *
 * <p>Why a helper instead of opening the scope inside each handler: ergonomics.
 * Every route would otherwise need an identical {@code runInEventScope}
 * wrapper, which is exactly the kind of boilerplate middleware exists to
 * eliminate. Javalin 7 has no handler that wraps a request, so the route
 * group applies it when it registers its paths.
 *
 * <p>It takes {@link UnitOfWork}, which a component gets injected; the container is a
 * {@code UnitOfWork} too. This class is part of the example module, not framework code.
 */
public final class TikoJavalin {

    private TikoJavalin() {}

    /**
     * Returns a new {@link Handler} that opens one unit of work around
     * the delegate's {@link Handler#handle(io.javalin.http.Context)}.
     * Javalin's own responses ({@code BadRequestResponse}, ...) pass as they are;
     * a checked exception is wrapped in an {@link IllegalStateException}.
     */
    public static Handler scoped(UnitOfWork unitOfWork, Handler delegate) {
        return ctx -> unitOfWork.runInEventScope(() -> {
            try {
                delegate.handle(ctx);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Request failed", e);
            }
        });
    }
}
