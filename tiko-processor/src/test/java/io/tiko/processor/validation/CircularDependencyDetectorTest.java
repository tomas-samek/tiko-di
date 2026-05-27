package io.tiko.processor.validation;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link CircularDependencyDetector} (#158) — previously zero, on one of the
 * framework's headline compile-time-safety features. Pins N-deep cycle detection with a
 * human-readable path, the {@code Provider<T>} escape valve, and clean compilation for
 * acyclic graphs. The readable-path behavior depends on the #166 fix (the detector now
 * propagates the full cycle, not just the root node).
 */
class CircularDependencyDetectorTest {

    private static Compilation compile(JavaFileObject... sources) {
        return Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(sources);
    }

    /** A SINGLETON {@code @Component} 'name' with one {@code @Inject} constructor param ({@code ctorParams}). */
    private static JavaFileObject bean(String name, String ctorParams) {
        return JavaFileObjects.forSourceLines(
                "demo." + name,
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class " + name + " {",
                "  @Inject public " + name + "(" + ctorParams + ") {}",
                "}");
    }

    @Test
    void twoDeepCycleFailsWithFullPath() {
        Compilation c = compile(bean("A", "B b"), bean("B", "A a"));

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("demo.A -> demo.B -> demo.A");
    }

    @Test
    void threeDeepCycleFailsWithFullPath() {
        Compilation c = compile(bean("A", "B b"), bean("B", "C c"), bean("C", "A a"));

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("demo.A -> demo.B -> demo.C -> demo.A");
    }

    @Test
    void cycleWithinLargerGraphReportsOnlyTheCycle() {
        // A -> B -> C -> B : the cycle is B<->C; A is an upstream entry point, not part of it.
        Compilation c = compile(bean("A", "B b"), bean("B", "C c"), bean("C", "B b"));

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("demo.B -> demo.C -> demo.B");
    }

    @Test
    void linearChainCompiles() {
        Compilation c = compile(bean("A", "B b"), bean("B", "C c"), bean("C", ""));

        CompilationSubject.assertThat(c).succeeded();
    }

    @Test
    void unrelatedComponentsCompile() {
        Compilation c = compile(bean("A", ""), bean("B", ""));

        CompilationSubject.assertThat(c).succeeded();
    }

    @Test
    void providerBreaksTheCycle() {
        // A injects Provider<B> (lazy → not followed by the detector), B injects A directly.
        Compilation c = compile(bean("A", "io.tiko.Provider<B> b"), bean("B", "A a"));

        CompilationSubject.assertThat(c).succeeded();
    }
}
