package io.tiko.generated.config;

import io.tiko.config.BindContext;
import io.tiko.config.ConfigBinder;
import io.tiko.config.internal.coercers.Coercers;
import io.tiko.config.internal.coercers.CompositeCoercers;
import io.tiko.kafka.KafkaConfig;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Hand-maintained ConfigBinder for {@link KafkaConfig}. Kept in sync with the fields on
 * that record. This file lives in {@code src/main/java} because tiko-kafka ships
 * tiko-config binding support as part of its jar without requiring users to run
 * tiko-processor against the library's own sources.
 */
public final class KafkaConfigBinder implements ConfigBinder<KafkaConfig> {

    @Override
    public Class<KafkaConfig> type() {
        return KafkaConfig.class;
    }

    @Override
    public String prefix() {
        return "tiko.kafka";
    }

    @Override
    public KafkaConfig bind(Map<String, Object> root, BindContext ctx) {
        Map<String, Object> node = ctx.requireSection(root, "tiko.kafka");
        String bootstrapServers = ctx.scalarOrDefault(
                node,
                "bootstrap-servers",
                "tiko.kafka.bootstrap-servers",
                Coercers.stringCoercer(),
                Coercers.stringCoercer().coerce("localhost:9092"));
        String consumerGroup = ctx.scalarOrDefault(
                node,
                "consumer-group",
                "tiko.kafka.consumer-group",
                Coercers.stringCoercer(),
                Coercers.stringCoercer().coerce("tiko-app"));
        String serializer = ctx.scalarOrDefault(
                node,
                "serializer",
                "tiko.kafka.serializer",
                Coercers.stringCoercer(),
                Coercers.stringCoercer().coerce("json"));
        String autoOffsetReset = ctx.scalarOrDefault(
                node,
                "auto-offset-reset",
                "tiko.kafka.auto-offset-reset",
                Coercers.stringCoercer(),
                Coercers.stringCoercer().coerce("earliest"));
        Duration pollTimeout = ctx.scalarOrDefault(
                node,
                "poll-timeout",
                "tiko.kafka.poll-timeout",
                Coercers.durationCoercer(),
                Coercers.durationCoercer().coerce("PT0.5S"));
        Duration shutdownTimeout = ctx.scalarOrDefault(
                node,
                "shutdown-timeout",
                "tiko.kafka.shutdown-timeout",
                Coercers.durationCoercer(),
                Coercers.durationCoercer().coerce("PT5S"));
        Map<String, String> producerProperties = ctx.requireScalar(
                node,
                "producer-properties",
                "tiko.kafka.producer-properties",
                CompositeCoercers.map(Coercers.stringCoercer()),
                null);
        Map<String, String> consumerProperties = ctx.requireScalar(
                node,
                "consumer-properties",
                "tiko.kafka.consumer-properties",
                CompositeCoercers.map(Coercers.stringCoercer()),
                null);
        String poisonRecordPolicy = ctx.scalarOrDefault(
                node,
                "poison-record-policy",
                "tiko.kafka.poison-record-policy",
                Coercers.stringCoercer(),
                Coercers.stringCoercer().coerce("SEEK"));
        ctx.checkUnknownKeys(
                node,
                "tiko.kafka",
                Set.of(
                        "bootstrap-servers",
                        "consumer-group",
                        "serializer",
                        "auto-offset-reset",
                        "poll-timeout",
                        "shutdown-timeout",
                        "producer-properties",
                        "consumer-properties",
                        "poison-record-policy"));
        return new KafkaConfig(
                bootstrapServers,
                consumerGroup,
                serializer,
                autoOffsetReset,
                pollTimeout,
                shutdownTimeout,
                producerProperties,
                consumerProperties,
                poisonRecordPolicy);
    }
}
