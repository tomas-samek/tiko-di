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
 * Pins the single-frame guard emission in the generated container.
 *
 * <p>The generator emits an {@code IllegalStateException} at the top of both
 * {@code runInEventScope} and {@code supplyInEventScope} when an EVENT scope is
 * already open. This test verifies that the guard (and its "single-frame" message)
 * survives future refactors of {@code ContainerGenerator}.
 */
class EventScopeGuardEmissionTest {

    @Test
    void runInEventScopeAndSupplyInEventScopeEmitSingleFrameGuard() throws IOException {
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
                .as("runInEventScope guard should be emitted with single-frame message")
                .contains("public void runInEventScope")
                .contains("single-frame")
                .contains("IllegalStateException");

        assertThat(source)
                .as("supplyInEventScope guard should be emitted with single-frame message")
                .contains("supplyInEventScope")
                .contains("single-frame");
    }
}
