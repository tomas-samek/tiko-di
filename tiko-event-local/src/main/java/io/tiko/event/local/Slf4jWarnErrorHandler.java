package io.tiko.event.local;

import io.tiko.ErrorContext;
import io.tiko.ErrorHandler;
import io.tiko.EventHandlerError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link ErrorHandler} implementation. Logs each {@link ErrorContext} at WARN
 * via slf4j. Used by {@code Tiko.create(...)} when the user did not supply a custom
 * handler via {@code TikoOptions.errorHandler(...)}.
 */
final class Slf4jWarnErrorHandler implements ErrorHandler {

    private static final Logger LOG = LoggerFactory.getLogger("io.tiko.events");

    @Override
    public void onError(ErrorContext context) {
        if (context instanceof EventHandlerError e) {
            LOG.warn("EventHandler {}#{} on event {} threw: {}",
                e.handler().declaringClass().getName(),
                e.handler().methodName(),
                e.handler().eventType().getName(),
                e.cause().toString(),
                e.cause());
        } else {
            // Forward-compatible: future ErrorContext subtypes log a generic message
            // until this default handler is updated to match the new permits.
            LOG.warn("Framework error: {}: {}", context.getClass().getSimpleName(), context.cause().toString(), context.cause());
        }
    }
}
