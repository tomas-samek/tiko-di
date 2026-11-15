package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PreDestroy;

/** An EVENT component with a {@code @PreDestroy} of its own, and a subclass that adds another. */
@Component(scope = Scope.EVENT)
public class BaseUnitBean {

    @PreDestroy
    public void baseBye() {
        TeardownRecorder.record(getClass().getSimpleName() + ".baseBye");
    }
}
