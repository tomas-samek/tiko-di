package io.tiko.processor;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A build that lists only tiko-processor on its processor path, as
 * {@code annotationProcessorPaths} and {@code ksp(...)} do, gets the processor plus its runtime
 * dependency closure there and nothing else. Compiling a {@code @Configuration} record must work
 * with that alone (#670).
 *
 * <p>javac runs in a separate process: an in-process javac loads processors under the test's own
 * class loader, which already holds every test dependency.
 */
class ProcessorPathTest {

    private static final String WITHOUT_DEFAULT = """
            package app;

            import io.tiko.annotations.Configuration;

            @Configuration(prefix = "app")
            public record Cfg(int size) {}
            """;

    private static final String WITH_DEFAULT = """
            package app;

            import io.tiko.annotations.Configuration;
            import io.tiko.annotations.Default;

            @Configuration(prefix = "app")
            public record Cfg(@Default("8") int size) {}
            """;

    @TempDir
    Path work;

    static Stream<Arguments> configurationRecords() {
        return Stream.of(
                Arguments.of("without @Default", WITHOUT_DEFAULT), Arguments.of("with @Default", WITH_DEFAULT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configurationRecords")
    void configurationRecordCompilesWithOnlyTheProcessorClosureOnTheProcessorPath(String name, String source)
            throws Exception {
        var sourceFile = Files.writeString(work.resolve("Cfg.java"), source);
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "javac").toString());
        command.add("-processorpath");
        command.add(processorPath());
        command.add("-classpath");
        command.add(System.getProperty("java.class.path"));
        command.add("-d");
        command.add(Files.createDirectories(work.resolve("classes")).toString());
        command.add("-s");
        command.add(Files.createDirectories(work.resolve("generated")).toString());
        command.add(sourceFile.toString());

        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), UTF_8);

        assertThat(process.waitFor()).as("javac output:%n%s", output).isZero();
    }

    /** tiko-processor's classes plus its runtime dependencies, written by maven-dependency-plugin. */
    private static String processorPath() throws Exception {
        var closure = Path.of("target", "processor-path.txt");
        assertThat(closure)
                .as("written by maven-dependency-plugin:build-classpath")
                .exists();
        var classes = Path.of("target", "classes").toAbsolutePath().toString();
        return classes + File.pathSeparator + Files.readString(closure).strip();
    }
}
