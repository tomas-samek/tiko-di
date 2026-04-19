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
 * <p><strong>Error Handling:</strong> If an event handler throws an exception,
 * it does not prevent other handlers from executing. The exception is logged
 * and can be accessed via error handling hooks.</p>
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
     * <p>When {@code true}, the handler runs in a separate thread and does not
     * block the event publisher. Useful for handlers with slow operations
     * (I/O, network calls, etc.) that don't need to complete before the
     * publisher continues.</p>
     *
     * <p>Default: {@code false} (synchronous)</p>
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
