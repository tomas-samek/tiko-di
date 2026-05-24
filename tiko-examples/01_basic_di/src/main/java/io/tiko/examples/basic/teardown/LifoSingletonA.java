package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.PreDestroy;

/** SINGLETON root: depends on B (which depends on C). Destroyed first in LIFO. */
@Component(scope = Scope.SINGLETON)
public class LifoSingletonA {

    private final LifoSingletonB b;

    @Inject
    public LifoSingletonA(LifoSingletonB b) {
        this.b = b;
    }

    @PreDestroy
    public void destroy() {
        TeardownRecorder.record("SingletonA");
    }

    public LifoSingletonB getB() {
        return b;
    }
}
