package io.tiko;

/**
 * Functional interface for programmatic event handling.
 *
 * <p>This is used for programmatic subscription to events via
 * {@link EventBus#subscribe(Class, EventCallback)}. For declarative
 * event handling, use the {@link io.tiko.annotations.EventHandler} annotation instead.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * EventBus eventBus = container.events();
 * Subscription sub = eventBus.subscribe(OrderCreatedEvent.class, event -> {
 *     System.out.println("Order created: " + event.orderId());
 * });
 *
 * // Later, unsubscribe
 * sub.unsubscribe();
 * }</pre>
 *
 * @param <T> the type of event to handle
 */
@FunctionalInterface
public interface EventCallback<T> {

    /**
     * Handles the given event.
     *
     * @param event the event to handle
     */
    void handle(T event);
}
