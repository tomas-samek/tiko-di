package io.tiko.examples.kafka.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.ErrorContext;
import io.tiko.config.ConfigSources;
import io.tiko.examples.kafka.events.OrderPlaced;
import io.tiko.kafka.KafkaRecordDeadLettered;
import io.tiko.kafka.KafkaTransport;
import io.tiko.kafka.serializer.JsonKafkaSerializer;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.kafka.test.FakeKafkaTransport;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #692: a record whose bridge fails permanently, followed by a valid record on the same partition (the
 * fake broker has one). Under the default {@code SEEK} policy the rejected order would be redelivered
 * forever and the valid one would never arrive; {@link WarehouseIngestPolicy} dead-letters it instead.
 */
class RejectedOrderDoesNotBlockPartitionIT {

    @TempDir
    Path tempDir;

    @AfterEach
    void resetProbeProperty() {
        System.clearProperty("probe.file");
    }

    @Test
    void validOrderBehindARejectedOneIsProcessedAndTheRejectedOneIsDeadLettered() throws IOException {
        var probe = tempDir.resolve("warehouse.probe");
        System.setProperty("probe.file", probe.toString());
        var broker = new FakeKafkaBroker();
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();

        try (var container = Tiko.create(TikoOptions.builder()
                .configSource(ConfigSources.classpath("application.yaml"))
                .replaceTransport(KafkaTransport.class, t -> FakeKafkaTransport.over(t, broker))
                .errorHandler(errors::add)
                .build())) {
            var serializer = new JsonKafkaSerializer();
            broker.produce("orders", serializer.serialize(new OrderPlaced("o-bad", BigDecimal.ZERO, Instant.now())));
            broker.produce(
                    "orders", serializer.serialize(new OrderPlaced("o-ok", new BigDecimal("5.00"), Instant.now())));

            await().atMost(Duration.ofSeconds(10)).until(() -> Files.exists(probe));
        }

        assertThat(Files.readAllLines(probe)).containsExactly("o-ok");
        assertThat(errors).singleElement().isInstanceOfSatisfying(KafkaRecordDeadLettered.class, dead -> {
            assertThat(dead.offset()).isZero();
            assertThat(dead.cause()).isInstanceOf(RejectedOrderException.class);
        });
    }
}
