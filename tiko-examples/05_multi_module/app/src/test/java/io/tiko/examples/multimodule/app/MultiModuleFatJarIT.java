package io.tiko.examples.multimodule.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * #537: the app packaged as a shaded fat jar (module-a, module-b and the app module in one jar)
 * boots every module's container, as the unshaded classpath does.
 */
class MultiModuleFatJarIT {

    /** The running JVM's own launcher, by absolute path. */
    private static final String JAVA =
            Path.of(System.getProperty("java.home"), "bin", "java").toString();

    @Test
    void fatJarResolvesComponentsFromEveryModule() throws Exception {
        Path jar = shadedJar();
        Process process = new ProcessBuilder(JAVA, "-jar", jar.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("the app exits").isTrue();
        assertThat(output)
                .contains("Retrieved UserService from Module A", "Retrieved NotificationService from Module B")
                .contains("Multi-module example completed successfully")
                .doesNotContain("NoSuchComponentException");
        assertThat(process.exitValue()).as(output).isZero();
    }

    private static Path shadedJar() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("target"))) {
            return files.filter(p -> p.getFileName().toString().matches("app-.*\\.jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("shaded app jar not found under target/"));
        }
    }
}
