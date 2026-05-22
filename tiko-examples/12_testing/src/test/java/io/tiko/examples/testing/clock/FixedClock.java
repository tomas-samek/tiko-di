package io.tiko.examples.testing.clock;

import io.tiko.examples.testing.domain.Clock;
import io.tiko.test.TestComponent;
import java.time.Instant;

@TestComponent
public class FixedClock extends Clock {
    public static final Instant FIXED = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public Instant now() {
        return FIXED;
    }
}
