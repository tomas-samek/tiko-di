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

    /**
     * #531: binders are discovered with ServiceLoader, so a shaded fat jar's
     * ServicesResourceTransformer merges every module's entries instead of keeping one manifest.
     */
    @Test
    void bindersAreListedAsConfigBinderServices() throws IOException {
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
        var services = c.generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/services/io.tiko.config.ConfigBinder");
        assertThat(services).isPresent();
        assertThat(read(services.get()).lines())
                .contains("io.tiko.generated.config.ABinder", "io.tiko.generated.config.BBinder");
        assertThat(c.generatedSourceFiles()).noneMatch(f -> f.getName().contains("ConfigBinderRegistry_"));
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
        assertThat(content).contains("io.example.A=a").doesNotContain("# registry=");
    }

    private static String read(JavaFileObject file) throws IOException {
        try (var in = file.openInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
