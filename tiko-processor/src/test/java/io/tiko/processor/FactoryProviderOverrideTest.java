package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FactoryProviderOverrideTest {

    @Test
    void factoryProviderLambdaConsultsOverrideAtGetTime() throws Exception {
        var iface = JavaFileObjects.forSourceLines("demo.Gateway", "package demo;", "public interface Gateway {}");
        var impl = JavaFileObjects.forSourceLines(
                "demo.HttpGateway",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class HttpGateway implements Gateway {}");
        var consumer = JavaFileObjects.forSourceLines(
                "demo.UsesProvider",
                "package demo;",
                "import io.tiko.Provider;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class UsesProvider {",
                "    @Inject public UsesProvider(Provider<Gateway> g) {}",
                "}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(iface, impl, consumer);
        assertThat(c).succeeded();

        var factory = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("UsesProviderFactory"))
                .findFirst()
                .orElseThrow();
        var content = new String(factory.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Provider's lambda body must consult container.options().hasOverride(Gateway.class).
        org.assertj.core.api.Assertions.assertThat(content)
                .contains("options().hasOverride(Gateway.class)")
                .contains("options().getOverride(Gateway.class).get()");
    }
}
