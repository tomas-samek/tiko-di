package io.tiko.config.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.ConfigSource;
import io.tiko.config.BindContext;
import io.tiko.config.ConfigBinder;
import io.tiko.config.ConfigSources;
import io.tiko.config.ConfigValidationException;
import io.tiko.config.internal.coercers.Coercers;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfigBootstrapTest {

    record DbConfig(String url, int max) {}

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
            int max = ctx.scalarOrDefault(node, "max", "db.max", Coercers.intCoercer(), 10);
            ctx.checkUnknownKeys(node, "db", Set.of("url", "max"));
            return new DbConfig(url, max);
        }
    }

    @Test
    void happy_path_returns_map_of_bound_records() {
        ConfigSource src = ConfigSources.fromMap(Map.of("db", Map.of("url", "x")));
        Map<Class<?>, Object> result = ConfigBootstrap.bind("config.yaml", src, List.of(new DbConfigBinder()));
        assertThat(result).hasSize(1);
        DbConfig cfg = (DbConfig) result.get(DbConfig.class);
        assertThat(cfg.url()).isEqualTo("x");
        assertThat(cfg.max()).isEqualTo(10);
    }

    @Test
    void unknown_top_level_section_is_an_error() {
        ConfigSource src = ConfigSources.fromMap(Map.of("db", Map.of("url", "x"), "kafkka", Map.of()));
        assertThatThrownBy(() -> ConfigBootstrap.bind("c.yaml", src, List.of(new DbConfigBinder())))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("unknown top-level section 'kafkka'");
    }

    @Test
    void interpolation_failure_aggregates_with_bind_errors() {
        ConfigSource src = ConfigSources.fromMap(Map.of("db", Map.of("url", "${MISSING}")));
        assertThatThrownBy(() -> ConfigBootstrap.bind("c.yaml", src, List.of(new DbConfigBinder())))
                .hasMessageContaining("MISSING");
    }
}
