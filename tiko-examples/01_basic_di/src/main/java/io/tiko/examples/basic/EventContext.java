package io.tiko.examples.basic;

/**
 * Per-unit-of-work context: one instance per EVENT scope (an HTTP request, a consumed
 * message, a scheduled job, ...). Required for proxy generation when injected into SINGLETON scope.
 */
public interface EventContext {
    String getEventId();

    long getTimestamp();
}
