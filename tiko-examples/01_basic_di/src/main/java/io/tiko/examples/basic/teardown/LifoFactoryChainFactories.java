package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Named;
import io.tiko.annotations.Produces;

/**
 * Two {@code @Produces} factory methods returning {@link FakePool} where
 * {@code chain-middle} depends on {@code chain-base}. Used by
 * {@code FactoryShutdownLifoTest} to pin that the unified dep-graph topo-sort
 * (#189) destroys {@code chain-middle} before {@code chain-base}.
 */
@Component(scope = Scope.SINGLETON)
public class LifoFactoryChainFactories {

    @Produces(scope = Scope.SINGLETON, name = "chain-base")
    public FakePool chainBase() {
        return new FakePool("chain-base");
    }

    @Produces(scope = Scope.SINGLETON, name = "chain-middle")
    public FakePool chainMiddle(@Named("chain-base") FakePool base) {
        return new FakePool("chain-middle");
    }

    /** Factory whose method-parameter is a {@code @Component} — pins the factory→component
     *  direction of #189: this produced bean must close BEFORE {@code LifoComponentDep}'s
     *  {@code @PreDestroy} fires. */
    @Produces(scope = Scope.SINGLETON, name = "needs-component")
    public FakePool factoryNeedingComponent(LifoComponentDep dep) {
        return new FakePool("needs-component");
    }
}
