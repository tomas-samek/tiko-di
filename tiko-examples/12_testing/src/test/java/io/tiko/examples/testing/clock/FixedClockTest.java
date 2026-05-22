package io.tiko.examples.testing.clock;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.examples.testing.domain.Clock;
import io.tiko.test.TikoTest;
import org.junit.jupiter.api.Test;

@TikoTest
class FixedClockTest {

    @Test
    void clockResolvesToTheTestComponentVariant(Clock clock) {
        assertThat(clock.now()).isEqualTo(FixedClock.FIXED);
    }
}
