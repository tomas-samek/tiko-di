package io.tiko.kafka.runtime;

import io.tiko.Container;
import io.tiko.ErrorHandler;
import io.tiko.EventBus;
import io.tiko.kafka.KafkaConfig;
import io.tiko.kafka.KafkaContext;
import io.tiko.kafka.KafkaIngestError;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.client.KafkaConsumerClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;

/**
 * One thread per source topic. The thread owns its own {@link KafkaConsumerClient} and
 * runs the consume loop documented in the spec: poll → deserialize → invoke bridge →
 * publish → commitSync(offset+1); on bridge throw, route via ErrorHandler and seek-back.
 */
public final class ThreadPerTopicRunner implements KafkaConsumerRunner {

    private final GeneratedSourceDescriptor source;
    private final KafkaConsumerClient consumer;
    private final Container container;
    private final EventBus eventBus;
    private final ErrorHandler errorHandler;
    private final KafkaSerializer serializer;
    private final KafkaConfig config;

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
        this.source = source;
        this.consumer = consumer;
        this.container = container;
        this.eventBus = eventBus;
        this.errorHandler = errorHandler;
        this.serializer = serializer;
        this.config = config;
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
        if (!running.compareAndSet(true, false)) {
            // Was never started or already stopped — still close the client.
            try {
                consumer.close();
            } catch (Exception ignored) {
                /* best-effort */
            }
            return;
        }
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
            ConsumerRecords<String, byte[]> records;
            try {
                records = consumer.poll(config.pollTimeout());
            } catch (WakeupException wakeup) {
                return;
            }
            for (ConsumerRecord<String, byte[]> r : records) {
                TopicPartition tp = new TopicPartition(r.topic(), r.partition());
                try {
                    Object payload = serializer.deserialize(r.value(), source.payloadType());
                    KafkaContext ctx = new KafkaContext(
                            r.topic(), r.partition(), r.offset(), Instant.ofEpochMilli(r.timestamp()), r.headers());
                    Object event = source.dispatcher().dispatch(container, payload, ctx);
                    eventBus.publish(event);
                    consumer.commitSync(Map.of(tp, new OffsetAndMetadata(r.offset() + 1)));
                } catch (Exception ex) {
                    errorHandler.onError(
                            new KafkaIngestError(r.topic(), r.partition(), r.offset(), r.headers(), ex));
                    consumer.seek(tp, r.offset());
                    break;
                }
            }
        }
        // close is handled by stop() to ensure exactly-once close
    }
}
