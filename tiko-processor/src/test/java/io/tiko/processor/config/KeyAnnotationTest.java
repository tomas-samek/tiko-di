package io.tiko.processor.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Coverage for the {@code @Key} annotation (#168) — previously zero, on a shipped, production-used
 * feature (e.g. {@code tiko-kafka}'s {@code KafkaConfig}). {@code @Key} remaps a config field to a
 * YAML key that differs from the field name. These tests pin the mapping at the point that
 * determines it: the generated binder/coercer reads the {@code @Key} value, not the field name —
 * which is also what makes a required-field-missing diagnostic cite the YAML key. Compile-testing
 * (mirroring {@link ConfigBinderGeneratorTest}) keeps each case isolated; an end-to-end bind would
 * couple to eager all-config binding and perturb the sibling config tests.
 */
class KeyAnnotationTest {

    private static String generatedSource(Compilation c, String nameFragment) throws IOException {
        JavaFileObject file = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains(nameFragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError(nameFragment + " not generated; got: "
                        + c.generatedSourceFiles().stream()
                                .map(JavaFileObject::getName)
                                .toList()));
        return new String(file.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void keyRemapsScalarFieldsAndDrivesRequiredDiagnostic() throws IOException {
        JavaFileObject src = JavaFileObjects.forSourceLines(
                "io.example.SvcConfig",
                "package io.example;",
                "import io.tiko.annotations.Configuration;",
                "import io.tiko.annotations.Key;",
                "@Configuration(prefix = \"svc\")",
                "public record SvcConfig(@Key(\"max-connections\") int maxConnections, @Key(\"api-token\") String apiToken) {}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        CompilationSubject.assertThat(c).succeeded();

        String binder = generatedSource(c, "SvcConfigBinder");
        // Both fields are read under their @Key values; the required-scalar call carries the YAML
        // key, so a missing-key error at runtime names "max-connections", not "maxConnections".
        assertThat(binder).contains("ctx.requireScalar(node, \"max-connections\"");
        assertThat(binder).contains("ctx.requireScalar(node, \"api-token\"");
        assertThat(binder).doesNotContain("\"maxConnections\"");
    }

    @Test
    void keyCombinesWithDefault() throws IOException {
        JavaFileObject src = JavaFileObjects.forSourceLines(
                "io.example.TimeoutConfig",
                "package io.example;",
                "import io.tiko.annotations.Configuration;",
                "import io.tiko.annotations.Default;",
                "import io.tiko.annotations.Key;",
                "@Configuration(prefix = \"svc\")",
                "public record TimeoutConfig(@Key(\"idle-seconds\") @Default(\"30\") int idleSeconds) {}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        CompilationSubject.assertThat(c).succeeded();

        String binder = generatedSource(c, "TimeoutConfigBinder");
        assertThat(binder).contains("ctx.scalarOrDefault(node, \"idle-seconds\"");
        assertThat(binder).contains("\"30\"");
    }

    @Test
    void keyOnNestedRecordFieldHonored() throws IOException {
        JavaFileObject outer = JavaFileObjects.forSourceLines(
                "io.example.AppConfig",
                "package io.example;",
                "import io.tiko.annotations.Configuration;",
                "@Configuration(prefix = \"app\")",
                "public record AppConfig(DbKeys db) {}");
        JavaFileObject inner = JavaFileObjects.forSourceLines(
                "io.example.DbKeys",
                "package io.example;",
                "import io.tiko.annotations.Key;",
                "public record DbKeys(@Key(\"host-name\") String hostName, int port) {}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(outer, inner);
        CompilationSubject.assertThat(c).succeeded();

        String nested = generatedSource(c, "DbKeysNestedCoercer_");
        assertThat(nested).contains("requireField(node, \"host-name\"");
        assertThat(nested).doesNotContain("\"hostName\"");
    }

    @Test
    void duplicateKeyValueIsAllowed() {
        // Two fields mapped to the same YAML key: the contract is "allowed" — both read the same
        // key (no duplicate-@Key validation). Pinned so a future change to reject it is deliberate.
        JavaFileObject src = JavaFileObjects.forSourceLines(
                "io.example.DupConfig",
                "package io.example;",
                "import io.tiko.annotations.Configuration;",
                "import io.tiko.annotations.Key;",
                "@Configuration(prefix = \"dup\")",
                "public record DupConfig(@Key(\"same\") String a, @Key(\"same\") int b) {}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);

        CompilationSubject.assertThat(c).succeeded();
    }
}
