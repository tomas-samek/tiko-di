package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #114: a fat jar on the classpath next to the jars it bundles repeats their
 * {@code META-INF/tiko/configs.txt}. The same registry named twice is the same binders, so it is
 * bound once — not reported as a duplicate {@code @Configuration} prefix.
 */
class ConfigRegistryDiscoveryTest {

    @Test
    void aRegistryNamedByTwoManifestsIsBoundOnce(@TempDir Path fatJar) throws Exception {
        Path meta = Files.createDirectories(fatJar.resolve("META-INF").resolve("tiko"));
        Files.writeString(
                meta.resolve("configs.txt"), "# registry=io.tiko.config.internal.TikoFrameworkConfigRegistry\n");

        try (var cl = new URLClassLoader(
                new URL[] {fatJar.toUri().toURL()}, getClass().getClassLoader())) {
            var bound = Tiko.bindConfigs(null, cl, ctx -> {});

            assertThat(bound.shutdownTimeout()).isEqualTo(Duration.ofSeconds(10));
        }
    }
}
