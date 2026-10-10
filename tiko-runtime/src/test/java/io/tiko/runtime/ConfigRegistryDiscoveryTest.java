package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.config.ConfigSources;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #114: how {@code Tiko.create} finds config registries in a packaged app. A shaded fat jar keeps
 * one copy of each same-named resource, and can sit on the classpath next to the jars it bundles.
 */
class ConfigRegistryDiscoveryTest {

    private static final String MODULE_REGISTRY = "# registry=io.tiko.runtime.TestModuleConfigRegistry\n";

    /** A fat jar sitting next to a jar it bundles names the same registry twice. */
    @Test
    void aRegistryNamedByTwoManifestsIsBoundOnce(@TempDir Path fatJar, @TempDir Path moduleJar) throws Exception {
        try (var cl = loader(withManifest(fatJar, MODULE_REGISTRY), withManifest(moduleJar, MODULE_REGISTRY))) {
            var user = ConfigSources.fromMap(Map.of("tiko", Map.of("kafka", Map.of("servers", "broker:9092"))));
            var bound = Tiko.bindConfigs(user, cl, ctx -> {});

            assertThat(bound.userConfigs()).containsOnlyKeys(TestModuleConfigRegistry.ModuleConfig.class);
        }
    }

    /**
     * In a fat jar only the module's {@code configs.txt} survives the merge; the framework's own
     * {@code tiko.*} keys still bind next to it.
     */
    @Test
    void frameworkKeysBindWhenOnlyAModuleManifestSurvivesTheFatJar(@TempDir Path fatJar) throws Exception {
        var user = ConfigSources.fromMap(
                Map.of("tiko", Map.of("shutdownTimeout", "PT5S", "kafka", Map.of("servers", "broker:9092"))));

        try (var cl = new OnlyTheseManifests(withManifest(fatJar, MODULE_REGISTRY))) {
            var bound = Tiko.bindConfigs(user, cl, ctx -> {});

            assertThat(bound.shutdownTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(bound.userConfigs())
                    .containsEntry(
                            TestModuleConfigRegistry.ModuleConfig.class,
                            new TestModuleConfigRegistry.ModuleConfig("broker:9092"));
        }
    }

    private static Path withManifest(Path jar, String manifest) throws IOException {
        Path meta = Files.createDirectories(jar.resolve("META-INF").resolve("tiko"));
        Files.writeString(meta.resolve("configs.txt"), manifest);
        return jar;
    }

    private static URLClassLoader loader(Path... jars) throws IOException {
        var urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) urls[i] = jars[i].toUri().toURL();
        return new URLClassLoader(urls, ConfigRegistryDiscoveryTest.class.getClassLoader());
    }

    /** Sees classes from the test classpath but only the given jar's {@code META-INF/tiko/} manifests. */
    private static final class OnlyTheseManifests extends URLClassLoader {
        OnlyTheseManifests(Path jar) throws IOException {
            super(new URL[] {jar.toUri().toURL()}, ConfigRegistryDiscoveryTest.class.getClassLoader());
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            return name.startsWith("META-INF/tiko/") ? findResources(name) : super.getResources(name);
        }

        @Override
        public URL getResource(String name) {
            return name.startsWith("META-INF/tiko/") ? findResource(name) : super.getResource(name);
        }
    }
}
