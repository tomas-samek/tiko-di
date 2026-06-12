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
 * Pins the post-shutdown gate in generated event dispatchers (#337). Subscriptions are
 * never released from the bus, and the per-component getters carry no stopped gate — so
 * the dispatcher itself must check the container's stopped flag and drop the delivery,
 * or a post-shutdown publish runs handlers on destroyed singletons (and, on lazy paths,
 * constructs new ones). Behavioral counterpart: {@code PostShutdownPublishTest} in
 * {@code tiko-examples/01_basic_di}.
 */
class EventRegistryPostShutdownGateTest {

    @Test
    void dispatchersGateOnTheContainerStoppedFlag() throws IOException {
        JavaFileObject evt =
                JavaFileObjects.forSourceLines("demo.PingEvent", "package demo;", "public class PingEvent {}");
        JavaFileObject handler = JavaFileObjects.forSourceLines(
                "demo.PingHandler",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.annotations.EventHandler;",
                "@Component(scope = Scope.SINGLETON)",
                "public class PingHandler {",
                "  @EventHandler",
                "  public void onPing(PingEvent event) {}",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(evt, handler);

        CompilationSubject.assertThat(c).succeeded();

        String registry = generatedSource(c, "EventRegistry");
        String container = generatedSource(c, "TikoContainerImpl");

        assertThat(registry)
                .as("each dispatcher must drop deliveries once the container is stopped")
                .contains("container.__isStopped()");
        assertThat(container)
                .as("the container must expose the stopped flag to its registry")
                .contains("__isStopped");
    }

    private static String generatedSource(Compilation c, String nameFragment) throws IOException {
        JavaFileObject file = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains(nameFragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError(nameFragment + " was not generated"));
        return new String(file.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
