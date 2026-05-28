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
 * Pins #242: injecting a {@code @Named}-qualified dependency that resolves to a named
 * {@code @Component} must generate a factory that compiles. The bug was that the qualified path
 * emitted {@code container.getEnGreeter("en")} — a per-class getter is no-arg, so the generated
 * code referenced a non-existent overload. The fix routes qualified lookups through the typed
 * {@code container.get(Type.class, "name")} dispatcher (the same path the runtime API uses).
 */
class NamedComponentInjectionTest {

    private static Compilation compileNamedInjection(String dependencyType) {
        JavaFileObject iface = JavaFileObjects.forSourceLines(
                "demo.Greeter", "package demo;", "public interface Greeter { String greet(); }");
        JavaFileObject en = JavaFileObjects.forSourceLines(
                "demo.EnGreeter",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(name = \"en\", scope = Scope.SINGLETON)",
                "public class EnGreeter implements Greeter { public String greet() { return \"hi\"; } }");
        JavaFileObject consumer = JavaFileObjects.forSourceLines(
                "demo.Consumer",
                "package demo;",
                "import io.tiko.Provider;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.Inject;",
                "import io.tiko.annotations.Named;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Consumer {",
                "    @Inject public Consumer(@Named(\"en\") " + dependencyType + " g) {}",
                "}");
        return Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(iface, en, consumer);
    }

    @Test
    void namedInterfaceInjectionCompilesAndRoutesThroughTypedDispatcher() throws Exception {
        Compilation c = compileNamedInjection("Greeter");
        assertThat(c).succeeded();

        JavaFileObject factory = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("ConsumerFactory"))
                .findFirst()
                .orElseThrow();
        String content = new String(factory.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
        // Named lookup must go through the typed dispatcher, not a no-arg per-class getter.
        Assertions.assertThat(content).contains("get(demo.Greeter.class, \"en\")");
    }

    @Test
    void namedProviderInjectionCompiles() {
        assertThat(compileNamedInjection("Provider<Greeter>")).succeeded();
    }
}
