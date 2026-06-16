package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    static Stream<Arguments> invalidRetryDeclarations() {
        return Stream.of(
                Arguments.of(
                        "retries on a sync handler", "@EventHandler(retries = 3)", "retries requires async = true"),
                Arguments.of("negative retries", "@EventHandler(async = true, retries = -1)", "retries"),
                Arguments.of(
                        "non-ISO-8601 backoff",
                        "@EventHandler(async = true, retries = 2, backoff = \"100ms\")",
                        "backoff"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRetryDeclarations")
    void invalidRetryDeclarationIsACompileError(String name, String handlerAnnotation, String expectedError) {
        Compilation c = compile(
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.EventHandler;",
                "@Component(scope = Scope.SINGLETON)",
                "public class H {",
                "  " + handlerAnnotation,
                "  public void onPing(PingEvent event) {}",
                "}");
        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining(expectedError);
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
