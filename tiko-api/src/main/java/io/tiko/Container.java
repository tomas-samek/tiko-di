package io.tiko;

/**
 * The main entry point for the Tiko dependency injection container.
 *
 * <p>The Container is responsible for managing the lifecycle of components and providing
 * access to instances. It is typically created once at application startup and shut down
 * at application exit.</p>
 *
 * <p><strong>Lifecycle Events:</strong> The container automatically publishes lifecycle events
 * that can be subscribed to using {@code @EventHandler}:</p>
 * <ul>
 *   <li>{@link io.tiko.events.ApplicationStartedEvent} - On container startup</li>
 *   <li>{@link io.tiko.events.ApplicationEndingEvent} - Before container shutdown</li>
 *   <li>{@link io.tiko.events.RequestStartedEvent} - On entering request scope</li>
 *   <li>{@link io.tiko.events.RequestEndingEvent} - Before exiting request scope</li>
 *   <li>{@link io.tiko.events.EventStartedEvent} - On entering event scope</li>
 *   <li>{@link io.tiko.events.EventEndingEvent} - Before exiting event scope</li>
 * </ul>
 *
 * <p>Example usage with try-with-resources:</p>
 * <pre>{@code
 * // Create container (triggers ApplicationStartedEvent)
 * try (Container container = Tiko.create()) {
 *     // Retrieve components
 *     UserService service = container.get(UserService.class);
 *
 *     // Run code in request scope (triggers Request lifecycle events)
 *     container.runInRequestScope(() -> {
 *         // Process multiple events in one request/transaction
 *         for (Order order : orders) {
 *             // Each event triggers Event lifecycle events
 *             container.runInEventScope(() -> {
 *                 container.getEventBus().publish(new OrderCreatedEvent(order));
 *             });
 *         }
 *     });
 * } // Automatic shutdown (triggers ApplicationEndingEvent)
 * }</pre>
 */
public interface Container extends AutoCloseable {

    /**
     * Retrieves an instance of the specified type from the container.
     *
     * @param type the class of the component to retrieve
     * @param <T>  the type of the component
     * @return an instance of the component
     * @throws IllegalArgumentException if no component of the specified type exists
     */
    <T> T get(Class<T> type);

    /**
     * Retrieves a named instance of the specified type from the container.
     *
     * @param type the class of the component to retrieve
     * @param name the qualifier name
     * @param <T>  the type of the component
     * @return an instance of the component
     * @throws IllegalArgumentException if no component with the specified type and name exists
     */
    <T> T get(Class<T> type, String name);

    /**
     * Retrieves a Provider for the specified type, allowing lazy resolution.
     *
     * @param type the class of the component
     * @param <T>  the type of the component
     * @return a Provider that can supply instances
     */
    <T> Provider<T> getProvider(Class<T> type);

    /**
     * Retrieves a named Provider for the specified type, allowing lazy resolution.
     *
     * @param type the class of the component
     * @param name the qualifier name
     * @param <T>  the type of the component
     * @return a Provider that can supply instances
     */
    <T> Provider<T> getProvider(Class<T> type, String name);

    /**
     * Entry point for the multi-axis lookup builder. Use {@link Pick#withName(String)}
     * to add a qualifier and one of {@link Pick#resolve()},
     * {@link Pick#asProvider()}, {@link Pick#orDefault(Object)} as the terminal.
     *
     * <p>{@code container.pick(Type.class).resolve()} with no filters is equivalent
     * to {@link #get(Class)}.
     *
     * @param type the class of the component
     * @param <T>  the type of the component
     * @return a fluent builder
     */
    default <T> Pick<T> pick(Class<T> type) {
        return new DefaultPick<>(this, type);
    }

    /**
     * Executes the given runnable within a request scope.
     * <p>
     * Request-scoped beans created during execution will be destroyed
     * when the scope exits. Multiple events can be processed within
     * a single request scope.
     *
     * @param runnable the code to execute in request scope
     */
    void runInRequestScope(Runnable runnable);

    /**
     * Executes the given supplier within a request scope and returns its result.
     * <p>
     * Request-scoped beans created during execution will be destroyed
     * when the scope exits.
     *
     * @param supplier the code to execute in request scope
     * @param <T>      the return type
     * @return the result of the supplier
     */
    <T> T supplyInRequestScope(java.util.function.Supplier<T> supplier);

    /**
     * Executes the given runnable within an event scope.
     * <p>
     * Event-scoped beans created during execution will be destroyed
     * when the scope exits. This represents a single event processing
     * within a potentially larger request scope.
     *
     * @param runnable the code to execute in event scope
     */
    void runInEventScope(Runnable runnable);

    /**
     * Executes the given supplier within an event scope and returns its result.
     * <p>
     * Event-scoped beans created during execution will be destroyed
     * when the scope exits.
     *
     * @param supplier the code to execute in event scope
     * @param <T>      the return type
     * @return the result of the supplier
     */
    <T> T supplyInEventScope(java.util.function.Supplier<T> supplier);

    /**
     * Shuts down the container gracefully.
     * <p>
     * Invokes {@code @PreDestroy} methods on all singleton beans in reverse
     * construction order and releases all resources.
     */
    void shutdown();

    /**
     * Returns the event bus for publishing and subscribing to events.
     *
     * @return the event bus
     */
    EventBus getEventBus();

    /**
     * Convenience method that returns the event bus.
     * <p>
     * This is an alias for {@link #getEventBus()} to provide a shorter method name.
     *
     * @return the event bus
     */
    default EventBus events() {
        return getEventBus();
    }

    /**
     * Returns the executor used for asynchronous event dispatch
     * ({@code @EventHandler(async = true)} and {@code @EventTrigger(async = true)}).
     *
     * <p>This is either the user-supplied {@link java.util.concurrent.ExecutorService}
     * passed via {@code TikoOptions.eventExecutor(...)}, or the framework's default
     * bounded {@link java.util.concurrent.ThreadPoolExecutor}. The returned executor
     * is alive for the lifetime of the container.
     *
     * <p>You may submit your own tasks to this executor if you want to share thread
     * resources with the framework. Mutating its state (e.g. calling
     * {@code shutdown()}) is your responsibility, but doing so on the framework's
     * default executor will cause undefined behaviour during subsequent event
     * dispatch — prefer supplying your own via {@code TikoOptions} if you need
     * lifecycle control.
     *
     * @return the event executor (never {@code null})
     */
    java.util.concurrent.ExecutorService getEventExecutor();

    /**
     * Closes the container and releases all resources.
     * <p>
     * This method is called automatically when using try-with-resources.
     * It delegates to {@link #shutdown()} to perform the actual cleanup.
     * <p>
     * Invokes {@code @PreDestroy} methods on all singleton beans in reverse
     * construction order and releases all resources.
     */
    @Override
    default void close() {
        shutdown();
    }
}
