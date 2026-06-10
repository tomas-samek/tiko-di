package io.tiko.test.fixtures;

/** Interface contract for the EVENT-scoped {@link RequestContextImpl}, required for proxying. */
public interface RequestContext {
    String getRequestId();
}
