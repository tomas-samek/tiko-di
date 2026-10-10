package io.tiko.generated.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.config.BindContext;
import io.tiko.config.ConfigBinder;
import io.tiko.kafka.KafkaConfig;
import java.time.Duration;
import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

/**
 * #531: tiko-kafka's binder is found as a {@code ConfigBinder} service (merged by a fat jar's
 * {@code ServicesResourceTransformer}), and binds even when the jar's {@code defaults.yaml} was
 * not the copy a fat jar kept.
 */
class KafkaConfigBinderTest {

    @Test
    void isListedAsAConfigBinderService() {
        assertThat(ServiceLoader.load(ConfigBinder.class))
                .anySatisfy(b -> assertThat(b).isInstanceOf(KafkaConfigBinder.class));
    }

    @Test
    void bindsTheRecordDefaultsWithoutATikoKafkaSection() {
        var ctx = new BindContext("config");

        KafkaConfig config = new KafkaConfigBinder().bind(Map.of(), ctx);

        assertThat(ctx.issues()).isEmpty();
        assertThat(config.bootstrapServers()).isEqualTo("localhost:9092");
        assertThat(config.pollTimeout()).isEqualTo(Duration.ofMillis(500));
    }
}
