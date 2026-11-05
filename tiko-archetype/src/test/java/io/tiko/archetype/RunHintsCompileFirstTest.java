package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code exec:java} doesn't compile, so on a freshly scaffolded project a bare {@code mvn exec:java}
 * fails with {@code ClassNotFoundException}. Every run hint a new user or agent reads says
 * {@code mvn compile exec:java}.
 */
class RunHintsCompileFirstTest {

    private static final Path TEMPLATE = Path.of("src", "main", "resources", "archetype-resources");
    private static final Pattern BARE_EXEC_JAVA = Pattern.compile("mvn\\s+exec:java");

    static Stream<Path> runHintFiles() throws IOException {
        try (var files = Files.walk(TEMPLATE)) {
            var template = files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md") || p.toString().endsWith(".java"))
                    .toList();
            return Stream.concat(Stream.of(Path.of("..", "README.md")), template.stream());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("runHintFiles")
    void runHintCompilesBeforeExecJava(Path file) throws IOException {
        assertThat(BARE_EXEC_JAVA.matcher(Files.readString(file)).find())
                .as("%s says `mvn exec:java` without `compile`", file)
                .isFalse();
    }
}
