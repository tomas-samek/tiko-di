package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.kafka.KafkaConfig;
import io.tiko.runtime.Tiko;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/**
 * SEEK redelivery backoff (#478). A real broker returns a sought-back record on the very next
 * poll; without a backoff a permanently failing record redelivers — and routes an error — as
 * fast as the loop can spin. The runner pauses only the failing partition until the backoff
 * elapses, so healthy partitions keep flowing and the failed record is still never committed.
 */
class KafkaSeekBackoffTest {

    private static final TopicPartition POISONED = new TopicPartition("t", 0);
    private static final TopicPartition HEALTHY = new TopicPartition("t", 1);

    /** Models a broker: P0 always serves the poison record; P1 serves a fresh good record per poll. */
    private static final class BrokerLikeClient extends ScriptedConsumerClient {
        final List<TopicPartition> pauses = new CopyOnWriteArrayList<>();
        final List<TopicPartition> resumes = new CopyOnWriteArrayList<>();
        private final Set<TopicPartition> paused = ConcurrentHashMap.newKeySet();
        private final AtomicLong healthyOffset = new AtomicLong();
        private final boolean serveHealthy;

        BrokerLikeClient(boolean serveHealthy) {
            super(List.of());
            this.serveHealthy = serveHealthy;
        }

        @Override
        public synchronized ConsumerRecords<String, byte[]> poll(Duration timeout) {
            super.poll(timeout); // honours wakeup()
            var batch = new java.util.HashMap<TopicPartition, List<ConsumerRecord<String, byte[]>>>();
            if (!paused.contains(POISONED)) {
                batch.put(POISONED, List.of(RunnerTestSupport.consumerRecord("t", 0, 0, "poison")));
            }
            if (serveHealthy && !paused.contains(HEALTHY)) {
                long offset = healthyOffset.getAndIncrement();
                batch.put(HEALTHY, List.of(RunnerTestSupport.consumerRecord("t", 1, offset, "ok-" + offset)));
            }
            return new ConsumerRecords<>(batch);
        }

        @Override
        public void pause(Collection<TopicPartition> partitions) {
            pauses.addAll(partitions);
            paused.addAll(partitions);
        }

        @Override
        public void resume(Collection<TopicPartition> partitions) {
            resumes.addAll(partitions);
            paused.removeAll(partitions);
        }
    }

    private static List<ErrorContext> consumeFor(BrokerLikeClient client, KafkaConfig config, Duration window) {
        List<ErrorContext> errors = new CopyOnWriteArrayList<>();
        try (Container container = Tiko.create()) {
            var runner = new ThreadPerTopicRunner(
                    RunnerTestSupport.stringSource("t", payload -> payload),
                    client,
                    container,
                    container.getEventBus(),
                    errors::add,
                    RunnerTestSupport.UTF8,
                    config);
            runner.start();
            try {
                await().pollDelay(window).until(() -> true);
            } finally {
                runner.stop();
            }
        }
        return errors;
    }

    @Test
    void poisonedRecordIsRedeliveredAtTheBackoffPaceNotImmediately() {
        var client = new BrokerLikeClient(false);
        var backoff = Duration.ofMillis(200);

        var errors = consumeFor(client, RunnerTestSupport.config("SEEK", backoff, backoff), Duration.ofSeconds(1));

        assertThat(client.seeks)
                .as("one redelivery per 200 ms backoff over ~1 s, not one per loop iteration")
                .hasSizeBetween(2, 8);
        assertThat(errors).hasSameSizeAs(client.seeks);
        assertThat(client.pauses)
                .as("the failing partition is paused, not the whole consumer")
                .containsOnly(POISONED);
        assertThat(client.resumes).as("and resumed once the backoff elapses").contains(POISONED);
        assertThat(client.commits)
                .as("SEEK still never commits past the failed record")
                .doesNotContain(Map.entry(POISONED, 1L));
    }

    @Test
    void healthyPartitionKeepsFlowingWhileAnotherBacksOff() {
        var client = new BrokerLikeClient(true);
        var backoff = Duration.ofSeconds(5);

        consumeFor(client, RunnerTestSupport.config("SEEK", backoff, backoff), Duration.ofMillis(500));

        assertThat(client.commits.stream().filter(c -> c.getKey().equals(HEALTHY)))
                .as("the healthy partition commits while the poisoned one is paused")
                .hasSizeGreaterThan(10);
        assertThat(client.pauses).doesNotContain(HEALTHY);
    }

    @Test
    void zeroBackoffKeepsImmediateRedelivery() {
        var client = new BrokerLikeClient(false);

        consumeFor(client, RunnerTestSupport.config("SEEK", Duration.ZERO, Duration.ZERO), Duration.ofMillis(300));

        assertThat(client.pauses).as("PT0S opts out of the backoff").isEmpty();
        assertThat(client.seeks).hasSizeGreaterThan(8);
    }
}
