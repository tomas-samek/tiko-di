package io.tiko.processor.validation;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import io.tiko.processor.TikoAnnotationProcessor;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Carve-out for the ambiguity validator: a {@code @TestComponent} that shadows a
 * production {@code @Component} for the same routable type is intentionally not
 * ambiguous — the test component overrides for the test-classpath container, and
 * the main container is unaffected. Two {@code @TestComponent} beans routing to
 * the same type without disambiguation are still ambiguous.
 */
class AmbiguityValidatorTestComponentTest {

    @Test
    void testComponentShadowingMainComponentIsNotAmbiguous() {
        JavaFileObject testComponentAnno = JavaFileObjects.forSourceLines(
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
        JavaFileObject clockIface =
                JavaFileObjects.forSourceLines("demo.Clock", "package demo;", "public interface Clock { long now(); }");
        JavaFileObject main = JavaFileObjects.forSourceLines(
                "demo.ProdClock",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class ProdClock implements Clock {",
                "    public long now() { return 0L; }",
                "}");
        JavaFileObject test = JavaFileObjects.forSourceLines(
                "demo.FakeClock",
                "package demo;",
                "import io.tiko.test.TestComponent;",
                "@TestComponent",
                "public class FakeClock implements Clock {",
                "    public long now() { return 42L; }",
                "}");
        JavaFileObject consumer = JavaFileObjects.forSourceLines(
                "demo.Consumer",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Consumer {",
                "    @Inject public Consumer(Clock c) {}",
                "}");

        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(testComponentAnno, clockIface, main, test, consumer);
        assertThat(c).succeeded();
    }

    @Test
    void twoTestComponentsOnSameTypeStillAmbiguous() {
        JavaFileObject testComponentAnno = JavaFileObjects.forSourceLines(
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
        JavaFileObject iface =
                JavaFileObjects.forSourceLines("demo.SharedIface", "package demo;", "public interface SharedIface {}");
        JavaFileObject consumer = JavaFileObjects.forSourceLines(
                "demo.Consumer",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Consumer {",
                "    @Inject public Consumer(SharedIface s) {}",
                "}");
        JavaFileObject t1 = JavaFileObjects.forSourceLines(
                "demo.FakeOne",
                "package demo;",
                "import io.tiko.test.TestComponent;",
                "@TestComponent",
                "public class FakeOne implements SharedIface {}");
        JavaFileObject t2 = JavaFileObjects.forSourceLines(
                "demo.FakeTwo",
                "package demo;",
                "import io.tiko.test.TestComponent;",
                "@TestComponent",
                "public class FakeTwo implements SharedIface {}");

        Compilation c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(testComponentAnno, iface, consumer, t1, t2);
        assertThat(c).hadErrorContaining("Multiple unnamed providers");
    }
}
