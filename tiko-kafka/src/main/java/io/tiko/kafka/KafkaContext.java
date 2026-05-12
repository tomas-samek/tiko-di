package io.tiko.kafka;

import java.time.Instant;
import org.apache.kafka.common.header.Headers;

/**
 * Transport-specific metadata exposed to {@code @KafkaSource} bridge methods as an
 * optional second parameter. Carries everything the Apache Kafka {@code ConsumerRecord}
 * exposes without binding the bridge to the underlying client type beyond {@code Headers}.
 *
 * <p>The {@link Headers} type comes from {@code org.apache.kafka.common.header}; this
 * couples bridge components to the Kafka client jar at compile time. Acceptable for the
 * MVP. A future {@code MessageHeaders} wrapper that detaches from the Apache type is a
 * follow-up.
 *
 * @param topic     source topic name
 * @param partition partition number
 * @param offset    record offset within the partition
 * @param timestamp record timestamp (producer- or broker-supplied, depending on broker config)
 * @param headers   record headers, never {@code null}
 */
public record KafkaContext(String topic, int partition, long offset, Instant timestamp, Headers headers) {}
