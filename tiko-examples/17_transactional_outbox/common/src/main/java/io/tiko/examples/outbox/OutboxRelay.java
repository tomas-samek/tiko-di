package io.tiko.examples.outbox;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

public final class OutboxRelay implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(OutboxRelay.class.getName());

    private final ScheduledExecutorService worker;
    private final JdbcOutbox outbox;
    private final KafkaProducer<String, byte[]> producer;

    public OutboxRelay(DataSource database, String bootstrapServers) {
        this.outbox = new JdbcOutbox(database);
        this.producer = new KafkaProducer<>(
                Map.of(
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        bootstrapServers,
                        ProducerConfig.ACKS_CONFIG,
                        "all",
                        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
                        true,
                        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                        5000,
                        ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                        3000,
                        ProducerConfig.MAX_BLOCK_MS_CONFIG,
                        2000),
                new StringSerializer(),
                new ByteArraySerializer());
        this.worker = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("outbox-relay").factory());
        worker.scheduleWithFixedDelay(this::publishNext, 0, 250, TimeUnit.MILLISECONDS);
    }

    private void publishNext() {
        try {
            outbox.publishNext(producer::send);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (SQLException | ExecutionException | TimeoutException | RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Outbox publish failed. The pending row will be retried.", failure);
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
                LOG.log(System.Logger.Level.WARNING, "Outbox relay did not stop within the shutdown deadline.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            producer.close(Duration.ofSeconds(5));
        }
    }
}
