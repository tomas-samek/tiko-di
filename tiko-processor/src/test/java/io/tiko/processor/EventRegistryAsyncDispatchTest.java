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
    void async_handler_generates_completable_future_dispatch_with_when_complete() throws IOException {
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

        // Async path uses CompletableFuture.runAsync
        assertThat(content).contains("CompletableFuture");
        assertThat(content).contains("runAsync");
        assertThat(content).contains("getEventExecutor()");

        // Re-enter chain context inside async task
        assertThat(content).contains("EventChainContext.enter");

        // whenComplete (or handle) routes to ErrorHandler
        assertThat(content).contains("whenComplete");
        assertThat(content).contains("getErrorHandler()");
        assertThat(content).contains("EventHandlerError(HANDLER_INFO_0");

        // CompletionException unwrap
        assertThat(content).contains("CompletionException");
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
