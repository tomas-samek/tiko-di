package io.tiko.processor.topology;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import java.io.IOException;
import java.util.stream.Stream;
import javax.tools.StandardLocation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code config-schema.json} is valid JSON for any {@code @Default} the processor accepts (SEC-5,
 * #496): strings with control characters are escaped, and numeric defaults are written in JSON's
 * number syntax, or as a string when the value has no JSON number form.
 */
class ConfigSchemaDefaultsJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Field type, the {@code @Default} literal as written in Java source, expected JSON default. */
    static Stream<Arguments> defaults() {
        return Stream.of(
                Arguments.of("control characters in a String", "String", "a\\nb\\u0001c", "\"a\\nb\\u0001c\""),
                Arguments.of("NaN double", "double", "NaN", "\"NaN\""),
                Arguments.of("Infinity double", "double", "Infinity", "\"Infinity\""),
                Arguments.of("hexadecimal double", "double", "0x1p3", "8.0"),
                Arguments.of("explicit-sign double", "double", "+1", "1.0"),
                Arguments.of("trailing-dot double", "double", "1.", "1.0"),
                Arguments.of("type-suffixed double", "double", "1d", "1.0"),
                Arguments.of("explicit-sign long", "long", "+1", "1"),
                Arguments.of("plain double", "double", "3.14", "3.14"),
                Arguments.of("plain int", "int", "10", "10"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("defaults")
    void schemaIsValidJsonWithTheDefault(String name, String type, String javaLiteral, String expectedJson)
            throws IOException {
        var c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(JavaFileObjects.forSourceLines(
                        "io.example.X",
                        "package io.example;",
                        "import io.tiko.annotations.*;",
                        "@Configuration(prefix = \"x\")",
                        "public record X(@Default(\"" + javaLiteral + "\") " + type + " value) {}"));
        assertThat(c).succeeded();

        JsonNode schema = JSON.readTree(schemaText(c));

        assertThat(schema.findValue("value").get("default")).isEqualTo(JSON.readTree(expectedJson));
    }

    private static String schemaText(Compilation c) throws IOException {
        var file = c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/config-schema.json")
                .orElseThrow();
        try (var in = file.openInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
