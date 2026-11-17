package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/**
 * {@code config-schema.json} stays valid JSON whatever the names in the source (SEC-5, #810). A
 * Kotlin name in backticks can hold a quote, which a Java identifier cannot, so the names of enum
 * constants and of a nested record's members are escaped like every other string in the file.
 */
class KspConfigSchemaNamesTest {

    @Test
    void namesWithAQuoteAreEscapedInTheSchema() throws Exception {
        var source = KspCompiler.kotlin(
                "App.kt",
                "package app",
                "",
                "import io.tiko.annotations.Configuration",
                "",
                "enum class Mode { `fast\"er`, steady }",
                "",
                "@JvmRecord",
                "data class Pool(val `max\"size`: Int)",
                "",
                "@Configuration(prefix = \"app\")",
                "@JvmRecord",
                "data class AppConfig(val mode: Mode, val pool: Pool)");

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        var schema = new ObjectMapper()
                .readTree(result.generatedResource("META-INF/tiko/config-schema.json")
                        .orElseThrow());
        var modes = new ArrayList<String>();
        schema.findValue("mode").get("enum").forEach(constant -> modes.add(constant.asText()));
        assertThat(modes).containsExactly("fast\"er", "steady");
        assertThat(schema.findValue("pool").get("properties").fieldNames())
                .toIterable()
                .containsExactly("max\"size");
    }
}
