package io.tiko.processor.validation;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class TestComponentScopeMismatchTest {

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
    void scopeMismatchBetweenTestAndProductionFailsCompile() {
        var prod = JavaFileObjects.forSourceLines(
                "demo.RequestRepo",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.REQUEST)",
                "public class RequestRepo {}");
        var fake = JavaFileObjects.forSourceLines(
                "demo.FakeRepo",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.test.TestComponent;",
                "@TestComponent(scope = Scope.SINGLETON)",
                "public class FakeRepo extends RequestRepo {}");
        var consumer = JavaFileObjects.forSourceLines(
                "demo.UsesRepo",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class UsesRepo {",
                "    @Inject public UsesRepo(RequestRepo r) {}",
                "}");

        var c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(TEST_COMPONENT_ANNO, prod, fake, consumer);
        assertThat(c).hadErrorContaining("Scope mismatch");
    }

    @Test
    void scopeMatchPassesCompile() {
        var prod = JavaFileObjects.forSourceLines(
                "demo.SingRepo",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class SingRepo {}");
        var fake = JavaFileObjects.forSourceLines(
                "demo.FakeSingRepo",
                "package demo;",
                "import io.tiko.test.TestComponent;",
                "@TestComponent",
                "public class FakeSingRepo extends SingRepo {}");
        var consumer = JavaFileObjects.forSourceLines(
                "demo.UsesSing",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class UsesSing {",
                "    @Inject public UsesSing(SingRepo r) {}",
                "}");

        var c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(TEST_COMPONENT_ANNO, prod, fake, consumer);
        assertThat(c).succeeded();
    }
}
