package io.tiko.processor.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The core's packages keep to their frontend: the neutral model ({@code model/}) and the root
 * package (diagnostics, output, the shared rules) import neither javac's processing API nor the
 * KSP API, the javac adapter ({@code javac/}) never imports KSP, and the KSP adapter
 * ({@code ksp/}) never imports javac's processing API. JavaPoet's {@code Modifier} enum is the one
 * allowed {@code javax.lang.model} import: it is JavaPoet's builder vocabulary and carries no
 * compiler state.
 */
class FrontendBoundaryTest {

    private static final List<String> JAVAC_API = List.of("javax.lang.model", "javax.annotation.processing");
    private static final List<String> KSP_API = List.of("com.google.devtools.ksp");
    private static final String JAVAPOET_MODIFIER_IMPORT = "import javax.lang.model.element.Modifier;";

    private static final Path CORE =
            Path.of(System.getProperty("user.dir"), "src", "main", "java", "io", "tiko", "processor", "core");

    @ParameterizedTest(name = "{0}")
    @MethodSource("boundaries")
    void packageImportsOnlyItsFrontend(String name, Path root, boolean recurse, List<String> forbidden)
            throws IOException {
        assertThat(root).isDirectory();
        try (var files = recurse ? Files.walk(root) : Files.list(root)) {
            var offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> imports(path, forbidden))
                    .map(path -> CORE.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();

            assertThat(offenders).as(name + " sources importing " + forbidden).isEmpty();
        }
    }

    static Stream<Arguments> boundaries() {
        var neutral = Stream.concat(JAVAC_API.stream(), KSP_API.stream()).toList();
        return Stream.of(
                Arguments.of("rootPackageIsFrontendNeutral", CORE, false, neutral),
                Arguments.of("modelIsFrontendNeutral", CORE.resolve("model"), true, neutral),
                Arguments.of("javacAdapterNeverImportsKsp", CORE.resolve("javac"), true, KSP_API),
                Arguments.of("kspAdapterNeverImportsJavac", CORE.resolve("ksp"), true, JAVAC_API));
    }

    private static boolean imports(Path source, List<String> forbidden) {
        try {
            return Files.readAllLines(source).stream()
                    .map(String::strip)
                    .filter(line -> line.startsWith("import "))
                    .filter(line -> !line.equals(JAVAPOET_MODIFIER_IMPORT))
                    .map(line -> line.substring("import ".length()).replaceFirst("^static ", ""))
                    .anyMatch(imported -> forbidden.stream().anyMatch(imported::startsWith));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
