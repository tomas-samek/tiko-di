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
 * to the configured {@link io.tiko.ErrorHandler} (default: logs at WARNING via
 * {@code System.Logger}). It does not
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
@Retention(RetentionPolicy.SOURCE)
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

    /**
     * Optional execution timeout for an asynchronous handler, as an ISO-8601
     * {@link java.time.Duration} string (e.g. {@code "PT5S"}, {@code "PT0.5S"}).
     *
     * <p>When set, a handler that runs longer than this budget has its worker thread
     * interrupted (best-effort — Java cannot force-stop a thread that ignores interruption),
     * the executor slot is freed, and the overrun is routed to the configured
     * {@link io.tiko.ErrorHandler} as an {@link io.tiko.EventHandlerError} whose
     * {@code cause()} is a {@link java.util.concurrent.TimeoutException}. Subsequent events
     * keep flowing.
     *
     * <p><strong>Requires {@code async = true}.</strong> A timeout on a synchronous handler
     * is a compile-time error: a sync handler runs on the publisher's thread and within the
     * publisher's unit-of-work (EVENT) scope, which cannot be preempted. Time-boxing requires
     * off-thread execution — exactly what {@code async = true} provides (its own fresh scope).
     *
     * <p>Default: {@code ""} (no timeout — opt-in).
     *
     * @return an ISO-8601 duration string, or empty for no timeout
     */
    String timeout() default "";

    /**
     * Optional retry count for an asynchronous handler (#108). When greater than zero and the
     * handler throws, it is re-invoked up to this many times before the final failure is routed
     * to the {@link io.tiko.ErrorHandler} — so {@code retries = 3} means one initial call plus up
     * to three retries (four attempts total). On the first attempt that succeeds, no error is
     * routed; once the budget is exhausted a single {@link io.tiko.EventHandlerError} is routed
     * whose {@code attempts()} is the total number of attempts made.
     *
     * <p><strong>Requires {@code async = true}.</strong> Retries wait for {@link #backoff()}
     * between attempts; doing that on the publisher's thread would block it. Like {@link #timeout()},
     * a retry on a synchronous handler is a compile-time error. If both {@code timeout} and
     * {@code retries} are set, each attempt is time-boxed and a timed-out attempt counts as a
     * failed attempt. Errors (as opposed to exceptions) are never retried.
     *
     * <p><strong>Idempotency is the handler's responsibility</strong> — a retried handler may run
     * its side effects more than once.
     *
     * <p>Default: {@code 0} (no retries — opt-in).
     *
     * @return the number of retries after the initial attempt, or 0 for none
     */
    int retries() default 0;

    /**
     * Base delay between retry attempts, as an ISO-8601 {@link java.time.Duration} string
     * (e.g. {@code "PT0.1S"}). Scaled across attempts per {@link #backoffStrategy()}. Only
     * meaningful when {@link #retries()} is greater than zero.
     *
     * <p>Default: {@code ""} (retry immediately, no delay).
     *
     * @return an ISO-8601 duration string, or empty for no delay
     */
    String backoff() default "";

    /**
     * How {@link #backoff()} grows across successive retries. Only meaningful when
     * {@link #retries()} is greater than zero.
     *
     * <p>Default: {@link BackoffStrategy#FIXED}.
     *
     * @return the backoff growth strategy
     */
    BackoffStrategy backoffStrategy() default BackoffStrategy.FIXED;
}
