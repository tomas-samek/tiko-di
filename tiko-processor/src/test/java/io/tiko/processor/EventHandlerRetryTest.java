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
 * Event-handler retries with backoff (#108). {@code @EventHandler(retries = ...)} is opt-in and
 * async-only (it waits for {@code backoff} between attempts, which would block the publisher's
 * thread), the backoff is an ISO-8601 {@link java.time.Duration}, and a handler with retries
 * dispatches through the retry-aware runtime helper carrying the budget, base delay, and strategy.
 */
class EventHandlerRetryTest {

    private static final JavaFileObject EVENT =
            JavaFileObjects.forSourceLines("demo.PingEvent", "package demo;", "public class PingEvent {}");

    private static Compilation compile(String... handlerLines) {
        return Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(EVENT, JavaFileObjects.forSourceLines("demo.H", handlerLines));
    }

    @Test
    void retriesOnSyncHandlerIsACompileError() {
        Compilation c = compile(
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.EventHandler;",
                "@Component(scope = Scope.SINGLETON)",
                "public class H {",
                "  @EventHandler(retries = 3)",
                "  public void onPing(PingEvent event) {}",
                "}");
        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("retries requires async = true");
    }

    @Test
    void negativeRetriesIsACompileError() {
        Compilation c = compile(
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.EventHandler;",
                "@Component(scope = Scope.SINGLETON)",
                "public class H {",
                "  @EventHandler(async = true, retries = -1)",
                "  public void onPing(PingEvent event) {}",
                "}");
        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("retries");
    }

    @Test
    void nonIso8601BackoffIsACompileError() {
        Compilation c = compile(
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.EventHandler;",
                "@Component(scope = Scope.SINGLETON)",
                "public class H {",
                "  @EventHandler(async = true, retries = 2, backoff = \"100ms\")",
                "  public void onPing(PingEvent event) {}",
                "}");
        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("backoff");
    }

    @Test
    void validRetriesGeneratesRetryDispatch() throws IOException {
        Compilation c = compile(
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.EventHandler;",
                "import io.tiko.annotations.BackoffStrategy;",
                "@Component(scope = Scope.SINGLETON)",
                "public class H {",
                "  @EventHandler(async = true, retries = 3, backoff = \"PT0.1S\","
                        + " backoffStrategy = BackoffStrategy.EXPONENTIAL)",
                "  public void onPing(PingEvent event) {}",
                "}");
        CompilationSubject.assertThat(c).succeeded();
        assertThat(generatedSource(c, "EventRegistry"))
                .as("a retrying handler dispatches through the retry-aware runtime helper")
                .contains("runAsyncWithRetry")
                // RetryPolicy(retries=3, backoffNanos=PT0.1S, EXPONENTIAL, timeoutNanos=0)
                .contains("RetryPolicy(3, 100000000L")
                .contains("EXPONENTIAL");
    }

    @Test
    void asyncWithoutRetriesKeepsPlainDispatch() throws IOException {
        Compilation c = compile(
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.EventHandler;",
                "@Component(scope = Scope.SINGLETON)",
                "public class H {",
                "  @EventHandler(async = true)",
                "  public void onPing(PingEvent event) {}",
                "}");
        CompilationSubject.assertThat(c).succeeded();
        assertThat(generatedSource(c, "EventRegistry")).doesNotContain("runAsyncWithRetry");
    }

    private static String generatedSource(Compilation c, String nameFragment) throws IOException {
        JavaFileObject file = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains(nameFragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError(nameFragment + " was not generated"));
        return new String(file.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
