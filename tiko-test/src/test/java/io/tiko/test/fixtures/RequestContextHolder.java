package io.tiko.test.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;

/** SINGLETON holding an EVENT-scoped dependency through the generated cross-scope proxy. */
@Component(scope = Scope.SINGLETON)
public class RequestContextHolder {

    private final RequestContext context;

    @Inject
    public RequestContextHolder(RequestContext context) {
        this.context = context;
    }

    public String currentRequestId() {
        return context.getRequestId();
    }
}
