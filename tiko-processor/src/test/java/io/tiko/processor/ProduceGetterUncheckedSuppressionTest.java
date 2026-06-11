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
 * Pins the {@code produce_*} getter suppression policy after #327 narrowed it. Generic
 * {@code @Produces} return types are now rejected up front by {@code ProducesSignatureValidator},
 * so the produced type is always concrete and the SINGLETON/EVENT getters' cast of the scope
 * map's Object back to that type is <em>checked</em> — no {@code @SuppressWarnings("unchecked")}
 * is emitted on any produce getter, regardless of scope. (The get/getAll dispatchers still
 * suppress, because their casts are to the type variable {@code T}; that is covered elsewhere.)
 *
 * <p>This reverses the uniform-suppression policy #309 introduced: that policy existed only to
 * cover the parameterized-producer cast, which #327 eliminated at the source.
 */
class ProduceGetterUncheckedSuppressionTest {

    @Test
    void produceGettersCarryNoSuppressionForConcreteTypes() throws IOException {
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
                .as("SINGLETON produce getter casts to a concrete type — checked, no suppression")
                .doesNotContain("@SuppressWarnings(\"unchecked\")publicStringWriterproduce_Producers_labels()")
                .contains("publicStringWriterproduce_Producers_labels()")
                .as("EVENT produce getter casts to a concrete type — checked, no suppression")
                .doesNotContain("@SuppressWarnings(\"unchecked\")publicStringBuilderproduce_Producers_token()")
                .contains("publicStringBuilderproduce_Producers_token()")
                .as("PROTOTYPE produce getter has no cast, so no suppression")
                .doesNotContain("@SuppressWarnings(\"unchecked\")publicThreadproduce_Producers_worker()")
                .contains("publicThreadproduce_Producers_worker()");
    }
}
