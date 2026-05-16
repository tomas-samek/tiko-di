package io.tiko.examples.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.runtime.Tiko;
import org.junit.jupiter.api.Test;

/**
 * End-to-end verification of #63: {@code Set<X>} fields bind from YAML lists,
 * dedupe duplicates via {@code LinkedHashSet}, and preserve first-occurrence
 * order. Uses {@link AllowlistConfig} (a test-only top-level
 * {@code @Configuration} record) so the assertion can inspect the bound value
 * directly through {@code container.get(...)}.
 */
class ConfigurationSetFieldTest {

    @Test
    void setFieldDedupesAndPreservesOrderEndToEnd() {
        try (Container c = Tiko.create(ConfigSources.classpath("set-config.yaml"))) {
            AllowlistConfig cfg = c.get(AllowlistConfig.class);
            assertThat(cfg.hosts()).containsExactly("alpha", "beta", "gamma");
        }
    }
}
