package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.kafka.KafkaIngestError;
import io.tiko.kafka.client.KafkaConsumerClient;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.runtime.Tiko;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/**
 * Failure handling in the per-topic consume loop (#340): infrastructure exceptions
 * (poll, commit, seek) must never silently kill the consumer thread, and a per-record
 * failure must stop only its own partition — records of other partitions in the same
 * poll batch keep flowing in the live session.
 */
class KafkaRunnerFailureHandlingTest {

    @Test
    void consumerThreadSurvivesPollFailures() {
        FakeKafkaBroker broker = new FakeKafkaBroker();
        broker.produce("t1", "ok".getBytes(StandardCharsets.UTF_8));
        AtomicInteger failingPolls = new AtomicInteger(2);
        KafkaConsumerClient flaky = new ForwardingClient(broker.consumerClient("g")) {
            @Override
            public ConsumerRecords<String, byte[]> poll(Duration timeout) {
                if (failingPolls.getAndDecrement() > 0) {
                    throw new RuntimeException("transient broker failure");
                }
                return super.poll(timeout);
            }
        };
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();
        List<Object> received = new CopyOnWriteArrayList<>();

        try (Container container = Tiko.create()) {
            container.getEventBus().subscribe(String.class, received::add);
            ThreadPerTopicRunner runner = new ThreadPerTopicRunner(
                    RunnerTestSupport.stringSource("t1", payload -> payload),
                    flaky,
                    container,
                    container.getEventBus(),
                    errors::add,
                    RunnerTestSupport.UTF8,
                    RunnerTestSupport.config());
            runner.start();
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> received.contains("ok"));
            } finally {
                runner.stop();
            }
        }

        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0))
                .isInstanceOfSatisfying(
                        KafkaIngestError.class,
                        e -> assertThat(e.cause()).hasMessageContaining("transient broker failure"));
    }

    @Test
    void partitionFailureDoesNotSkipOtherPartitionsInTheSameBatch() {
        TopicPartition p0 = new TopicPartition("t2", 0);
        TopicPartition p1 = new TopicPartition("t2", 1);
        Map<TopicPartition, List<ConsumerRecord<String, byte[]>>> batch = new LinkedHashMap<>();
        batch.put(p0, List.of(RunnerTestSupport.consumerRecord("t2", 0, 0, "poison")));
        batch.put(p1, List.of(RunnerTestSupport.consumerRecord("t2", 1, 0, "ok")));
        ScriptedConsumerClient client = new ScriptedConsumerClient(List.of(new ConsumerRecords<>(batch)));
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();
        List<Object> received = new CopyOnWriteArrayList<>();

        try (Container container = Tiko.create()) {
            container.getEventBus().subscribe(String.class, received::add);
            ThreadPerTopicRunner runner = new ThreadPerTopicRunner(
                    RunnerTestSupport.stringSource("t2", payload -> payload),
                    client,
                    container,
                    container.getEventBus(),
                    errors::add,
                    RunnerTestSupport.UTF8,
                    RunnerTestSupport.config());
            runner.start();
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> received.contains("ok"));
            } finally {
                runner.stop();
            }
        }

        assertThat(client.commits).contains(Map.entry(p1, 1L));
        assertThat(client.seeks).contains(Map.entry(p0, 0L));
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0))
                .isInstanceOfSatisfying(
                        KafkaIngestError.class, e -> assertThat(e.partition()).isZero());
    }

    @Test
    void seekFailureAfterRebalanceDoesNotKillTheThread() {
        TopicPartition p0 = new TopicPartition("t3", 0);
        // Poll 1 delivers the record; commit 1 fails (rebalance) and the follow-up seek
        // throws "no current assignment"; poll 2 redelivers the same record (as the real
        // broker would after a rebalance) and commit 2 succeeds.
        ScriptedConsumerClient client =
                new ScriptedConsumerClient(List.of(
                        new ConsumerRecords<>(Map.of(p0, List.of(RunnerTestSupport.consumerRecord("t3", 0, 0, "ok")))),
                        new ConsumerRecords<>(
                                Map.of(p0, List.of(RunnerTestSupport.consumerRecord("t3", 0, 0, "ok")))))) {
                    private int commitCalls;

                    @Override
                    public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
                        if (++commitCalls == 1) {
                            throw new org.apache.kafka.clients.consumer.CommitFailedException();
                        }
                        super.commitSync(offsets);
                    }

                    @Override
                    public void seek(TopicPartition partition, long offset) {
                        throw new IllegalStateException("No current assignment for partition " + partition);
                    }
                };
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();
        List<Object> received = new CopyOnWriteArrayList<>();

        try (Container container = Tiko.create()) {
            container.getEventBus().subscribe(String.class, received::add);
            ThreadPerTopicRunner runner = new ThreadPerTopicRunner(
                    RunnerTestSupport.stringSource("t3", payload -> payload),
                    client,
                    container,
                    container.getEventBus(),
                    errors::add,
                    RunnerTestSupport.UTF8,
                    RunnerTestSupport.config());
            runner.start();
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> client.commits.contains(Map.entry(p0, 1L)));
            } finally {
                runner.stop();
            }
        }

        assertThat(received).containsExactly("ok", "ok");
        assertThat(errors).isNotEmpty();
    }

    /** Delegating client base for per-test overrides. */
    private static class ForwardingClient implements KafkaConsumerClient {
        private final KafkaConsumerClient delegate;

        ForwardingClient(KafkaConsumerClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public void subscribe(Collection<String> topics) {
            delegate.subscribe(topics);
        }

        @Override
        public ConsumerRecords<String, byte[]> poll(Duration timeout) {
            return delegate.poll(timeout);
        }

        @Override
        public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
            delegate.commitSync(offsets);
        }

        @Override
        public void seek(TopicPartition partition, long offset) {
            delegate.seek(partition, offset);
        }

        @Override
        public void wakeup() {
            delegate.wakeup();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
