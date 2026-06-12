package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.kafka.runtime.fixtures.GatedAsyncRecorder;
import io.tiko.kafka.runtime.fixtures.GatedPing;
import io.tiko.runtime.Tiko;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/**
 * Pins the delivery contract at the handler boundary (#341): the Kafka offset
 * acknowledges successful delivery TO THE BUS, not handler outcome. Handler execution is
 * at-most-once per delivery — a failing sync handler routes to the ErrorHandler and the
 * offset still commits (no redelivery), and an async handler's commit precedes its
 * execution entirely. The redelivery boundary is the bridge: deserialize/dispatch/publish
 * failures seek back (pinned by {@code KafkaIngestErrorTest}); handler-side recovery is
 * the dead-letter story tracked in #111.
 */
class KafkaHandlerCommitSemanticsTest {

    @Test
    void offsetCommitsEvenWhenEverySyncHandlerThrows() {
        TopicPartition p0 = new TopicPartition("commit-sync", 0);
        ScriptedConsumerClient client = new ScriptedConsumerClient(List.of(new ConsumerRecords<>(
                Map.of(p0, List.of(RunnerTestSupport.consumerRecord("commit-sync", 0, 0, "ok"))))));
        List<ErrorContext> ingestErrors = new CopyOnWriteArrayList<>();
        List<Object> delivered = new CopyOnWriteArrayList<>();

        try (Container container = Tiko.create()) {
            container.getEventBus().subscribe(String.class, e -> {
                delivered.add(e);
                throw new IllegalStateException("sync handler failure");
            });
            ThreadPerTopicRunner runner = new ThreadPerTopicRunner(
                    RunnerTestSupport.stringSource("commit-sync", payload -> payload),
                    client,
                    container,
                    container.getEventBus(),
                    ingestErrors::add,
                    RunnerTestSupport.UTF8,
                    RunnerTestSupport.config());
            runner.start();
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> client.commits.contains(Map.entry(p0, 1L)));
            } finally {
                runner.stop();
            }
        }

        assertThat(delivered).containsExactly("ok");
        assertThat(client.seeks)
                .as("handler failure must not trigger redelivery")
                .isEmpty();
        assertThat(ingestErrors)
                .as("handler failure is an event-bus concern, not a transport ingest error")
                .isEmpty();
    }

    @Test
    void offsetCommitsBeforeAsyncHandlerExecutes() {
        TopicPartition p0 = new TopicPartition("commit-async", 0);
        ScriptedConsumerClient client = new ScriptedConsumerClient(List.of(new ConsumerRecords<>(
                Map.of(p0, List.of(RunnerTestSupport.consumerRecord("commit-async", 0, 0, "g-1"))))));
        List<ErrorContext> ingestErrors = new CopyOnWriteArrayList<>();

        try (Container container = Tiko.create()) {
            GatedAsyncRecorder recorder = container.get(GatedAsyncRecorder.class);
            ThreadPerTopicRunner runner = new ThreadPerTopicRunner(
                    RunnerTestSupport.stringSource("commit-async", GatedPing::new),
                    client,
                    container,
                    container.getEventBus(),
                    ingestErrors::add,
                    RunnerTestSupport.UTF8,
                    RunnerTestSupport.config());
            runner.start();
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> client.commits.contains(Map.entry(p0, 1L)));
                assertThat(recorder.received)
                        .as("commit must not wait for the gated async handler")
                        .isEmpty();
            } finally {
                recorder.gate.countDown();
                runner.stop();
            }
            await().atMost(Duration.ofSeconds(5)).until(() -> !recorder.received.isEmpty());
            assertThat(recorder.received).containsExactly(new GatedPing("g-1"));
        }

        assertThat(ingestErrors).isEmpty();
    }
}
