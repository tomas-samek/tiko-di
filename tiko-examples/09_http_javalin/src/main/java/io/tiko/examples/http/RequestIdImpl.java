package io.tiko.examples.http;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import java.util.UUID;

/**
 * EVENT-scoped {@link RequestId}: each unit of work constructs a fresh
 * instance with its own UUID. Re-reading {@code value()} during the request
 * returns the same string.
 */
@Component(scope = Scope.EVENT)
public class RequestIdImpl implements RequestId {

    private final String value = UUID.randomUUID().toString();

    @Override
    public String value() {
        return value;
    }
}
