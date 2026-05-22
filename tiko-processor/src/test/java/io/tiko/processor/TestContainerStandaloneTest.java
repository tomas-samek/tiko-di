package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class TestContainerStandaloneTest {

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
    void testContainerIsStandaloneNotExtendingMain() throws Exception {
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

        var testContainer = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TestContainerImpl_")
                        || f.getName().contains("TestTikoContainerImpl_"))
                .findFirst()
                .orElseThrow();
        var content = new String(testContainer.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(content).doesNotContain("extends TikoContainerImpl");
        org.assertj.core.api.Assertions.assertThat(content).contains("implements Container");
    }

    @Test
    void testShadowsPropertiesEmittedWithShadowedKeys() throws Exception {
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

        var shadowsFile = c.generatedFiles().stream()
                .filter(f -> f.getName().endsWith("META-INF/tiko/test-shadows.properties"))
                .findFirst()
                .orElseThrow();
        var content = new String(shadowsFile.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(content).contains("demo.Clock=io.tiko.generated.");
    }

    @Test
    void testContainerHasFactoryForTestSideComponent() throws Exception {
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

        var testContainer = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TestContainerImpl_")
                        || f.getName().contains("TestTikoContainerImpl_"))
                .findFirst()
                .orElseThrow();
        var content = new String(testContainer.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(content).contains("FakeClock");
    }
}
