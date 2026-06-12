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
 * Pins that generated SINGLETON getters never run a factory inside a
 * {@code ConcurrentHashMap} mapping function (#338): a singleton factory resolves its
 * dependencies through other getters, so {@code computeIfAbsent} nests on the same map —
 * a documented CHM contract violation that throws
 * {@code IllegalStateException("Recursive update")} whenever two chain keys share a hash
 * bin. Getters must route through the reentrant {@code __getOrCreateSingleton} helper
 * instead. Behavioral counterpart: {@code SingletonChainHashCollisionTest} in
 * {@code tiko-examples/12_testing}.
 */
class SingletonGetterReentrancyTest {

    @Test
    void singletonGettersUseTheReentrantHelperNotComputeIfAbsent() throws IOException {
        JavaFileObject b = JavaFileObjects.forSourceLines(
                "demo.B",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class B {}");
        JavaFileObject a = JavaFileObjects.forSourceLines(
                "demo.A",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class A {",
                "  @Inject public A(B b) {}",
                "}");
        JavaFileObject factory = JavaFileObjects.forSourceLines(
                "demo.Factories",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Factories {",
                "  @Produces(scope = Scope.SINGLETON)",
                "  public String banner(B b) { return b.toString(); }",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(a, b, factory);

        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject containerSource = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TikoContainerImpl was not generated"));

        String source = new String(containerSource.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(source)
                .as("singleton creation must be reentrant — never inside a CHM mapping function")
                .doesNotContain("singletons.computeIfAbsent")
                .contains("__getOrCreateSingleton")
                .contains("synchronized (singletonLock)");
    }
}
