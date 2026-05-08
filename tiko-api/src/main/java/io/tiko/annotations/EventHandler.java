package io.tiko.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an event handler.
 *
 * <p>Event handler methods are automatically registered with the EventBus and invoked
 * when matching events are published. The method must have exactly one parameter
 * (the event type), or optionally two parameters (event and Event wrapper).</p>
 *
 * <p><strong>Basic Usage:</strong></p>
 * <pre>{@code
 * @Component(scope = Scope.SINGLETON)
 * public class NotificationService {
 *     @EventHandler
 *     public void onUserRegistered(UserRegisteredEvent event) {
 *         sendWelcomeEmail(event.email());
 *     }
 * }
 * }</pre>
 *
 * <p><strong>With Event Wrapper (for origin tracking):</strong></p>
 * <pre>{@code
 * @EventHandler
 * public void onOrderProcessed(OrderProcessedEvent event, Event<?> eventWrapper) {
 *     // Access origin chain
 *     List<Object> chain = eventWrapper.getOriginChain();
 *     logger.info("Event chain: {}", chain);
 * }
 * }</pre>
 *
 * <p><strong>With Return Value (for explicit event publishing):</strong></p>
 * <pre>{@code
 * @EventHandler
 * public NotificationResult onUserRegistered(UserRegisteredEvent event) {
 *     // Return value can be used by framework or ignored
 *     return sendWelcomeEmail(event.email());
 * }
 * }</pre>
 *
 * <p><strong>Event Chaining with @EventTrigger:</strong></p>
 * <pre>{@code
 * @EventHandler
 * @EventTrigger(eventName = "OrderValidated")
 * @EventTrigger(eventName = "InventoryChecked", async = true)
 * public ValidationResult onOrderCreated(OrderCreatedEvent event) {
 *     // Return value becomes payload of triggered events
 *     return validateOrder(event);
 * }
 * }</pre>
 *
 * <p><strong>Async Processing:</strong></p>
 * <pre>{@code
 * @EventHandler(async = true)
 * public void onOrderCreated(OrderCreatedEvent event) {
 *     // Processed asynchronously, doesn't block publisher
 *     performExpensiveOperation(event);
 * }
 * }</pre>
 *
 * <p><strong>Error handling:</strong> If a handler throws, the exception is routed
 * to the configured {@link io.tiko.ErrorHandler} (default: slf4j WARN). It does not
 * propagate to the publisher and does not prevent other handlers from running.
 * Async handler exceptions are routed identically.
 *
 * <p>The hook is for observability — do not throw from a handler to signal business
 * state. Use {@link EventTrigger} together with {@link io.tiko.EventTriggerGuard}
 * to branch on outcomes; throwing is an error path, not a control-flow primitive.</p>
 *
 * <p><strong>Ordering:</strong> Event handlers execute in registration order
 * (typically class scan order). For deterministic ordering, use explicit
 * event chains with {@link EventTrigger}.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EventHandler {

    /**
     * Whether this handler should be invoked asynchronously.
     *
     * <p>When {@code true}, the handler runs on the container's event executor —
     * a bounded {@link java.util.concurrent.ThreadPoolExecutor} by default
     * (see the framework defaults documented on
     * {@code io.tiko.runtime.DefaultEventExecutorFactory}), or the user-supplied
     * {@link java.util.concurrent.ExecutorService} passed via
     * {@code TikoOptions.eventExecutor(...)}.
     *
     * <p>The publisher does not wait for an async handler to complete. Async handler
     * exceptions are routed to the configured {@link io.tiko.ErrorHandler}, identical
     * to sync handler errors.
     *
     * <p>Default: {@code false} (synchronous).
     *
     * @return true for async execution, false for sync
     */
    boolean async() default false;

    /**
     * Optional event type to handle (for disambiguation).
     *
     * <p>Normally the event type is inferred from the method parameter.
     * Use this when you need to handle a specific subtype or when using
     * generic event wrappers.</p>
     *
     * <p>Default: {@code Object.class} (inferred from parameter)</p>
     *
     * @return the event class to handle
     */
    Class<?> eventType() default Object.class;
}
