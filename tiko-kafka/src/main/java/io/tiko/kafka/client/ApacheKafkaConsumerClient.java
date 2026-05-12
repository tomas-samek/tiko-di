package io.tiko.kafka.client;

import io.tiko.kafka.KafkaConfig;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Production {@link KafkaConsumerClient} backed by {@link KafkaConsumer}. One instance
 * per source topic; lifetime managed by {@code ThreadPerTopicRunner}.
 *
 * <p>Auto-commit is forced off — the runner does manual {@code commitSync(offset+1)} per
 * record on success and {@code seek} on bridge failure. Tiko-owned settings win over
 * user-supplied {@code consumer-properties} on collision.
 */
public final class ApacheKafkaConsumerClient implements KafkaConsumerClient {

    private final KafkaConsumer<String, byte[]> consumer;

    public ApacheKafkaConsumerClient(KafkaConfig config, String consumerGroup) {
        Properties props = new Properties();
        if (config.consumerProperties() != null) {
            props.putAll(config.consumerProperties());
        }
        // Tiko-owned settings win.
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, config.autoOffsetReset());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        this.consumer = new KafkaConsumer<>(props);
    }

    @Override
    public void subscribe(Collection<String> topics) {
        consumer.subscribe(topics);
    }

    @Override
    public ConsumerRecords<String, byte[]> poll(Duration timeout) {
        return consumer.poll(timeout);
    }

    @Override
    public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
        consumer.commitSync(offsets);
    }

    @Override
    public void seek(TopicPartition partition, long offset) {
        consumer.seek(partition, offset);
    }

    @Override
    public void wakeup() {
        consumer.wakeup();
    }

    @Override
    public void close() {
        consumer.close();
    }
}
