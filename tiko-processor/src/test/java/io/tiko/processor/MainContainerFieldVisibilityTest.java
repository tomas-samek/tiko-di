package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class MainContainerFieldVisibilityTest {

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

    @Test
    void mainContainerScopeStorageFieldsStayPrivateEvenWithTestComponents() throws Exception {
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
        var consumer = JavaFileObjects.forSourceLines(
                "demo.UsesClock",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class UsesClock {",
                "    @Inject public UsesClock(Clock c) {}",
                "}");

        var c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(TEST_COMPONENT_ANNO, prod, fake, consumer);
        assertThat(c).succeeded();

        var main = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl_")
                        && !f.getName().contains("TestTikoContainerImpl"))
                .findFirst()
                .orElseThrow();
        var content = new String(main.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(content)
                .contains("private final Map<String, Object> singletons")
                .contains("private final ThreadLocal<Map<String, Object>> eventScoped")
                .contains("private final TikoOptions options");
    }

    @Test
    void mainContainerStaysFinalClassEvenWithTestComponents() throws Exception {
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

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(TEST_COMPONENT_ANNO, prod, fake);
        assertThat(c).succeeded();

        var main = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl_")
                        && !f.getName().contains("TestTikoContainerImpl"))
                .findFirst()
                .orElseThrow();
        var content = new String(main.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(content).containsPattern("public final class TikoContainerImpl_");
    }
}
