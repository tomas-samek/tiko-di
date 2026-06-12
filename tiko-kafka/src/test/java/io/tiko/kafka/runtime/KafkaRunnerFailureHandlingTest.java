package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.kafka.KafkaConfig;
import io.tiko.kafka.KafkaIngestError;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.client.KafkaConsumerClient;
import io.tiko.kafka.test.FakeKafkaBroker;
import io.tiko.runtime.Tiko;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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

    private static final KafkaSerializer UTF8 = new KafkaSerializer() {
        @Override
        public byte[] serialize(Object value) {
            return value.toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public <T> T deserialize(byte[] bytes, Class<T> type) {
            return type.cast(new String(bytes, StandardCharsets.UTF_8));
        }
    };

    private static KafkaConfig config() {
        return new KafkaConfig(
                "unused:9092",
                "g",
                "json",
                "earliest",
                Duration.ofMillis(10),
                Duration.ofSeconds(2),
                Map.of(),
                Map.of());
    }

    /** Passthrough dispatcher publishing the String payload; payload "poison" always throws. */
    private static GeneratedSourceDescriptor stringSource(String topic) {
        return new GeneratedSourceDescriptor(
                topic,
                "",
                "StringEvent",
                String.class,
                KafkaSerializer.Default.class,
                false,
                (container, payload, ctx) -> {
                    if ("poison".equals(payload)) {
                        throw new IllegalStateException("poison payload");
                    }
                    return payload;
                });
    }

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
                    stringSource("t1"), flaky, container, container.getEventBus(), errors::add, UTF8, config());
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
        batch.put(p0, List.of(record("t2", 0, 0, "poison")));
        batch.put(p1, List.of(record("t2", 1, 0, "ok")));
        StubClient client = new StubClient(new ArrayList<>(List.of(new ConsumerRecords<>(batch))));
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();
        List<Object> received = new CopyOnWriteArrayList<>();

        try (Container container = Tiko.create()) {
            container.getEventBus().subscribe(String.class, received::add);
            ThreadPerTopicRunner runner = new ThreadPerTopicRunner(
                    stringSource("t2"), client, container, container.getEventBus(), errors::add, UTF8, config());
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
        StubClient client =
                new StubClient(new ArrayList<>(List.of(
                        new ConsumerRecords<>(Map.of(p0, List.of(record("t3", 0, 0, "ok")))),
                        new ConsumerRecords<>(Map.of(p0, List.of(record("t3", 0, 0, "ok"))))))) {
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
                    stringSource("t3"), client, container, container.getEventBus(), errors::add, UTF8, config());
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

    private static ConsumerRecord<String, byte[]> record(String topic, int partition, long offset, String payload) {
        return new ConsumerRecord<>(topic, partition, offset, null, payload.getBytes(StandardCharsets.UTF_8));
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

    /** Scripted client: serves the given batches once each, then empty polls; records commits and seeks. */
    private static class StubClient implements KafkaConsumerClient {
        final List<Map.Entry<TopicPartition, Long>> commits = new CopyOnWriteArrayList<>();
        final List<Map.Entry<TopicPartition, Long>> seeks = new CopyOnWriteArrayList<>();
        private final List<ConsumerRecords<String, byte[]>> batches;
        private volatile boolean wakeup;

        StubClient(List<ConsumerRecords<String, byte[]>> batches) {
            this.batches = batches;
        }

        @Override
        public void subscribe(Collection<String> topics) {
            // scripted — nothing to do
        }

        @Override
        public synchronized ConsumerRecords<String, byte[]> poll(Duration timeout) {
            if (wakeup) {
                wakeup = false;
                throw new org.apache.kafka.common.errors.WakeupException();
            }
            if (batches.isEmpty()) {
                return new ConsumerRecords<>(Map.of());
            }
            return batches.remove(0);
        }

        @Override
        public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
            offsets.forEach((tp, om) -> commits.add(Map.entry(tp, om.offset())));
        }

        @Override
        public void seek(TopicPartition partition, long offset) {
            seeks.add(Map.entry(partition, offset));
        }

        @Override
        public void wakeup() {
            wakeup = true;
        }

        @Override
        public void close() {
            // scripted — nothing to release
        }
    }
}
