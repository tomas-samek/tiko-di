package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.kafka.IngestDecision;
import io.tiko.kafka.KafkaIngestError;
import io.tiko.kafka.KafkaIngestErrorDecider;
import io.tiko.kafka.KafkaRecordDeadLettered;
import io.tiko.runtime.Tiko;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class KafkaIngestErrorDeciderTest {

    private static final TopicPartition P0 = new TopicPartition("t", 0);

    private static ConsumerRecords<String, byte[]> poisonBatch(long offset) {
        return new ConsumerRecords<>(Map.of(P0, List.of(RunnerTestSupport.consumerRecord("t", 0, offset, "poison"))));
    }

    private static ThreadPerTopicRunner runner(
            ScriptedConsumerClient client, Container container, List<ErrorContext> routed, KafkaIngestErrorDecider d) {
        return new ThreadPerTopicRunner(
                RunnerTestSupport.stringSource("t", payload -> payload),
                client,
                container,
                container.getEventBus(),
                routed::add,
                RunnerTestSupport.UTF8,
                RunnerTestSupport.config(),
                d);
    }

    @Test
    void retriesWhileTransientThenDeadLettersWithTheAttemptCount() {
        ScriptedConsumerClient client =
                new ScriptedConsumerClient(List.of(poisonBatch(0), poisonBatch(0), poisonBatch(0)));
        List<ErrorContext> routed = new CopyOnWriteArrayList<>();
        KafkaIngestErrorDecider decider =
                (error, attempt) -> attempt < 3 ? IngestDecision.SEEK : IngestDecision.DEAD_LETTER;

        try (Container container = Tiko.create()) {
            ThreadPerTopicRunner runner = runner(client, container, routed, decider);
            runner.start();
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> client.commits.contains(Map.entry(P0, 1L)));
            } finally {
                runner.stop();
            }
        }

        assertThat(client.seeks)
                .as("attempts 1 and 2 seek back at the failed offset")
                .containsExactly(Map.entry(P0, 0L), Map.entry(P0, 0L));
        assertThat(client.commits).as("dead-letter commits past the record").contains(Map.entry(P0, 1L));
        KafkaRecordDeadLettered dl = routed.stream()
                .filter(KafkaRecordDeadLettered.class::isInstance)
                .map(KafkaRecordDeadLettered.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no KafkaRecordDeadLettered routed: " + routed));
        assertThat(dl.attempts()).isEqualTo(3);
        assertThat(dl.offset()).isZero();
    }

    @Test
    void skipDecisionCommitsPastAndRoutesTheIngestError() {
        ScriptedConsumerClient client = new ScriptedConsumerClient(List.of(poisonBatch(0)));
        List<ErrorContext> routed = new CopyOnWriteArrayList<>();

        try (Container container = Tiko.create()) {
            ThreadPerTopicRunner runner = runner(client, container, routed, (error, attempt) -> IngestDecision.SKIP);
            runner.start();
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> client.commits.contains(Map.entry(P0, 1L)));
            } finally {
                runner.stop();
            }
        }

        assertThat(client.seeks).as("SKIP must not seek").isEmpty();
        assertThat(routed).hasSize(1).allSatisfy(e -> assertThat(e).isInstanceOf(KafkaIngestError.class));
    }

    @Test
    void failDecisionStopsTheRunnerAndLeavesTheOffsetUncommitted() {
        // A good record at offset 1 in a second batch would commit(offset+1=2) if the runner kept
        // going — so empty commits proves FAIL stopped the loop before that batch was ever polled.
        ScriptedConsumerClient client = new ScriptedConsumerClient(List.of(
                poisonBatch(0),
                new ConsumerRecords<>(Map.of(P0, List.of(RunnerTestSupport.consumerRecord("t", 0, 1, "ok"))))));
        List<ErrorContext> routed = new CopyOnWriteArrayList<>();

        try (Container container = Tiko.create()) {
            ThreadPerTopicRunner runner = runner(client, container, routed, (error, attempt) -> IngestDecision.FAIL);
            runner.start();
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> !routed.isEmpty());
            } finally {
                runner.stop();
            }
        }

        assertThat(client.commits)
                .as("FAIL stops the runner: the offset is uncommitted AND the next batch is never processed")
                .isEmpty();
        assertThat(client.seeks).as("FAIL does not seek").isEmpty();
        assertThat(routed).allSatisfy(e -> assertThat(e).isInstanceOf(KafkaIngestError.class));
    }

    @Test
    void aThrowingDeciderFallsBackToSeekAndKeepsTheThreadAlive() {
        ScriptedConsumerClient client = new ScriptedConsumerClient(List.of(poisonBatch(0)));
        List<ErrorContext> routed = new CopyOnWriteArrayList<>();

        try (Container container = Tiko.create()) {
            ThreadPerTopicRunner runner = runner(client, container, routed, (error, attempt) -> {
                throw new RuntimeException("decider boom");
            });
            runner.start();
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> client.seeks.contains(Map.entry(P0, 0L)));
            } finally {
                runner.stop();
            }
        }

        assertThat(client.commits).as("SEEK fallback must not commit").isEmpty();
    }

    @Test
    void attemptCountIsPerOffsetAndResetsWhenTheOffsetChanges() {
        ScriptedConsumerClient client =
                new ScriptedConsumerClient(List.of(poisonBatch(0), poisonBatch(0), poisonBatch(1)));
        List<ErrorContext> routed = new CopyOnWriteArrayList<>();
        List<Integer> attemptsSeen = new CopyOnWriteArrayList<>();

        try (Container container = Tiko.create()) {
            ThreadPerTopicRunner runner = runner(client, container, routed, (error, attempt) -> {
                attemptsSeen.add(attempt);
                return IngestDecision.SEEK;
            });
            runner.start();
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> attemptsSeen.size() >= 3);
            } finally {
                runner.stop();
            }
        }

        assertThat(attemptsSeen)
                .as("offset 0 fails twice (1,2); a new offset restarts the count at 1")
                .startsWith(1, 2, 1);
    }
}
