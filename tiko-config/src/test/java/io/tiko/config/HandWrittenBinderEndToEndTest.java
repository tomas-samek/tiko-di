package io.tiko.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.ConfigSource;
import io.tiko.config.internal.Interpolator;
import io.tiko.config.internal.coercers.Coercers;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HandWrittenBinderEndToEndTest {

    record DbConfig(String url, int maxConnections, Optional<Duration> connectTimeout) {}

    static class DbConfigBinder implements ConfigBinder<DbConfig> {
        public Class<DbConfig> type() {
            return DbConfig.class;
        }

        public String prefix() {
            return "db";
        }

        public DbConfig bind(Map<String, Object> root, BindContext ctx) {
            Map<String, Object> node = ctx.requireSection(root, "db");
            String url = ctx.requireScalar(node, "url", "db.url", Coercers.stringCoercer(), "");
            int max = ctx.scalarOrDefault(node, "maxConnections", "db.maxConnections", Coercers.intCoercer(), 10);
            Optional<Duration> timeout =
                    ctx.optionalScalar(node, "connectTimeout", "db.connectTimeout", Coercers.durationCoercer());
            ctx.checkUnknownKeys(node, "db", Set.of("url", "maxConnections", "connectTimeout"));
            return new DbConfig(url, max, timeout);
        }
    }

    @Test
    void happy_path_binds_record_from_map_source() {
        ConfigSource src = ConfigSources.fromMap(
                Map.of("db", Map.of("url", "jdbc:postgres", "maxConnections", 20, "connectTimeout", "PT10S")));
        BindContext ctx = new BindContext("test");
        DbConfig cfg = new DbConfigBinder().bind(src.load(), ctx);

        assertThat(ctx.hasErrors()).isFalse();
        assertThat(cfg.url()).isEqualTo("jdbc:postgres");
        assertThat(cfg.maxConnections()).isEqualTo(20);
        assertThat(cfg.connectTimeout()).contains(Duration.ofSeconds(10));
    }

    @Test
    void default_substituted_when_absent() {
        ConfigSource src = ConfigSources.fromMap(Map.of("db", Map.of("url", "x")));
        BindContext ctx = new BindContext("test");
        DbConfig cfg = new DbConfigBinder().bind(src.load(), ctx);
        assertThat(cfg.maxConnections()).isEqualTo(10);
        assertThat(cfg.connectTimeout()).isEmpty();
        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void missing_required_field_accumulates_error() {
        ConfigSource src = ConfigSources.fromMap(Map.of("db", Map.of()));
        BindContext ctx = new BindContext("test");
        new DbConfigBinder().bind(src.load(), ctx);
        assertThat(ctx.hasErrors()).isTrue();
        assertThat(ctx.errors().get(0).message()).contains("db.url is required");
    }

    @Test
    void unknown_key_accumulates_error() {
        ConfigSource src = ConfigSources.fromMap(Map.of("db", Map.of("url", "x", "foo", "bar")));
        BindContext ctx = new BindContext("test");
        new DbConfigBinder().bind(src.load(), ctx);
        assertThat(ctx.errors().stream().anyMatch(e -> e.message().contains("unknown key 'db.foo'")))
                .isTrue();
    }

    @Test
    void interpolation_substitutes_env_var_before_binding() {
        Map<String, Object> raw = Map.of("db", Map.of("url", "${DB_URL:jdbc:default}"));
        BindContext ctx = new BindContext("test");
        Map<String, Object> after = (Map<String, Object>) Interpolator.interpolate(raw, k -> null, ctx);

        DbConfig cfg = new DbConfigBinder().bind(after, ctx);
        assertThat(cfg.url()).isEqualTo("jdbc:default");
        assertThat(ctx.hasErrors()).isFalse();
    }

    @Test
    void final_exception_carries_full_report() {
        ConfigSource src = ConfigSources.fromMap(Map.of("db", Map.of("maxConnections", "ten", "junk", true)));
        BindContext ctx = new BindContext("test");
        new DbConfigBinder().bind(src.load(), ctx);
        assertThatThrownBy(() -> {
                    throw new ConfigValidationException(ctx.source(), ctx.errors());
                })
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("db.url is required")
                .hasMessageContaining("db.maxConnections expected integer")
                .hasMessageContaining("unknown key 'db.junk'");
    }
}
