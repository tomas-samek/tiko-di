package io.tiko.examples.events;

import io.tiko.EventTriggerGuard;

/**
 * Suppresses the shipping trigger when validation rejected the order.
 * Demonstrates the declarative-flow alternative to wrapping the publish in an if-else.
 */
public final class ValidOrderGuard implements EventTriggerGuard {
    @Override
    public boolean shouldTrigger(Object handlerResult, Object originalEvent) {
        return originalEvent instanceof OrderValidated v && v.valid();
    }
}
