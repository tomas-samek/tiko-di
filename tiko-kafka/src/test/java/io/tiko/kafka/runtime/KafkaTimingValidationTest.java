package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.kafka.KafkaConfig;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Timing settings that would let a failing record or a failing poll repeat without a time bound
 * are rejected when the consumer runner is created, i.e. at startup (SEC-11, #495). The message
 * names the key, not the configured value.
 */
class KafkaTimingValidationTest {

    static Stream<Arguments> rejected() {
        return Stream.of(
                Arguments.of("zero poll-timeout", "PT0S", "PT0.5S", "PT30S", "tiko.kafka.poll-timeout"),
                Arguments.of("negative poll-timeout", "PT-1S", "PT0.5S", "PT30S", "tiko.kafka.poll-timeout"),
                Arguments.of("negative seek-backoff", "PT0.5S", "PT-1S", "PT30S", "tiko.kafka.seek-backoff"),
                Arguments.of("negative seek-backoff-max", "PT0.5S", "PT0.5S", "PT-1S", "tiko.kafka.seek-backoff-max"),
                Arguments.of("cap below the backoff", "PT0.5S", "PT0.5S", "PT0S", "tiko.kafka.seek-backoff-max"));
    }

    static Stream<Arguments> accepted() {
        return Stream.of(
                Arguments.of("defaults", "PT0.5S", "PT0.5S", "PT30S"),
                Arguments.of("seek-backoff PT0S opts out of backoff", "PT0.5S", "PT0S", "PT0S"),
                Arguments.of("backoff equal to its cap", "PT0.5S", "PT2S", "PT2S"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void unboundedTimingFailsAtStartupNamingTheKey(
            String name, String pollTimeout, String seekBackoff, String seekBackoffMax, String key) {
        var config = config(pollTimeout, seekBackoff, seekBackoffMax);

        assertThatThrownBy(() -> runner(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(key)
                .hasMessageNotContaining("PT-1S")
                .hasMessageNotContaining("PT0S");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("accepted")
    void boundedTimingIsAccepted(String name, String pollTimeout, String seekBackoff, String seekBackoffMax) {
        var config = config(pollTimeout, seekBackoff, seekBackoffMax);

        assertThatCode(() -> runner(config)).doesNotThrowAnyException();
    }

    private static ThreadPerTopicRunner runner(KafkaConfig config) {
        return new ThreadPerTopicRunner(
                RunnerTestSupport.stringSource("orders", s -> s),
                null,
                null,
                null,
                null,
                RunnerTestSupport.UTF8,
                config,
                null);
    }

    private static KafkaConfig config(String pollTimeout, String seekBackoff, String seekBackoffMax) {
        return new KafkaConfig(
                "unused:9092",
                "g",
                "json",
                "earliest",
                Duration.parse(pollTimeout),
                Duration.ofSeconds(2),
                Map.of(),
                Map.of(),
                "SEEK",
                Duration.parse(seekBackoff),
                Duration.parse(seekBackoffMax));
    }
}
