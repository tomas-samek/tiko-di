package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.PreDestroy;

@Component(scope = Scope.EVENT)
public class LifoEventA {

    private final LifoEventB b;

    @Inject
    public LifoEventA(LifoEventB b) {
        this.b = b;
    }

    @PreDestroy
    public void destroy() {
        TeardownRecorder.record("EventA");
    }
}
