package io.tiko.config.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.tiko.ConfigIssue;
import io.tiko.ConfigIssueCode;
import io.tiko.ConfigSource;
import io.tiko.config.BindContext;
import io.tiko.config.ConfigBinder;
import io.tiko.config.ConfigSources;
import io.tiko.config.ConfigValidationException;
import io.tiko.config.TikoFrameworkConfig;
import io.tiko.config.internal.TikoFrameworkConfigBinder;
import io.tiko.config.internal.coercers.Coercers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #114: the framework's own {@code tiko.*} keys bind through the standard pipeline, so they get
 * {@code ${VAR}} interpolation, source-anchored errors and unknown-key checks like any
 * {@code @Configuration}, and coexist with application records and module sub-prefixes
 * ({@code tiko.kafka}).
 */
class TikoFrameworkConfigTest {

    private static final ConfigSource DEFAULTS = ConfigSources.classpathAll("META-INF/tiko/defaults.yaml");

    record AppConfig(String name) {}

    /** An application record next to the framework one. */
    static class AppConfigBinder implements ConfigBinder<AppConfig> {
        public Class<AppConfig> type() {
            return AppConfig.class;
        }

        public String prefix() {
            return "app";
        }

        public AppConfig bind(Map<String, Object> root, BindContext ctx) {
            Map<String, Object> node = ctx.requireSection(root, "app");
            String name = ctx.requireScalar(node, "name", "app.name", Coercers.stringCoercer(), "");
            ctx.checkUnknownKeys(node, "app", Set.of("name"));
            return new AppConfig(name);
        }
    }

    record ModuleConfig(String servers) {}

    /** A module record under the framework prefix, the way tiko-kafka claims {@code tiko.kafka}. */
    static class ModuleConfigBinder implements ConfigBinder<ModuleConfig> {
        public Class<ModuleConfig> type() {
            return ModuleConfig.class;
        }

        public String prefix() {
            return "tiko.kafka";
        }

        public ModuleConfig bind(Map<String, Object> root, BindContext ctx) {
            Map<String, Object> node = ctx.requireSection(root, "tiko.kafka");
            String servers =
                    ctx.scalarOrDefault(node, "servers", "tiko.kafka.servers", Coercers.stringCoercer(), "localhost");
            ctx.checkUnknownKeys(node, "tiko.kafka", Set.of("servers"));
            return new ModuleConfig(servers);
        }
    }

    @Test
    void withoutATikoSectionTheShutdownTimeoutIsTheFrameworkDefault() {
        var bound = bind(ConfigSources.fromMap(Map.of()), new TikoFrameworkConfigBinder());

        assertThat(framework(bound).shutdownTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void shutdownTimeoutInterpolatesEnvironmentVariables() {
        var user = ConfigSources.fromMap(Map.of("tiko", Map.of("shutdownTimeout", "${TIKO_114_UNSET_SHUTDOWN:PT5S}")));

        var bound = bind(user, new TikoFrameworkConfigBinder());

        assertThat(framework(bound).shutdownTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void malformedShutdownTimeoutIsReportedAtItsSourceLocation(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("app.yaml"), "tiko:\n  shutdownTimeout: soon\n");

        var issue = singleIssue(ConfigSources.file(file), new TikoFrameworkConfigBinder());

        assertThat(issue.code()).isEqualTo(ConfigIssueCode.INVALID_VALUE);
        assertThat(issue.description()).contains("app.yaml:2:", "tiko.shutdownTimeout");
    }

    @Test
    void negativeShutdownTimeoutIsRejected(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("app.yaml"), "tiko:\n  shutdownTimeout: -PT1S\n");

        var issue = singleIssue(ConfigSources.file(file), new TikoFrameworkConfigBinder());

        assertThat(issue.code()).isEqualTo(ConfigIssueCode.INVALID_VALUE);
        assertThat(issue.description()).contains("app.yaml:2:", "must not be negative");
    }

    @Test
    void aMistypedTikoKeyIsReported() {
        var user = ConfigSources.fromMap(Map.of("tiko", Map.of("shutdownTimout", "PT5S")));

        var issue = singleIssue(user, new TikoFrameworkConfigBinder());

        assertThat(issue.code()).isEqualTo(ConfigIssueCode.UNKNOWN_KEY);
        assertThat(issue.description()).contains("tiko.shutdownTimout", "tiko.shutdownTimeout");
    }

    /** Before #114 a {@code tiko:} section failed startup in any app with its own @Configuration. */
    @Test
    void tikoSectionCoexistsWithAnApplicationRecord() {
        var user = ConfigSources.fromMap(
                Map.of("app", Map.of("name", "notes"), "tiko", Map.of("shutdownTimeout", "PT5S")));

        var bound = bind(user, new TikoFrameworkConfigBinder(), new AppConfigBinder());

        assertThat(framework(bound).shutdownTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(bound.get(AppConfig.class)).isEqualTo(new AppConfig("notes"));
    }

    @Test
    void aModuleSubPrefixUnderTikoIsNotAnUnknownKey() {
        var user = ConfigSources.fromMap(
                Map.of("tiko", Map.of("shutdownTimeout", "PT5S", "kafka", Map.of("servers", "broker:9092"))));

        var bound = bind(user, new TikoFrameworkConfigBinder(), new ModuleConfigBinder());

        assertThat(framework(bound).shutdownTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(bound.get(ModuleConfig.class)).isEqualTo(new ModuleConfig("broker:9092"));
    }

    @Test
    void aMistypedModuleSectionUnderTikoIsStillReported() {
        var user = ConfigSources.fromMap(Map.of("tiko", Map.of("kavka", Map.of("servers", "broker:9092"))));

        var e = catchThrowableOfType(
                ConfigValidationException.class,
                () -> bind(user, new TikoFrameworkConfigBinder(), new ModuleConfigBinder()));

        // The typo is reported, with the module's section offered as the likely intent.
        assertThat(e.issues())
                .filteredOn(i -> i.code() == ConfigIssueCode.UNKNOWN_KEY)
                .singleElement()
                .satisfies(i -> assertThat(i.description()).contains("tiko.kavka", "tiko.kafka"));
    }

    private static Map<Class<?>, Object> bind(ConfigSource user, ConfigBinder<?>... binders) {
        return ConfigBootstrap.bind("config", ConfigSources.layered(DEFAULTS, user), List.of(binders));
    }

    private static ConfigIssue singleIssue(ConfigSource user, ConfigBinder<?>... binders) {
        var e = catchThrowableOfType(ConfigValidationException.class, () -> bind(user, binders));
        assertThat(e).as("binding must fail").isNotNull();
        assertThat(e.issues()).hasSize(1);
        return e.issues().get(0);
    }

    private static TikoFrameworkConfig framework(Map<Class<?>, Object> bound) {
        return (TikoFrameworkConfig) bound.get(TikoFrameworkConfig.class);
    }
}
