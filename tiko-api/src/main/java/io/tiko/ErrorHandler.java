package io.tiko;

/**
 * Hook for observing exceptions raised inside the framework — handler throws,
 * async dispatch failures, and (future) lifecycle / config / scope errors.
 *
 * <p><strong>Contract:</strong>
 * <ul>
 *   <li>Invoked synchronously on whichever thread surfaced the error (publisher thread
 *       for sync handlers; executor thread for async handlers). The framework does not
 *       hop threads to invoke this hook.</li>
 *   <li>The return type is {@code void} on purpose — implementations cannot influence
 *       dispatch flow. This hook is for logs, metrics, and alerts. To branch on
 *       handler outcomes, use {@code @EventTrigger} with an {@code EventTriggerGuard};
 *       do not throw exceptions from event handlers as a control-flow signal.</li>
 *   <li>Implementations should be fast and non-throwing. Event dispatch catches and logs
 *       observer exceptions to prevent handler-of-handler recursion.</li>
 *   <li>During regular singleton teardown, the first observer failure propagates after cleanup.
 *       The triggering teardown failure is attached as a suppressed exception when distinct.
 *       During startup rollback, the startup failure remains primary.</li>
 * </ul>
 *
 * <p>The default implementation logs at WARNING via {@code System.Logger}. Override via
 * {@code TikoOptions.errorHandler(...)}.
 */
@FunctionalInterface
public interface ErrorHandler {
    void onError(ErrorContext context);
}
