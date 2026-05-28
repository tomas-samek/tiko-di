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
 * Pins #106: the generated container's SINGLETON shutdown delegates each {@code @PreDestroy} /
 * factory {@code AutoCloseable.close()} to {@code BoundedExecution.run(...)}, passing
 * {@code this.options.teardownTimeout()} and the {@code PreDestroyFailure} / {@code AutoCloseFailure}
 * factories — so an opt-in timeout bounds the hook while the unset default stays inline.
 *
 * <p>REQUEST scope-exit teardown is explicitly out of scope and must stay inline; the fixture
 * carries one REQUEST {@code @PreDestroy} + one REQUEST factory {@code AutoCloseable} alongside the
 * SINGLETON pair, and the test asserts exactly the two SINGLETON sites delegate to the primitive.
 */
class ShutdownTeardownTimeoutWiringTest {

    @Test
    void singletonTeardownDelegatesToBoundedExecutionWithTeardownTimeout() throws IOException {
        JavaFileObject sRes = JavaFileObjects.forSourceLines(
                "io.example.SRes",
                "package io.example;",
                "public class SRes implements AutoCloseable { public void close() {} }");
        JavaFileObject rRes = JavaFileObjects.forSourceLines(
                "io.example.RRes",
                "package io.example;",
                "public class RRes implements AutoCloseable { public void close() {} }");
        JavaFileObject singletonBean = JavaFileObjects.forSourceLines(
                "io.example.SingletonBean",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.PreDestroy;",
                "import io.tiko.annotations.Produces;",
                "@Component(scope = Scope.SINGLETON)",
                "public class SingletonBean {",
                "  @PreDestroy public void down() {}",
                "  @Produces(scope = Scope.SINGLETON) public SRes res() { return new SRes(); }",
                "}");
        JavaFileObject requestBean = JavaFileObjects.forSourceLines(
                "io.example.RequestBean",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.PreDestroy;",
                "import io.tiko.annotations.Produces;",
                "@Component(scope = Scope.REQUEST)",
                "public class RequestBean {",
                "  @PreDestroy public void down() {}",
                "  @Produces(scope = Scope.REQUEST) public RRes res() { return new RRes(); }",
                "}");

        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(sRes, rRes, singletonBean, requestBean);
        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TikoContainerImpl not generated"));
        String content = new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // The SINGLETON @PreDestroy and SINGLETON factory AutoCloseable both route through the
        // primitive, under the opt-in knob.
        assertThat(content).contains("BoundedExecution.run(");
        assertThat(content).contains("this.options.teardownTimeout()");
        // Failure factories preserved across both hook kinds.
        assertThat(content).contains("PreDestroyFailure");
        assertThat(content).contains("AutoCloseFailure");

        // Exactly the two SINGLETON sites delegate — REQUEST teardown stays inline (out of scope).
        assertThat(countOccurrences(content, "BoundedExecution.run("))
                .as("only the two SINGLETON teardown sites use the bounded primitive")
                .isEqualTo(2);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
