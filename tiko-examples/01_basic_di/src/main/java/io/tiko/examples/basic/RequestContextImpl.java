package io.tiko.examples.basic;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import java.util.UUID;

/**
 * REQUEST-scoped context implementation.
 * One instance per request/transaction.
 *
 * When injected into SINGLETON scope, a proxy will be generated automatically.
 */
@Component(scope = Scope.EVENT)
public class RequestContextImpl implements RequestContext {

    private final String requestId;
    private final String userId;

    public RequestContextImpl() {
        this.requestId = "REQ-" + UUID.randomUUID().toString().substring(0, 8);
        this.userId = "user-" + System.currentTimeMillis();
        System.out.println("[RequestContext] Created for request: " + requestId);
    }

    @Override
    public String getRequestId() {
        return requestId;
    }

    @Override
    public String getUserId() {
        return userId;
    }

    @Override
    public String toString() {
        return "RequestContext{requestId='" + requestId + "', userId='" + userId + "'}";
    }
}
