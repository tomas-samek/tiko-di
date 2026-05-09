package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PreDestroy;

/**
 * REQUEST-scoped bean whose {@code @PreDestroy} throws. Used to verify that a
 * failing destroy hook does not skip the rest of the scope's teardown.
 */
@Component(scope = Scope.REQUEST)
public class ThrowingPreDestroyRequestBean {

    @PreDestroy
    public void boom() {
        TeardownRecorder.record("Throwing.boom");
        throw new IllegalStateException("intentional");
    }
}
