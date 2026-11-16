package io.tiko.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Finds the shaded {@code tiko-mcp} jar for the {@code *IT} classes that run after {@code package}. */
final class ShadedJar {

    private ShadedJar() {}

    /** The shaded jar {@code package} built: the module's main jar, not {@code original-…}. */
    static Path find() throws IOException {
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
