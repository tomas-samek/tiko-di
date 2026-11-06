package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * However the {@code @EventTrigger}s on a handler are written, each one reaches the generated
 * {@code EventRegistry}. A direct trigger next to an explicit {@code @EventTriggers} container is
 * legal Java and counts: javac keeps the direct annotation and the container's elements apart.
 */
class EventRegistryTriggerShapesTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void everyWrittenTriggerIsDispatched(String name, List<String> annotations, List<String> guards)
            throws IOException {
        var lines = new java.util.ArrayList<String>(List.of(
                "package io.example;",
                "import io.tiko.EventTriggerGuard;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.EventHandler;",
                "import io.tiko.annotations.EventTrigger;",
                "import io.tiko.annotations.EventTriggers;",
                "import io.tiko.Scope;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Triggering {",
                "    @EventHandler"));
        lines.addAll(annotations);
        lines.addAll(List.of(
                "    public Pong onPing(Ping event) { return new Pong(); }",
                "    public static class G1 implements EventTriggerGuard {",
                "        public boolean shouldTrigger(Object result, Object event) { return true; }",
                "    }",
                "    public static class G2 implements EventTriggerGuard {",
                "        public boolean shouldTrigger(Object result, Object event) { return true; }",
                "    }",
                "}"));
        var component = JavaFileObjects.forSourceLines("io.example.Triggering", lines);
        var ping = JavaFileObjects.forSourceLines("io.example.Ping", "package io.example;", "public record Ping() {}");
        var pong = JavaFileObjects.forSourceLines("io.example.Pong", "package io.example;", "public record Pong() {}");

        var compilation =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(component, ping, pong);

        CompilationSubject.assertThat(compilation).succeeded();
        var registry = compilation.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("EventRegistry"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("EventRegistry not generated"));
        var content = new String(registry.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(content.split("publishWithOrigin[(]", -1))
                .as("publish calls")
                .hasSize(guards.size() + 1);
        for (var guard : guards) {
            assertThat(content).contains("new Triggering." + guard + "().shouldTrigger(__result, event)");
        }
    }

    static Stream<Arguments> shapes() {
        return Stream.of(
                Arguments.of("single", List.of("    @EventTrigger(guard = G1.class)"), List.of("G1")),
                Arguments.of(
                        "repeated",
                        List.of("    @EventTrigger(guard = G1.class)", "    @EventTrigger(guard = G2.class)"),
                        List.of("G1", "G2")),
                Arguments.of(
                        "explicitContainer",
                        List.of(
                                "    @EventTriggers({@EventTrigger(guard = G1.class), @EventTrigger(guard = G2.class)})"),
                        List.of("G1", "G2")),
                Arguments.of(
                        "directNextToExplicitContainer",
                        List.of(
                                "    @EventTrigger(guard = G1.class)",
                                "    @EventTriggers({@EventTrigger(guard = G2.class)})"),
                        List.of("G1", "G2")));
    }
}
