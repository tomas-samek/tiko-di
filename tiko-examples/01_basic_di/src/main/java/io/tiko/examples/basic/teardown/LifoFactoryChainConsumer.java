package io.tiko.examples.basic.teardown;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.Named;
import io.tiko.annotations.PreDestroy;

/**
 * Pure-{@code @Component} consumer that injects the {@code chain-middle} factory bean.
 * Used by {@code FactoryShutdownLifoTest} to pin that this consumer's {@code @PreDestroy}
 * fires BEFORE either factory bean is closed — the mixed-chain ordering contract from #189.
 */
@Component(scope = Scope.SINGLETON)
public class LifoFactoryChainConsumer {

    private final FakePool middle;

    @Inject
    public LifoFactoryChainConsumer(@Named("chain-middle") FakePool middle) {
        this.middle = middle;
    }

    @PreDestroy
    public void destroy() {
        TeardownRecorder.record("FactoryChainConsumer");
    }

    public FakePool getMiddle() {
        return middle;
    }
}
