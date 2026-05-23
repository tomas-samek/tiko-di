package io.tiko.processor.topology;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;

/**
 * Bumping {@code SCHEMA_VERSION} fails this test on purpose — the change must be
 * deliberate. v1 is additive-only; any v2 needs a docs/topology-schema.md update,
 * a migration note in the release notes, and an MCP server version bump.
 */
class TopologyWriterVersionGuardTest {

    @Test
    void schemaVersionIsExactlyOne() throws Exception {
        var component = JavaFileObjects.forSourceLines(
                "io.example.X",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.*;",
                "@Component(scope = Scope.SINGLETON) public class X { @Inject public X() {} }");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(component);
        assertThat(c).succeeded();

        var file = c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/topology.json")
                .orElseThrow();
        String json;
        try (var r = new InputStreamReader(file.openInputStream(), StandardCharsets.UTF_8)) {
            json = new java.io.BufferedReader(r).lines().reduce("", (acc, line) -> acc + line + "\n");
        }
        assertThat(json)
                .as("topology schemaVersion must remain 1 — bump only with docs + MCP server update")
                .contains("\"schemaVersion\": 1");
    }
}
