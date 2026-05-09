package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PreDestroy;

/** Leaf REQUEST-scoped bean: no dependencies. Destroyed last in LIFO. */
@Component(scope = Scope.REQUEST)
public class LifoRequestC {

    @PreDestroy
    public void destroy() {
        TeardownRecorder.record("RequestC");
    }
}
