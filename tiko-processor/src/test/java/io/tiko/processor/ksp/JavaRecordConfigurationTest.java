package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import com.tschuchort.compiletesting.KotlinCompilation;
import com.tschuchort.compiletesting.SourceFile;
import io.tiko.processor.TikoAnnotationProcessor;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.util.ErrorReporter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code @Configuration} on a Java record processed by KSP (#583). KSP doesn't expose a Java
 * record's header, so its components come from the canonical constructor. When the record
 * declares that constructor next to another one whose parameters all match accessors, the
 * components are unknown, and the record is rejected with a located error rather than bound by a
 * guess. javac reads the header, so the same record compiles there: the divergence is intended.
 */
class JavaRecordConfigurationTest {

    /** A declared canonical constructor next to the same components in another order. */
    private static final String AMBIGUOUS = String.join(
            "\n",
            "package app;", // 1
            "", // 2
            "import io.tiko.annotations.Configuration;", // 3
            "", // 4
            "@Configuration(prefix = \"app\")", // 5
            "public record AppConfig(int port, String host) {", // 6
            "    public AppConfig(String host, int port) { this(port, host); }", // 7
            "    public AppConfig(int port, String host) { this.port = port; this.host = host; }", // 8
            "}"); // 9

    @Test
    void ambiguousCanonicalConstructorIsRejectedOnKsp() {
        var result = KspCompiler.compile(
                new TikoSymbolProcessorProvider(), KspCompiler.java("app/AppConfig.java", AMBIGUOUS), anchor());

        WiringErrorsJson.assertRejected(
                result,
                ErrorReporter.KIND_OTHER,
                List.of(
                        "The components of record 'AppConfig' can't be told on KSP",
                        "javac builds read the header and are unaffected",
                        "1. Let the compiler supply the canonical constructor",
                        "2. Give the other constructor a parameter that no accessor matches",
                        "3. If the record comes from a library, bind a record of your own"));
        assertThat(result.messages().lines())
                .as(result.messages())
                .anySatisfy(line -> assertThat(line)
                        .contains("AppConfig.java:6: ")
                        .contains("The components of record 'AppConfig'"));
    }

    @Test
    void nestedRecordWithAmbiguousCanonicalConstructorIsRejectedOnKsp() {
        var config = KspCompiler.java(
                "app/AppConfig.java",
                "package app;",
                "",
                "import io.tiko.annotations.Configuration;",
                "import java.util.Optional;",
                "",
                "@Configuration(prefix = \"app\")",
                "public record AppConfig(String host, Optional<Pool> pool) {}");
        var pool = KspCompiler.java(
                "app/Pool.java",
                "package app;",
                "",
                "public record Pool(int size, String name) {",
                "    public Pool(int size, String name) { this.size = size; this.name = name; }",
                "    public int sum() { return size; }",
                "    public Pool(int size, String name, int sum) { this(size + sum, name); }",
                "}");

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), config, pool, anchor());

        WiringErrorsJson.assertRejected(
                result, ErrorReporter.KIND_OTHER, List.of("The components of record 'Pool' can't be told on KSP"));
    }

    @Test
    void nestedRecordSharedByTwoConfigurationsIsReportedOnce() {
        var first = configReferencingPool("app/FirstConfig.java", "FirstConfig", "first");
        var second = configReferencingPool("app/SecondConfig.java", "SecondConfig", "second");
        var pool = KspCompiler.java(
                "app/Pool.java",
                "package app;",
                "",
                "public record Pool(int size) {",
                "    public Pool(int size) { this.size = size; }",
                "    public Pool() { this(1); }",
                "}");

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), first, second, pool, anchor());

        WiringErrorsJson.assertRejected(
                result, ErrorReporter.KIND_OTHER, List.of("The components of record 'Pool' can't be told on KSP"));
        assertThat(WiringErrorsJson.errors(result))
                .filteredOn(error -> String.valueOf(error.get("message")).contains("record 'Pool'"))
                .hasSize(1);
    }

    /**
     * A record compiled into a library has no synthesized constructor: its canonical one counts as
     * declared, so a no-arg convenience constructor next to it makes the components unknown.
     */
    @Test
    void libraryRecordWithConvenienceConstructorIsRejectedOnKsp() {
        var library = KspCompiler.library(KspCompiler.java(
                "lib/Pool.java",
                "package lib;",
                "",
                "public record Pool(int size, String name) {",
                "    public Pool() { this(1, \"default\"); }",
                "}"));
        var config = KspCompiler.java(
                "app/AppConfig.java",
                "package app;",
                "",
                "import io.tiko.annotations.Configuration;",
                "",
                "@Configuration(prefix = \"app\")",
                "public record AppConfig(String host, lib.Pool pool) {}");

        var result = KspCompiler.compile(
                List.of(new TikoSymbolProcessorProvider()), Map.of(), List.of(library), config, anchor());

        WiringErrorsJson.assertRejected(
                result,
                ErrorReporter.KIND_OTHER,
                List.of(
                        "The components of record 'Pool' can't be told on KSP",
                        "3. If the record comes from a library, bind a record of your own with the same components"));
    }

    @Test
    void compactCanonicalConstructorAloneBindsOnKsp(@TempDir Path dir) throws Exception {
        var config = KspCompiler.java(
                "app/AppConfig.java",
                "package app;",
                "",
                "import io.tiko.annotations.Configuration;",
                "",
                "@Configuration(prefix = \"app\")",
                "public record AppConfig(int port, String host) {",
                "    public AppConfig {",
                "        if (port < 0) throw new IllegalArgumentException(\"port\");",
                "    }",
                "}");
        var server = KspCompiler.kotlin(
                "Server.kt",
                "package app",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Server(val config: AppConfig) {",
                "    fun describe(): String = config.host() + \":\" + config.port()",
                "}");
        var yaml = dir.resolve("app.yaml");
        Files.writeString(
                yaml, String.join("\n", "app:", "  port: 8080", "  host: example.org"), StandardCharsets.UTF_8);

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), config, server);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        try (var program = KspProgram.boot(result, yaml)) {
            var instance = program.get("app.Server");
            assertThat(instance.getClass().getMethod("describe").invoke(instance))
                    .isEqualTo("example.org:8080");
        }
    }

    @Test
    void ambiguousCanonicalConstructorCompilesOnJavac() {
        var compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(JavaFileObjects.forSourceString("app.AppConfig", AMBIGUOUS));

        CompilationSubject.assertThat(compilation).succeeded();
    }

    private static SourceFile configReferencingPool(String path, String name, String prefix) {
        return KspCompiler.java(
                path,
                "package app;",
                "",
                "import io.tiko.annotations.Configuration;",
                "",
                "@Configuration(prefix = \"" + prefix + "\")",
                "public record " + name + "(String host, Pool pool) {}");
    }

    private static SourceFile anchor() {
        return KspCompiler.kotlin("Anchor.kt", "package app", "", "@io.tiko.annotations.Component class Anchor");
    }
}
