package io.tiko.kafka.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.tiko.Container;
import io.tiko.kafka.KafkaConfig;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.runtime.Tiko;
import io.tiko.runtime.TikoOptions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/**
 * {@code KafkaConsumer} is not thread-safe (#648): while the consumer thread is alive only it
 * touches the consumer, and it closes its own client on exit. {@code shutdown-timeout} bounds
 * the loop exiting; the close that follows is waited for without that bound.
 */
class StuckBridgeShutdownTest {

    private static final String THREAD = "tiko-kafka-consumer-t-stuck";

    /** Records which thread closed the client, whether the owner was alive then, and the close count. */
    private static class ThreadCheckingClient extends ScriptedConsumerClient {
        final AtomicReference<Thread> owner = new AtomicReference<>();
        final AtomicReference<Thread> closer = new AtomicReference<>();
        final AtomicInteger closeCount = new AtomicInteger();
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
            closeCount.incrementAndGet();
            beforeClosed();
            closed.countDown();
        }

        void beforeClosed() {
            // hook for subclasses
        }
    }

    private static KafkaConfig shortTimeout() {
        var base = RunnerTestSupport.config();
        return new KafkaConfig(
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
    }

    private static List<ConsumerRecords<String, byte[]>> oneRecord() {
        var partition = new TopicPartition("t-stuck", 0);
        return List.of(new ConsumerRecords<String, byte[]>(
                Map.of(partition, List.of(RunnerTestSupport.consumerRecord("t-stuck", 0, 0, "ok")))));
    }

    private static GeneratedSourceDescriptor source(CountDownLatch inBridge, CountDownLatch release) {
        return new GeneratedSourceDescriptor(
                "t-stuck", "", "StringEvent", String.class, KafkaSerializer.Default.class, false, (c, payload, ctx) -> {
                    inBridge.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return payload;
                });
    }

    private static ThreadPerTopicRunner runner(
            GeneratedSourceDescriptor source, ThreadCheckingClient client, Container container) {
        return new ThreadPerTopicRunner(
                source, client, container, container.getEventBus(), e -> {}, RunnerTestSupport.UTF8, shortTimeout());
    }

    @Test
    void stuckBridgeKeepsItsConsumerUntilItExitsAndIsReported() throws Exception {
        var client = new ThreadCheckingClient(oneRecord());
        var inBridge = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reported = new CopyOnWriteArrayList<Thread>();
        var container = Tiko.create(
                TikoOptions.builder().onThreadsOutlivingClose(reported::addAll).build());
        var runner = runner(source(inBridge, release), client, container);
        try {
            runner.start();
            assertThat(inBridge.await(5, TimeUnit.SECONDS)).isTrue();

            runner.stop();
            container.close();

            assertThat(client.closed.getCount())
                    .as("closed while the bridge is stuck")
                    .isEqualTo(1);
            assertThat(reported).extracting(Thread::getName).contains(THREAD);
        } finally {
            release.countDown();
            await().atMost(Duration.ofSeconds(5)).until(() -> client.closed.getCount() == 0);
            client.owner.get().join(5_000);
        }
        assertThat(client.ownerAliveAtClose).isFalse();
        assertThat(client.closer.get()).isSameAs(client.owner.get());
        assertThat(client.closeCount).hasValue(1);
    }

    @Test
    void stopWaitsForTheConsumerToFinishClosingBeyondShutdownTimeout() throws Exception {
        var closeEntered = new CountDownLatch(1);
        var releaseClose = new CountDownLatch(1);
        var client = new ThreadCheckingClient(List.of()) {
            @Override
            void beforeClosed() {
                closeEntered.countDown();
                try {
                    releaseClose.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        try (Container container = Tiko.create()) {
            var runner = runner(source(new CountDownLatch(1), new CountDownLatch(0)), client, container);
            runner.start();
            CompletableFuture<Void> stop = null;
            try {
                stop = CompletableFuture.runAsync(runner::stop);
                assertThat(closeEntered.await(5, TimeUnit.SECONDS)).isTrue();
                // Well past shutdown-timeout (100 ms): stop() must still be waiting for the close.
                final var pending = stop;
                await().during(Duration.ofMillis(400))
                        .atMost(Duration.ofSeconds(5))
                        .until(() -> !pending.isDone());
            } finally {
                releaseClose.countDown();
                if (stop != null) stop.get(5, TimeUnit.SECONDS);
            }
            assertThat(client.closed.getCount()).isZero();
        }
    }

    @Test
    void neverStartedRunnerClosesItsConsumerExactlyOnce() {
        var client = new ThreadCheckingClient(List.of());
        try (Container container = Tiko.create()) {
            var runner = runner(source(new CountDownLatch(1), new CountDownLatch(0)), client, container);

            runner.stop();
            runner.stop();

            assertThat(client.closeCount).hasValue(1);
        }
    }
}
