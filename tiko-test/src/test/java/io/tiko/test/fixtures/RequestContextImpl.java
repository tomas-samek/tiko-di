package io.tiko.test.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import java.util.UUID;

@Component(scope = Scope.EVENT)
public class RequestContextImpl implements RequestContext {

    private final String requestId = UUID.randomUUID().toString();

    @Override
    public String getRequestId() {
        return requestId;
    }
}
