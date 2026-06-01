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
 * Pins #116: {@code @PreDestroy} / {@code AutoCloseable.close()} failures route <em>only</em>
 * through {@code ErrorHandler} — the generated container no longer emits a redundant catch-site
 * WARN log (which had doubled every failure). Asserted on the generated source across all four
 * catch sites (SINGLETON + EVENT × component {@code @PreDestroy} + factory {@code AutoCloseable}).
 * The bus-defect / drain logs (no {@code ErrorHandler} permit) keep their direct log.
 */
class ShutdownFailureLoggingTest {

    @Test
    void preDestroyAndAutoCloseFailuresRouteOnlyThroughErrorHandler() throws IOException {
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
        JavaFileObject eventBean = JavaFileObjects.forSourceLines(
                "io.example.EventBean",
                "package io.example;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.PreDestroy;",
                "import io.tiko.annotations.Produces;",
                "@Component(scope = Scope.EVENT)",
                "public class EventBean {",
                "  @PreDestroy public void down() {}",
                "  @Produces(scope = Scope.EVENT) public RRes res() { return new RRes(); }",
                "}");

        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(sRes, rRes, singletonBean, eventBean);
        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TikoContainerImpl not generated"));
        String content = new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Routing intact for both hook kinds, across SINGLETON and EVENT scopes.
        assertThat(content).contains("PreDestroyFailure");
        assertThat(content).contains("AutoCloseFailure");
        // No redundant catch-site log at any of the four sites — the removed logs all read
        // "<hook> threw on <bean>", so the substring is a clean discriminator.
        assertThat(content).doesNotContain("threw on");
        // Bus-defect log (no ErrorHandler permit) is intentionally kept.
        assertThat(content).contains("ApplicationEndingEvent publish threw");
    }
}
