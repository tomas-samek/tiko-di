package io.tiko.kafka.test;

import io.tiko.kafka.client.KafkaConsumerClient;
import io.tiko.kafka.client.KafkaProducerClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;

/**
 * In-memory stand-in for a Kafka broker, sufficient for unit-testing
 * {@code KafkaTransportBootstrap} and bridge components without running Docker. Not a
 * production component.
 *
 * <p>Each topic has a single partition (id 0); each record gets a monotonically increasing
 * offset. The fake supports per-record commit, seek, and wakeup so the bootstrap's
 * happy-path AND error-path code paths are testable.
 *
 * <p>Usage:
 * <pre>{@code
 * FakeKafkaBroker broker = new FakeKafkaBroker();
 * KafkaProducerClient producer = broker.producerClient();
 * KafkaConsumerClient consumer = broker.consumerClient("group-a");
 *
 * broker.produce("orders", "{...}".getBytes(StandardCharsets.UTF_8), "X-Trace", "abc");
 *
 * consumer.subscribe(List.of("orders"));
 * ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(10));
 * // ... assertions ...
 *
 * List<ProducerRecord<String, byte[]>> produced = broker.produced("orders");
 * }</pre>
 */
public final class FakeKafkaBroker {

    private final Map<String, List<StoredRecord>> records = new ConcurrentHashMap<>();
    private final List<ProducerRecord<String, byte[]>> produced = new ArrayList<>();

    /** Inject a record into the fake's storage for inbound testing. */
    public synchronized void produce(String topic, byte[] payload, String... headerKv) {
        Headers headers = new RecordHeaders();
        for (int i = 0; i + 1 < headerKv.length; i += 2) {
            headers.add(
                    new RecordHeader(headerKv[i], headerKv[i + 1].getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        List<StoredRecord> list = records.computeIfAbsent(topic, k -> new ArrayList<>());
        list.add(new StoredRecord(list.size(), payload, headers, System.currentTimeMillis()));
    }

    /** All records that were sent through {@link #producerClient()} for the given topic, in send order. */
    public synchronized List<ProducerRecord<String, byte[]>> produced(String topic) {
        List<ProducerRecord<String, byte[]>> result = new ArrayList<>();
        for (ProducerRecord<String, byte[]> r : produced) {
            if (r.topic().equals(topic)) result.add(r);
        }
        return result;
    }

    /** Look up the latest record by header value, useful for correlation-id-keyed assertions. */
    public synchronized Optional<ProducerRecord<String, byte[]>> findProduced(
            String topic, String headerKey, String headerValue) {
        return produced(topic).stream()
                .filter(r -> {
                    Header h = r.headers().lastHeader(headerKey);
                    return h != null
                            && new String(h.value(), java.nio.charset.StandardCharsets.UTF_8).equals(headerValue);
                })
                .findFirst();
    }

    /** Returns a {@link KafkaProducerClient} that captures sends into this broker's storage. */
    public KafkaProducerClient producerClient() {
        return new FakeProducerClient(this);
    }

    /** Returns a {@link KafkaConsumerClient} bound to the given consumer group. */
    public KafkaConsumerClient consumerClient(String consumerGroup) {
        return new FakeConsumerClient(this, consumerGroup);
    }

    // --- internal types -----------------------------------------------------------

    record StoredRecord(int offset, byte[] payload, Headers headers, long timestamp) {
        // Override the record defaults because of the byte[] component (S6218): the generated
        // equals/hashCode/toString use array identity, not contents.
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o
                    instanceof
                    StoredRecord(int otherOffset, byte[] otherPayload, Headers otherHeaders, long otherTs))) {
                return false;
            }
            return offset == otherOffset
                    && timestamp == otherTs
                    && Arrays.equals(payload, otherPayload)
                    && Objects.equals(headers, otherHeaders);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(offset, timestamp, headers) + Arrays.hashCode(payload);
        }

        @Override
        public String toString() {
            return "StoredRecord[offset=" + offset + ", payload=" + Arrays.toString(payload) + ", headers=" + headers
                    + ", timestamp=" + timestamp + "]";
        }
    }

