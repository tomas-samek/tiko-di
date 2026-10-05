package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The SEEK backoff (#478) doubles per consecutive failure of the same record, capped at the max. */
class SeekBackoffDelayTest {

    private static final Duration HALF_SECOND = Duration.ofMillis(500);
    private static final Duration THIRTY_SECONDS = Duration.ofSeconds(30);

    static Stream<Arguments> delays() {
        return Stream.of(
                Arguments.of("first failure waits the initial delay", 1, HALF_SECOND, THIRTY_SECONDS, HALF_SECOND),
                Arguments.of("second failure doubles", 2, HALF_SECOND, THIRTY_SECONDS, Duration.ofSeconds(1)),
                Arguments.of("third failure doubles again", 3, HALF_SECOND, THIRTY_SECONDS, Duration.ofSeconds(2)),
                Arguments.of("growth stops at the cap", 7, HALF_SECOND, THIRTY_SECONDS, THIRTY_SECONDS),
                Arguments.of(
                        "huge attempt counts stay at the cap", 10_000, HALF_SECOND, THIRTY_SECONDS, THIRTY_SECONDS),
                Arguments.of("zero initial disables the backoff", 5, Duration.ZERO, THIRTY_SECONDS, Duration.ZERO),
                Arguments.of(
                        "a cap below the initial wins",
                        1,
                        HALF_SECOND,
                        Duration.ofMillis(100),
                        Duration.ofMillis(100)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("delays")
    void backoffDoublesPerAttemptUpToTheCap(
            String name, int attempt, Duration initial, Duration max, Duration expected) {
        assertThat(ThreadPerTopicRunner.seekBackoff(attempt, initial, max)).isEqualTo(expected);
    }
}
