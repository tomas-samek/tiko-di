package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.PreDestroy;

@Component(scope = Scope.EVENT)
public class LifoEventB {

    private final LifoEventC c;

    @Inject
    public LifoEventB(LifoEventC c) {
        this.c = c;
    }

    @PreDestroy
    public void destroy() {
        TeardownRecorder.record("EventB");
    }
}
