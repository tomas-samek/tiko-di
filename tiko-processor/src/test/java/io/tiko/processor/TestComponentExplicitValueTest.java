package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
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

    // TODO #129 Task 7: explicitValueRegistersAdditionalRoutableKey asserted on the
    // generated TestTikoContainerImpl source — the subclass emission goes away once T7
    // lands the standalone-container path, so this assertion is rewritten there.

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
