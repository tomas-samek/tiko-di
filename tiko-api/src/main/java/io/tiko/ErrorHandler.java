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
 *   <li>Implementations should be fast and non-throwing. An exception thrown <em>from</em>
 *       {@code onError} is caught by the framework, logged at ERROR via slf4j, and
 *       suppressed — preventing handler-of-handler recursion.</li>
 * </ul>
 *
 * <p>The default implementation logs at WARN via slf4j. Override via
 * {@code TikoOptions.errorHandler(...)}.
 */
@FunctionalInterface
public interface ErrorHandler {
    void onError(ErrorContext context);
}
