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
 * Regression coverage for Issue #97: a {@code @PostConstruct} method may
 * declare a checked exception. The generated factory's {@code create()}
 * catches {@link Throwable} (widened from the prior
 * {@code RuntimeException | Error}), publishes {@code PostConstructFailure}
 * for observability, and sneaky-throws the original to propagate.
 */
class PostConstructCheckedExceptionPropagationTest {

    @Test
    void postConstructDeclaringCheckedExceptionCompilesAndIsCaughtAsThrowable() throws IOException {
        JavaFileObject impl = JavaFileObjects.forSourceLines(
                "io.example.Init",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.PostConstruct;",
                "import java.sql.SQLException;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Init {",
                "  @PostConstruct public void start() throws SQLException {}",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(impl);

        // Pre-fix: javac fails on the generated InitFactory because instance.start()
        // throws an undeclared SQLException.
        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject factorySource = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("InitFactory"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("InitFactory was not generated"));

        String body = new String(factorySource.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // The catch is widened to Exception (covers checked + RuntimeException; Errors
        // propagate without routing), ErrorHandler is still routed, and the original
        // throwable propagates via sneakyThrow.
        assertThat(body).contains("catch (Exception __t)");
        assertThat(body).contains("container.getErrorHandler().onError(new PostConstructFailure(");
        assertThat(body).contains("Unchecked.<RuntimeException>sneakyThrow(__t)");
        assertThat(body).doesNotContain("catch (RuntimeException | Error __t)");
        assertThat(body).doesNotContain("catch (Throwable __t)");
    }
}
