package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

/**
 * Verifies that when any {@code @TestComponent} is present in the round, the processor
 * emits a dedicated {@code TestTikoContainerImpl_<hash>} plus a
 * {@code META-INF/tiko/test-container.properties} descriptor — in addition to the
 * regular main-classpath {@code TikoContainerImpl_<hash>} / {@code container.properties}.
 *
 * <p>Runtime preference (test descriptor wins over main descriptor) is covered by
 * {@code Tiko.createInternal}'s discovery logic; this test only asserts emission.
 */
class TestContainerEmissionTest {

    @Test
    void presenceOfTestComponentEmitsTestContainerAndDescriptor() {
        var testComponentAnno = JavaFileObjects.forSourceLines(
                "io.tiko.test.TestComponent",
                "package io.tiko.test;",
                "import io.tiko.Scope;",
                "import java.lang.annotation.*;",
                "@Retention(RetentionPolicy.SOURCE)",
                "@Target(ElementType.TYPE)",
                "public @interface TestComponent {",
                "    Scope scope() default Scope.SINGLETON;",
                "    String name() default \"\";",
                "}");
        var prod = JavaFileObjects.forSourceLines(
                "demo.Clock",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Clock {}");
        var fake = JavaFileObjects.forSourceLines(
                "demo.FakeClock",
                "package demo;",
                "import io.tiko.test.TestComponent;",
                "@TestComponent",
                "public class FakeClock extends Clock {}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(testComponentAnno, prod, fake);
        assertThat(c).succeeded();

        var hasTestContainer =
                c.generatedSourceFiles().stream().anyMatch(f -> f.getName().contains("TestTikoContainerImpl_"));
        var hasTestDescriptor = c.generatedFiles().stream()
                .anyMatch(f -> f.getName().endsWith("META-INF/tiko/test-container.properties"));
        org.assertj.core.api.Assertions.assertThat(hasTestContainer)
                .as("test container source")
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(hasTestDescriptor)
                .as("test-container.properties")
                .isTrue();
    }
}
