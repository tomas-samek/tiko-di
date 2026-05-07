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

class EventRegistryDispatcherTryCatchTest {

    @Test
    void generated_dispatcher_wraps_handler_in_try_catch_routing_to_error_handler() throws IOException {
        JavaFileObject component = JavaFileObjects.forSourceLines(
            "io.example.MyHandler",
            "package io.example;",
            "import io.tiko.annotations.Component;",
            "import io.tiko.annotations.EventHandler;",
            "import io.tiko.Scope;",
            "@Component(scope = Scope.SINGLETON)",
            "public class MyHandler {",
            "    @EventHandler",
            "    public void onPing(Ping event) {}",
            "}"
        );
        JavaFileObject event = JavaFileObjects.forSourceLines(
            "io.example.Ping",
            "package io.example;",
            "public record Ping() {}"
        );

        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(component, event);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        JavaFileObject registry = c.generatedSourceFiles().stream()
            .filter(f -> f.getName().contains("EventRegistry"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("EventRegistry not generated"));

        String content = new String(registry.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // HANDLER_INFO_0 constant exists with correct args
        assertThat(content).contains("HANDLER_INFO_0");
        assertThat(content).contains("MyHandler.class");
        assertThat(content).contains("\"onPing\"");
        assertThat(content).contains("Ping.class");
        // Handler invocation is present
        assertThat(content).contains("__handler.onPing(event)");
        // try/catch wrapping the handler invocation
        assertThat(content).contains("} catch (");
        // Container error handler is called
        assertThat(content).contains("container.getErrorHandler()");
        // EventHandlerError constructed with HANDLER_INFO_0
        assertThat(content).contains("EventHandlerError(HANDLER_INFO_0, event,");
    }
}
