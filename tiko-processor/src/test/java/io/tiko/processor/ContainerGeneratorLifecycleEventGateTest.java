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

    @Test
    void runInEventScopeDelegatesToCoreWithStandingFlag() throws IOException {
        // #433: the public sync bracket carries no publish of its own — it passes the container's
        // standing publishLifecycleEvents flag to the __runInEventScope core, so a per-module
        // container under an AggregatingContainer (flag off) stays silent for sync units and the
        // aggregator remains the sole publisher.
        String content = generateContainerSource();
        assertThat(content).contains("__runInEventScope(task, publishLifecycleEvents)");
    }

    @Test
    void detachedScopeForcesUnitLifecyclePublish() throws IOException {
        // #433: an async detached unit is bracketed by no aggregator (async dispatch does not
        // traverse the aggregator's scope path), so the module container is its sole lifecycle
        // publisher — detached forces the core's publish flag true regardless of the standing flag.
        String content = generateContainerSource();
        assertThat(content).contains("__runInEventScope(task, true)");
    }

    @Test
    void coreScopeGatesUnitLifecyclePublishesOnParameter() throws IOException {
        assertUnitLifecyclePublishesGated("private void __runInEventScope", "__publishLifecycle");
    }

    @Test
    void supplyInEventScopeGatesUnitLifecyclePublishes() throws IOException {
        assertUnitLifecyclePublishesGated("public <T> T supplyInEventScope", "publishLifecycleEvents");
    }

    /**
     * #339 / #433: unit-of-work brackets publish their EventStarted/EventEnding pair only when
     * their gate is set. The sync core ({@code __runInEventScope}) gates on its
     * {@code __publishLifecycle} parameter; {@code supplyInEventScope} gates on the standing
     * {@code publishLifecycleEvents} field — so a module container under an AggregatingContainer
     * (field off) does not double-publish the aggregator's single pair.
     */
    private void assertUnitLifecyclePublishesGated(String methodSignature, String gateExpression) throws IOException {
        String content = generateContainerSource();
        String gate = "if (" + gateExpression + ")";

        int methodPos = content.indexOf(methodSignature);
        assertThat(methodPos).as("scope method present").isPositive();

        int startedGate = content.indexOf(gate, methodPos);
        int startedPublish = content.indexOf("__publishUnitLifecycle(new EventStartedEvent", methodPos);
        assertThat(startedGate).as("gate before EventStartedEvent publish").isPositive();
        assertThat(startedPublish).isGreaterThan(startedGate);

        int endingGate = content.indexOf(gate, startedPublish);
        int endingPublish = content.indexOf("__publishUnitLifecycle(new EventEndingEvent", startedPublish);
        assertThat(endingGate).as("gate before EventEndingEvent publish").isPositive();
        assertThat(endingPublish).isGreaterThan(endingGate);
    }

    private String generateContainerSource() throws IOException {
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

        return new String(container.openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
