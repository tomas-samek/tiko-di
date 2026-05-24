package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PreDestroy;

/**
 * {@code @Component} with a {@code @PreDestroy} hook, used as the deepest dep in the
 * factory→Component chain for {@code FactoryShutdownLifoTest}. Pins the "vice versa"
 * direction of #189: a factory's produced bean is closed BEFORE this component's
 * {@code @PreDestroy} fires.
 */
@Component(scope = Scope.SINGLETON)
public class LifoComponentDep {

    @PreDestroy
    public void destroy() {
        TeardownRecorder.record("LifoComponentDep");
    }
}
