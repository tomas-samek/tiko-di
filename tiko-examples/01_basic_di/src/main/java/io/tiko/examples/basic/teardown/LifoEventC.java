package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PreDestroy;

@Component(scope = Scope.EVENT)
public class LifoEventC {

    @PreDestroy
    public void destroy() {
        TeardownRecorder.record("EventC");
    }
}
