package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for #327: a {@code @Produces} method whose return type is a
 * parameterized type (e.g. {@code List<String>}) must generate a container that compiles.
 *
 * <p>The factory dispatch arms in {@code get(Class)}, {@code get(Class, String)}, and
 * {@code getAll(Class)} key on {@code type == ProducedType.class}. Emitting the class
 * literal on the full parameterized type produces {@code List<String>.class}, which is
 * not legal Java - {@code javac} rejects it with {@code <identifier> expected}. The arm
 * must key on the <em>raw</em> type ({@code List.class}); erasure means a single
 * {@code List.class} token exists at runtime, which is exactly what a caller passes to
 * {@code get(List.class)}.
 */
class ProducesParameterizedReturnTypeTest {

    private static final JavaFileObject PRODUCERS = JavaFileObjects.forSourceLines(
            "demo.Producers",
            "package demo;",
            "import java.util.List;",
            "import java.util.Map;",
            "import io.tiko.Scope;",
            "import io.tiko.annotations.Component;",
            "import io.tiko.annotations.Produces;",
            "@Component(scope = Scope.SINGLETON)",
            "public class Producers {",
            // Unnamed parameterized producer: exercises the get(Class) and getAll(Class) arms.
            "    @Produces(scope = Scope.SINGLETON)",
            "    public List<String> labels() { return List.of(); }",
            // Named parameterized producer (distinct raw type): exercises the
            // get(Class, String) named arm and a second getAll(Class) arm.
            "    @Produces(scope = Scope.SINGLETON, name = \"settings\")",
            "    public Map<String, Integer> settings() { return Map.of(); }",
            "}");

    private static Compilation compile() {
        return Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(PRODUCERS);
    }

    private static String generatedContainer(Compilation c) throws Exception {
        var container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl_"))
                .findFirst()
                .orElseThrow();
        return new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void parameterizedProducerGeneratesCompilingContainer() throws Exception {
        Compilation c = compile();

        assertThat(c).succeeded();

        var content = generatedContainer(c);
        // Raw-type class literals (legal Java, the tokens a caller actually passes):
        // List.class is dispatched in get(Class) + getAll(Class); Map.class (named) in
        // get(Class, String) + getAll(Class). The illegal parameterized class literals
        // must never be emitted.
        Assertions.assertThat(content)
                .contains("type == List.class")
                .contains("type == Map.class")
                .doesNotContain("List<String>.class")
                .doesNotContain("Map<String, Integer>.class");
    }
}
