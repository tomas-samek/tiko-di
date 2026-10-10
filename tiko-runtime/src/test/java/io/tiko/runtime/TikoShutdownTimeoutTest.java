package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.tiko.ConfigSource;
import io.tiko.ConfigurationFailure;
import io.tiko.ErrorContext;
import io.tiko.config.ConfigSources;
import io.tiko.config.ConfigValidationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code tiko.shutdownTimeout} precedence — programmatic > YAML > 10s default — with the YAML value
 * bound through the standard {@code @Configuration} pipeline (#114): {@code ${VAR}} interpolation,
 * and source-anchored {@link ConfigurationFailure}s for a bad value.
 */
class TikoShutdownTimeoutTest {

    private static final ClassLoader CL = Thread.currentThread().getContextClassLoader();

    @Test
    void programmaticWinsOverYaml() throws Exception {
        TikoOptions opts = TikoOptions.builder()
                .shutdownTimeout(Duration.ofSeconds(3))
                .configSource(yaml("PT7S"))
                .build();

        assertThat(effective(opts)).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void yamlValueUsedWhenProgrammaticUnset() throws Exception {
        assertThat(effective(TikoOptions.builder().configSource(yaml("PT7S")).build()))
                .isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void yamlValueInterpolatesEnvironmentVariables() throws Exception {
        TikoOptions opts = TikoOptions.builder()
                .configSource(yaml("${TIKO_114_UNSET_SHUTDOWN:PT4S}"))
                .build();

        assertThat(effective(opts)).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void defaultTenSecondsWhenYamlHasNoValue() throws Exception {
        assertThat(effective(TikoOptions.builder().build())).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void defaultTenSecondsWithoutTikoConfig() {
        assertThat(Tiko.effectiveShutdownTimeout(TikoOptions.builder().build(), BoundConfigs.NONE))
                .isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void frameworkConfigIsNotInjectedIntoTheContainer() throws Exception {
        var bound = Tiko.bindConfigs(yaml("PT7S"), CL, ctx -> {});

        assertThat(bound.userConfigs()).isEmpty();
    }

    @Test
    void badYamlValueFailsStartupWithAnAnchoredConfigurationFailure(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("app.yaml"), "tiko:\n  shutdownTimeout: -PT7S\n");
        List<ErrorContext> routed = new ArrayList<>();
        TikoOptions opts = TikoOptions.builder()
                .configSource(ConfigSources.file(file))
                .errorHandler(routed::add)
                .build();

        var e = catchThrowableOfType(ConfigValidationException.class, () -> Tiko.create(opts));

        assertThat(e).isNotNull();
        assertThat(e.issues())
                .singleElement()
                .satisfies(i -> assertThat(i.description())
                        .contains("app.yaml:2:", "tiko.shutdownTimeout", "must not be negative")
                        .doesNotContain("-PT7S"));
        assertThat(routed).singleElement().isInstanceOf(ConfigurationFailure.class);
    }

    private static Duration effective(TikoOptions opts) throws Exception {
        return Tiko.effectiveShutdownTimeout(opts, Tiko.bindConfigs(opts.configSource(), CL, ctx -> {}));
    }

    private static ConfigSource yaml(String shutdownTimeout) {
        return ConfigSources.fromMap(Map.of("tiko", Map.of("shutdownTimeout", shutdownTimeout)));
    }
}
