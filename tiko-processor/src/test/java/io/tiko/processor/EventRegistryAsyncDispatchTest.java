package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class EventRegistryAsyncDispatchTest {

    @Test
    void asyncHandlerRoutesDispatchThroughRunAsyncWithTimeoutHelper() throws IOException {
        JavaFileObject component = JavaFileObjects.forSourceLines(
                "io.example.AsyncHandler",
                "package io.example;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.EventHandler;",
                "import io.tiko.Scope;",
                "@Component(scope = Scope.SINGLETON)",
                "public class AsyncHandler {",
                "    @EventHandler(async = true)",
                "    public void onPing(Ping event) {}",
                "}");
        JavaFileObject event =
                JavaFileObjects.forSourceLines("io.example.Ping", "package io.example;", "public record Ping() {}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(component, event);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        JavaFileObject registry = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("EventRegistry"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("EventRegistry not generated"));

        String content = new String(registry.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // HANDLER_INFO has async=true now
        assertThat(content).contains("AsyncHandler.class");
        assertThat(content).contains("\"onPing\"");
        assertThat(content).contains("Ping.class");
        assertThat(content).contains("true)"); // The async boolean — last arg of EventHandlerInfo

        // Plain async now routes through the shared runAsyncWithTimeout helper with a zero budget (#111),
        // so the executor submit, ROUTE_TO_DLQ overflow handling, and error routing live in one runtime
        // path instead of being inlined into every generated dispatcher.
        assertThat(content).contains("EventChainContext.runAsyncWithTimeout");
        assertThat(content).contains("0L"); // zero timeout budget — no time-boxing, a bare runAsync
        assertThat(content).contains("getEventExecutor()");
        assertThat(content).contains("getErrorHandler()");
        assertThat(content).contains("HANDLER_INFO_0");

        // Chain context is re-entered inside the async body
        assertThat(content).contains("EventChainContext.enter");

        // The inline CompletableFuture / whenComplete / EventHandlerError wiring now lives in the runtime helper.
        assertThat(content).doesNotContain("whenComplete");
    }

    @Test
    void sync_handler_generates_unchanged_inline_dispatch() throws IOException {
        JavaFileObject component = JavaFileObjects.forSourceLines(
                "io.example.SyncHandler",
                "package io.example;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.EventHandler;",
                "import io.tiko.Scope;",
                "@Component(scope = Scope.SINGLETON)",
                "public class SyncHandler {",
                "    @EventHandler",
                "    public void onPing(Ping event) {}",
                "}");
        JavaFileObject event =
                JavaFileObjects.forSourceLines("io.example.Ping", "package io.example;", "public record Ping() {}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(component, event);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        JavaFileObject registry = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("EventRegistry"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("EventRegistry not generated"));

        String content = new String(registry.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // No CompletableFuture for sync handlers
        assertThat(content).doesNotContain("CompletableFuture.runAsync");
        // Sync invocation still emitted
        assertThat(content).contains("__handler.onPing(event)");
    }
}
