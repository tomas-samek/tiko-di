package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tiko.ConfigSource;
import io.tiko.ContainerInitializationException;
import io.tiko.config.ConfigSources;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How {@code Tiko.create} finds {@code @Configuration} binders in a packaged app (#531, #114). A
 * shaded fat jar keeps one copy of each same-named resource, except services files, which
 * {@code ServicesResourceTransformer} merges; it can also sit next to the jars it bundles.
 */
class ConfigRegistryDiscoveryTest {

    private static final String APP_BINDER = "io.tiko.runtime.TestAppConfigBinder";
    private static final String MODULE_BINDER = "io.tiko.runtime.TestModuleConfigBinder";

    private static final ConfigSource USER = ConfigSources.fromMap(Map.of(
            "warehouse", Map.of("site", "north"),
            "tiko", Map.of("shutdownTimeout", "PT5S", "kafka", Map.of("servers", "broker:9092"))));

    /**
     * The fat jar of an app with its own record plus a module: one merged services file lists
     * both binders, and the module's {@code configs.txt} is gone. Both records and the framework's
     * {@code tiko.*} keys bind.
     */
    @Test
    void appAndModuleBindersListedInOneMergedServicesFileBothBind(@TempDir Path fatJar) throws Exception {
        try (var cl = loader(withServices(fatJar, APP_BINDER, MODULE_BINDER))) {
            var bound = Tiko.bindConfigs(USER, cl, ctx -> {});

            assertThat(bound.userConfigs())
                    .containsEntry(TestAppConfigBinder.AppConfig.class, new TestAppConfigBinder.AppConfig("north"))
                    .containsEntry(
                            TestModuleConfigBinder.ModuleConfig.class,
                            new TestModuleConfigBinder.ModuleConfig("broker:9092"));
            assertThat(bound.shutdownTimeout()).isEqualTo(Duration.ofSeconds(5));
        }
    }

    /** A fat jar next to a jar it bundles lists the same binder twice; it binds once. */
    @Test
    void aBinderListedByTwoServicesFilesIsBoundOnce(@TempDir Path fatJar, @TempDir Path moduleJar) throws Exception {
        try (var cl = loader(withServices(fatJar, APP_BINDER, MODULE_BINDER), withServices(moduleJar, MODULE_BINDER))) {
            var bound = Tiko.bindConfigs(USER, cl, ctx -> {});

            assertThat(bound.userConfigs())
                    .containsOnlyKeys(TestAppConfigBinder.AppConfig.class, TestModuleConfigBinder.ModuleConfig.class);
        }
    }

    /** A stale entry (the class is gone) is a clear startup error, not a ServiceConfigurationError. */
    @Test
    void aListedBinderThatCannotBeLoadedFailsStartupClearly(@TempDir Path jar) throws Exception {
        try (var cl = loader(withServices(jar, "io.tiko.runtime.NoSuchBinder"))) {
            assertThatThrownBy(() -> Tiko.bindConfigs(USER, cl, ctx -> {}))
                    .isInstanceOf(ContainerInitializationException.class)
                    .hasMessageContaining("META-INF/services/io.tiko.config.ConfigBinder");
        }
    }

    private static Path withServices(Path jar, String... binders) throws IOException {
        Path services = Files.createDirectories(jar.resolve("META-INF").resolve("services"));
        Files.writeString(services.resolve("io.tiko.config.ConfigBinder"), String.join("\n", binders) + "\n");
        return jar;
    }

    private static URLClassLoader loader(Path... jars) throws IOException {
        var urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) urls[i] = jars[i].toUri().toURL();
        return new URLClassLoader(urls, ConfigRegistryDiscoveryTest.class.getClassLoader());
    }
}
