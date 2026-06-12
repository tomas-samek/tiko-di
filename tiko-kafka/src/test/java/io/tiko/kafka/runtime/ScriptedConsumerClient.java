package io.tiko.kafka.runtime;

import io.tiko.kafka.client.KafkaConsumerClient;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

/**
 * Scripted {@link KafkaConsumerClient} for runner tests: serves the given batches once
 * each (then empty polls), and records commits and seeks for assertions. Subclasses
 * override individual methods to inject failures.
 */
class ScriptedConsumerClient implements KafkaConsumerClient {

    final List<Map.Entry<TopicPartition, Long>> commits = new CopyOnWriteArrayList<>();
    final List<Map.Entry<TopicPartition, Long>> seeks = new CopyOnWriteArrayList<>();

    private final List<ConsumerRecords<String, byte[]>> batches;
    private volatile boolean wakeup;

    ScriptedConsumerClient(List<ConsumerRecords<String, byte[]>> batches) {
        this.batches = new CopyOnWriteArrayList<>(batches);
    }

    @Override
    public void subscribe(Collection<String> topics) {
        // scripted — nothing to do
    }

    @Override
    public synchronized ConsumerRecords<String, byte[]> poll(Duration timeout) {
        if (wakeup) {
            wakeup = false;
            throw new org.apache.kafka.common.errors.WakeupException();
        }
        if (batches.isEmpty()) {
            return new ConsumerRecords<>(Map.of());
        }
        return batches.remove(0);
    }

    @Override
    public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
        offsets.forEach((tp, om) -> commits.add(Map.entry(tp, om.offset())));
    }

    @Override
    public void seek(TopicPartition partition, long offset) {
        seeks.add(Map.entry(partition, offset));
    }

    @Override
    public void wakeup() {
        wakeup = true;
    }

    @Override
    public void close() {
        // scripted — nothing to release
    }
}
