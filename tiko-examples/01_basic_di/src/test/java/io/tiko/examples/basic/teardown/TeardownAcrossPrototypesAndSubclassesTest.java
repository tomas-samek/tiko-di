package io.tiko.examples.basic.teardown;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Teardown order and hook selection where the component graph is not a plain SINGLETON chain:
 * a dependency that passes through a PROTOTYPE (#739) and a component that extends another (#742).
 */
class TeardownAcrossPrototypesAndSubclassesTest {

    @BeforeEach
    void resetRecorder() {
        TeardownRecorder.reset();
    }

    @Test
    void singletonIsTornDownBeforeASingletonItReachesThroughAPrototype() {
        try (Container container = Tiko.create()) {
            container.get(ChainedAService.class);
        }

        assertThat(TeardownRecorder.order)
                .filteredOn(entry -> entry.startsWith("Chained"))
                .as("the service closes while the pool it reaches through its helper is still open")
                .containsExactly("ChainedAService.close(poolClosed=false)", "ChainedPool.close");
    }

    @Test
    void eventComponentRunsItsOwnAndItsInheritedPreDestroyEachOnce() {
        try (Container container = Tiko.create()) {
            container.runInEventScope(() -> {
                container.get(BaseUnitBean.class);
                container.get(DerivedUnitBean.class);
            });
        }

        assertThat(TeardownRecorder.order)
                .filteredOn(entry -> entry.contains("UnitBean."))
                .as("LIFO across the unit; within the subclass, its own hook first, then the inherited one")
                .containsExactly("DerivedUnitBean.derivedBye", "DerivedUnitBean.baseBye", "BaseUnitBean.baseBye");
    }
}
