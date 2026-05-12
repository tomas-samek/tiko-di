package io.tiko.kafka.client;

import java.util.concurrent.Future;
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
     * Send a record. Returns a {@link Future} that completes when the broker acknowledges
     * the send (or when the fake broker captures it). Used by the bootstrap to detect
     * send failures and route them to {@code KafkaEgressError}.
     */
    Future<RecordMetadata> send(ProducerRecord<String, byte[]> record);

    /** Release client resources. Idempotent. */
    @Override
    void close();
}
