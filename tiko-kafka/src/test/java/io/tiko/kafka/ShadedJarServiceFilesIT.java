package io.tiko.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * The shaded {@code tiko-kafka} jar carries Jackson under a relocated package only (#766). A
 * service file left under Jackson's own name ({@code META-INF/services/com.fasterxml.jackson.databind.Module})
 * names a class the jar no longer has under that name, so an application's own
 * {@code ObjectMapper.findAndRegisterModules()} fails with a {@code ServiceConfigurationError} as
 * soon as {@code tiko-kafka} is on its classpath.
 *
 * <p>Runs under failsafe ({@code mvn verify}), after {@code package} has built the shaded jar.
 */
class ShadedJarServiceFilesIT {

    private static final String SERVICES = "META-INF/services/";

    @Test
    void noServiceFileIsLeftUnderJacksonsOwnName() throws IOException {
        assertThat(entries())
                .filteredOn(name -> name.startsWith(SERVICES + "com.fasterxml."))
                .as("service files an application's own Jackson would read")
                .isEmpty();
    }

    @Test
    void everyProviderAServiceFileNamesIsAClassInTheJar() throws IOException {
        var missing = new ArrayList<String>();
        try (var jar = new JarFile(shadedJar().toFile())) {
            for (var entry : jar.stream()
                    .filter(e -> e.getName().startsWith(SERVICES) && !e.isDirectory())
                    .toList()) {
                var content = new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                for (var provider : providers(content)) {
                    if (jar.getEntry(provider.replace('.', '/') + ".class") == null) {
                        missing.add(entry.getName() + " -> " + provider);
                    }
                }
            }
        }

        assertThat(missing)
                .as("providers named by a service file but not in the jar")
                .isEmpty();
    }

    @Test
    void jacksonClassesAreOnlyInTheRelocatedPackage() throws IOException {
        assertThat(entries())
                .filteredOn(name -> name.endsWith(".class") && name.contains("com/fasterxml/"))
                .as("Jackson classes under their own name, multi-release copies included")
                .isEmpty();
        assertThat(entries())
                .filteredOn(name -> name.endsWith("module-info.class"))
                .as("module descriptors of the shaded artifacts")
                .isEmpty();
        assertThat(entries())
                .as("the relocated Jackson")
                .anyMatch(name -> name.startsWith("io/tiko/kafka/internal/jackson/databind/ObjectMapper"));
    }

    private static List<String> providers(String serviceFile) {
        return serviceFile
                .lines()
                .map(line -> line.contains("#") ? line.substring(0, line.indexOf('#')) : line)
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    private static List<String> entries() throws IOException {
        try (var jar = new JarFile(shadedJar().toFile())) {
            return jar.stream().map(entry -> entry.getName()).toList();
        }
    }

    /** The shaded jar {@code package} built: the module's main jar, not {@code original-…}. */
    private static Path shadedJar() throws IOException {
        try (var files = Files.list(Path.of("target"))) {
            var jar = files.filter(path -> {
                        var name = path.getFileName().toString();
                        return name.startsWith("tiko-kafka-")
                                && name.endsWith(".jar")
                                && !name.endsWith("-sources.jar")
                                && !name.endsWith("-javadoc.jar")
                                && !name.endsWith("-tests.jar");
                    })
                    .sorted()
                    .findFirst();
            assertThat(jar)
                    .as("shaded tiko-kafka jar in target/ (run under mvn verify)")
                    .isPresent();
            return jar.get();
        }
    }
}
