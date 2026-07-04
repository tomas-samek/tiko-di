package io.tiko.examples.kafka.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.examples.kafka.events.OrderPlaced;
import io.tiko.kafka.KafkaTransport;
import io.tiko.kafka.serializer.JsonKafkaSerializer;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.kafka.test.FakeKafkaTransport;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The #414 reference recipe, inbound half: a record produced onto the fake broker flows
 * through the app's generated {@code @KafkaSource} bridge into the local event chain.
 */
class FakeBrokerWarehouseConsumeIT {

    @TempDir
    Path tempDir;

    @AfterEach
    void resetProbeProperty() {
        System.clearProperty("probe.file");
    }

    @Test
    void recordOnOrdersTopicReachesTheWarehouseHandler() {
        Path probe = tempDir.resolve("warehouse.probe");
        System.setProperty("probe.file", probe.toString());

        FakeKafkaBroker broker = new FakeKafkaBroker();

        try (Container container = Tiko.create(TikoOptions.builder()
                .configSource(ConfigSources.classpath("application.yaml"))
                .replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker))
                .build())) {

            byte[] payload =
                    new JsonKafkaSerializer().serialize(new OrderPlaced("o-7", new BigDecimal("5.00"), Instant.now()));
            broker.produce("orders", payload);

            await().atMost(Duration.ofSeconds(10)).until(() -> Files.exists(probe));
            assertThat(Files.readAllLines(probe)).containsExactly("o-7");
        } catch (java.io.IOException e) {
            throw new AssertionError("probe file could not be read", e);
        }
    }
}
