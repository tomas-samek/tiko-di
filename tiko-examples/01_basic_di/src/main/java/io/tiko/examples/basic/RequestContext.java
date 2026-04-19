package io.tiko.examples.basic;

/**
 * Interface for REQUEST-scoped context.
 * Required for proxy generation when injected into SINGLETON scope.
 */
public interface RequestContext {
    String getRequestId();
    String getUserId();
}
