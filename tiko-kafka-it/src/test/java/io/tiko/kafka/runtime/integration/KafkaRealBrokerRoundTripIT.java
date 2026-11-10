package io.tiko.kafka.runtime.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.config.ConfigSources;
import io.tiko.kafka.KafkaConfig;
import io.tiko.kafka.runtime.integration.fixtures.ItOrderPlaced;
import io.tiko.kafka.runtime.integration.fixtures.ItOrderReceived;
import io.tiko.kafka.runtime.integration.fixtures.ItOrderRecorder;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Exercises the generated sink and source against a real Kafka broker. The sink sends
 * {@link ItOrderPlaced} to Kafka, and the source turns the consumed payload into
 * {@link ItOrderReceived}, the event observed by the recorder.
 */
@Testcontainers(disabledWithoutDocker = true)
class KafkaRealBrokerRoundTripIT {

    /**
     * The Apache native broker image, at the kafka-clients version, starts in about two seconds on a
     * CI runner. A start that stalls is retried on a fresh container instead of failing the build
     * (#581).
     */
    @org.testcontainers.junit.jupiter.Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.9.2")).withStartupAttempts(3);

    @Test
    void orderRoundTripsThroughRealBroker() {
        var bootstrapServers = KAFKA.getBootstrapServers();
        var consumerGroup = "kafka-it";
        var configSource = ConfigSources.fromMap(Map.of(
                "tiko",
                Map.of("kafka", Map.of("bootstrap-servers", bootstrapServers, "consumer-group", consumerGroup))));

        var outlived = new CopyOnWriteArrayList<Thread>();
        var options = TikoOptions.builder()
                .configSource(configSource)
                .onThreadsOutlivingClose(outlived::addAll)
                .build();

        try (var container = Tiko.create(options)) {
            var config = container.get(KafkaConfig.class);
            assertThat(config.bootstrapServers()).as("Kafka bootstrap servers").isEqualTo(bootstrapServers);
            assertThat(config.consumerGroup()).as("Kafka consumer group").isEqualTo(consumerGroup);

            container.getEventBus().publish(new ItOrderPlaced("real-1", 99));

            var recorder = container.get(ItOrderRecorder.class);
            await().alias("order received from Kafka")
                    .atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(recorder.received).contains(new ItOrderReceived("real-1", 99)));
        }

        // A clean close leaves no consumer thread behind (#635).
        assertThat(outlived).as("threads outliving close()").isEmpty();
        assertThat(Thread.getAllStackTraces().keySet())
                .as("live consumer threads")
                .noneMatch(t -> t.isAlive() && t.getName().startsWith("tiko-kafka-consumer-"));
    }
}
