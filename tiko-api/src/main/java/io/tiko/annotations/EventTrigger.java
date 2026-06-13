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
 * value of the handler method becomes the payload of the triggered event, and the triggered
 * event is <strong>routed by that return type</strong> — every {@code @EventHandler} of the
 * returned type receives it. {@code eventName} is an optional human-readable label for the
 * topology / tracing view only; it is <strong>not</strong> a routing key (see
 * {@link #eventName()}). Model distinct intents as distinct return types, not as event names.</p>
 *
 * <p><strong>Basic Example:</strong></p>
 * <pre>{@code
 * @Component(scope = Scope.SINGLETON)
 * public class OrderService {
 *     @EventHandler
 *     @EventTrigger
 *     public OrderProcessedEvent onOrderCreated(OrderCreatedEvent event) {
 *         // Process order
 *         return new OrderProcessedEvent(event.orderId());
 *     }
 *     // When OrderCreatedEvent is published, after processing, the returned
 *     // OrderProcessedEvent is automatically published to its @EventHandlers.
 * }
 * }</pre>
 *
 * <p><strong>Multiple Triggers:</strong> the annotation is {@link Repeatable}, but because
 * routing is by return type, every repeat publishes the <em>same</em> return value to the
 * <em>same</em> handlers — repeating {@code @EventTrigger} with different {@code eventName}s
 * does not create different events (names don't route) and only results in duplicate
 * publishes. If you need genuinely distinct downstream events, return distinct types from
 * distinct handlers rather than stacking triggers.</p>
 *
 * <p><strong>Async Processing:</strong></p>
 * <pre>{@code
 * @EventHandler
 * @EventTrigger(async = true)
 * public NotificationResult onUserRegistered(UserRegisteredEvent event) {
 *     // Published asynchronously — doesn't block the handler's completion.
 *     return new NotificationResult(event.userId());
 * }
 * }</pre>
 *
 * <p><strong>Spread Collections:</strong></p>
 * <pre>{@code
 * @EventHandler
 * @EventTrigger(spread = true)
 * public List<Order> onBatchReceived(BatchReceivedEvent event) {
 *     // Each Order in the list is published separately to Order handlers.
 *     return event.orders();
 * }
 * }</pre>
 *
 * <p><strong>Conditional Triggering:</strong></p>
 * <pre>{@code
 * @EventHandler
 * @EventTrigger(guard = HighValueGuard.class)
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
@Retention(RetentionPolicy.SOURCE)
@Repeatable(EventTriggers.class)
@Documented
public @interface EventTrigger {

    /**
     * Optional human-readable label for this trigger, surfaced in the generated
     * {@code topology.json} and the MCP event-flow view for tracing and documentation.
     *
     * <p><strong>It is not a routing key.</strong> The triggered event is dispatched by the
     * handler's return type — every {@code @EventHandler} of that type receives it — exactly
     * as if you had called {@code eventBus.publish(returnValue)}. The label has no effect on
     * which handlers run; a typo or a rename changes only the trace label, never the wiring.
     * Model distinct intents as distinct return types, not as event names.</p>
     *
     * <p>Defaults to empty (no label).</p>
     *
     * @return the trace label, or {@code ""} for none
     */
    String eventName() default "";

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
