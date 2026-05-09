package io.tiko.processor.config;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;

class ConfigRegistryAndManifestTest {

    @Test
    void registry_class_lists_all_binders() {
        JavaFileObject a = JavaFileObjects.forSourceLines(
                "io.example.A",
                "package io.example;",
                "import io.tiko.annotations.Configuration;",
                "@Configuration(prefix = \"a\") public record A(String x) {}");
        JavaFileObject b = JavaFileObjects.forSourceLines(
                "io.example.B",
                "package io.example;",
                "import io.tiko.annotations.Configuration;",
                "@Configuration(prefix = \"b\") public record B(String x) {}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(a, b);
        assertThat(c).succeeded();
        // Registry class name carries the same hash as TikoContainerImpl_<hash>;
        // search by simple-name pattern across generated files.
        var generated = c.generatedSourceFiles();
        var registry = generated.stream()
                .filter(f -> f.getName().contains("ConfigBinderRegistry_"))
                .findFirst()
                .orElseThrow();
        try {
            String src = new String(registry.openInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(src).contains("new ABinder()").contains("new BBinder()");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void manifest_lists_fqn_prefix_pairs() throws IOException {
        JavaFileObject a = JavaFileObjects.forSourceLines(
                "io.example.A",
                "package io.example;",
                "import io.tiko.annotations.Configuration;",
                "@Configuration(prefix = \"a\") public record A(String x) {}");
        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(a);
        assertThat(c).succeeded();
        var manifestOpt = c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/tiko/configs.txt");
        assertThat(manifestOpt).isPresent();
        String content;
        try (var r = new InputStreamReader(manifestOpt.get().openInputStream(), StandardCharsets.UTF_8)) {
            content = new java.io.BufferedReader(r).lines().reduce("", (acc, line) -> acc + line + "\n");
        }
        assertThat(content).contains("io.example.A=a");
    }
}
