package io.tiko.kafka.runtime;

import io.tiko.Container;
import io.tiko.ErrorContext;
import io.tiko.ErrorHandler;
import io.tiko.EventBus;
import io.tiko.kafka.IngestDecision;
import io.tiko.kafka.IngestErrorPolicy;
import io.tiko.kafka.KafkaConfig;
import io.tiko.kafka.KafkaContext;
import io.tiko.kafka.KafkaIngestError;
import io.tiko.kafka.KafkaIngestErrorDecider;
import io.tiko.kafka.KafkaRecordDeadLettered;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.client.KafkaConsumerClient;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.internals.RecordHeaders;

/**
 * One thread per source topic. The thread owns its own {@link KafkaConsumerClient} and
 * runs the consume loop documented in the spec: poll → deserialize → invoke bridge →
 * publish → commitSync(offset+1); on bridge throw, route via ErrorHandler and seek-back.
 *
 * <h2>Delivery contract (#341)</h2>
 *
 * <p>The offset acknowledges successful delivery <em>to the bus</em>, not handler
 * outcome. The redelivery boundary is the bridge: a deserialize / dispatch / publish
 * failure routes a {@code KafkaIngestError} and seeks back, so the record is delivered
 * at-least-once and handlers must be idempotent. Once {@code publish} returns, the
 * offset commits — handler execution is at-most-once per delivery: a throwing sync
 * handler routes to the ErrorHandler (the bus isolates it from the publisher), and an
 * async handler runs entirely after the commit. Holding the offset hostage to N
 * independent in-process handlers would invert the event model's isolation contract;
 * handler-side recovery is the dead-letter story (#111).
 */
public final class ThreadPerTopicRunner implements KafkaConsumerRunner {

    private final GeneratedSourceDescriptor source;
    private final KafkaConsumerClient consumer;
    private final Container container;
    private final EventBus eventBus;
    private final ErrorHandler errorHandler;
    private final KafkaSerializer serializer;
    private final KafkaConfig config;
    private final IngestErrorPolicy poisonRecordPolicy;

    // Null when no @Component decider is registered — the static poison-record-policy path runs (#313).
    private final KafkaIngestErrorDecider decider;

    // Per-partition consecutive-failure tracking for the decider's `attempt` argument (#385).
    // Only ever touched from the single run() thread, so a plain HashMap is safe.
    private final Map<TopicPartition, Attempt> attempts = new HashMap<>();

    private record Attempt(long offset, int count) {}

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public ThreadPerTopicRunner(
            GeneratedSourceDescriptor source,
            KafkaConsumerClient consumer,
            Container container,
            EventBus eventBus,
            ErrorHandler errorHandler,
            KafkaSerializer serializer,
            KafkaConfig config) {
        this(source, consumer, container, eventBus, errorHandler, serializer, config, null);
    }

