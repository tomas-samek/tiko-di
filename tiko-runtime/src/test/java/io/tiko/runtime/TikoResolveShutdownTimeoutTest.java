package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.ConfigSource;
import io.tiko.ContainerInitializationException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies Tiko.resolveShutdownTimeout precedence: programmatic > YAML > 10s default.
 * A malformed YAML value surfaces as a coerce-time exception.
 */
class TikoResolveShutdownTimeoutTest {

    private static final ClassLoader CL = Thread.currentThread().getContextClassLoader();

    @Test
    void programmaticWinsOverYaml() {
        // YAML says 7s but the user explicitly set 3s programmatically.
        ConfigSource source = new MapConfigSource(Map.of("tiko", Map.of("shutdownTimeout", "PT7S")));
        TikoOptions opts = TikoOptions.builder()
                .shutdownTimeout(Duration.ofSeconds(3))
                .configSource(source)
                .build();

        assertThat(Tiko.resolveShutdownTimeout(opts, CL)).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void yamlValueUsedWhenProgrammaticUnset() {
        ConfigSource source = new MapConfigSource(Map.of("tiko", Map.of("shutdownTimeout", "PT7S")));
        TikoOptions opts = TikoOptions.builder().configSource(source).build();

        assertThat(Tiko.resolveShutdownTimeout(opts, CL)).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void defaultTenSecondsWhenNeitherSet() {
        TikoOptions opts = TikoOptions.builder().build();

        assertThat(Tiko.resolveShutdownTimeout(opts, CL)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void defaultTenSecondsWhenYamlHasNoTikoSection() {
        ConfigSource source = new MapConfigSource(Map.of("db", Map.of("url", "jdbc:test")));
        TikoOptions opts = TikoOptions.builder().configSource(source).build();

        assertThat(Tiko.resolveShutdownTimeout(opts, CL)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void negativeYamlValueThrowsAtBootstrap() {
        ConfigSource source = new MapConfigSource(Map.of("tiko", Map.of("shutdownTimeout", "PT-1S")));
        TikoOptions opts = TikoOptions.builder().configSource(source).build();

        assertThatThrownBy(() -> Tiko.resolveShutdownTimeout(opts, CL))
                .isInstanceOf(ContainerInitializationException.class)
                .hasMessageContaining("shutdownTimeout")
                .hasMessageContaining("negative");
    }

    /**
     * Minimal in-memory ConfigSource — avoids depending on ConfigSources.fromMap (which lives
     * in tiko-config and may not be on the tiko-runtime test classpath at this point).
     */
    private record MapConfigSource(Map<String, Object> data) implements ConfigSource {
        @Override
        public Map<String, Object> load() {
            return data;
        }
    }
}
