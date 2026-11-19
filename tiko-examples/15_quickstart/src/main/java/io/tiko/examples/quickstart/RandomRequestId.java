package io.tiko.examples.quickstart;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import java.util.UUID;

/**
 * EVENT-scoped: every request gets its own instance, and every component in that request sees
 * the same one. A SINGLETON that takes {@link RequestId} gets a proxy that resolves the current
 * unit's instance on each call.
 */
@Component(scope = Scope.EVENT)
public class RandomRequestId implements RequestId {

    private final String value = UUID.randomUUID().toString();

    @Override
    public String value() {
        return value;
    }
}
