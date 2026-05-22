package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class TestComponentImplicitWalkTest {

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
    void fakeExtendsProductionClassShadowsViaSuperclassWalk() throws Exception {
        var clockClass = JavaFileObjects.forSourceLines(
                "demo.Clock",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import java.time.Instant;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Clock {",
                "    public Instant now() { return Instant.now(); }",
                "}");
        var fake = JavaFileObjects.forSourceLines(
                "demo.FakeClock",
                "package demo;",
                "import io.tiko.test.TestComponent;",
                "import java.time.Instant;",
                "@TestComponent",
                "public class FakeClock extends Clock {",
                "    @Override public Instant now() { return Instant.EPOCH; }",
                "}");
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
                .compile(TEST_COMPONENT_ANNO, clockClass, fake, consumer);
        assertThat(c).succeeded();

        var testContainer = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TestTikoContainerImpl_"))
                .findFirst()
                .orElseThrow();
        var content = new String(testContainer.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(content)
                .contains("@Override")
                .contains("getClock");
    }

    @Test
    void firstComponentAncestorWinsForMultiLevelHierarchy() throws Exception {
        var aClass = JavaFileObjects.forSourceLines(
                "demo.A",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class A {}");
        var bClass = JavaFileObjects.forSourceLines(
                "demo.B",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class B extends A {}");
        var fake = JavaFileObjects.forSourceLines(
                "demo.FakeB",
                "package demo;",
                "import io.tiko.test.TestComponent;",
                "@TestComponent",
                "public class FakeB extends B {}");
        var consumer = JavaFileObjects.forSourceLines(
                "demo.UsesB",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class UsesB {",
                "    @Inject public UsesB(B b) {}",
                "}");

        var c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(TEST_COMPONENT_ANNO, aClass, bClass, fake, consumer);
        assertThat(c).succeeded();

        var testContainer = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TestTikoContainerImpl_"))
                .findFirst()
                .orElseThrow();
        var content = new String(testContainer.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // FakeB should shadow B (the nearer @Component ancestor): the test container
        // overrides getB() and FakeB is wired into it.
        org.assertj.core.api.Assertions.assertThat(content)
                .contains("@Override")
                .contains("getB")
                .contains("FakeB");
    }

    @Test
    void noComponentAncestorMeansPureAddition() throws Exception {
        var fake = JavaFileObjects.forSourceLines(
                "demo.StandaloneFake",
                "package demo;",
                "import io.tiko.test.TestComponent;",
                "@TestComponent",
                "public class StandaloneFake {}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(TEST_COMPONENT_ANNO, fake);
        assertThat(c).succeeded();
    }
}
