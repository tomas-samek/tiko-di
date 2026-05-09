package io.tiko.processor.validation;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;

import static com.google.testing.compile.CompilationSubject.assertThat;

/**
 * Verifies the closeable-field leak warning emitted by {@link CloseableLeakValidator}.
 * The validator emits warnings only — these compilations all succeed.
 */
class CloseableLeakValidatorTest {

    @Test
    void warns_when_component_holds_closeable_field_without_cleanup() {
        Compilation compilation = Compiler.javac()
            .withProcessors(new TikoAnnotationProcessor())
            .compile(LEAKY_COMPONENT);

        assertThat(compilation).succeeded();
        assertThat(compilation)
            .hadWarningContaining("holds AutoCloseable field 'stream'");
    }

    @Test
    void no_warning_when_component_implements_autocloseable_itself() {
        Compilation compilation = Compiler.javac()
            .withProcessors(new TikoAnnotationProcessor())
            .compile(SELF_AUTOCLOSEABLE_COMPONENT);

        assertThat(compilation).succeeded();
        // Only Tiko-DI emitted warnings should be considered; ensure none mention this component
        for (var diag : compilation.warnings()) {
            org.assertj.core.api.Assertions.assertThat(diag.getMessage(null))
                .doesNotContain("holds AutoCloseable field");
        }
    }

    @Test
    void no_warning_when_component_declares_predestroy() {
        Compilation compilation = Compiler.javac()
            .withProcessors(new TikoAnnotationProcessor())
            .compile(PREDESTROY_COMPONENT);

        assertThat(compilation).succeeded();
        for (var diag : compilation.warnings()) {
            org.assertj.core.api.Assertions.assertThat(diag.getMessage(null))
                .doesNotContain("holds AutoCloseable field");
        }
    }

    @Test
    void suppression_at_field_level_silences_the_warning() {
        Compilation compilation = Compiler.javac()
            .withProcessors(new TikoAnnotationProcessor())
            .compile(SUPPRESSED_COMPONENT);

        assertThat(compilation).succeeded();
        for (var diag : compilation.warnings()) {
            org.assertj.core.api.Assertions.assertThat(diag.getMessage(null))
                .doesNotContain("holds AutoCloseable field");
        }
    }

    @Test
    void no_warning_when_closeable_field_is_an_injected_dependency() {
        Compilation compilation = Compiler.javac()
            .withProcessors(new TikoAnnotationProcessor())
            .compile(INJECTED_DEPENDENCY_COMPONENT, AUTOCLOSEABLE_DEP);

        assertThat(compilation).succeeded();
        for (var diag : compilation.warnings()) {
            org.assertj.core.api.Assertions.assertThat(diag.getMessage(null))
                .doesNotContain("holds AutoCloseable field");
        }
    }

    // Note: tests use java.io.InputStream (Closeable since JDK 1.5) rather than
    // ExecutorService — the latter only became AutoCloseable in JDK 19, which would
    // make these tests pass on JDK 19+ but fail on the JDK 17 baseline.

    private static final JavaFileObject LEAKY_COMPONENT =
        JavaFileObjects.forSourceLines(
            "io.tiko.processor.fixtures.leaky.LeakyService",
            "package io.tiko.processor.fixtures.leaky;",
            "",
            "import java.io.InputStream;",
            "import io.tiko.Scope;",
            "import io.tiko.annotations.Component;",
            "",
            "@Component(scope = Scope.SINGLETON)",
            "public class LeakyService {",
            "    private InputStream stream;",
            "    public LeakyService() {}",
            "}"
        );

    private static final JavaFileObject SELF_AUTOCLOSEABLE_COMPONENT =
        JavaFileObjects.forSourceLines(
            "io.tiko.processor.fixtures.leaky.SelfClosing",
            "package io.tiko.processor.fixtures.leaky;",
            "",
            "import java.io.InputStream;",
            "import io.tiko.Scope;",
            "import io.tiko.annotations.Component;",
            "",
            "@Component(scope = Scope.SINGLETON)",
            "public class SelfClosing implements AutoCloseable {",
            "    private InputStream stream;",
            "    public SelfClosing() {}",
            "    @Override public void close() throws Exception { if (stream != null) stream.close(); }",
            "}"
        );

    private static final JavaFileObject PREDESTROY_COMPONENT =
        JavaFileObjects.forSourceLines(
            "io.tiko.processor.fixtures.leaky.WithPreDestroy",
            "package io.tiko.processor.fixtures.leaky;",
            "",
            "import java.io.IOException;",
            "import java.io.InputStream;",
            "import io.tiko.Scope;",
            "import io.tiko.annotations.Component;",
            "import io.tiko.annotations.PreDestroy;",
            "",
            "@Component(scope = Scope.SINGLETON)",
            "public class WithPreDestroy {",
            "    private InputStream stream;",
            "    public WithPreDestroy() {}",
            "    @PreDestroy public void closeStream() throws IOException { if (stream != null) stream.close(); }",
            "}"
        );

    private static final JavaFileObject SUPPRESSED_COMPONENT =
        JavaFileObjects.forSourceLines(
            "io.tiko.processor.fixtures.leaky.Suppressed",
            "package io.tiko.processor.fixtures.leaky;",
            "",
            "import java.io.InputStream;",
            "import io.tiko.Scope;",
            "import io.tiko.annotations.Component;",
            "",
            "@Component(scope = Scope.SINGLETON)",
            "public class Suppressed {",
            "    @SuppressWarnings(\"resource\")",
            "    private InputStream stream;",
            "    public Suppressed() {}",
            "}"
        );

    private static final JavaFileObject AUTOCLOSEABLE_DEP =
        JavaFileObjects.forSourceLines(
            "io.tiko.processor.fixtures.leaky.SharedClient",
            "package io.tiko.processor.fixtures.leaky;",
            "",
            "import io.tiko.Scope;",
            "import io.tiko.annotations.Component;",
            "",
            "@Component(scope = Scope.SINGLETON)",
            "public class SharedClient implements AutoCloseable {",
            "    public SharedClient() {}",
            "    @Override public void close() {}",
            "}"
        );

    private static final JavaFileObject INJECTED_DEPENDENCY_COMPONENT =
        JavaFileObjects.forSourceLines(
            "io.tiko.processor.fixtures.leaky.User",
            "package io.tiko.processor.fixtures.leaky;",
            "",
            "import io.tiko.Scope;",
            "import io.tiko.annotations.Component;",
            "import io.tiko.annotations.Inject;",
            "",
            "@Component(scope = Scope.SINGLETON)",
            "public class User {",
            "    private final SharedClient client;",
            "    @Inject public User(SharedClient client) { this.client = client; }",
            "}"
        );
}
