package io.tiko.examples.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.runtime.Tiko;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
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
            assertThat(cfg.group())
                    .hasValueSatisfying(g -> assertThat(g.members()).containsExactly("ops"));
        }
    }

    /** #485: each duplicate warning names the field — dot-path at top level, record + field when nested. */
    @Test
    void duplicateWarningsNameTheFieldEndToEnd() {
        var configLogger = Logger.getLogger("io.tiko.config");
        List<String> warnings = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel() == Level.WARNING) warnings.add(r.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        configLogger.addHandler(capture);
        try (Container c = Tiko.create(ConfigSources.classpath("set-config.yaml"))) {
            assertThat(warnings)
                    .containsExactly(
                            "@Configuration allow.hosts: duplicate element at index 2 deduped",
                            "@Configuration allow.hosts: duplicate element at index 4 deduped",
                            "@Configuration AllowlistGroup.members: duplicate element at index 1 deduped");
        } finally {
            configLogger.removeHandler(capture);
        }
    }
}
