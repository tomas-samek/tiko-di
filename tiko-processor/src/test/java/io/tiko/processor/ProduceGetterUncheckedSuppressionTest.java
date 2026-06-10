package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Pins the uniform unchecked-cast suppression policy from #309. SINGLETON and EVENT
 * {@code produce_*} getters cast the scope map's Object back to the produced type, so they
 * carry the same {@code @SuppressWarnings("unchecked")} the dispatchers do. PROTOTYPE
 * getters perform no cast and stay unannotated.
 */
class ProduceGetterUncheckedSuppressionTest {

    @Test
    void scopedProduceGettersCarrySuppressionPrototypeDoesNot() throws IOException {
        JavaFileObject factory = JavaFileObjects.forSourceLines(
                "demo.Producers",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "import java.io.StringWriter;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Producers {",
                "  @Produces(scope = Scope.SINGLETON)",
                "  public StringWriter labels() { return new StringWriter(); }",
                "  @Produces(scope = Scope.EVENT)",
                "  public StringBuilder token() { return new StringBuilder(); }",
                "  @Produces(scope = Scope.PROTOTYPE)",
                "  public Thread worker() { return new Thread(); }",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(factory);
        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TikoContainerImpl not generated"));

        String normalized =
                new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s", "");

        assertThat(normalized)
                .as("SINGLETON produce getter carries the suppression")
                .contains("@SuppressWarnings(\"unchecked\")publicStringWriterproduce_Producers_labels()")
                .as("EVENT produce getter carries the suppression")
                .contains("@SuppressWarnings(\"unchecked\")publicStringBuilderproduce_Producers_token()")
                .as("PROTOTYPE produce getter has no cast, so no suppression")
                .doesNotContain("@SuppressWarnings(\"unchecked\")publicThreadproduce_Producers_worker()")
                .contains("publicThreadproduce_Producers_worker()");
    }
}
