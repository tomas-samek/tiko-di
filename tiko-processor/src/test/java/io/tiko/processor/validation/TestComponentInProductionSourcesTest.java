package io.tiko.processor.validation;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #497: a {@code @TestComponent} compiled from production sources would ship test wiring in a
 * jar, so a source file under {@code src/main/} is a compile error. Test sources are unaffected.
 */
class TestComponentInProductionSourcesTest {

    private static final JavaFileObject TEST_COMPONENT_ANNO = JavaFileObjects.forSourceLines(
            "io.tiko.test.TestComponent",
            "package io.tiko.test;",
            "import io.tiko.Scope;",
            "import java.lang.annotation.*;",
            "@Retention(RetentionPolicy.SOURCE)",
            "@Target(ElementType.TYPE)",
            "public @interface TestComponent {",
            "    Class<?> value() default Void.class;",
            "    Scope scope() default Scope.SINGLETON;",
            "    String name() default \"\";",
            "}");

    private static final JavaFileObject REAL_GREETER = JavaFileObjects.forSourceLines(
            "demo.RealGreeter",
            "package demo;",
            "import io.tiko.Scope;",
            "import io.tiko.annotations.Component;",
            "@Component(scope = Scope.SINGLETON)",
            "public class RealGreeter {}");

    @TempDir
    Path project;

    @Test
    void testComponentInProductionSourcesIsACompileError() throws Exception {
        Compilation c = compileFakeGreeterFrom("src/main/java");

        assertThat(c).failed();
        assertThat(c).hadErrorContaining("@TestComponent FakeGreeter is in production sources");
        assertThat(c).hadErrorContaining("Move FakeGreeter under src/test/java");
    }

    @Test
    void testComponentInTestSourcesCompiles() throws Exception {
        assertThat(compileFakeGreeterFrom("src/test/java")).succeeded();
    }

    private Compilation compileFakeGreeterFrom(String sourceRoot) throws Exception {
        Path file = project.resolve(sourceRoot).resolve("demo").resolve("FakeGreeter.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package demo;
                import io.tiko.test.TestComponent;
                @TestComponent
                public class FakeGreeter extends RealGreeter {}
                """);
        return Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(
                        TEST_COMPONENT_ANNO,
                        REAL_GREETER,
                        JavaFileObjects.forResource(file.toUri().toURL()));
    }
}
