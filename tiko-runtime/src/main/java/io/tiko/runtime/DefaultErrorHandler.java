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

/**
 * Default {@link ErrorHandler} implementation. Logs each {@link ErrorContext} at
 * {@link System.Logger.Level#WARNING} via {@link java.lang.System#getLogger}. Used by
 * {@code Tiko.create(...)} when the user did not supply a custom handler via
 * {@code TikoOptions.errorHandler(...)}.
 *
 * <p>Backed by {@code System.Logger} so the framework requires no logging-binding
 * dependency to start. Default routing is JUL; users on slf4j stacks add a
 * {@code System.LoggerFinder} provider (e.g. {@code slf4j-jdk-platform-logging}).
 *
 * <p>Dispatch is an exhaustive {@code switch} on the sealed {@link ErrorContext}. Adding a
 * new top-level permit makes this class fail to compile until a corresponding case is
 * added — the project's intentional compile-time-loud contract.
 */
public final class DefaultErrorHandler implements ErrorHandler {

    // Lazy holder: defers System.LoggerFinder resolution until the first error fires.
    // Most apps never touch this path on startup, so Tiko.create() pays no logging cost.
    private static final class LoggerHolder {
        static final System.Logger LOG = System.getLogger("io.tiko.events");
    }

    @Override
    public void onError(ErrorContext context) {
        switch (context) {
            case EventHandlerError(var handler, var event, var cause, var attempts) ->
                TikoLog.log(
                        LoggerHolder.LOG,
                        System.Logger.Level.WARNING,
                        cause,
                        "EventHandler {0}#{1} on event {2} threw after {3} attempt(s): {4}",
                        handler.declaringClass().getName(),
                        handler.methodName(),
                        handler.eventType().getName(),
                        attempts,
                        cause);
            case PostConstructFailure(var component, var cause) ->
                TikoLog.log(
                        LoggerHolder.LOG,
                        System.Logger.Level.WARNING,
                        cause,
                        "@PostConstruct on {0} threw: {1}",
                        component.getName(),
                        cause);
            case PreDestroyFailure(var component, var cause) ->
                TikoLog.log(
                        LoggerHolder.LOG,
                        System.Logger.Level.WARNING,
                        cause,
                        "@PreDestroy on {0} threw: {1}",
                        component.getName(),
                        cause);
            case AutoCloseFailure(var component, var cause) ->
                TikoLog.log(
                        LoggerHolder.LOG,
                        System.Logger.Level.WARNING,
                        cause,
                        "AutoCloseable.close() on {0} threw: {1}",
                        component.getName(),
                        cause);
            case ConfigurationFailure f -> {
                // One log line per issue at WARNING — keep them grepable / metric-friendly.
                for (var issue : f.issues()) {
                    TikoLog.log(
                            LoggerHolder.LOG,
                            System.Logger.Level.WARNING,
                            "@Configuration [{0}] {1}",
                            issue.code(),
                            issue.description());
                }
            }
            case ProduceFailure(var declaringClass, var methodName, var cause) ->
                TikoLog.log(
                        LoggerHolder.LOG,
                        System.Logger.Level.WARNING,
                        cause,
                        "@Produces {0}#{1} threw: {2}",
                        declaringClass.getName(),
                        methodName,
                        cause);
            case TransportError t ->
                TikoLog.log(
                        LoggerHolder.LOG,
                        System.Logger.Level.WARNING,
                        t.cause(),
                        "Transport {0} error: {1}",
                        t.transport(),
                        t.cause());
        }
    }
}
