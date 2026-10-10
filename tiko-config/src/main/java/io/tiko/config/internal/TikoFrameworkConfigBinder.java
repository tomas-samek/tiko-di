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
 * {@code Tiko.create} adds it to every binding run directly rather than through a
 * {@code META-INF/tiko/configs.txt}: tiko-config ships no {@code META-INF/tiko/} resources, so a
 * shaded fat jar can't lose a module's manifest to it (#114). The {@code tiko:} section is
 * optional; every field has a default.
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
        // Optional section: absent (or present only for module sub-prefixes) means all defaults.
        Map<String, Object> node = ctx.optionalSection(root, "tiko");
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
