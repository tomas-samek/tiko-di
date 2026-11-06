package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.util.ErrorReporter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}
