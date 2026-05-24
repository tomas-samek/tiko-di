package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PreDestroy;

/** SINGLETON leaf: depends on nothing. Destroyed last in LIFO. */
@Component(scope = Scope.SINGLETON)
public class LifoSingletonC {

    @PreDestroy
    public void destroy() {
        TeardownRecorder.record("SingletonC");
    }
}
