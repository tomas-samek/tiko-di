package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PreDestroy;

/** Runs its own {@code @PreDestroy} and then the one it inherits from {@link BaseUnitBean}. */
@Component(scope = Scope.EVENT)
public class DerivedUnitBean extends BaseUnitBean {

    @PreDestroy
    public void derivedBye() {
        TeardownRecorder.record(getClass().getSimpleName() + ".derivedBye");
    }
}
