package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for #301: the JDK lifecycle marker interfaces
 * ({@code java.lang.AutoCloseable}, {@code java.io.Closeable}) must not become
 * routable dispatch keys. {@code TypeUtil.getFirstInterface} already skips them
 * for proxy/interface-key purposes; {@code get(Class)} / {@code getAll(Class)}
 * routing must agree, otherwise every {@code AutoCloseable} bean contributes a
 * {@code type == AutoCloseable.class} disjunct — a silent first-match-wins
 * resolution plus unreachable arms for the later beans.
 */
class MarkerInterfaceRoutingTest {

    private static String generatedContainer(JavaFileObject... sources) throws Exception {
        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(sources);
        assertThat(c).succeeded();
        var container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl_"))
                .findFirst()
                .orElseThrow();
        return new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static final JavaFileObject GATEWAY =
            JavaFileObjects.forSourceLines("demo.Gateway", "package demo;", "public interface Gateway {}");

    private static final JavaFileObject CLOSEABLE_A = JavaFileObjects.forSourceLines(
            "demo.ResourceA",
            "package demo;",
            "import io.tiko.Scope;",
            "import io.tiko.annotations.Component;",
            "@Component(scope = Scope.SINGLETON)",
            "public class ResourceA implements Gateway, AutoCloseable {",
            "    public void close() {}",
            "}");

    private static final JavaFileObject CLOSEABLE_B = JavaFileObjects.forSourceLines(
            "demo.ResourceB",
            "package demo;",
            "import io.tiko.Scope;",
            "import io.tiko.annotations.Component;",
            "@Component(scope = Scope.SINGLETON)",
            "public class ResourceB implements AutoCloseable {",
            "    public void close() {}",
            "}");

    @Test
    void markerInterfaceIsNotEmittedAsRoutingKey() throws Exception {
        var content = generatedContainer(GATEWAY, CLOSEABLE_A, CLOSEABLE_B);

        org.assertj.core.api.Assertions.assertThat(content).doesNotContain("type == AutoCloseable.class");
    }

    @Test
    void realInterfaceAndConcreteClassRemainRoutable() throws Exception {
        var content = generatedContainer(GATEWAY, CLOSEABLE_A, CLOSEABLE_B);

        org.assertj.core.api.Assertions.assertThat(content)
                .contains("type == Gateway.class")
                .contains("type == ResourceA.class")
                .contains("type == ResourceB.class");
    }
}
