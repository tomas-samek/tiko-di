package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.runtime.fixtures.AuditKafkaConsumer;
import io.tiko.kafka.runtime.fixtures.AuditPayload;
import io.tiko.kafka.runtime.fixtures.AuditRecorded;
import io.tiko.kafka.runtime.fixtures.AuditRecorder;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.runtime.Tiko;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class KafkaContextInjectionTest {

    @Test
    void second_parameter_receives_kafka_context() {
        FakeKafkaBroker broker = new FakeKafkaBroker();

        List<GeneratedSourceDescriptor> sources = List.of(new GeneratedSourceDescriptor(
                "audits",
                "",
                "AuditRecorded",
                AuditPayload.class,
                KafkaSerializer.Default.class,
                true,
                (container, payload, ctx) ->
                        container.get(AuditKafkaConsumer.class).fromKafka((AuditPayload) payload, ctx)));

        try (Container container = Tiko.create();
                TestKafkaBootstrap bootstrap = TestKafkaBootstrap.start(container, broker, sources, List.of())) {

            broker.produce(
                    "audits",
                    "{\"id\":\"a-1\",\"action\":\"login\"}".getBytes(StandardCharsets.UTF_8),
                    "X-Correlation-Id",
                    "trace-42");

            AuditRecorder recorder = container.get(AuditRecorder.class);
            await().atMost(Duration.ofSeconds(3)).until(() -> !recorder.received.isEmpty());

            AuditRecorded got = recorder.received.get(0);
            assertThat(got).isEqualTo(new AuditRecorded("a-1", "login", "trace-42"));
        }
    }
}
