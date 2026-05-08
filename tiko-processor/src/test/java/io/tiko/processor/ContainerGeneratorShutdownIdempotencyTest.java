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

/**
 * Codegen-level coverage for #47. Verifies the generated TikoContainerImpl carries
 * the new state fields and emits the idempotency CAS, drain barrier, and PreDestroy
 * thread-local bypass at the expected sites.
 */
class ContainerGeneratorShutdownIdempotencyTest {

    @Test
    void generated_container_has_shutdown_state_fields() throws IOException {
        String content = generateContainerSource();

        assertThat(content).contains("private final AtomicBoolean shutdownInvoked = new AtomicBoolean(false)");
        assertThat(content).contains("private final AtomicBoolean stopped = new AtomicBoolean(false)");
        assertThat(content).contains("private final AtomicInteger inFlightGets = new AtomicInteger(0)");
        assertThat(content).contains("ThreadLocal<Boolean> inShutdownThread");
    }

    @Test
    void generated_shutdown_starts_with_idempotency_cas() throws IOException {
        String content = generateContainerSource();

        assertThat(content).contains("if (!shutdownInvoked.compareAndSet(false, true))");
        // CAS appears at the top of shutdown() — before any event publish or PreDestroy work.
        int casPos = content.indexOf("shutdownInvoked.compareAndSet");
        int publishPos = content.indexOf("eventBus.publish(new ApplicationEndingEvent");
        assertThat(casPos).isPositive();
        assertThat(publishPos).isGreaterThan(casPos);
    }

    @Test
    void generated_shutdown_drains_in_flight_gets_before_predestroy() throws IOException {
        String content = generateContainerSource();

        assertThat(content).contains("stopped.set(true)");
        assertThat(content).contains("inFlightGets.get()");
        assertThat(content).contains("Thread.onSpinWait()");

        // stopped.set(true) and the drain loop must come before the PreDestroy iteration.
        // We use the bypass-set as a stable marker for "PreDestroy phase begins".
        int drainPos = content.indexOf("stopped.set(true)");
        int bypassPos = content.indexOf("inShutdownThread.set(Boolean.TRUE)");
        assertThat(drainPos).isPositive();
        assertThat(bypassPos).isGreaterThan(drainPos);
    }

    @Test
    void generated_get_methods_are_gated_and_track_in_flight() throws IOException {
        String content = generateContainerSource();

        assertThat(content).contains("if (stopped.get() && !inShutdownThread.get())");
        assertThat(content).contains("throw new IllegalStateException(\"Container has been shut down\")");
        assertThat(content).contains("inFlightGets.incrementAndGet()");
        assertThat(content).contains("inFlightGets.decrementAndGet()");
    }

    @Test
    void generated_shutdown_publishes_ending_event_in_try_catch() throws IOException {
        String content = generateContainerSource();

        // Phase 2's publish is wrapped in try/catch so a bus-impl defect doesn't skip @PreDestroy.
        assertThat(content).contains("eventBus.publish(new ApplicationEndingEvent");
        // The catch should log via JUL at WARNING (per #53 logging swap).
        assertThat(content).contains("ApplicationEndingEvent publish threw");
    }

    private String generateContainerSource() throws IOException {
        JavaFileObject src = JavaFileObjects.forSourceLines(
            "io.example.MyService",
            "package io.example;",
            "import io.tiko.annotations.Component;",
            "import io.tiko.annotations.PreDestroy;",
            "import io.tiko.Scope;",
            "@Component(scope = Scope.SINGLETON)",
            "public class MyService {",
            "  public MyService() {}",
            "  @PreDestroy public void cleanup() {}",
            "}"
        );
        Compilation c = Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);
        com.google.testing.compile.CompilationSubject.assertThat(c).succeeded();

        JavaFileObject container = c.generatedSourceFiles().stream()
            .filter(f -> f.getName().contains("TikoContainerImpl"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("TikoContainerImpl not generated"));

        return new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
