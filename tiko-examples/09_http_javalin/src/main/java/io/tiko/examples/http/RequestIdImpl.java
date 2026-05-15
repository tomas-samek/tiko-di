package io.tiko.examples.http;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import java.util.UUID;

/**
 * REQUEST-scoped {@link RequestId}: each scope entry constructs a fresh
 * instance with its own UUID. Re-reading {@code value()} during the request
 * returns the same string.
 */
@Component(scope = Scope.REQUEST)
public class RequestIdImpl implements RequestId {

    private final String value = UUID.randomUUID().toString();

    @Override
    public String value() {
        return value;
    }
}
