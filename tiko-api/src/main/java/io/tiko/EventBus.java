package io.tiko;

/**
 * Event bus for publishing and subscribing to events.
 *
 * <p>The EventBus provides a decoupled way for components to communicate through events.
 * It can be backed by different implementations (in-memory, Kafka, etc.) without changing
 * application code.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * @Component
 * public class OrderService {
 *     private final EventBus events;
 *
 *     @Inject
 *     public OrderService(Container container) {
 *         this.events = container.events();
 *     }
 *
 *     public void createOrder(Order order) {
 *         // Save order...
 *         events.publish(new OrderCreatedEvent(order));
 *     }
 * }
 *
 * @Component
 * public class NotificationService {
 *     @EventHandler
 *     public void onOrderCreated(OrderCreatedEvent event) {
 *         // Send notification...
 *     }
 * }
 * }</pre>
 */
public interface EventBus {

    /**
     * Publishes an event to all registered handlers.
     *
     * @param event the event to publish
     * @param <T>   the type of the event
     */
    <T> void publish(T event);

    /**
     * Subscribes a handler to events of the specified type.
     *
     * @param eventType the class of events to listen for
     * @param callback  the callback to invoke when events occur
     * @param <T>       the type of events
     * @return a subscription that can be used to unsubscribe
     */
    <T> Subscription subscribe(Class<T> eventType, EventCallback<T> callback);
}
