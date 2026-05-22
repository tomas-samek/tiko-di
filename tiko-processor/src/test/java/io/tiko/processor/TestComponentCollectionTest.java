package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Verifies the processor collects {@code @TestComponent}-annotated classes alongside
 * {@code @Component}, emitting a factory for each.
 *
 * <p>The annotation source is provided inline as a {@link JavaFileObject} so this test
 * does not require a compile-time dependency on the tiko-test module (the processor must
 * also tolerate the annotation's absence in production builds).
 */
class TestComponentCollectionTest {

    private static final JavaFileObject TEST_COMPONENT_ANNOTATION = JavaFileObjects.forSourceLines(
            "io.tiko.test.TestComponent",
            "package io.tiko.test;",
            "import io.tiko.Scope;",
            "import java.lang.annotation.ElementType;",
            "import java.lang.annotation.Retention;",
            "import java.lang.annotation.RetentionPolicy;",
            "import java.lang.annotation.Target;",
            "@Retention(RetentionPolicy.SOURCE)",
            "@Target(ElementType.TYPE)",
            "public @interface TestComponent {",
            "  Scope scope() default Scope.SINGLETON;",
            "  String name() default \"\";",
            "}");

    @Test
    void testComponentIsCollectedAsAComponent() {
        var src = JavaFileObjects.forSourceLines(
                "demo.FakeClock",
                "package demo;",
                "import io.tiko.test.TestComponent;",
                "@TestComponent",
                "public class FakeClock {}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(TEST_COMPONENT_ANNOTATION, src);
        assertThat(c).succeeded();
        // The factory file for FakeClock should be emitted, confirming the processor saw it.
        org.assertj.core.api.Assertions.assertThat(c.generatedSourceFiles().stream()
                        .map(JavaFileObject::getName)
                        .anyMatch(n -> n.contains("FakeClock")))
                .as("FakeClock factory must be emitted")
                .isTrue();
    }
}
