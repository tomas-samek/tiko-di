package io.tiko.examples.testing.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.test.TikoTest;
import org.junit.jupiter.api.Test;

/**
 * This module's test tree declares components, so its {@code @TikoTest} container is a test-wiring
 * container. Such a container does not construct its SINGLETONs when it starts; each is built on first use.
 * A SINGLETON that handles {@code ApplicationStartedEvent} is the exception, because delivering that event
 * at start resolves it.
 *
 * <p>The counters are static and other tests boot containers too, so each test compares the count around
 * its own lookup instead of expecting an absolute value.
 */
@TikoTest
class SingletonConstructionTest {

    @Test
    void singletonIsConstructedOnFirstUseNotWhenTheContainerStarts(Container container) {
        var constructedBeforeTheLookup = OnDemandSingleton.constructed();

        container.get(OnDemandSingleton.class);

        assertThat(OnDemandSingleton.constructed())
                .as("the lookup constructs the SINGLETON, so starting the container did not")
                .isEqualTo(constructedBeforeTheLookup + 1);
    }

    @Test
    void singletonHandlingApplicationStartedEventIsConstructedWhenTheContainerStarts(Container container) {
        var constructedBeforeTheLookup = StartedSingleton.constructed();

        container.get(StartedSingleton.class);

        assertThat(StartedSingleton.constructed())
                .as("the lookup finds the instance the start already constructed")
                .isPositive()
                .isEqualTo(constructedBeforeTheLookup);
    }
}
