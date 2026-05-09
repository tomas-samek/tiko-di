package io.tiko.examples.basic;

/**
 * Interface for EVENT-scoped context.
 * Required for proxy generation when injected into SINGLETON/REQUEST scope.
 */
public interface EventContext {
    String getEventId();

    long getTimestamp();
}
