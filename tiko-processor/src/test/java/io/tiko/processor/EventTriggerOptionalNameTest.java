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
 * {@code @EventTrigger} is a declarative "republish the return value by type" hook;
 * {@code eventName} is an optional human-readable label for topology/tracing, not a
 * routing key (#349). So a trigger with no {@code eventName} must compile and still fire
 * by return type — previously the attribute was mandatory (javac required it) and the
 * processor model additionally rejected an empty name.
 */
class EventTriggerOptionalNameTest {

    @Test
    void triggerWithoutEventNameCompilesAndFiresByReturnType() throws IOException {
        JavaFileObject component = JavaFileObjects.forSourceLines(
                "io.example.Triggering",
                "package io.example;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.EventHandler;",
                "import io.tiko.annotations.EventTrigger;",
                "import io.tiko.Scope;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Triggering {",
                "    @EventHandler",
                "    @EventTrigger",
                "    public Pong onPing(Ping event) { return new Pong(); }",
                "}");
        JavaFileObject ping =
                JavaFileObjects.forSourceLines("io.example.Ping", "package io.example;", "public record Ping() {}");
        JavaFileObject pong =
                JavaFileObjects.forSourceLines("io.example.Pong", "package io.example;", "public record Pong() {}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(component, ping, pong);

        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject registry = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("EventRegistry"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("EventRegistry not generated"));
        String content = new String(registry.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Dispatch is by the return type — the unnamed trigger still publishes __result.
        assertThat(content).contains("publishWithOrigin(eventBus, __result, __wrapper)");
    }

    @Test
    void triggerWithExplicitEventNameStillCompiles() {
        JavaFileObject component = JavaFileObjects.forSourceLines(
                "io.example.Named",
                "package io.example;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.EventHandler;",
                "import io.tiko.annotations.EventTrigger;",
                "import io.tiko.Scope;",
                "@Component(scope = Scope.SINGLETON)",
                "public class Named {",
                "    @EventHandler",
                "    @EventTrigger(eventName = \"PongHappened\")",
                "    public Pong3 onPing(Ping3 event) { return new Pong3(); }",
                "}");
        JavaFileObject ping =
                JavaFileObjects.forSourceLines("io.example.Ping3", "package io.example;", "public record Ping3() {}");
        JavaFileObject pong =
                JavaFileObjects.forSourceLines("io.example.Pong3", "package io.example;", "public record Pong3() {}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(component, ping, pong);

        // The label remains accepted — purely backward compatible.
        CompilationSubject.assertThat(c).succeeded();
    }
}
