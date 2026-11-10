package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import com.tschuchort.compiletesting.KotlinCompilation;
import com.tschuchort.compiletesting.SourceFile;
import io.tiko.processor.TikoAnnotationProcessor;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.core.ksp.KspProgram;
import io.tiko.processor.util.ErrorReporter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code @Configuration} on Kotlin: a {@code @JvmRecord data class} is a record (its components are
 * the primary constructor's parameters, in order), and the generated binder fills it from YAML at
 * boot. A plain data class is rejected with a Kotlin fix.
 */
class KotlinConfigurationTest {

    @Test
    void jvmRecordDataClassBindsFromYaml(@TempDir Path dir) throws Exception {
        var source = KspCompiler.kotlin(
                "App.kt",
                "package app",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "import io.tiko.annotations.Configuration",
                "import io.tiko.annotations.Default",
                "import io.tiko.annotations.Key",
                "import java.time.Duration",
                "",
                "@Configuration(prefix = \"app\")",
                "@JvmRecord",
                "data class AppConfig(",
                "    val host: String,",
                "    val port: Int,",
                "    @Key(\"max-conn\") val maxConnections: Int,",
                "    @Default(\"5s\") val timeout: Duration,",
                "    val pool: Pool,",
                ")",
                "",
                "@JvmRecord",
                "data class Pool(val size: Int)",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Server(val config: AppConfig) {",
                "    fun describe(): String =",
                "        config.host + \":\" + config.port + \"/\" + config.maxConnections + \"/\" + config.timeout"
                        + " + \"/\" + config.pool.size",
                "}");
        var yaml = dir.resolve("app.yaml");
        Files.writeString(
                yaml,
                String.join(
                        "\n",
                        "app:",
                        "  host: example.org",
                        "  port: 8080",
                        "  max-conn: 12",
                        "  pool:",
                        "    size: 3"),
                StandardCharsets.UTF_8);

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        try (var program = KspProgram.boot(result, yaml)) {
            var server = program.get("app.Server");
            assertThat(server.getClass().getMethod("describe").invoke(server)).isEqualTo("example.org:8080/12/PT5S/3");
        }
    }

    @Test
    void plainDataClassIsRejectedWithKotlinFix() {
        var source = KspCompiler.kotlin(
                "App.kt",
                "package app",
                "",
                "import io.tiko.annotations.Configuration",
                "",
                "@Configuration(prefix = \"app\")",
                "data class AppConfig(val host: String)",
                "",
                "@io.tiko.annotations.Component class Anchor");

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);

        WiringErrorsJson.assertRejected(
                result,
                ErrorReporter.KIND_OTHER,
                List.of("@Configuration must be applied to a record", "Annotate it with @JvmRecord"));
    }

    /**
     * A {@code @JvmRecord} class from another compilation: {@code JvmRecord} has SOURCE retention,
     * so the library class carries no such annotation, yet it is a JVM record and binds (#587).
     */
    @Test
    void libraryJvmRecordNestedInConfigurationBindsFromYaml(@TempDir Path dir) throws Exception {
        var library = KspCompiler.library(KspCompiler.kotlin(
                "Db.kt", "package lib", "", "@JvmRecord", "data class Db(val url: String, val pool: Int)"));

        var result = KspCompiler.compile(
                List.of(new TikoSymbolProcessorProvider()), Map.of(), List.of(library), configNesting("lib.Db"));

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        assertThat(bootAndDescribe(result, dir)).isEqualTo("jdbc:h2:mem/4");
    }

    @Test
    void libraryJavaRecordNestedInKotlinConfigurationBindsFromYaml(@TempDir Path dir) throws Exception {
        var library = KspCompiler.library(
                KspCompiler.java("lib/Db.java", "package lib;", "", "public record Db(String url, int pool) {}"));

        var result = KspCompiler.compile(
                List.of(new TikoSymbolProcessorProvider()), Map.of(), List.of(library), configNesting("lib.Db"));

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        assertThat(bootAndDescribe(result, dir)).isEqualTo("jdbc:h2:mem/4");
    }

    /** javac reads the library class's Record attribute, so the same config compiles there too. */
    @Test
    void libraryJvmRecordNestedInConfigurationCompilesOnJavac() {
        var library = KspCompiler.library(KspCompiler.kotlin(
                "Db.kt", "package lib", "", "@JvmRecord", "data class Db(val url: String, val pool: Int)"));
        var classpath = new ArrayList<File>(List.of(library));
        for (var entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            classpath.add(new File(entry));
        }

        var compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .withClasspath(classpath)
                .compile(JavaFileObjects.forSourceLines(
                        "app.AppConfig",
                        "package app;",
                        "",
                        "import io.tiko.annotations.Configuration;",
                        "",
                        "@Configuration(prefix = \"app\")",
                        "public record AppConfig(lib.Db db) {}"));

        CompilationSubject.assertThat(compilation).succeeded();
    }

    private static SourceFile configNesting(String dbType) {
        return KspCompiler.kotlin(
                "App.kt",
                "package app",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "import io.tiko.annotations.Configuration",
                "",
                "@Configuration(prefix = \"app\")",
                "@JvmRecord",
                "data class AppConfig(val db: " + dbType + ")",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Server(val config: AppConfig) {",
                "    fun describe(): String = config.db.url + \"/\" + config.db.pool",
                "}");
    }

    private static Object bootAndDescribe(KspCompiler.KspResult result, Path dir) throws Exception {
        var yaml = dir.resolve("app.yaml");
        Files.writeString(
                yaml,
                String.join("\n", "app:", "  db:", "    url: jdbc:h2:mem", "    pool: 4"),
                StandardCharsets.UTF_8);
        try (var program = KspProgram.boot(result, yaml)) {
            var server = program.get("app.Server");
            return server.getClass().getMethod("describe").invoke(server);
        }
    }
}
