package io.tiko.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.config.BindContext;
import io.tiko.generated.config.KafkaConfigBinder;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The SEEK redelivery backoff (#478) binds from {@code tiko.kafka.seek-backoff[-max]} with safe defaults. */
class KafkaConfigSeekBackoffBindingTest {

    private static KafkaConfig bind(Map<String, Object> kafka) {
        var section = new HashMap<String, Object>(kafka);
        section.putIfAbsent("producer-properties", Map.of());
        section.putIfAbsent("consumer-properties", Map.of());
        return new KafkaConfigBinder().bind(Map.of("tiko", Map.of("kafka", section)), new BindContext("test"));
    }

    @Test
    void seekBackoffDefaultsToHalfASecondCappedAtThirtySeconds() {
        var config = bind(Map.of());

        assertThat(config.seekBackoff()).isEqualTo(Duration.ofMillis(500));
        assertThat(config.seekBackoffMax()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void seekBackoffIsConfigurable() {
        var config = bind(Map.of("seek-backoff", "PT0S", "seek-backoff-max", "PT2M"));

        assertThat(config.seekBackoff()).isEqualTo(Duration.ZERO);
        assertThat(config.seekBackoffMax()).isEqualTo(Duration.ofMinutes(2));
    }
}
