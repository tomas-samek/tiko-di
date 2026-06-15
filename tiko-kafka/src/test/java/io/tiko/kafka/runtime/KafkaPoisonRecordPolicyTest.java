package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.kafka.KafkaIngestError;
import io.tiko.runtime.Tiko;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/**
 * Poison-record policy (#313). A record whose ingest fails is, by default, sought back and
 * redelivered ({@code SEEK} — no data loss); opting into {@code SKIP} routes the
 * {@link KafkaIngestError} and commits past the record so the partition advances, without a
 * {@code null}-returning serializer workaround.
 */
class KafkaPoisonRecordPolicyTest {

    private static final TopicPartition P0 = new TopicPartition("t", 0);

    @Test
    void skipPolicyCommitsPastTheFailedRecordInsteadOfSeekingBack() {
        ScriptedConsumerClient client = new ScriptedConsumerClient(List.of(
                new ConsumerRecords<>(Map.of(P0, List.of(RunnerTestSupport.consumerRecord("t", 0, 0, "poison"))))));
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();

        try (Container container = Tiko.create()) {
            ThreadPerTopicRunner runner = new ThreadPerTopicRunner(
                    RunnerTestSupport.stringSource("t", payload -> payload),
                    client,
                    container,
                    container.getEventBus(),
                    errors::add,
                    RunnerTestSupport.UTF8,
                    RunnerTestSupport.config("SKIP"));
            runner.start();
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> client.commits.contains(Map.entry(P0, 1L)));
            } finally {
                runner.stop();
            }
        }

        assertThat(client.commits)
                .as("SKIP commits past the poison record (offset+1)")
                .contains(Map.entry(P0, 1L));
        assertThat(client.seeks).as("SKIP must not seek back").isEmpty();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).isInstanceOf(KafkaIngestError.class);
    }

    @Test
    void seekPolicyIsTheDefaultAndRedeliversTheFailedRecord() {
        ScriptedConsumerClient client = new ScriptedConsumerClient(List.of(
                new ConsumerRecords<>(Map.of(P0, List.of(RunnerTestSupport.consumerRecord("t", 0, 0, "poison"))))));
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();

        try (Container container = Tiko.create()) {
            ThreadPerTopicRunner runner = new ThreadPerTopicRunner(
                    RunnerTestSupport.stringSource("t", payload -> payload),
                    client,
                    container,
                    container.getEventBus(),
                    errors::add,
                    RunnerTestSupport.UTF8,
                    RunnerTestSupport.config()); // default SEEK
            runner.start();
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> !errors.isEmpty());
            } finally {
                runner.stop();
            }
        }

        assertThat(client.seeks)
                .as("SEEK (default) rewinds to the failed offset for redelivery")
                .contains(Map.entry(P0, 0L));
        assertThat(client.commits)
                .as("SEEK must not commit past the failed record")
                .isEmpty();
    }
}
