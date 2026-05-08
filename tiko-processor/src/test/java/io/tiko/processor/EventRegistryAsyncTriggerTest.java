package io.tiko.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

class EventRegistryAsyncTriggerTest {

    @Test
    void async_event_trigger_passes_executor_and_error_handler() throws IOException {
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
            "    @EventTrigger(eventName = \"io.example.Pong\", async = true)",
            "    public Pong onPing(Ping event) { return new Pong(); }",
            "}"
        );
        JavaFileObject ping = JavaFileObjects.forSourceLines(
            "io.example.Ping",
            "package io.example;",
            "public record Ping() {}"
        );
        JavaFileObject pong = JavaFileObjects.forSourceLines(
            "io.example.Pong",
            "package io.example;",
            "public record Pong() {}"
        );

        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(component, ping, pong);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        JavaFileObject registry = c.generatedSourceFiles().stream()
            .filter(f -> f.getName().contains("EventRegistry"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("EventRegistry not generated"));

        String content = new String(registry.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // 6-arg publishAsync — confirm by substring of the new args
        assertThat(content).contains("publishAsync(eventBus, __result, __wrapper");
        assertThat(content).contains("container.getEventExecutor()");
        assertThat(content).contains("container.getErrorHandler()");
        assertThat(content).contains("HANDLER_INFO_0");
    }

    @Test
    void sync_event_trigger_keeps_3_arg_signature() throws IOException {
        JavaFileObject component = JavaFileObjects.forSourceLines(
            "io.example.SyncTrig",
            "package io.example;",
            "import io.tiko.annotations.Component;",
            "import io.tiko.annotations.EventHandler;",
            "import io.tiko.annotations.EventTrigger;",
            "import io.tiko.Scope;",
            "@Component(scope = Scope.SINGLETON)",
            "public class SyncTrig {",
            "    @EventHandler",
            "    @EventTrigger(eventName = \"io.example.Pong2\")",
            "    public Pong2 onPing(Ping2 event) { return new Pong2(); }",
            "}"
        );
        JavaFileObject ping = JavaFileObjects.forSourceLines("io.example.Ping2",
            "package io.example;",
            "public record Ping2() {}");
        JavaFileObject pong = JavaFileObjects.forSourceLines("io.example.Pong2",
            "package io.example;",
            "public record Pong2() {}");

        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(component, ping, pong);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        JavaFileObject registry = c.generatedSourceFiles().stream()
            .filter(f -> f.getName().contains("EventRegistry"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("EventRegistry not generated"));

        String content = new String(registry.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Sync helper is publishWithOrigin with 3 args (no executor/errorHandler/info)
        assertThat(content).contains("publishWithOrigin(eventBus, __result, __wrapper)");
        assertThat(content).doesNotContain("publishWithOrigin(eventBus, __result, __wrapper,");
    }
}
