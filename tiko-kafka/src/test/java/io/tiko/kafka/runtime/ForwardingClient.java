package io.tiko.kafka.runtime;

import io.tiko.kafka.client.KafkaConsumerClient;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

/** Delegating {@link KafkaConsumerClient} for runner tests: a test overrides the methods it wants to change. */
class ForwardingClient implements KafkaConsumerClient {

    private final KafkaConsumerClient delegate;

    ForwardingClient(KafkaConsumerClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public void subscribe(Collection<String> topics) {
        delegate.subscribe(topics);
    }

    @Override
    public ConsumerRecords<String, byte[]> poll(Duration timeout) {
        return delegate.poll(timeout);
    }

    @Override
    public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
        delegate.commitSync(offsets);
    }

    @Override
    public void seek(TopicPartition partition, long offset) {
        delegate.seek(partition, offset);
    }

    @Override
    public void pause(Collection<TopicPartition> partitions) {
        delegate.pause(partitions);
    }

    @Override
    public void resume(Collection<TopicPartition> partitions) {
        delegate.resume(partitions);
    }

    @Override
    public void wakeup() {
        delegate.wakeup();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