    public ThreadPerTopicRunner(
            GeneratedSourceDescriptor source,
            KafkaConsumerClient consumer,
            Container container,
            EventBus eventBus,
            ErrorHandler errorHandler,
            KafkaSerializer serializer,
            KafkaConfig config,
            KafkaIngestErrorDecider decider) {
        this.source = source;
        this.consumer = consumer;
        this.container = container;
        this.eventBus = eventBus;
        this.errorHandler = errorHandler;
        this.serializer = serializer;
        this.config = config;
        this.decider = decider;
        // Parse eagerly so a typo'd policy fails fast at start() rather than per record.
        this.poisonRecordPolicy = IngestErrorPolicy.parse(config.poisonRecordPolicy());
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        consumer.subscribe(List.of(source.topic()));
        thread = new Thread(this::run, "tiko-kafka-consumer-" + source.topic());
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        // running may already be false if a FAIL decision stopped the loop from the consumer
        // thread (#385); still wake and join before close so we never close the client while a
        // poll() is in flight — KafkaConsumer is single-thread-access.
        running.set(false);
        consumer.wakeup();
        try {
            if (thread != null) thread.join(config.shutdownTimeout().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            consumer.close();
        } catch (Exception ignored) {
            /* best-effort */
        }
    }

    private void run() {
        while (running.get()) {
            try {
                ConsumerRecords<String, byte[]> records = consumer.poll(config.pollTimeout());
                for (TopicPartition tp : records.partitions()) {
                    processPartition(tp, records.records(tp));
                }
            } catch (WakeupException wakeup) {
                // stop() woke us (possibly mid-commit) — orderly shutdown.
                return;
            } catch (Exception infrastructureFailure) {
                // Poll/transport failure (auth, fetch, rebalance fallout). The thread must
                // never die silently (#340): surface the failure, back off one poll window
                // so a persistent outage doesn't hot-spin, and keep consuming.
                routeError(new KafkaIngestError(source.topic(), -1, -1L, new RecordHeaders(), infrastructureFailure));
                LoggerHolder.LOG.log(
                        System.Logger.Level.WARNING,
                        "Kafka consumer loop failure on topic '" + source.topic() + "'; retrying",
                        infrastructureFailure);
                LockSupport.parkNanos(config.pollTimeout().toNanos());
            }
        }
        // close is handled by stop() to ensure exactly-once close
    }

    /**
     * Processes one partition's slice of the poll batch. A record failure routes the
     * error, rewinds THIS partition to the failed offset, and stops — records of other
     * partitions in the same batch keep flowing (#340; the old whole-batch break left
     * their already-advanced positions unprocessed and uncommitted for the live session).
     */
    private void processPartition(TopicPartition tp, List<ConsumerRecord<String, byte[]>> partitionRecords) {
        for (ConsumerRecord<String, byte[]> r : partitionRecords) {
            try {
                Object payload = serializer.deserialize(r.value(), source.payloadType());
                KafkaContext ctx = new KafkaContext(
                        r.topic(), r.partition(), r.offset(), Instant.ofEpochMilli(r.timestamp()), r.headers());
                // Each consumed message is one unit of work (#347): open a fresh EVENT scope so
                // EVENT-scoped beans resolve and tear down per message and unit lifecycle events
                // publish. The scope closes (teardown runs) BEFORE commit, so per-unit resources
                // flush before the offset is acknowledged. Sync handlers run in this frame; async
                // handlers detach to the executor with their own scope (#220).
                container.runInEventScope(() -> {
                    Object event = source.dispatcher().dispatch(container, payload, ctx);
                    eventBus.publish(event);
                });
                consumer.commitSync(Map.of(tp, new OffsetAndMetadata(r.offset() + 1)));
                attempts.remove(tp);
            } catch (WakeupException wakeup) {
                throw wakeup; // orderly shutdown — handled by run()
            } catch (Exception ex) {
                KafkaIngestError error = new KafkaIngestError(r.topic(), r.partition(), r.offset(), r.headers(), ex);
                if (decider == null) {
                    // Static poison-record-policy path (#313), unchanged.
                    routeError(error);
                    if (poisonRecordPolicy == IngestErrorPolicy.SKIP) {
                        commitSafely(tp, r.offset() + 1);
                    } else {
                        seekSafely(tp, r.offset());
                        return;
                    }
                } else {
                    int attempt = nextAttempt(tp, r.offset());
                    switch (decide(error, attempt)) {
                        case SEEK -> {
                            routeError(error);
                            seekSafely(tp, r.offset());
                            return;
                        }
                        case SKIP -> {
                            routeError(error);
                            attempts.remove(tp);
                            commitSafely(tp, r.offset() + 1);
                        }
                        case DEAD_LETTER -> {
                            routeError(new KafkaRecordDeadLettered(
                                    r.topic(), r.partition(), r.offset(), r.headers(), ex, attempt));
                            attempts.remove(tp);
                            commitSafely(tp, r.offset() + 1);
                        }
                        case FAIL -> {
                            routeError(error);
                            // Stop this topic's runner; the record is left uncommitted and
                            // redelivers if the consumer is restarted. Other topics are unaffected.
                            running.set(false);
                            return;
                        }
                    }
                }
            }
        }
    }

    /** Routes an error context without letting a throwing ErrorHandler kill the consumer thread. */
    private void routeError(ErrorContext error) {
        try {
            errorHandler.onError(error);
        } catch (Exception handlerFailure) {
            LoggerHolder.LOG.log(
                    System.Logger.Level.WARNING,
                    "ErrorHandler threw while handling a Kafka ingest error",
                    handlerFailure);
        }
    }

    /** Consecutive failure count for {@code offset} on {@code tp}, starting at 1; resets when the offset changes. */
    private int nextAttempt(TopicPartition tp, long offset) {
        Attempt prior = attempts.get(tp);
        int count = (prior != null && prior.offset() == offset) ? prior.count() + 1 : 1;
        attempts.put(tp, new Attempt(offset, count));
        return count;
    }

    /** Invokes the decider under a guard: an exception or a null return falls back to SEEK (safest — no data loss). */
    private IngestDecision decide(KafkaIngestError error, int attempt) {
        try {
            IngestDecision decision = decider.decide(error, attempt);
            return decision != null ? decision : IngestDecision.SEEK;
        } catch (Exception deciderFailure) {
            LoggerHolder.LOG.log(
                    System.Logger.Level.WARNING, "KafkaIngestErrorDecider threw; falling back to SEEK", deciderFailure);
            return IngestDecision.SEEK;
        }
    }

    /**
     * Seeks back for redelivery, tolerating a concurrently revoked partition: after a
     * rebalance, {@code seek} throws {@code IllegalStateException} ("no current
     * assignment") — the offset was never committed, so the new assignee redelivers the
     * record anyway and this consumer just moves on.
     */
    private void seekSafely(TopicPartition tp, long offset) {
        try {
            consumer.seek(tp, offset);
        } catch (Exception seekFailure) {
            LoggerHolder.LOG.log(
                    System.Logger.Level.WARNING,
                    "Seek-back failed for " + tp + " at offset " + offset + " (partition revoked?); the uncommitted"
                            + " record will be redelivered to its current assignee",
                    seekFailure);
        }
    }

    /**
     * Commits {@code offset} for the SKIP poison policy, tolerating a concurrently revoked
     * partition the same way {@link #seekSafely}/the success-path commit do: after a
     * rebalance {@code commitSync} throws, the offset was never committed, and the new
     * assignee redelivers the record — the thread must not die over it.
     */
    private void commitSafely(TopicPartition tp, long offset) {
        try {
            consumer.commitSync(Map.of(tp, new OffsetAndMetadata(offset)));
        } catch (Exception commitFailure) {
            LoggerHolder.LOG.log(
                    System.Logger.Level.WARNING,
                    "Commit of skipped record offset " + offset + " for " + tp + " failed (partition revoked?); the"
                            + " record may be redelivered to its current assignee",
                    commitFailure);
        }
    }

    /** Lazy holder: defers System.LoggerFinder resolution until the first failure path runs. */
    private static final class LoggerHolder {
        static final System.Logger LOG = System.getLogger("io.tiko.kafka");
    }
}
