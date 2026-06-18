package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for #306: an {@code Error} thrown by an async {@code @EventHandler}
 * must be observable, not silently swallowed.
 *
 * <p>{@code CompletableFuture.runAsync} captures every {@code Throwable} (including
 * {@code Error}) into the future and completes it exceptionally on the executor thread, so
 * the thread's uncaught-exception handler never fires. The old {@code whenComplete} guard
 * {@code if (__t != null && !(__t instanceof Error))} then dropped the {@code Error} — it
 * was neither routed nor logged. The dispatcher must instead log the {@code Error} (kept
 * out of {@code ErrorHandler}, consistent with the sync path's Exception-only routing).
 */
class EventRegistryAsyncErrorObservableTest {

    private static String generatedRegistry() throws IOException {
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
        return new String(registry.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void asyncDispatcherDelegatesErrorObservabilityToRuntimeHelper() throws IOException {
        var content = generatedRegistry();

        // #306's Error-observability now lives in the runtime (#111): the plain async dispatcher delegates
        // to EventChainContext.runAsyncWithTimeout, which logs an Error via logUnhandledAsyncError — see the
        // runtime EventChainContext tests — rather than inlining that guard into every generated dispatcher.
        assertThat(content).contains("runAsyncWithTimeout");
    }

    @Test
    void asyncDispatcherNoLongerSilentlyFiltersErrorsOut() throws IOException {
        var content = generatedRegistry();

        assertThat(content).doesNotContain("!(__t instanceof Error)");
    }
}
