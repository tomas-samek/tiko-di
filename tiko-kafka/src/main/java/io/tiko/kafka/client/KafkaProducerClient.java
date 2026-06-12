package io.tiko.kafka.client;

import java.util.concurrent.Future;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

/**
 * Thin abstraction over {@code org.apache.kafka.clients.producer.Producer}. Exists so
 * tests can substitute {@link io.tiko.kafka.test.FakeKafkaBroker FakeKafkaBroker} without
 * running a real broker, and so any future producer variant (transactional, Confluent
 * registry-aware, ...) can be slotted in without changing the bootstrap.
 *
 * <p>MVP exposes only {@code send} and {@code close} — the surface the bootstrap actually
 * uses. Additional capabilities are added when a use case requires them.
 */
public interface KafkaProducerClient extends AutoCloseable {

    /**
     * Send a record with a completion callback. The callback is invoked when the broker
     * acknowledges the send or when it fails asynchronously (record-too-large, topic
     * authorization, delivery timeout) — the bootstrap routes such failures to
     * {@code KafkaEgressError} (#342). {@code callback} may be {@code null}.
     */
    Future<RecordMetadata> send(ProducerRecord<String, byte[]> record, Callback callback);

    /** Fire-and-forget variant of {@link #send(ProducerRecord, Callback)}. */
    default Future<RecordMetadata> send(ProducerRecord<String, byte[]> record) {
        return send(record, null);
    }

    /** Release client resources. Idempotent. */
    @Override
    void close();
}
