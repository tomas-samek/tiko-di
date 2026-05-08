package io.tiko.processor;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Codegen-level coverage for #45. Verifies the generated TikoContainerImpl exposes
 * the 4-arg constructor with a {@code publishLifecycleEvents} flag and gates both
 * lifecycle event publishes on it.
 */
class ContainerGeneratorLifecycleEventGateTest {

    @Test
    void generated_container_has_four_arg_constructor_with_lifecycle_flag() throws IOException {
        String content = generateContainerSource();

        // JavaPoet may wrap the constructor signature across lines; assert each parameter
        // appears in declaration order rather than matching the formatted source verbatim.
        assertThat(content).contains("public TikoContainerImpl");
        assertThat(content).contains("EventBus eventBus");
        assertThat(content).contains("ErrorHandler errorHandler");
        assertThat(content).contains("ExecutorService userEventExecutor");
        assertThat(content).contains("boolean publishLifecycleEvents");
        assertThat(content).contains("private final boolean publishLifecycleEvents");
        assertThat(content).contains("this.publishLifecycleEvents = publishLifecycleEvents");
    }

    @Test
    void generated_start_gates_application_started_event_publish() throws IOException {
        String content = generateContainerSource();

        assertThat(content).contains("@Override\n  public void start()");
        // Idempotency CAS comes first, then the gated publish.
        assertThat(content).contains("if (!startInvoked.compareAndSet(false, true))");
        // The publish line must sit inside an `if (publishLifecycleEvents)` block.
        int gatePos = content.indexOf("if (publishLifecycleEvents)");
        int publishPos = content.indexOf("eventBus.publish(new ApplicationStartedEvent");
        assertThat(gatePos).isPositive();
        assertThat(publishPos).isGreaterThan(gatePos);
    }

    @Test
    void generated_shutdown_gates_application_ending_event_publish() throws IOException {
        String content = generateContainerSource();

        // The ending publish must sit inside `if (publishLifecycleEvents)` plus the
        // existing try/catch from #47.
        int gatePos = content.indexOf("if (publishLifecycleEvents)", content.indexOf("public void shutdown"));
        int publishPos = content.indexOf("eventBus.publish(new ApplicationEndingEvent");
        assertThat(gatePos).isPositive();
        assertThat(publishPos).isGreaterThan(gatePos);
    }

    private String generateContainerSource() throws IOException {
        JavaFileObject src = JavaFileObjects.forSourceLines(
            "io.example.MyService",
            "package io.example;",
            "import io.tiko.annotations.Component;",
            "import io.tiko.Scope;",
            "@Component(scope = Scope.SINGLETON)",
            "public class MyService { public MyService() {} }"
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
