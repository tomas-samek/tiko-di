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
 * Event-handler execution timeouts (#107). {@code @EventHandler(timeout = "...")} is opt-in,
 * async-only (a sync handler runs on the publisher thread and can't be interrupted), and the
 * value is an ISO-8601 {@link java.time.Duration}. A timed async handler's generated dispatcher
 * runs the invocation under a timeout that interrupts the worker and routes the overrun.
 */
class EventHandlerTimeoutTest {

    private static final JavaFileObject EVENT =
            JavaFileObjects.forSourceLines("demo.PingEvent", "package demo;", "public class PingEvent {}");

    @Test
    void timeoutOnSyncHandlerIsACompileError() {
        JavaFileObject handler = JavaFileObjects.forSourceLines(
                "demo.SyncHandler",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.EventHandler;",
                "@Component(scope = Scope.SINGLETON)",
                "public class SyncHandler {",
                "  @EventHandler(timeout = \"PT5S\")",
                "  public void onPing(PingEvent event) {}",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(EVENT, handler);

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("timeout requires async = true");
    }

    @Test
    void nonIso8601TimeoutIsACompileError() {
        JavaFileObject handler = JavaFileObjects.forSourceLines(
                "demo.BadDurationHandler",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.EventHandler;",
                "@Component(scope = Scope.SINGLETON)",
                "public class BadDurationHandler {",
                "  @EventHandler(async = true, timeout = \"5s\")", // not ISO-8601
                "  public void onPing(PingEvent event) {}",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(EVENT, handler);

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("timeout");
    }

    @Test
    void zeroOrNegativeTimeoutIsACompileError() {
        JavaFileObject handler = JavaFileObjects.forSourceLines(
                "demo.ZeroHandler",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.EventHandler;",
                "@Component(scope = Scope.SINGLETON)",
                "public class ZeroHandler {",
                "  @EventHandler(async = true, timeout = \"PT0S\")",
                "  public void onPing(PingEvent event) {}",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(EVENT, handler);

        CompilationSubject.assertThat(c).failed();
        CompilationSubject.assertThat(c).hadErrorContaining("timeout");
    }

    @Test
    void validAsyncTimeoutGeneratesTimeoutDispatch() throws IOException {
        JavaFileObject handler = JavaFileObjects.forSourceLines(
                "demo.TimedHandler",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.EventHandler;",
                "@Component(scope = Scope.SINGLETON)",
                "public class TimedHandler {",
                "  @EventHandler(async = true, timeout = \"PT5S\")",
                "  public void onPing(PingEvent event) {}",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(EVENT, handler);

        CompilationSubject.assertThat(c).succeeded();
        assertThat(generatedSource(c, "EventRegistry"))
                .as("a timed async handler dispatches through the timeout-aware runtime helper")
                .contains("runAsyncWithTimeout")
                .contains("5000000000L"); // PT5S in nanos
    }

    @Test
    void asyncHandlerWithoutTimeoutKeepsPlainRunAsyncDispatch() throws IOException {
        JavaFileObject handler = JavaFileObjects.forSourceLines(
                "demo.PlainAsyncHandler",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.EventHandler;",
                "@Component(scope = Scope.SINGLETON)",
                "public class PlainAsyncHandler {",
                "  @EventHandler(async = true)",
                "  public void onPing(PingEvent event) {}",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(EVENT, handler);

        CompilationSubject.assertThat(c).succeeded();
        assertThat(generatedSource(c, "EventRegistry"))
                .as("an untimed async handler is unchanged — plain runAsync, no timeout helper")
                .doesNotContain("runAsyncWithTimeout");
    }

    private static String generatedSource(Compilation c, String nameFragment) throws IOException {
        JavaFileObject file = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains(nameFragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError(nameFragment + " was not generated"));
        return new String(file.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
