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
 * Regression coverage for Issue #97: a {@code @Produces} factory method may
 * declare a checked exception. The generated container emits a per-factory
 * helper {@code invokeFactory_<id>()} that catches {@link Throwable},
 * publishes {@code ProduceFailure}, and sneaky-throws the original. The
 * existing scoped getter ({@code produce_<id>()}) is unchanged in shape —
 * its lambda calls the helper instead of the user method directly.
 */
class ProducesCheckedExceptionPropagationTest {

    @Test
    void producesDeclaringCheckedExceptionCompilesAndIsCaughtAsThrowable() throws IOException {
        JavaFileObject factory = JavaFileObjects.forSourceLines(
                "io.example.PoolFactory",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "import javax.sql.DataSource;",
                "import java.sql.SQLException;",
                "@Component(scope = Scope.SINGLETON)",
                "public class PoolFactory {",
                "  @Produces(scope = Scope.SINGLETON)",
                "  public DataSource dataSource() throws SQLException { return null; }",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(factory);

        // Pre-fix: javac fails because produce_PoolFactory_dataSource()
        // calls getPoolFactory().dataSource() with an undeclared SQLException.
        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject containerSource = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TikoContainerImpl was not generated"));

        String body = new String(containerSource.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // A per-factory invokeFactory_*() helper exists, with the try/catch
        // and the ProduceFailure + sneakyThrow plumbing.
        assertThat(body).contains("invokeFactory_PoolFactory_dataSource");
        assertThat(body).contains("catch (Throwable __t)");
        assertThat(body).contains("getErrorHandler().onError(new ProduceFailure(");
        assertThat(body).contains("\"dataSource\"");
        assertThat(body).contains("Unchecked.<RuntimeException>sneakyThrow(__t)");

        // The public scoped getter still exists and now calls the helper
        // rather than the user method directly.
        assertThat(body).contains("produce_PoolFactory_dataSource");
    }
}
