package io.tiko.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FactoryOverrideInterfaceKeyTest {

    @Test
    void factoryWrapsInterfaceParamResolutionWithOverrideKeyedOnInterface() throws Exception {
        var iface = JavaFileObjects.forSourceLines("demo.Gateway", "package demo;", "public interface Gateway {}");
        var impl = JavaFileObjects.forSourceLines(
                "demo.HttpGateway",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.SINGLETON)",
                "public class HttpGateway implements Gateway {}");
        var consumer = JavaFileObjects.forSourceLines(
                "demo.UsesGateway",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "@Component(scope = Scope.SINGLETON)",
                "public class UsesGateway {",
                "    @Inject public UsesGateway(Gateway g) {}",
                "}");

        var c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(iface, impl, consumer);
        assertThat(c).succeeded();

        var factory = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("UsesGatewayFactory"))
                .findFirst()
                .orElseThrow();
        var content = new String(factory.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // The factory must consult override under Gateway.class (the param's declared type),
        // not just HttpGateway.class. The factory reaches options via the package-private
        // accessor on the generated TikoContainerImpl_<hash>. resolveOverride does one map
        // lookup and falls back to the production getter (#309).
        org.assertj.core.api.Assertions.assertThat(content)
                .contains("options().resolveOverride(Gateway.class, () ->")
                .doesNotContain("hasOverride");
    }
}
