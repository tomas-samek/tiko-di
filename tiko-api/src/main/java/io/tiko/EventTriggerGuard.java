package io.tiko;

/**
 * Guard interface for conditionally triggering events.
 *
 * <p>Guards are used with {@link io.tiko.annotations.EventTrigger} to prevent event triggering
 * based on specific conditions. This allows declarative control flow in
 * event chains.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * public class OnlyIfSuccessfulGuard implements EventTriggerGuard {
 *     @Override
 *     public boolean shouldTrigger(Object handlerResult, Object originalEvent) {
 *         return handlerResult instanceof SuccessResult;
 *     }
 * }
 *
 * @Component(scope = Scope.SINGLETON)
 * public class OrderService {
 *     @EventHandler
 *     @EventTrigger(eventName = "order.notification", guard = OnlyIfSuccessfulGuard.class)
 *     public Result processOrder(OrderCreatedEvent event) {
 *         // Only triggers notification if result indicates success
 *         return process(event.order());
 *     }
 * }
 * }</pre>
 */
@FunctionalInterface
public interface EventTriggerGuard {

    /**
     * Determines whether the event should be triggered.
     *
     * @param handlerResult the return value from the event handler method
     * @param originalEvent the original event that triggered the handler
     * @return true if the event should be triggered, false otherwise
     */
    boolean shouldTrigger(Object handlerResult, Object originalEvent);

    /**
     * Default guard that always allows triggering.
     */
    class AlwaysAllow implements EventTriggerGuard {
        @Override
        public boolean shouldTrigger(Object handlerResult, Object originalEvent) {
            return true;
        }
    }

    /**
     * Guard that only triggers if handler result is non-null.
     */
    class OnlyIfNonNull implements EventTriggerGuard {
        @Override
        public boolean shouldTrigger(Object handlerResult, Object originalEvent) {
            return handlerResult != null;
        }
    }
}
