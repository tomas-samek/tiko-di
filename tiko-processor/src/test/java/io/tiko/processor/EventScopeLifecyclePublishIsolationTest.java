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
 * Pins the exception isolation of the unit-of-work lifecycle publishes (#336).
 *
 * <p>Both {@code runInEventScope} and {@code supplyInEventScope} must route their
 * {@code EventStartedEvent}/{@code EventEndingEvent} publishes through the
 * {@code __publishUnitLifecycle} helper, which catches {@code Throwable} — a throwing
 * publish (bus decorator, or an {@code Error} escaping a sync handler) must never skip
 * {@code __closeEventScope()} or leave {@code __unitFrameOpen} stuck on the thread.
 * Behavioral counterpart: {@code EventScopeLifecyclePublishFailureTest} in
 * {@code tiko-examples/01_basic_di}.
 */
class EventScopeLifecyclePublishIsolationTest {

    @Test
    void unitLifecyclePublishesAreRoutedThroughTheIsolatingHelper() throws IOException {
        JavaFileObject src = JavaFileObjects.forSourceLines(
                "demo.MyEventBeanImpl",
                "package demo;",
                "import io.tiko.Scope;",
                "import io.tiko.annotations.Component;",
                "@Component(scope = Scope.EVENT)",
                "public class MyEventBeanImpl {",
                "}");

        Compilation c =
                Compiler.javac().withProcessors(new TikoAnnotationProcessor()).compile(src);

        CompilationSubject.assertThat(c).succeeded();

        JavaFileObject containerSource = c.generatedSourceFiles().stream()
                .filter(f -> f.getName().contains("TikoContainerImpl"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("TikoContainerImpl was not generated"));

        String source = new String(containerSource.openInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(source)
                .as("scope-bracket lifecycle publishes must go through the Throwable-isolating helper")
                .contains("__publishUnitLifecycle(new EventStartedEvent")
                .contains("__publishUnitLifecycle(new EventEndingEvent")
                .contains("private void __publishUnitLifecycle")
                .as("the helper must isolate Throwable, not just Exception")
                .contains("catch (Throwable")
                .as("the scope bracket must not publish lifecycle events on the bus directly")
                .doesNotContain("eventBus.publish(new EventStartedEvent")
                .doesNotContain("eventBus.publish(new EventEndingEvent");
    }
}
