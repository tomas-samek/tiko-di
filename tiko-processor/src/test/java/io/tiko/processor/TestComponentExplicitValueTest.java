package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class TestComponentExplicitValueTest {

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
    void explicitValueRegistersAdditionalRoutableKey() throws Exception {
        var prod = JavaFileObjects.forSourceLines(
                "demo.PaymentGateway", "package demo;", "public interface PaymentGateway {}");
        var prodImpl = JavaFileObjects.forSourceLines(
                "demo.HttpPaymentGateway",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class HttpPaymentGateway implements PaymentGateway {}");
        var test = JavaFileObjects.forSourceLines(
                "demo.StubPaymentGateway",
                "package demo;",
                "import io.tiko.test.TestComponent;",
                "@TestComponent(value = PaymentGateway.class)",
                "public class StubPaymentGateway implements PaymentGateway {}");
        var consumer = JavaFileObjects.forSourceLines(
                "demo.UsesGateway",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class UsesGateway {",
                "    @Inject public UsesGateway(PaymentGateway g) {}",
                "}");

        var c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(TEST_COMPONENT_ANNO, prod, prodImpl, test, consumer);
        assertThat(c).succeeded();

        var testContainer = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TestTikoContainerImpl_"))
                .findFirst()
                .orElseThrow();
        var content = new String(testContainer.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(content)
                .contains("@Override")
                .contains("getHttpPaymentGateway");
    }

    @Test
    void valueMustBeAssignableFromAnnotatedClass() {
        var iface = JavaFileObjects.forSourceLines(
                "demo.PaymentGateway", "package demo;", "public interface PaymentGateway {}");
        var bogusTest = JavaFileObjects.forSourceLines(
                "demo.UnrelatedFake",
                "package demo;",
                "import io.tiko.test.TestComponent;",
                "@TestComponent(value = PaymentGateway.class)",
                "public class UnrelatedFake {}"); // does NOT implement PaymentGateway

        var c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(TEST_COMPONENT_ANNO, iface, bogusTest);
        assertThat(c).hadErrorContaining("not assignable to");
    }
}
