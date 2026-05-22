package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
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

    // TODO #129 Task 7: fakeExtendsProductionClassShadowsViaSuperclassWalk and
    // firstComponentAncestorWinsForMultiLevelHierarchy both asserted on the generated
    // TestTikoContainerImpl source — that subclass emission goes away in T7. The
    // superclass-walk behaviour itself is still covered by TestComponentCollectionTest
    // and the new standalone-container assertions T7 will add.

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
