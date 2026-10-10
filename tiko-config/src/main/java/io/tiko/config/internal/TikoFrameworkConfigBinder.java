package io.tiko.config.internal;

import io.tiko.ConfigIssueCode;
import io.tiko.config.BindContext;
import io.tiko.config.ConfigBinder;
import io.tiko.config.TikoFrameworkConfig;
import io.tiko.config.internal.coercers.Coercers;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Hand-maintained ConfigBinder for {@link TikoFrameworkConfig}, kept in sync with its fields.
 * tiko-config ships it (with {@code META-INF/tiko/configs.txt} and {@code defaults.yaml}) so the
 * framework's keys bind without running tiko-processor on this library's sources — the same
 * arrangement as tiko-kafka's {@code KafkaConfigBinder}.
 */
public final class TikoFrameworkConfigBinder implements ConfigBinder<TikoFrameworkConfig> {

    static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    @Override
    public Class<TikoFrameworkConfig> type() {
        return TikoFrameworkConfig.class;
    }

    @Override
    public String prefix() {
        return "tiko";
    }

    @Override
    public TikoFrameworkConfig bind(Map<String, Object> root, BindContext ctx) {
        Map<String, Object> node = ctx.requireSection(root, "tiko");
        Duration shutdownTimeout = ctx.scalarOrDefault(
                node, "shutdownTimeout", "tiko.shutdownTimeout", Coercers.durationCoercer(), DEFAULT_SHUTDOWN_TIMEOUT);
        if (shutdownTimeout.isNegative()) {
            ctx.reportAtPath(
                    ConfigIssueCode.INVALID_VALUE, "tiko.shutdownTimeout", "tiko.shutdownTimeout must not be negative");
            shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;
        }
        ctx.checkUnknownKeys(node, "tiko", Set.of("shutdownTimeout"));
        return new TikoFrameworkConfig(shutdownTimeout);
    }
}
