package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** #612: an instance {@code @Produces} on a {@code @Component(name = "...")} is wired like the unnamed one. */
class NamedComponentInstanceProducesTest {

    @Test
    void instanceProducesOnNamedComponentCompilesAndCallsTheComponentGetter() throws Exception {
        var widget = JavaFileObjects.forSourceLines("demo.Widget", "package demo;", "public class Widget {}");
        var producers = JavaFileObjects.forSourceLines(
                "demo.Producers",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Produces;",
                "@Component(name = \"main\", scope = Scope.SINGLETON)",
                "public class Producers {",
                "  @Produces(scope = Scope.SINGLETON)",
                "  public Widget widget() { return new Widget(); }",
                "}");
        var consumer = JavaFileObjects.forSourceLines(
                "demo.Consumer",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Consumer {",
                "  public Consumer(Widget widget) {}",
                "}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(widget, producers, consumer);

        assertThat(c).succeeded();
        var container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl_"))
                .findFirst()
                .orElseThrow();
        var content = new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content).contains("return getProducers().widget()");
    }
}
