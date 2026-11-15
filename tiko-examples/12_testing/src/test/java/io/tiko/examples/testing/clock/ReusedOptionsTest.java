package io.tiko.examples.testing.clock;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.examples.testing.domain.Clock;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import org.junit.jupiter.api.Test;

/**
 * #734: built {@code TikoOptions} are immutable, so one object boots any number of containers. This
 * module's test tree shadows {@link Clock} with {@link FixedClock}; applying that shadow must not
 * write into the options the caller passed.
 */
class ReusedOptionsTest {

    @Test
    void oneOptionsObjectBootsTwoIndependentTestWiringContainers() {
        TikoOptions options = TikoOptions.builder().testWiring(true).build();

        Clock first;
        try (Container container = Tiko.create(options)) {
            first = container.get(Clock.class);
        }
        assertThat(options.hasOverride(Clock.class))
                .as("Tiko.create does not change the options it is given")
                .isFalse();

        Clock second;
        try (Container container = Tiko.create(options)) {
            second = container.get(Clock.class);
        }

        assertThat(first).isInstanceOf(FixedClock.class);
        assertThat(second)
                .as("the second container resolves its own test component")
                .isInstanceOf(FixedClock.class)
                .isNotSameAs(first);
    }
}
