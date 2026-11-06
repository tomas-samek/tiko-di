package io.tiko.kafka.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The Kafka validation and generation logic runs on the frontend-neutral processor model, so it
 * can serve javac and KSP alike. Only the javac entry class may touch javac's processing API.
 */
class FrontendBoundaryTest {

    private static final String JAVAC_ENTRY = "KafkaAnnotationProcessor.java";

    /**
     * JavaPoet's builder vocabulary: {@code addModifiers} takes only this JDK enum. It carries no
     * compiler state, so generators may use it on any frontend.
     */
    private static final String JAVAPOET_MODIFIER_IMPORT = "import javax.lang.model.element.Modifier;";

    @Test
    void mainSourcesImportNoJavacTypesOutsideEntry() throws IOException {
        var mainSources = Path.of(System.getProperty("user.dir"), "src", "main", "java");

        try (var files = Files.walk(mainSources)) {
            var offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals(JAVAC_ENTRY))
                    .filter(FrontendBoundaryTest::importsJavacTypes)
                    .map(path -> mainSources.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();

            assertThat(offenders).as("main sources importing javac types").isEmpty();
        }
    }

    private static boolean importsJavacTypes(Path source) {
        try {
            return Files.readAllLines(source).stream()
                    .map(String::strip)
                    .filter(line -> !line.equals(JAVAPOET_MODIFIER_IMPORT))
                    .anyMatch(line -> line.startsWith("import javax.lang.model")
                            || line.startsWith("import javax.annotation.processing"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
