package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DispatcherGetWithNameOverrideTest {

    @Test
    void getWithNameDispatcherChecksOverrideOnLookupTypeAndName() throws Exception {
        var iface = JavaFileObjects.forSourceLines("demo.Gateway", "package demo;", "public interface Gateway {}");
        var impl = JavaFileObjects.forSourceLines(
                "demo.HttpGateway",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON, name = \"primary\")",
                "public class HttpGateway implements Gateway {}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(iface, impl);
        assertThat(c).succeeded();

        var container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl_"))
                .findFirst()
                .orElseThrow();
        var content = new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Single lookup + class-token cast (#309): one getOverride read, no hasOverride pre-check.
        org.assertj.core.api.Assertions.assertThat(content)
                .contains("public <T> T get(Class<T> type, String name)")
                .contains("Supplier<?> __override = options.getOverride(type, name)")
                .contains("return type.cast(__override.get())")
                .doesNotContain("options.hasOverride");
    }
}
