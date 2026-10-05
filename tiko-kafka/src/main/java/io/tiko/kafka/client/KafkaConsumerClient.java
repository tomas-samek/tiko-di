package io.tiko.kafka.client;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

/**
 * Thin abstraction over {@code org.apache.kafka.clients.consumer.Consumer}. Tests substitute
 * {@link io.tiko.kafka.test.FakeKafkaBroker FakeKafkaBroker}; production code uses
 * {@link ApacheKafkaConsumerClient}.
 *
 * <p>The bootstrap drives one runner per source topic; each runner owns one client. Apache
 * Kafka clients are single-threaded — clients must not be shared across threads.
 */
public interface KafkaConsumerClient extends AutoCloseable {

    /** Subscribe this consumer to the given topics. */
    void subscribe(Collection<String> topics);

    /** Poll for records with the given timeout. May return an empty batch. */
    ConsumerRecords<String, byte[]> poll(Duration timeout);

    /** Synchronously commit the given offsets. */
    void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets);

    /** Seek the consumer back to the given offset on the given partition. */
    void seek(TopicPartition partition, long offset);

    /**
     * Stop returning records for the given partitions from {@link #poll} until they are
     * {@link #resume resumed}. The runner pauses a partition while a failed record backs off
     * before redelivery (#478), so other partitions keep flowing.
     */
    void pause(Collection<TopicPartition> partitions);

    /** Return records for previously {@link #pause paused} partitions again. */
    void resume(Collection<TopicPartition> partitions);

    /**
     * Wake the consumer thread up out of a blocking {@code poll}, causing it to throw
     * {@code WakeupException}. Used at shutdown.
     */
    void wakeup();

    /** Release client resources. Idempotent. */
    @Override
    void close();
}
