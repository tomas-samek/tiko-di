package io.tiko.generated.config;

import io.tiko.config.ConfigBinder;
import java.util.List;

/**
 * Hand-maintained registry that advertises the {@link KafkaConfigBinder} to the
 * tiko-config runtime. Referenced from {@code META-INF/tiko/configs.txt} so that
 * {@code Tiko.create()} discovers and binds {@link io.tiko.kafka.KafkaConfig} automatically.
 */
public final class ConfigBinderRegistry {

    private ConfigBinderRegistry() {}

    public static List<ConfigBinder<?>> all() {
        return List.of(new KafkaConfigBinder());
    }
}
