package io.tiko.kafka.client;

import io.tiko.kafka.KafkaConfig;
import java.util.Properties;
import java.util.concurrent.Future;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Production {@link KafkaProducerClient} backed by {@link KafkaProducer}. Constructed
 * once per container by {@link io.tiko.kafka.runtime.KafkaTransportBootstrap} and shared
 * across every {@code @KafkaSink} subscription.
 *
 * <p>Tiko-owned settings ({@code bootstrap.servers}, {@code key.serializer},
 * {@code value.serializer}) win over user-supplied {@code producer-properties} on
 * collision.
 */
public final class ApacheKafkaProducerClient implements KafkaProducerClient {

    private final KafkaProducer<String, byte[]> producer;

    public ApacheKafkaProducerClient(KafkaConfig config) {
        Properties props = new Properties();
        if (config.producerProperties() != null) {
            props.putAll(config.producerProperties());
        }
        // Tiko-owned settings win.
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        this.producer = new KafkaProducer<>(props);
    }

    @Override
    public Future<RecordMetadata> send(ProducerRecord<String, byte[]> record, Callback callback) {
        return producer.send(record, callback);
    }

    @Override
    public void close() {
        producer.close();
    }
}
