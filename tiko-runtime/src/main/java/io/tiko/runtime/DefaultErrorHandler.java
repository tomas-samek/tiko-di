package io.tiko.runtime;

import io.tiko.AutoCloseFailure;
import io.tiko.ConfigurationFailure;
import io.tiko.ErrorContext;
import io.tiko.ErrorHandler;
import io.tiko.EventHandlerError;
import io.tiko.PostConstructFailure;
import io.tiko.PreDestroyFailure;
import io.tiko.ProduceFailure;
import io.tiko.TransportError;
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
 *
 * <p>Dispatch is an exhaustive {@code switch} on the sealed {@link ErrorContext}. Adding a
 * new top-level permit makes this class fail to compile until a corresponding case is
 * added — the project's intentional compile-time-loud contract.
 */
public final class DefaultErrorHandler implements ErrorHandler {

    // Lazy holder: defers java.util.logging.LogManager init until the first error fires.
    // Most apps never touch this path on startup, so Tiko.create() pays no logging cost.
    private static final class LoggerHolder {
        static final Logger LOG = Logger.getLogger("io.tiko.events");
    }

    @Override
    public void onError(ErrorContext context) {
        switch (context) {
            case EventHandlerError(var handler, var event, var cause) ->
                LoggerHolder.LOG.log(
                        Level.WARNING,
                        "EventHandler %s#%s on event %s threw: %s"
                                .formatted(
                                        handler.declaringClass().getName(),
                                        handler.methodName(),
                                        handler.eventType().getName(),
                                        cause),
                        cause);
            case PostConstructFailure(var component, var cause) ->
                LoggerHolder.LOG.log(
                        Level.WARNING, "@PostConstruct on %s threw: %s".formatted(component.getName(), cause), cause);
            case PreDestroyFailure(var component, var cause) ->
                LoggerHolder.LOG.log(
                        Level.WARNING, "@PreDestroy on %s threw: %s".formatted(component.getName(), cause), cause);
            case AutoCloseFailure(var component, var cause) ->
                LoggerHolder.LOG.log(
                        Level.WARNING,
                        "AutoCloseable.close() on %s threw: %s".formatted(component.getName(), cause),
                        cause);
            case ConfigurationFailure f -> {
                // One log line per issue at WARNING — keep them grepable / metric-friendly.
                for (var issue : f.issues()) {
                    LoggerHolder.LOG.log(
                            Level.WARNING, "@Configuration [%s] %s".formatted(issue.code(), issue.description()));
                }
            }
            case ProduceFailure(var declaringClass, var methodName, var cause) ->
                LoggerHolder.LOG.log(
                        Level.WARNING,
                        "@Produces %s#%s threw: %s".formatted(declaringClass.getName(), methodName, cause),
                        cause);
            case TransportError t ->
                LoggerHolder.LOG.log(
                        Level.WARNING, "Transport %s error: %s".formatted(t.transport(), t.cause()), t.cause());
        }
    }
}
