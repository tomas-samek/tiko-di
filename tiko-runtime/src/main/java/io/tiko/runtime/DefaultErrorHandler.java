package io.tiko.runtime;

import io.tiko.ErrorContext;
import io.tiko.ErrorHandler;
import io.tiko.EventHandlerError;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default {@link ErrorHandler} implementation. Logs each {@link ErrorContext} at
 * {@link Level#WARNING} via {@link java.util.logging}. Used by {@code Tiko.create(...)}
 * when the user did not supply a custom handler via {@code TikoOptions.errorHandler(...)}.
 *
 * <p>Backed by JUL rather than slf4j so the framework requires no logging-binding
 * dependency to start. Users on slf4j stacks can supply their own handler:
 * <pre>{@code
 * Logger slf4j = LoggerFactory.getLogger("io.tiko.events");
 * Tiko.create(TikoOptions.builder()
 *     .errorHandler(ctx -> slf4j.warn("{}", ctx.cause().toString(), ctx.cause()))
 *     .build());
 * }</pre>
 */
public final class DefaultErrorHandler implements ErrorHandler {

    // Lazy holder: defers java.util.logging.LogManager init until the first error fires.
    // Most apps never touch this path on startup, so Tiko.create() pays no logging cost.
    private static final class LoggerHolder {
        static final Logger LOG = Logger.getLogger("io.tiko.events");
    }

    @Override
    public void onError(ErrorContext context) {
        if (context instanceof EventHandlerError e) {
            LoggerHolder.LOG.log(Level.WARNING,
                String.format("EventHandler %s#%s on event %s threw: %s",
                    e.handler().declaringClass().getName(),
                    e.handler().methodName(),
                    e.handler().eventType().getName(),
                    e.cause().toString()),
                e.cause());
        } else {
            // Forward-compatible: future ErrorContext subtypes log a generic message
            // until this default handler is updated to match the new permits.
            LoggerHolder.LOG.log(Level.WARNING,
                String.format("Framework error: %s: %s",
                    context.getClass().getSimpleName(),
                    context.cause().toString()),
                context.cause());
        }
    }
}
