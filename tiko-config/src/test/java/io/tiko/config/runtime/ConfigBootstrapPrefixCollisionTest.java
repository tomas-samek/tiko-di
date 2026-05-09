package io.tiko.config.runtime;

import io.tiko.config.BindContext;
import io.tiko.config.ConfigBinder;
import io.tiko.config.ConfigSources;
import io.tiko.config.ConfigValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #18: when two modules declare {@code @Configuration} records with the same prefix,
 * the runtime must fail fast with a clear message instead of silently letting one
 * binder overwrite the other (last-binder-wins, undefined order).
 */
class ConfigBootstrapPrefixCollisionTest {

    record AlphaConfig(String value) {}
    record BetaConfig(String value) {}

    private static final class AlphaBinder implements ConfigBinder<AlphaConfig> {
        public Class<AlphaConfig> type() { return AlphaConfig.class; }
        public String prefix() { return "shared"; }
        public AlphaConfig bind(Map<String, Object> root, BindContext ctx) {
            return new AlphaConfig("alpha");
        }
    }

    private static final class BetaBinder implements ConfigBinder<BetaConfig> {
        public Class<BetaConfig> type() { return BetaConfig.class; }
        public String prefix() { return "shared"; }
        public BetaConfig bind(Map<String, Object> root, BindContext ctx) {
            return new BetaConfig("beta");
        }
    }

    @Test
    void duplicate_prefix_across_binders_fails_with_clear_message() {
        List<ConfigBinder<?>> binders = List.of(new AlphaBinder(), new BetaBinder());

        assertThatThrownBy(() ->
            ConfigBootstrap.bind("test", ConfigSources.fromMap(Map.of()), binders)
        )
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("shared")
            .hasMessageContaining("AlphaConfig")
            .hasMessageContaining("BetaConfig");
    }
}
