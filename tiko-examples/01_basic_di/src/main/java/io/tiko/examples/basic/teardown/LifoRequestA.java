package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.PreDestroy;

/** REQUEST-scoped root bean: depends on B (which depends on C). Destroyed first in LIFO. */
@Component(scope = Scope.REQUEST)
public class LifoRequestA {

    private final LifoRequestB b;

    @Inject
    public LifoRequestA(LifoRequestB b) {
        this.b = b;
    }

    @PreDestroy
    public void destroy() {
        TeardownRecorder.record("RequestA");
    }

    public LifoRequestB getB() {
        return b;
    }
}
