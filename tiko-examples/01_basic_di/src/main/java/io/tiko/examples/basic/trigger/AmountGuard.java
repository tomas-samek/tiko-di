package io.tiko.examples.basic.trigger;

import io.tiko.EventTriggerGuard;

/**
 * Allows a trigger only when the handler returned a {@link GuardedResult} whose amount
 * exceeds $100. Used by {@code EventTriggerTest} to verify guard suppression.
 */
public final class AmountGuard implements EventTriggerGuard {
    @Override
    public boolean shouldTrigger(Object handlerResult, Object originalEvent) {
        return handlerResult instanceof GuardedResult result && result.amount() > 100.0;
    }
}
