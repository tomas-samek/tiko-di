package io.tiko.examples.basic.teardown;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the SINGLETON shutdown contract for {@code @Produces} factory-produced AutoCloseable
 * beans and mixed {@code @Component}↔factory dep chains (#189). After #151 the same LIFO
 * contract applies uniformly to component-to-component, factory-to-factory, and
 * component-to-factory dependency edges: a dependent is closed/destroyed before the dep it
 * relies on.
 */
class FactoryShutdownLifoTest {

    @BeforeEach
    void resetRecorder() {
        TeardownRecorder.reset();
    }

    @Test
    void factoryChainClosesInReverseDependencyOrder() {
        Container container = Tiko.create();
        try {
            // Triggering the consumer creates ChainConsumer -> chain-middle -> chain-base.
            // Creation chain: chain-base, chain-middle, ChainConsumer.
            container.get(LifoFactoryChainConsumer.class);
        } finally {
            container.shutdown();
        }

        List<String> chain = TeardownRecorder.order.stream()
                .filter(s -> s.equals("FactoryChainConsumer")
                        || s.equals("FakePool.chain-middle")
                        || s.equals("FakePool.chain-base"))
                .toList();
        // LIFO: dependent first. Consumer (depth 0) -> middle factory (depth 1) -> base factory (depth 2).
        assertThat(chain).containsExactly("FactoryChainConsumer", "FakePool.chain-middle", "FakePool.chain-base");
    }

    @Test
    void factoryDependingOnComponentClosesBeforeComponentDestroy() {
        Container container = Tiko.create();
        try {
            // Touching the factory bean ("needs-component" produced by LifoFactoryChainFactories)
            // pulls in LifoComponentDep — creation chain: LifoComponentDep, then the factory bean.
            container.get(FakePool.class, "needs-component");
        } finally {
            container.shutdown();
        }

        List<String> chain = TeardownRecorder.order.stream()
                .filter(s -> s.equals("FakePool.needs-component") || s.equals("LifoComponentDep"))
                .toList();
        // LIFO: factory bean (dependent) closes BEFORE the Component it depends on is destroyed.
        assertThat(chain).containsExactly("FakePool.needs-component", "LifoComponentDep");
    }
}
