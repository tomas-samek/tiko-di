package io.tiko.examples.basic.teardown;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.runtime.Tiko;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #687: container start constructs SINGLETON components, but a SINGLETON {@code @Produces} value
 * only on first use. {@code FakePool "primary"} is produced by the SINGLETON
 * {@code FakePoolFactories} and taken by no component, so a container that starts and stops
 * without resolving it never builds it and has nothing of it to close.
 */
class ProducedSingletonConstructionTest {

    @BeforeEach
    void resetRecorder() {
        TeardownRecorder.reset();
    }

    @Test
    void producedSingletonNothingResolvesIsNeverBuilt() {
        Tiko.create().shutdown();

        assertThat(TeardownRecorder.order).doesNotContain("FakePool.primary");
    }

    @Test
    void producedSingletonIsBuiltOnFirstResolve() {
        var container = Tiko.create();
        container.get(FakePool.class, "primary");
        container.shutdown();

        assertThat(TeardownRecorder.order).contains("FakePool.primary");
    }
}
