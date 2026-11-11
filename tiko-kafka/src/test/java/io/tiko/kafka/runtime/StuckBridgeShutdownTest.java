package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.kafka.KafkaConfig;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.runtime.Tiko;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/**
 * {@code KafkaConsumer} is not thread-safe (#648): when a bridge is stuck past
 * {@code shutdown-timeout}, the closer thread must not touch the consumer while the consumer
 * thread is still alive; the consumer thread closes its own client once it exits.
 */
class StuckBridgeShutdownTest {

    /** Records which thread closed the client and whether the consumer thread was alive then. */
    private static final class ThreadCheckingClient extends ScriptedConsumerClient {
        final AtomicReference<Thread> owner = new AtomicReference<>();
        final AtomicReference<Thread> closer = new AtomicReference<>();
        volatile boolean ownerAliveAtClose;
        final CountDownLatch closed = new CountDownLatch(1);

        ThreadCheckingClient(List<ConsumerRecords<String, byte[]>> batches) {
            super(batches);
        }

        @Override
        public synchronized ConsumerRecords<String, byte[]> poll(Duration timeout) {
            owner.compareAndSet(null, Thread.currentThread());
            return super.poll(timeout);
        }

        @Override
        public void close() {
            var o = owner.get();
            closer.set(Thread.currentThread());
            ownerAliveAtClose = o != null && o != Thread.currentThread() && o.isAlive();
            closed.countDown();
        }
    }

    @Test
    void stuckBridgeKeepsItsConsumerUntilItExitsAndClosesItItself() throws Exception {
        var partition = new TopicPartition("t-stuck", 0);
        var batch = new ConsumerRecords<String, byte[]>(
                Map.of(partition, List.of(RunnerTestSupport.consumerRecord("t-stuck", 0, 0, "ok"))));
        var client = new ThreadCheckingClient(List.of(batch));
        var inBridge = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var source = new GeneratedSourceDescriptor(
                "t-stuck", "", "StringEvent", String.class, KafkaSerializer.Default.class, false, (c, payload, ctx) -> {
                    inBridge.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return payload;
                });
        var base = RunnerTestSupport.config();
        var config = new KafkaConfig(
                base.bootstrapServers(),
                base.consumerGroup(),
                base.serializer(),
                base.autoOffsetReset(),
                base.pollTimeout(),
                Duration.ofMillis(100),
                Map.of(),
                Map.of(),
                base.poisonRecordPolicy(),
                base.seekBackoff(),
                base.seekBackoffMax());

        try (Container container = Tiko.create()) {
            var runner = new ThreadPerTopicRunner(
                    source, client, container, container.getEventBus(), e -> {}, RunnerTestSupport.UTF8, config);
            runner.start();
            try {
                assertThat(inBridge.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        .isTrue();

                runner.stop();

                assertThat(client.closed.getCount())
                        .as("closed while the bridge is stuck")
                        .isEqualTo(1);
            } finally {
                release.countDown();
                await().atMost(Duration.ofSeconds(5)).until(() -> client.closed.getCount() == 0);
                client.owner.get().join(5_000);
            }
            assertThat(client.ownerAliveAtClose).isFalse();
            assertThat(client.closer.get()).isSameAs(client.owner.get());
        }
    }
}
