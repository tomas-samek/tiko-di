package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for #303: {@code getAll(Class)} must route {@code @Produces}
 * outputs by <em>exact</em> type, not {@code type.isAssignableFrom(ProducedType.class)}.
 * The assignability form makes {@code getAll(Object.class)} (and any super-type lookup)
 * fire every factory arm, eagerly invoking producers that throw by design or are
 * EVENT-scoped. Exact match aligns getAll with both its component arms and the unnamed
 * {@code get(Class)} factory routing, which already key on {@code type == ProducedType.class}.
 *
 * <p>For an <em>unnamed</em> producer, {@code isAssignableFrom(ProducedType.class)} is
 * emitted only by the buggy getAll arm — {@code get(Class)} is exact and
 * {@code get(Class,String)} emits factory arms only for named producers — so its absence
 * is an unambiguous signal that the getAll arm is exact.
 */
class GetAllFactoryExactMatchTest {

    private static final JavaFileObject WIDGET =
            JavaFileObjects.forSourceLines("demo.Widget", "package demo;", "public class Widget {}");

    private static final JavaFileObject FACTORY = JavaFileObjects.forSourceLines(
            "demo.Widgets",
            "package demo;",
            "import io.tiko.Scope;",
            "import io.tiko.annotations.Component;",
            "import io.tiko.annotations.Produces;",
            "@Component(scope = Scope.SINGLETON)",
            "public class Widgets {",
            "    @Produces(scope = Scope.SINGLETON)",
            "    public Widget makeWidget() { return new Widget(); }",
            "}");

    private static String generatedContainer() throws Exception {
        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(WIDGET, FACTORY);
        assertThat(c).succeeded();
        var container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl_"))
                .findFirst()
                .orElseThrow();
        return new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void getAllDoesNotWidenProducerArmsWithAssignability() throws Exception {
        var content = generatedContainer();

        org.assertj.core.api.Assertions.assertThat(content).doesNotContain("isAssignableFrom(Widget.class)");
    }

    @Test
    void producerRemainsRoutableByExactTypeInBothGetAndGetAll() throws Exception {
        var content = generatedContainer();

        // One exact arm in get(Class), one in getAll(Class) — both keyed on the produced type.
        long arms = content.split("type == Widget.class", -1).length - 1;
        org.assertj.core.api.Assertions.assertThat(arms).isGreaterThanOrEqualTo(2);
    }
}
