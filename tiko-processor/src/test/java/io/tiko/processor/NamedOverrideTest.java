package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class NamedOverrideTest {

    @Test
    void namedGetterChecksQualifiedOverride() throws Exception {
        var iface =
                JavaFileObjects.forSourceLines("demo.DataSource", "package demo;", "public interface DataSource {}");
        var impl = JavaFileObjects.forSourceLines(
                "demo.PrimaryDs",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON, name = \"primary\")",
                "public class PrimaryDs implements DataSource {}");

        var compilation =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(iface, impl);
        assertThat(compilation).succeeded();

        var f = compilation.generatedSourceFiles().stream()
                .filter(x -> x.getName().contains("TikoContainerImpl_"))
                .findFirst()
                .orElseThrow();
        var content = new String(f.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // JavaPoet imports the type — short form `DataSource.class` (not `demo.DataSource.class`).
        // Single lookup + class-token cast (#309): one getOverride read per arm, no hasOverride.
        org.assertj.core.api.Assertions.assertThat(content)
                .contains("options.getOverride(DataSource.class, \"primary\")")
                .contains("return type.cast(__namedOverride.get())")
                .doesNotContain("options.hasOverride");
    }
}
