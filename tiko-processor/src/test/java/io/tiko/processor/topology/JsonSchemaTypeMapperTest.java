package io.tiko.processor.topology;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;

/**
 * Behavioral test — drives the mapper via a real {@code @Configuration} compile
 * and asserts the generated {@code config-schema.json} fragment per type. This is
 * cheaper than wiring up a standalone TypeMirror fixture and exercises the same
 * code path as production.
 */
class JsonSchemaTypeMapperTest {

    @Test
    void primitivesAndStringsMapAsExpected() throws Exception {
        var json = compileAndReadSchema(JavaFileObjects.forSourceLines(
                "io.example.T",
                "package io.example;",
                "import io.tiko.annotations.*;",
                "@Configuration(prefix = \"t\")",
                "public record T(String s, int i, long l, boolean b, double d) {}"));

        assertThat(json).contains("\"s\":").contains("\"type\":\"string\"");
        assertThat(json).contains("\"i\":").contains("\"type\":\"integer\"");
        assertThat(json).contains("\"l\":").contains("\"type\":\"integer\"");
        assertThat(json).contains("\"b\":").contains("\"type\":\"boolean\"");
        assertThat(json).contains("\"d\":").contains("\"type\":\"number\"");
    }

    @Test
    void durationMapsToStringWithFormatDuration() throws Exception {
        var json = compileAndReadSchema(JavaFileObjects.forSourceLines(
                "io.example.T",
                "package io.example;",
                "import io.tiko.annotations.*;",
                "import java.time.Duration;",
                "@Configuration(prefix = \"t\") public record T(Duration d) {}"));
        assertThat(json).contains("\"format\":\"duration\"");
    }

    @Test
    void listAndSetMapToArray() throws Exception {
        var json = compileAndReadSchema(JavaFileObjects.forSourceLines(
                "io.example.T",
                "package io.example;",
                "import io.tiko.annotations.*;",
                "import java.util.List;",
                "import java.util.Set;",
                "@Configuration(prefix = \"t\") public record T(List<String> xs, Set<Integer> ys) {}"));
        assertThat(json).contains("\"xs\":").contains("\"type\":\"array\"").contains("\"items\":{\"type\":\"string\"}");
        assertThat(json).contains("\"ys\":").contains("\"items\":{\"type\":\"integer\"}");
    }

    @Test
    void enumMapsToStringEnum() throws Exception {
        var json = compileAndReadSchema(
                JavaFileObjects.forSourceLines(
                        "io.example.Mode", "package io.example;", "public enum Mode { FAST, SLOW }"),
                JavaFileObjects.forSourceLines(
                        "io.example.T",
                        "package io.example;",
                        "import io.tiko.annotations.*;",
                        "@Configuration(prefix = \"t\") public record T(Mode mode) {}"));
        assertThat(json).contains("\"mode\":");
        assertThat(json).contains("\"enum\":[\"FAST\",\"SLOW\"]");
    }

    @Test
    void nestedRecordMapsToNestedObject() throws Exception {
        var json = compileAndReadSchema(
                JavaFileObjects.forSourceLines(
                        "io.example.Inner", "package io.example;", "public record Inner(String s) {}"),
                JavaFileObjects.forSourceLines(
                        "io.example.T",
                        "package io.example;",
                        "import io.tiko.annotations.*;",
                        "@Configuration(prefix = \"t\") public record T(Inner inner) {}"));
        assertThat(json).contains("\"inner\":");
        assertThat(json).contains("\"type\":\"object\"");
        assertThat(json).contains("\"s\":");
    }

    private String compileAndReadSchema(javax.tools.JavaFileObject... sources) throws Exception {
        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(sources);
        assertThat(c).succeeded();
        var file = c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/config-schema.json")
                .orElseThrow();
        try (var r = new BufferedReader(new InputStreamReader(file.openInputStream(), StandardCharsets.UTF_8))) {
            return r.lines().reduce("", (acc, line) -> acc + line);
        }
    }
}
