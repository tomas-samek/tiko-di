package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

class MainDescriptorDetectionTest {

    @Test
    void noMainDescriptorOnClasspathFallsBackToFreshMainGeneration() throws Exception {
        // Standard case (compile-testing harness has no classpath descriptor): processor
        // generates a fresh main container as today.
        var src = JavaFileObjects.forSourceLines(
                "demo.Simple",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Simple {}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        assertThat(c).succeeded();

        boolean mainEmitted =
                c.generatedSourceFiles().stream().anyMatch(f -> f.getName().contains("TikoContainerImpl_"));
        org.assertj.core.api.Assertions.assertThat(mainEmitted).isTrue();
    }
}
