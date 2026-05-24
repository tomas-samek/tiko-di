package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.PreDestroy;

/** SINGLETON middle: depends on C. Destroyed second in LIFO. */
@Component(scope = Scope.SINGLETON)
public class LifoSingletonB {

    private final LifoSingletonC c;

    @Inject
    public LifoSingletonB(LifoSingletonC c) {
        this.c = c;
    }

    @PreDestroy
    public void destroy() {
        TeardownRecorder.record("SingletonB");
    }

    public LifoSingletonC getC() {
        return c;
    }
}
