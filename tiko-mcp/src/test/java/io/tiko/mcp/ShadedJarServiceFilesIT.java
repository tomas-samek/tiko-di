package io.tiko.mcp;

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
 * Every provider a service file of the shaded {@code tiko-mcp} jar names is a class in that jar
 * (#766). The jar bundles its dependencies under their own names, so its service files are read
 * by whatever shares its classpath: one that names a class the jar does not carry fails that
 * reader's {@code ServiceLoader} with a {@code ServiceConfigurationError}.
 *
 * <p>Runs under failsafe ({@code mvn verify}), after {@code package} has built the shaded jar.
 */
class ShadedJarServiceFilesIT {

    private static final String SERVICES = "META-INF/services/";

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

    private static List<String> providers(String serviceFile) {
        return serviceFile
                .lines()
                .map(line -> line.contains("#") ? line.substring(0, line.indexOf('#')) : line)
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    /** The shaded jar {@code package} built: the module's main jar, not {@code original-…}. */
    private static Path shadedJar() throws IOException {
        try (var files = Files.list(Path.of("target"))) {
            var jar = files.filter(path -> {
                        var name = path.getFileName().toString();
                        return name.startsWith("tiko-mcp-")
                                && name.endsWith(".jar")
                                && !name.endsWith("-sources.jar")
                                && !name.endsWith("-javadoc.jar");
                    })
                    .sorted()
                    .findFirst();
            assertThat(jar)
                    .as("shaded tiko-mcp jar in target/ (run under mvn verify)")
                    .isPresent();
            return jar.get();
        }
    }
}
