package io.tiko.kafka.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Kafka validation and generation logic runs on the frontend-neutral processor model, so it
 * can serve javac and KSP alike. Only the javac entry class may touch javac's processing API, and
 * only the KSP entry class the KSP API.
 */
class FrontendBoundaryTest {

    private static final String JAVAC_ENTRY = "io/tiko/kafka/processor/KafkaAnnotationProcessor.java";

    private static final String KSP_ENTRY = "io/tiko/kafka/processor/ksp/KafkaSymbolProcessorProvider.java";

    private static final String KSP_PACKAGE = "com.google.devtools.ksp";

    private static final List<String> JAVAC_PACKAGES = List.of("javax.lang.model", "javax.annotation.processing");

    /**
     * JavaPoet's builder vocabulary: {@code addModifiers} takes only this JDK enum. It carries no
     * compiler state, so generators may use it on any frontend.
     */
    private static final String JAVAPOET_MODIFIER_IMPORT = "import javax.lang.model.element.Modifier;";

    /** The JavaPoet code generators. */
    private static final List<String> JAVAPOET_GENERATORS = List.of("io/tiko/kafka/processor/generator/");

    @Test
    void mainSourcesImportNoJavacTypesOutsideEntry() throws IOException {
        var mainSources = Path.of(System.getProperty("user.dir"), "src", "main", "java");

        try (var files = Files.walk(mainSources)) {
            var offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> mainSources.relativize(path).toString().replace('\\', '/'))
                    .filter(source -> !source.equals(JAVAC_ENTRY))
                    .filter(source -> importsJavacTypes(mainSources, source))
                    .sorted()
                    .toList();

            assertThat(offenders).as("main sources importing javac types").isEmpty();
        }
    }

    /**
     * #871: the KSP API stays in the KSP entry, as javac's stays in the javac entry. A source names it
     * by import or by its qualified name; either counts.
     */
    @Test
    void mainSourcesUseNoKspTypesOutsideKspEntry() throws IOException {
        var mainSources = Path.of(System.getProperty("user.dir"), "src", "main", "java");

        try (var files = Files.walk(mainSources)) {
            var offenders = files.filter(path -> path.toString().endsWith(".java"))
                    .map(path -> mainSources.relativize(path).toString().replace('\\', '/'))
                    .filter(source -> !source.equals(KSP_ENTRY))
                    .filter(source -> namesKspTypes(mainSources, source))
                    .sorted()
                    .toList();

            assertThat(offenders).as("main sources using the KSP API").isEmpty();
        }
    }

    private static boolean namesKspTypes(Path mainSources, String source) {
        try {
            return Files.readString(mainSources.resolve(source)).contains(KSP_PACKAGE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean importsJavacTypes(Path mainSources, String source) {
        var generator = JAVAPOET_GENERATORS.stream().anyMatch(source::startsWith);
        try {
            return Files.readAllLines(mainSources.resolve(source)).stream()
                    .map(String::strip)
                    .filter(line -> !(generator && line.equals(JAVAPOET_MODIFIER_IMPORT)))
                    .anyMatch(FrontendBoundaryTest::isJavacImport);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isJavacImport(String line) {
        return JAVAC_PACKAGES.stream()
                .anyMatch(pkg -> line.startsWith("import " + pkg) || line.startsWith("import static " + pkg));
    }
}
