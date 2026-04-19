package io.tiko.annotations;

import io.tiko.EventTriggerGuard;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declaratively triggers additional events after an event handler completes successfully.
 *
 * <p>This annotation enables event chaining without explicit EventBus injection. The return
 * value of the handler method becomes the payload of the triggered event.</p>
 *
 * <p><strong>Basic Example:</strong></p>
 * <pre>{@code
 * @Component(scope = Scope.SINGLETON)
 * public class OrderService {
 *     @EventHandler
 *     @EventTrigger(eventName = "OrderProcessed")
 *     public OrderProcessedEvent onOrderCreated(OrderCreatedEvent event) {
 *         // Process order
 *         return new OrderProcessedEvent(event.orderId());
 *     }
 *     // When OrderCreatedEvent is published, after processing,
 *     // OrderProcessedEvent is automatically published
 * }
 * }</pre>
 *
 * <p><strong>Multiple Triggers:</strong></p>
 * <pre>{@code
 * @EventHandler
 * @EventTrigger(eventName = "OrderValidated")
 * @EventTrigger(eventName = "InventoryChecked")
 * @EventTrigger(eventName = "PaymentAuthorized")
 * public OrderValidationResult validate(OrderCreatedEvent event) {
 *     // All three events triggered with same payload (return value)
 *     return validateOrder(event);
 * }
 * }</pre>
 *
 * <p><strong>Async Processing:</strong></p>
 * <pre>{@code
 * @EventHandler
 * @EventTrigger(eventName = "EmailSent", async = true)
 * @EventTrigger(eventName = "NotificationSent", async = true)
 * public NotificationResult onUserRegistered(UserRegisteredEvent event) {
 *     // Async events fire in parallel, don't block main flow
 *     return new NotificationResult(event.userId());
 * }
 * }</pre>
 *
 * <p><strong>Spread Collections:</strong></p>
 * <pre>{@code
 * @EventHandler
 * @EventTrigger(eventName = "IndividualOrderProcessed", spread = true)
 * public List<Order> onBatchReceived(BatchReceivedEvent event) {
 *     // Each order in the list triggers separate IndividualOrderProcessed event
 *     return event.orders();
 * }
 * }</pre>
 *
 * <p><strong>Conditional Triggering:</strong></p>
 * <pre>{@code
 * @EventHandler
 * @EventTrigger(
 *     eventName = "HighValueOrder",
 *     guard = HighValueGuard.class
 * )
 * public OrderDetails onOrderCreated(OrderCreatedEvent event) {
 *     return getOrderDetails(event.orderId());
 * }
 *
 * public class HighValueGuard implements EventTriggerGuard {
 *     public boolean shouldTrigger(Object result, Object originalEvent) {
 *         return ((OrderDetails) result).amount() > 10000;
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Event Chains:</strong> Triggered events can themselves have handlers with
 * {@code @EventTrigger}, creating chains of events. The framework tracks the full origin
 * chain for debugging and tracing.</p>
 *
 * <p><strong>Important:</strong> Events are only triggered if the handler completes
 * successfully. If an exception is thrown, no events are triggered.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(EventTriggers.class)
@Documented
public @interface EventTrigger {

    /**
     * Name of the event to trigger after the handler completes successfully.
     *
     * <p>This can be either a simple event class name (e.g., "OrderProcessed")
     * or a fully qualified event name for disambiguation.</p>
     *
     * @return event name
     */
    String eventName();

    /**
     * Whether to trigger the event asynchronously.
     *
     * <p>When {@code true}, the event is published asynchronously and does not
     * block the handler's completion. Useful for side effects like notifications,
     * logging, or metrics that don't need to complete before returning.</p>
     *
     * <p>Default: {@code false} (synchronous)</p>
     *
     * @return true for async processing, false for sync
     */
    boolean async() default false;

    /**
     * Whether to spread collection results as individual events.
     *
     * <p>When {@code true} and the handler returns a Collection or array,
     * each item triggers a separate event rather than the entire collection
     * being the payload of a single event.</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * @EventTrigger(eventName = "ItemProcessed", spread = true)
     * public List<Item> processBatch(BatchEvent event) {
     *     return event.items(); // Each item triggers ItemProcessed
     * }
     * }</pre>
     *
     * <p>Default: {@code false} (no spreading)</p>
     *
     * @return true to spread collections, false otherwise
     */
    boolean spread() default false;

    /**
     * Guard classes that determine whether the event should be triggered.
     *
     * <p>Guards are evaluated in order. All guards must return {@code true}
     * for the event to be triggered (AND logic).</p>
     *
     * <p>Default: {@link EventTriggerGuard.AlwaysAllow} (always trigger)</p>
     *
     * @return array of guard classes
     */
    Class<? extends EventTriggerGuard>[] guard() default EventTriggerGuard.AlwaysAllow.class;
}
