package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class ContainerGeneratorEventExecutorTest {

    @Test
    void generated_container_has_event_executor_field_constructor_and_accessor() throws IOException {
        JavaFileObject src = JavaFileObjects.forSourceLines(
                "io.example.MyService",
                "package io.example;",
                "import io.tiko.annotations.Component;",
                "import io.tiko.Scope;",
                "@Component(scope = Scope.SINGLETON)",
                "public class MyService { public MyService() {} }");
        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        JavaFileObject container = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TikoContainerImpl not generated"));

        String content = new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Adjust for JavaPoet's import-style output. The substantive checks:
        assertThat(content).contains("ExecutorService eventExecutor");
        assertThat(content).contains("boolean ownsEventExecutor");
        assertThat(content).contains("ExecutorService getEventExecutor()");
        // #438: the single-module container samples its framework-owned pool, mirroring
        // AggregatingContainer — only when it owns a ThreadPoolExecutor, empty otherwise.
        assertThat(content).contains("Optional<ExecutorMetrics> eventExecutorMetrics()");
        assertThat(content).contains("ownsEventExecutor && eventExecutor instanceof ThreadPoolExecutor tpe");
        // Constructor takes EventBus, ErrorHandler, ExecutorService userEventExecutor, ...
        assertThat(content).contains("EventBus eventBus, ErrorHandler errorHandler");
        assertThat(content).contains("ExecutorService userEventExecutor");
        // Default executor wired when user-supplied is null, parameterized by the configured
        // queue capacity and overflow policy (#109) AND the pool-sizing knobs (#435): the
        // single-module container must call the five-arg factory overload so
        // eventExecutorCoreSize/MaxSize/KeepAlive are honored, not silently dropped.
        assertThat(content)
                .contains("DefaultEventExecutorFactory.create(options.queueCapacity(), options.onOverflow(), "
                        + "options.eventExecutorCoreSize(), options.eventExecutorMaxSize(), "
                        + "options.eventExecutorKeepAlive())");
        assertThat(content).contains("userEventExecutor != null ? userEventExecutor");
        // ownsEventExecutor flag
        assertThat(content).contains("this.ownsEventExecutor = (userEventExecutor == null)");

        // Shutdown handles the default executor; the timeout now comes from the
        // shutdownTimeout field (#48), not a hardcoded 10s literal.
        assertThat(content).contains("if (this.ownsEventExecutor)");
        assertThat(content).contains("this.eventExecutor.shutdown()");
        assertThat(content).contains("awaitTermination(this.shutdownTimeout.toNanos()");
        assertThat(content).contains("this.eventExecutor.shutdownNow()");
    }
}
