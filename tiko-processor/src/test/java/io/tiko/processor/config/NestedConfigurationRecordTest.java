package io.tiko.processor.config;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * A nested {@code @Configuration} record must produce a friendly, located compile error
 * rather than a {@link ClassCastException} from the unguarded {@code PackageElement} cast
 * in {@code ConfigurationCollector} (#105). Approach (A): nested records are unsupported;
 * the processor names the offending record and tells the user to make it top-level.
 */
class NestedConfigurationRecordTest {

    @Test
    void nestedConfigurationRecordFailsWithFriendlyError() {
        JavaFileObject outer = JavaFileObjects.forSourceLines(
                "io.example.Outer",
                "package io.example;",
                "import io.tiko.annotations.Configuration;",
                "import java.util.Set;",
                "class Outer {",
                "  @Configuration(prefix = \"allow\")",
                "  public record AllowlistConfig(Set<String> hosts) {}",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(outer);

        CompilationSubject.assertThat(c).failed();
        // Reaching this located message proves the guard ran instead of the cast throwing CCE.
        CompilationSubject.assertThat(c)
                .hadErrorContaining(
                        "@Configuration record 'AllowlistConfig' must be top-level (declared inside 'io.example.Outer'). Move it to its own .java file.");
    }

    @Test
    void topLevelConfigurationRecordCompiles() {
        JavaFileObject config = JavaFileObjects.forSourceLines(
                "io.example.AllowlistConfig",
                "package io.example;",
                "import io.tiko.annotations.Configuration;",
                "import java.util.Set;",
                "@Configuration(prefix = \"allow\")",
                "public record AllowlistConfig(Set<String> hosts) {}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(config);

        CompilationSubject.assertThat(c).succeeded();
    }
}
