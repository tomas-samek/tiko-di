package io.tiko.processor.collect;

import io.tiko.EventTriggerGuard;
import io.tiko.annotations.BackoffStrategy;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.EventTrigger;
import io.tiko.processor.core.ProcessingEnv;
import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.model.EventHandlerModel;
import io.tiko.processor.model.EventTriggerModel;
import io.tiko.processor.util.ErrorReporter;
import io.tiko.processor.util.ProcessorContext;
import io.tiko.processor.util.TypeRefs;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds an {@link EventHandlerModel} for every {@code @EventHandler} method of the round and
 * registers it with the {@link ProcessorContext}. Invalid timeout and retry declarations are
 * reported here, and the handler is skipped; so is a handler on a rejected component.
 */
public final class EventHandlerCollector {

    private static final String EVENT_HANDLER = EventHandler.class.getCanonicalName();
    private static final String EVENT_TRIGGER = EventTrigger.class.getCanonicalName();
    private static final String ASYNC = "async";
    private static final String ALWAYS_ALLOW = EventTriggerGuard.AlwaysAllow.class.getCanonicalName();

    private EventHandlerCollector() {}

    /** Collects the round's {@code @EventHandler} methods into {@code ctx}. */
    public static void collect(ProcessingEnv env, ProcessorContext ctx) {
        for (var method : env.methodsAnnotatedWith(EVENT_HANDLER)) {
            if (ctx.isRejected(method)) {
                continue; // a Kotlin construct DI does not support, already reported
            }
            // A handler on a rejected component is still checked, but not registered: there is no
            // component to call it on, and the component's own error already says why (#589).
            var handler = buildEventHandlerModel(method, ctx.getErrorReporter());
            if (handler != null && !ctx.isRejected(handler.getOwner())) {
                ctx.registerEventHandler(handler);
            }
        }
    }

    /** The handler's model, or {@code null} when a validation error was reported for it. */
    private static EventHandlerModel buildEventHandlerModel(MethodDecl method, ErrorReporter errors) {
        var annotation = method.annotation(EVENT_HANDLER).orElseThrow();
        var owner = method.owner().orElseThrow();

        var parameters = method.parameters();
        if (parameters.isEmpty()) {
            errors.error(
                    method.location(),
                    "@EventHandler method must have at least one parameter (the event)",
                    "Add the event type as the method's first parameter");
            return null;
        }

        // First parameter is the event type
        var eventType = parameters.get(0).type();
        var eventTypeName = TypeRefs.qualifiedName(eventType);

        // Check if second parameter is Event<?> wrapper
        boolean hasEventWrapper = parameters.size() > 1
                && TypeRefs.qualifiedName(parameters.get(1).type()).equals("io.tiko.Event");

        var eventTriggers = collectEventTriggers(method, errors);

        long timeoutNanos = resolveHandlerTimeoutNanos(method, annotation, errors);
        if (timeoutNanos < 0) {
            return null; // a timeout validation error was reported; skip this handler
        }

        long backoffNanos = resolveHandlerBackoffNanos(method, annotation, errors);
        if (backoffNanos < 0 || !validateRetries(method, annotation, errors)) {
            return null; // a retry validation error was reported; skip this handler
        }

        if (eventTriggers == null) {
            return null; // an unresolved guard was reported; skip this handler
        }

        return EventHandlerModel.builder()
                .method(method)
                .owner(owner)
                .methodName(method.name())
                .eventType(eventType)
                .eventTypeName(eventTypeName)
                .async(annotation.bool(ASYNC))
                .hasEventWrapper(hasEventWrapper)
                .timeoutNanos(timeoutNanos)
                .retries(annotation.integer("retries"))
                .backoffNanos(backoffNanos)
                .backoffStrategy(BackoffStrategy.valueOf(annotation.enumName("backoffStrategy")))
                .eventTriggers(eventTriggers)
                .build();
    }

    /**
     * Validates {@code @EventHandler(retries = ...)} (#108): retries are async-only (they wait for
     * the backoff between attempts, which would block the publisher's thread) and the count cannot
     * be negative. Returns {@code false} (and reports a compile error) when invalid.
     */
    private static boolean validateRetries(MethodDecl method, AnnotationRef annotation, ErrorReporter errors) {
        int retries = annotation.integer("retries");
        if (retries == 0) {
            return true;
        }
        if (retries < 0) {
            errors.error(
                    method.location(),
                    "@EventHandler retries '" + retries + "' must not be negative",
                    "Use a non-negative retry count, e.g. retries = 3");
            return false;
        }
        if (!annotation.bool(ASYNC)) {
            errors.error(
                    method.location(),
                    "@EventHandler retries requires async = true: retrying with backoff on the"
                            + " publisher's thread would block it",
                    "Add async = true to retry off-thread",
                    "Or remove retries");
            return false;
        }
        return true;
    }

