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
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
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
 * at-least-once and handlers must be idempotent. Kafka commits the offset after
 * {@code publish} returns successfully. Handler exceptions go to the ErrorHandler
 * without triggering redelivery. Async work may start before the commit, and an
 * invocation that runs inline under {@code CALLER_RUNS} completes before publish
 * returns. Handler-side recovery is the dead-letter story (#111).
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

    // Per-partition consecutive-failure tracking for the decider's `attempt` argument (#385)
    // and the SEEK backoff (#478). Only ever touched from the single run() thread, so a plain
    // HashMap is safe.
    private final Map<TopicPartition, Attempt> attempts = new HashMap<>();

    /** How long a suspend bridge cancelled at shutdown gets to run its cleanup (#681). */
    private static final Duration CANCEL_GRACE = Duration.ofSeconds(1);

    private record Attempt(long offset, int count) {}

    // Partitions paused after a SEEK, mapped to the System.nanoTime() at which they resume (#478).
    // run()-thread only, like attempts.
    private final Map<TopicPartition, Long> resumeAt = new HashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);

    // True while the consumer thread is inside a dispatcher that opens its own unit (#681): a
    // suspend bridge, which runs as a coroutine on that thread and is cancelled by interrupting it.
    private volatile boolean cancellableDispatch;

    private Thread thread; // guarded by this
    private CountDownLatch loopExited = new CountDownLatch(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);

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
        requireBoundedTiming(config);
    }

    /**
     * Rejects timing that would let a failing record or a failing poll repeat without a time
     * bound (#495). {@code poll-timeout} also paces recovery after a failed poll, so it must be
     * positive; {@code seek-backoff: PT0S} stays the documented opt-out of backoff.
     */
    private static void requireBoundedTiming(KafkaConfig config) {
        if (config.pollTimeout().isNegative() || config.pollTimeout().isZero()) {
            throw new IllegalArgumentException("tiko.kafka.poll-timeout must be positive");
        }
        if (config.seekBackoff().isNegative()) {
            throw new IllegalArgumentException("tiko.kafka.seek-backoff must not be negative");
        }
        if (config.seekBackoffMax().compareTo(config.seekBackoff()) < 0) {
            throw new IllegalArgumentException(
                    "tiko.kafka.seek-backoff-max must not be less than tiko.kafka.seek-backoff");
        }
    }

    @Override
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) return;
        consumer.subscribe(List.of(source.topic()));
        loopExited = new CountDownLatch(1);
        // The container's factory tracks the thread, so a consumer that outlives close() is reported (#635).
        var consumerThread = container.threadFactory().newThread(this::runAndClose);
        consumerThread.setName("tiko-kafka-consumer-" + source.topic());
        consumerThread.setDaemon(true);
        var started = false;
        try {
            consumerThread.start();
            started = true;
        } finally {
            if (!started) {
                // The thread never ran, so nobody else will close the consumer.
                running.set(false);
                closeConsumer();
            }
        }
        thread = consumerThread;
    }

    @Override
    public synchronized void stop() {
        // running may already be false if a FAIL decision stopped the loop from the consumer
        // thread (#385). KafkaConsumer is single-thread-access, so once the consumer thread exists
        // only it touches the consumer: it closes its own client on exit (#648).
        running.set(false);
        consumer.wakeup();
        if (thread == null) {
            closeConsumer();
            return;
        }
        try {
            // shutdown-timeout bounds the loop exiting (a bridge may be stuck); the close that
            // follows is bounded by the consumer's own close timeout, as before.
            if (loopExited.await(config.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    || cancelSuspendedBridge()) {
                thread.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Cancels a suspend bridge that is still running after the shutdown timeout (#681), by
     * interrupting the consumer thread that runs its coroutine, and gives it {@link #CANCEL_GRACE}
     * to run its cleanup and close its unit. A regular bridge is not interrupted, as before.
     *
     * @return whether the loop has exited
     */
    private boolean cancelSuspendedBridge() throws InterruptedException {
        if (!cancellableDispatch) {
            return false;
        }
        thread.interrupt();
        return loopExited.await(CANCEL_GRACE.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void runAndClose() {
        try {
            run();
        } finally {
            loopExited.countDown();
            // stop() may have interrupted this thread to cancel a suspend bridge (#681). That
            // interrupt has done its work; the client's close must not be cut short by it.
            Thread.interrupted();
            closeConsumer();
        }
    }

    private void closeConsumer() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            consumer.close();
        } catch (Exception ignored) {
            /* best-effort */
        }
    }

    @SuppressWarnings("java:S1181") // an Error ends the thread; it must not end it unreported (#749)
    private void run() {
        while (running.get()) {
            try {
                resumeDuePartitions();
                ConsumerRecords<String, byte[]> records = consumer.poll(config.pollTimeout());
                for (TopicPartition tp : records.partitions()) {
                    // A FAIL decision, an Error or stop() ended this runner mid-batch: what is left
                    // of the batch stays uncommitted and is redelivered (#749).
                    if (!running.get()) break;
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
            } catch (Error fatal) {
                stopOn(new KafkaIngestError(source.topic(), -1, -1L, new RecordHeaders(), fatal));
            }
        }
        // runAndClose() closes the consumer on this same thread, exactly once
    }

    /**
     * Processes one partition's slice of the poll batch. A record failure routes the
     * error, rewinds THIS partition to the failed offset, and stops — records of other
     * partitions in the same batch keep flowing (#340; the old whole-batch break left
     * their already-advanced positions unprocessed and uncommitted for the live session).
     */
    @SuppressWarnings("java:S1181") // an Error ends the thread; it must not end it unreported (#749)
    private void processPartition(TopicPartition tp, List<ConsumerRecord<String, byte[]>> partitionRecords) {
        for (ConsumerRecord<String, byte[]> r : partitionRecords) {
            try {
                // A record without a value (a tombstone) has no payload to hand to the bridge: it
                // is consumed and publishes nothing, like a bridge that returns null (#751).
                if (r.value() != null) {
                    publish(r);
                }
                consumer.commitSync(Map.of(tp, new OffsetAndMetadata(r.offset() + 1)));
                attempts.remove(tp);
            } catch (WakeupException wakeup) {
                throw wakeup; // orderly shutdown — handled by run()
            } catch (Exception ex) {
                if (interruptedByStop(ex)) {
                    return; // the record stays uncommitted and is delivered again
                }
                if (!handleIngestFailure(tp, r, ex)) return;
            } catch (Error fatal) {
                stopOn(new KafkaIngestError(r.topic(), r.partition(), r.offset(), r.headers(), fatal));
                return;
            }
        }
    }

    /**
     * Whether {@code failure} is the interrupt {@link #stop()} sends to cancel a suspend bridge
     * (#681): the cancelled bridge throws it as an {@link InterruptedException}, and a commit it
     * lands in after the bridge returned throws Kafka's {@link InterruptException}. Either way the
     * runner is stopping, so it is no ingest failure.
     */
    private boolean interruptedByStop(Exception failure) {
        return !running.get() && (failure instanceof InterruptedException || failure instanceof InterruptException);
    }

    /** Decodes one record, runs its bridge and publishes the bridge's event, all in one unit of work. */
    private void publish(ConsumerRecord<String, byte[]> r) {
        Object payload = serializer.deserialize(r.value(), source.payloadType());
        KafkaContext ctx = new KafkaContext(
                r.topic(), r.partition(), r.offset(), Instant.ofEpochMilli(r.timestamp()), r.headers());
        if (source.opensItsUnit()) {
            // A suspend bridge (#681): its dispatcher runs it as a coroutine on this thread, in a
            // unit that travels with the coroutine, and publishes its event inside that unit.
            cancellableDispatch = true;
            try {
                source.dispatcher().dispatch(container, payload, ctx);
            } finally {
                cancellableDispatch = false;
            }
            return;
        }
        // Each consumed message is one unit of work (#347): open a fresh EVENT scope so
        // EVENT-scoped beans resolve and tear down per message and unit lifecycle events
        // publish. The scope closes (teardown runs) BEFORE commit, so per-unit resources
        // flush before the offset is acknowledged. Sync handlers run in this frame; async
        // handlers detach to the executor with their own scope (#220).
        container.runInEventScope(() -> {
            Object event = source.dispatcher().dispatch(container, payload, ctx);
            eventBus.publish(event);
        });
    }

    /**
     * Ends this runner on an {@link Error} from a bridge, a sync handler or the client (#749). An
     * {@code Error} is not a record failure to retry or skip, so no policy and no decider is asked:
     * the error is reported with the record it came from, that record stays uncommitted, and this
     * topic's consumer stops, as after a {@code FAIL} decision.
     */
    @SuppressWarnings("java:S1181") // a handler that fails here must not hide the reported Error
    private void stopOn(KafkaIngestError error) {
        running.set(false);
        LoggerHolder.LOG.log(
                System.Logger.Level.ERROR,
                "Kafka consumer for topic '" + source.topic() + "' stopped on " + error.cause()
                        + "; the record is left uncommitted");
        try {
            errorHandler.onError(error);
        } catch (Exception | Error handlerFailure) {
            LoggerHolder.LOG.log(
                    System.Logger.Level.WARNING,
                    "ErrorHandler threw while handling the Error that stopped a Kafka consumer",
                    handlerFailure);
        }
    }

    /**
     * Applies the failure policy for one record. Returns {@code true} if this partition's
     * remaining records should keep processing, {@code false} if the loop must stop — either
     * because the position was rewound or because this runner is shutting down.
     */
    private boolean handleIngestFailure(TopicPartition tp, ConsumerRecord<String, byte[]> r, Exception ex) {
        KafkaIngestError error = new KafkaIngestError(r.topic(), r.partition(), r.offset(), r.headers(), ex);
        return decider == null ? applyStaticPolicy(tp, r, error) : applyDecision(tp, r, error, ex);
    }

    /** Static poison-record-policy path (#313), unchanged. */
    private boolean applyStaticPolicy(TopicPartition tp, ConsumerRecord<String, byte[]> r, KafkaIngestError error) {
        routeError(error);
        if (poisonRecordPolicy == IngestErrorPolicy.SKIP) {
            attempts.remove(tp);
            commitSafely(tp, r.offset() + 1);
            return true;
        }
        seekWithBackoff(tp, r.offset(), nextAttempt(tp, r.offset()));
        return false;
    }

    /** Programmatic per-error decision path (#385); overrides the static policy when a decider is registered. */
    private boolean applyDecision(
            TopicPartition tp, ConsumerRecord<String, byte[]> r, KafkaIngestError error, Exception ex) {
        int attempt = nextAttempt(tp, r.offset());
        return switch (decide(error, attempt)) {
            case SEEK -> {
                routeError(error);
                seekWithBackoff(tp, r.offset(), attempt);
                yield false;
            }
            case SKIP -> {
                routeError(error);
                attempts.remove(tp);
                commitSafely(tp, r.offset() + 1);
                yield true;
            }
            case DEAD_LETTER -> {
                routeError(new KafkaRecordDeadLettered(r.topic(), r.partition(), r.offset(), r.headers(), ex, attempt));
                attempts.remove(tp);
                commitSafely(tp, r.offset() + 1);
                yield true;
            }
            case FAIL -> {
                routeError(error);
                // Stop this topic's runner; the record is left uncommitted and
                // redelivers if the consumer is restarted. Other topics are unaffected.
                running.set(false);
                yield false;
            }
        };
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
     * Seeks back to the failed record and pauses only its partition for the backoff (#478), so
     * a permanently failing record redelivers at the backoff pace instead of on every poll while
     * the topic's other partitions keep flowing. {@code seek-backoff: PT0S} redelivers immediately.
     */
    private void seekWithBackoff(TopicPartition tp, long offset, int attempt) {
        seekSafely(tp, offset);
        Duration delay = seekBackoff(attempt, config.seekBackoff(), config.seekBackoffMax());
        if (delay.isZero()) return;
        try {
            consumer.pause(List.of(tp));
            resumeAt.put(tp, System.nanoTime() + delay.toNanos());
        } catch (Exception pauseFailure) {
            // Revoked between the failure and the pause: the uncommitted record redelivers to its
            // new assignee, which applies its own backoff. Nothing to resume here.
            resumeAt.remove(tp);
        }
    }

    /** Resumes every paused partition whose backoff has elapsed, one at a time (#478). */
    private void resumeDuePartitions() {
        if (resumeAt.isEmpty()) return;
        long now = System.nanoTime();
        List<TopicPartition> due = resumeAt.entrySet().stream()
                .filter(e -> now - e.getValue() >= 0)
                .map(Map.Entry::getKey)
                .toList();
        for (TopicPartition tp : due) {
            resumeAt.remove(tp);
            try {
                consumer.resume(List.of(tp));
            } catch (Exception resumeFailure) {
                // Revoked by a rebalance while paused — the new assignment starts unpaused.
            }
        }
    }

    /**
     * The SEEK backoff for the {@code attempt}-th consecutive failure of one record: {@code initial}
     * doubled per further attempt, never above {@code max}. A zero {@code initial} disables it.
     */
    static Duration seekBackoff(int attempt, Duration initial, Duration max) {
        if (initial.isZero() || initial.isNegative()) return Duration.ZERO;
        int doublings = Math.clamp(attempt - 1L, 0, 30);
        Duration grown = initial.multipliedBy(1L << doublings);
        return grown.compareTo(max) > 0 ? max : grown;
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
