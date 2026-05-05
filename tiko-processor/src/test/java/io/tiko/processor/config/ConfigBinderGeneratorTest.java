package io.tiko.processor.config;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

class ConfigBinderGeneratorTest {

    @Test
    void simple_record_generates_binder_with_expected_calls() throws IOException {
        JavaFileObject src = JavaFileObjects.forSourceLines(
            "io.example.DbConfig",
            "package io.example;",
            "import java.time.Duration;",
            "import java.util.Optional;",
            "import io.tiko.annotations.Configuration;",
            "import io.tiko.annotations.Default;",
            "@Configuration(prefix = \"db\")",
            "public record DbConfig(String url, @Default(\"10\") int maxConnections, Optional<Duration> connectTimeout) {}"
        );
        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        // Find the generated binder file
        JavaFileObject binder = c.generatedSourceFiles().stream()
            .filter(f -> f.getName().contains("DbConfigBinder"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("DbConfigBinder not generated"));

        String content = new String(binder.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content).contains("ctx.requireSection(root, \"db\")");
        assertThat(content).contains("ctx.requireScalar(node, \"url\"");
        assertThat(content).contains("ctx.scalarOrDefault(node, \"maxConnections\"");
        assertThat(content).contains("ctx.optionalScalar(node, \"connectTimeout\"");
        assertThat(content).contains("ctx.checkUnknownKeys(node, \"db\"");
        assertThat(content).contains("return new DbConfig(");
    }
}