    /**
     * Resolves {@code @EventHandler(backoff = ...)} (#108) to a non-negative nanosecond base delay,
     * or {@code 0} when no backoff is declared. Returns {@code -1} (and reports a compile error)
     * when the value is not an ISO-8601 {@link Duration} or is negative.
     */
    private static long resolveHandlerBackoffNanos(MethodDecl method, AnnotationRef annotation, ErrorReporter errors) {
        var backoff = annotation.string("backoff");
        if (backoff == null || backoff.isBlank()) {
            return 0L;
        }
        Duration duration;
        try {
            duration = Duration.parse(backoff.trim());
        } catch (DateTimeParseException badFormat) {
            errors.error(
                    method.location(),
                    "@EventHandler backoff '" + backoff + "' is not a valid ISO-8601 Duration",
                    "Use an ISO-8601 duration such as \"PT0.1S\" (100 ms) or \"PT2S\" (2 seconds)");
            return -1L;
        }
        if (duration.isNegative()) {
            errors.error(
                    method.location(),
                    "@EventHandler backoff '" + backoff + "' must not be negative",
                    "Use a non-negative duration such as \"PT0.1S\"");
            return -1L;
        }
        return duration.toNanos();
    }

    /**
     * Resolves {@code @EventHandler(timeout = ...)} (#107) to a positive nanosecond budget, or
     * {@code 0} when no timeout is declared. Returns {@code -1} (and reports a compile error) when
     * the declaration is invalid: timeouts are async-only (a sync handler runs on the publisher's
     * thread and cannot be interrupted), the value must be an ISO-8601 {@link Duration}, and it
     * must be positive.
     */
    private static long resolveHandlerTimeoutNanos(MethodDecl method, AnnotationRef annotation, ErrorReporter errors) {
        var timeout = annotation.string("timeout");
        if (timeout == null || timeout.isBlank()) {
            return 0L;
        }
        if (!annotation.bool(ASYNC)) {
            errors.error(
                    method.location(),
                    "@EventHandler timeout requires async = true: a sync handler runs on the publisher's"
                            + " thread (and its unit-of-work scope) and cannot be interrupted",
                    "Add async = true to time-box this handler (it then runs off-thread in its own scope)",
                    "Or remove the timeout");
            return -1L;
        }
        Duration duration;
        try {
            duration = Duration.parse(timeout.trim());
        } catch (DateTimeParseException badFormat) {
            errors.error(
                    method.location(),
                    "@EventHandler timeout '" + timeout + "' is not a valid ISO-8601 Duration",
                    "Use an ISO-8601 duration such as \"PT5S\" (5 seconds) or \"PT0.5S\" (500 ms)");
            return -1L;
        }
        if (duration.isZero() || duration.isNegative()) {
            errors.error(
                    method.location(),
                    "@EventHandler timeout '" + timeout + "' must be positive",
                    "Use a positive duration such as \"PT5S\"");
            return -1L;
        }
        return duration.toNanos();
    }

    /**
     * Every {@code @EventTrigger} on the method, in declaration order, whether written once or
     * repeated (the compiler wraps repeats in {@code @EventTriggers}). Default {@code AlwaysAllow}
     * guards are dropped — codegen treats an empty guard list as "always allow". Returns
     * {@code null} (and reports a compile error) when a guard class does not resolve: dropping it
     * would publish unconditionally.
     */
    private static List<EventTriggerModel> collectEventTriggers(MethodDecl method, ErrorReporter errors) {
        var raw = method.annotationsByType(EVENT_TRIGGER);
        var models = new ArrayList<EventTriggerModel>(raw.size());
        boolean guardsResolved = true;
        for (var trigger : raw) {
            if (trigger.isUnresolved("guard")) {
                reportUnresolvedGuard(method, trigger, errors);
                guardsResolved = false;
                continue;
            }
            var guards = new ArrayList<>(trigger.types("guard"));
            // Strip the default AlwaysAllow so codegen can skip the guard call entirely.
            guards.removeIf(guard -> TypeRefs.qualifiedName(guard).equals(ALWAYS_ALLOW));

            models.add(EventTriggerModel.builder()
                    .eventName(trigger.string("eventName"))
                    .async(trigger.bool(ASYNC))
                    .spread(trigger.bool("spread"))
                    .guardClasses(guards)
                    .build());
        }
        return guardsResolved ? models : null;
    }

    /**
     * A guard class another annotation processor generates is not yet resolved when Tiko collects
     * the handler (Tiko collects and generates in the first round that carries its annotations).
     */
    private static void reportUnresolvedGuard(MethodDecl method, AnnotationRef trigger, ErrorReporter errors) {
        var owner = method.owner().map(decl -> decl.className().simpleName()).orElse("<top level>");
        errors.error(
                method.location(),
                "@EventTrigger(eventName = \"" + trigger.string("eventName") + "\") on " + owner + "."
                        + method.name() + ": a guard class could not be resolved when Tiko collected the handler,"
                        + " so the trigger cannot be gated",
                "Use a guard class compiled in this module or in a dependency;"
                        + " guards generated by another annotation processor are not supported yet",
                "Check the guard's class literal for a typo or a missing import");
    }
}
