package io.tiko.examples.quickstart;

import io.javalin.http.Handler;
import io.tiko.UnitOfWork;

/**
 * Runs a Javalin handler in a unit of work of its own: one per request. A route group wraps each
 * of its handlers with {@link #inUnit}, because Javalin 7 has no handler that wraps a request.
 */
final class RequestUnits {

    private RequestUnits() {}

    static Handler inUnit(UnitOfWork unitOfWork, Handler handler) {
        return ctx -> unitOfWork.runInEventScope(() -> {
            try {
                handler.handle(ctx);
            } catch (RuntimeException failure) {
                throw failure; // Javalin's own responses (BadRequestResponse, ...) pass as they are.
            } catch (Exception failure) {
                throw new IllegalStateException("Request failed", failure);
            }
        });
    }
}
