package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.PreDestroy;

/** REQUEST-scoped middle bean: depends on C, so C is created before B. */
@Component(scope = Scope.REQUEST)
public class LifoRequestB {

    private final LifoRequestC c;

    @Inject
    public LifoRequestB(LifoRequestC c) {
        this.c = c;
    }

    @PreDestroy
    public void destroy() {
        TeardownRecorder.record("RequestB");
    }

    public LifoRequestC getC() {
        return c;
    }
}
