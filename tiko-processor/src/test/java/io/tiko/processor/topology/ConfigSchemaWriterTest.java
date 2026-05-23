package io.tiko.processor.topology;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;

class ConfigSchemaWriterTest {

    @Test
    void coversRequiredOptionalDefaultedAcrossSeveralTypes() throws Exception {
        JavaFileObject cfg = JavaFileObjects.forSourceLines(
                "io.example.DbConfig",
                "package io.example;",
                "import io.tiko.annotations.*;",
                "import java.time.Duration;",
                "import java.util.List;",
                "import java.util.Optional;",
                "@Configuration(prefix = \"database\")",
                "public record DbConfig(",
                "  String url,",
                "  String username,",
                "  @Default(\"10\") int poolSize,",
                "  @Default(\"PT30S\") Duration connectTimeout,",
                "  Optional<String> ca,",
                "  List<String> hosts",
                ") {}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(cfg);
        assertThat(c).succeeded();

        var file = c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/config-schema.json")
                .orElseThrow();
        String json;
        try (var r = new InputStreamReader(file.openInputStream(), StandardCharsets.UTF_8)) {
            json = new java.io.BufferedReader(r).lines().reduce("", (acc, line) -> acc + line + "\n");
        }

        // Draft + id
        assertThat(json).contains("\"$schema\": \"https://json-schema.org/draft/2020-12/schema\"");
        assertThat(json).contains("\"$id\": \"tiko://config-schema\"");

        // Per-prefix block
        assertThat(json).contains("\"database\":");
        assertThat(json).contains("\"title\": \"io.example.DbConfig\"");
        assertThat(json).contains("\"additionalProperties\": false");

        // Fields
        assertThat(json).contains("\"url\":").contains("\"type\":\"string\"");
        assertThat(json)
                .contains("\"poolSize\":")
                .contains("\"type\":\"integer\"")
                .contains("\"default\":\"10\"");
        assertThat(json).contains("\"connectTimeout\":").contains("\"format\":\"duration\"");
        assertThat(json).contains("\"hosts\":").contains("\"type\":\"array\"");

        // required[] lists url + username (REQUIRED), excludes ca (OPTIONAL) and defaulted fields
        assertThat(json).contains("\"required\":");
        // crude check: required block contains url + username, doesn't contain poolSize
        int requiredStart = json.indexOf("\"required\":");
        int requiredEnd = json.indexOf("]", requiredStart);
        String requiredSlice = json.substring(requiredStart, requiredEnd);
        assertThat(requiredSlice).contains("\"url\"").contains("\"username\"");
        assertThat(requiredSlice).doesNotContain("poolSize");
        assertThat(requiredSlice).doesNotContain("\"ca\"");
    }

    @Test
    void noConfigurationsMeansNoSchemaFile() {
        JavaFileObject plain = JavaFileObjects.forSourceLines(
                "io.example.X",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.*;",
                "@Component(scope = Scope.SINGLETON) public class X { @Inject public X() {} }");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(plain);
        assertThat(c).succeeded();

        assertThat(c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/config-schema.json"))
                .isNotPresent();
    }

    @Test
    void optOutSuppressesConfigSchemaJson() {
        JavaFileObject cfg = JavaFileObjects.forSourceLines(
                "io.example.X",
                "package io.example;",
                "import io.tiko.annotations.*;",
                "@Configuration(prefix = \"x\") public record X(String s) {}");

        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .withOptions("-Atiko.topology.bundle=false")
                .compile(cfg);
        assertThat(c).succeeded();

        assertThat(c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/config-schema.json"))
                .isNotPresent();
    }
}
