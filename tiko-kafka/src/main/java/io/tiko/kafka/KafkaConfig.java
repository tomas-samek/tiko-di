package io.tiko.kafka;

import io.tiko.annotations.Configuration;
import io.tiko.annotations.Default;
import io.tiko.annotations.Key;
import java.time.Duration;
import java.util.Map;

/**
 * YAML-backed configuration root for {@code tiko-kafka}. Auto-discovered through the
 * existing {@code tiko-config} plumbing — no special path through {@code Tiko.create}.
 *
 * <p>Defaults are shipped in {@code META-INF/tiko/defaults.yaml} bundled inside this
 * jar so a user app that pulls {@code tiko-kafka} can start without a {@code config.yaml}
 * as long as the defaults (localhost broker, group {@code tiko-app}, JSON serializer,
 * earliest offset reset) are acceptable.
 *
 * <p>{@code producerProperties} and {@code consumerProperties} are pass-through into the
 * underlying Apache Kafka client {@code Properties}. Every native client knob
 * ({@code linger.ms}, {@code compression.type}, {@code max.poll.records},
 * {@code enable.idempotence}, ...) is reachable without the framework wrapping each one.
 * Tiko-supplied values ({@code bootstrap.servers}, {@code group.id},
 * {@code auto.offset.reset}, {@code key.deserializer}, {@code value.deserializer}) win on
 * collision.
 */
@Configuration(prefix = "tiko.kafka")
public record KafkaConfig(
        @Default("localhost:9092") @Key("bootstrap-servers") String bootstrapServers,
        @Default("tiko-app") @Key("consumer-group") String consumerGroup,
        @Default("json") String serializer,
        @Default("earliest") @Key("auto-offset-reset") String autoOffsetReset,
        @Default("PT0.5S") @Key("poll-timeout") Duration pollTimeout,
        @Default("PT5S") @Key("shutdown-timeout") Duration shutdownTimeout,
        @Key("producer-properties") Map<String, String> producerProperties,
        @Key("consumer-properties") Map<String, String> consumerProperties,
        @Default("SEEK") @Key("poison-record-policy") String poisonRecordPolicy) {}
