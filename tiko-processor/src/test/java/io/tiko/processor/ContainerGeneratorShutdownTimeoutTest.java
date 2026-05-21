package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Verifies the generated single-module container honours TikoOptions.shutdownTimeout (#48):
 *
 * <ul>
 *   <li>The canonical constructor accepts {@code Duration shutdownTimeout}.</li>
 *   <li>The generated {@code shutdown()} reads from the field, not the hardcoded {@code 10, SECONDS}.</li>
 * </ul>
 *
 * <p>The legacy 4-arg shim was removed in tiko-test/T5 (the constructor took on a sixth
 * {@code TikoOptions} parameter and {@code Tiko.createInternal} + {@code AggregatingContainer}
 * always pass the full canonical set now), so this test no longer asserts on the shim.
 */
class ContainerGeneratorShutdownTimeoutTest {

    @Test
    void generatedContainerExposesFiveArgCtorAndUsesShutdownTimeoutField() throws IOException {
        JavaFileObject src = JavaFileObjects.forSourceLines(
                "io.example.Foo",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Foo {}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TikoContainerImpl was not generated"));

        String body = new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Canonical constructor with Duration parameter exists (JavaPoet imports java.time.Duration).
        assertThat(body).contains("import java.time.Duration;");
        assertThat(body).contains("Duration shutdownTimeout");
        assertThat(body).contains("private final Duration shutdownTimeout");

        // Shutdown reads from the field, not the hardcoded 10 seconds.
        assertThat(body).contains("this.shutdownTimeout.toNanos()");
        assertThat(body).contains("NANOSECONDS");

        // Negative assertion: the old `awaitTermination(10, SECONDS)` literal is gone from shutdown.
        assertThat(body).doesNotContain("awaitTermination(10, ");
    }
}
