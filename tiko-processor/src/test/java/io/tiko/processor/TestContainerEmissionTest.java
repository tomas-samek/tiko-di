package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Verifies that when any {@code @TestComponent} is present in the round, the processor
 * emits a dedicated {@code TestTikoContainerImpl_<hash>} plus a
 * {@code META-INF/tiko/test-container.properties} descriptor — in addition to the
 * regular main-classpath {@code TikoContainerImpl_<hash>} / {@code container.properties}.
 *
 * <p>Runtime preference (test descriptor wins over main descriptor) is covered by
 * {@code Tiko.createInternal}'s discovery logic; this test only asserts emission.
 */
class TestContainerEmissionTest {

    private static final JavaFileObject TEST_COMPONENT_ANNO = JavaFileObjects.forSourceLines(
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

    private static final JavaFileObject CLOCK_IFACE = JavaFileObjects.forSourceLines(
            "demo.Clock", "package demo;", "public interface Clock {", "    long now();", "}");

    private static final JavaFileObject PROD_CLOCK = JavaFileObjects.forSourceLines(
            "demo.ProdClock",
            "package demo;",
            "import io.tiko.Scope;",
            "import io.tiko.annotations.Component;",
            "@Component(scope = Scope.SINGLETON)",
            "public class ProdClock implements Clock {",
            "    public long now() { return System.currentTimeMillis(); }",
            "}");

    private static final JavaFileObject FAKE_CLOCK = JavaFileObjects.forSourceLines(
            "demo.FakeClock",
            "package demo;",
            "import io.tiko.test.TestComponent;",
            "@TestComponent",
            "public class FakeClock implements Clock {",
            "    public long now() { return 0L; }",
            "}");

    // Critical: a consumer of Clock so the AmbiguityValidator actually fires the shadow check.
    private static final JavaFileObject USES_CLOCK = JavaFileObjects.forSourceLines(
            "demo.UsesClock",
            "package demo;",
            "import io.tiko.Scope;",
            "import io.tiko.annotations.Component;",
            "import io.tiko.annotations.Inject;",
            "@Component(scope = Scope.SINGLETON)",
            "public class UsesClock {",
            "    @Inject public UsesClock(Clock clock) {}",
            "}");

    @Test
    void presenceOfTestComponentEmitsTestContainerAndDescriptor() {
        var c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(TEST_COMPONENT_ANNO, CLOCK_IFACE, PROD_CLOCK, FAKE_CLOCK, USES_CLOCK);
        assertThat(c).succeeded();

        var hasTestContainer =
                c.generatedSourceFiles().stream().anyMatch(f -> f.getName().contains("TestTikoContainerImpl_"));
        var hasTestDescriptor = c.generatedFiles().stream()
                .anyMatch(f -> f.getName().endsWith("META-INF/tiko/test-container.properties"));
        org.assertj.core.api.Assertions.assertThat(hasTestContainer)
                .as("test container source")
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(hasTestDescriptor)
                .as("test-container.properties")
                .isTrue();
    }

    @Test
    void testContainerOverridesShadowedGetterAndConsultsOverrideViaFacingType() throws Exception {
        var c = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(TEST_COMPONENT_ANNO, CLOCK_IFACE, PROD_CLOCK, FAKE_CLOCK, USES_CLOCK);
        assertThat(c).succeeded();

        var testContainer = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TestTikoContainerImpl_"))
                .findFirst()
                .orElseThrow();
        var content =
                new String(testContainer.openInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

        // The shadow override consults the SAME override-key as the main getter — the
        // user-facing @Component class (ProdClock). Pre-Fix #1 the emission used the
        // @TestComponent impl class (FakeClock), which a user's
        // `options.override(ProdClock.class, ...)` could never hit.
        org.assertj.core.api.Assertions.assertThat(content)
                .contains("@Override")
                .contains("getProdClock") // shadow override of the main getter
                .contains("options.hasOverride(ProdClock.class)") // override-key matches main getter
                .doesNotContain("options.hasOverride(FakeClock.class)") // never key off the test impl
                .contains("fakeClockFactory.create()"); // factory call wires the test impl
    }
}