    synchronized List<StoredRecord> recordsFor(String topic) {
        return records.computeIfAbsent(topic, k -> new ArrayList<>());
    }

    synchronized void capture(ProducerRecord<String, byte[]> record) {
        produced.add(record);
        // Also store under the topic so a consumer subscribed to the same topic can see it.
        produce(
                record.topic(),
                record.value(),
                Optional.ofNullable(record.headers().lastHeader("X-Correlation-Id"))
                        .map(h -> new String(h.value(), java.nio.charset.StandardCharsets.UTF_8))
                        .map(v -> new String[] {"X-Correlation-Id", v})
                        .orElse(new String[0]));
    }

    private static final class FakeProducerClient implements KafkaProducerClient {
        private final FakeKafkaBroker broker;

        FakeProducerClient(FakeKafkaBroker broker) {
            this.broker = broker;
        }

        @Override
        public Future<RecordMetadata> send(ProducerRecord<String, byte[]> record) {
            broker.capture(record);
            RecordMetadata md = new RecordMetadata(
                    new TopicPartition(record.topic(), 0),
                    broker.recordsFor(record.topic()).size() - 1L,
                    0,
                    System.currentTimeMillis(),
                    0,
                    record.value() == null ? 0 : record.value().length);
            return CompletableFuture.completedFuture(md);
        }

        @Override
        public void close() {
            /* nothing */
        }
    }

    private static final class FakeConsumerClient implements KafkaConsumerClient {
        private final FakeKafkaBroker broker;

        @SuppressWarnings("unused")
        private final String consumerGroup;

        private Collection<String> subscribed = List.of();
        private final Map<TopicPartition, AtomicLong> positions = new HashMap<>();
        private volatile boolean wakeup;

        FakeConsumerClient(FakeKafkaBroker broker, String consumerGroup) {
            this.broker = broker;
            this.consumerGroup = consumerGroup;
        }

        @Override
        public void subscribe(Collection<String> topics) {
            this.subscribed = List.copyOf(topics);
            for (String t : topics) positions.putIfAbsent(new TopicPartition(t, 0), new AtomicLong(0));
        }

        @Override
        public ConsumerRecords<String, byte[]> poll(Duration timeout) {
            if (wakeup) {
                wakeup = false;
                throw new org.apache.kafka.common.errors.WakeupException();
            }
            Map<TopicPartition, List<ConsumerRecord<String, byte[]>>> out = new HashMap<>();
            for (String topic : subscribed) {
                TopicPartition tp = new TopicPartition(topic, 0);
                long pos = positions.computeIfAbsent(tp, k -> new AtomicLong(0)).get();
                List<StoredRecord> stored = broker.recordsFor(topic);
                List<ConsumerRecord<String, byte[]>> batch = new ArrayList<>();
                for (int i = (int) pos; i < stored.size(); i++) {
                    StoredRecord r = stored.get(i);
                    batch.add(new ConsumerRecord<>(
                            topic,
                            0,
                            r.offset(),
                            r.timestamp(),
                            org.apache.kafka.common.record.TimestampType.CREATE_TIME,
                            0,
                            r.payload() == null ? 0 : r.payload().length,
                            null,
                            r.payload(),
                            r.headers(),
                            Optional.empty()));
                }
                if (!batch.isEmpty()) {
                    positions.get(tp).set(stored.size());
                    out.put(tp, batch);
                }
            }
            return new ConsumerRecords<>(out);
        }

        @Override
        public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
            // Fake doesn't persist committed offsets across instances — runtime commits are
            // verified through call-recording in tests if needed.
        }

        @Override
        public void seek(TopicPartition partition, long offset) {
            positions.computeIfAbsent(partition, k -> new AtomicLong()).set(offset);
        }

        @Override
        public void wakeup() {
            wakeup = true;
        }

        @Override
        public void close() {
            /* nothing */
        }
    }
}
